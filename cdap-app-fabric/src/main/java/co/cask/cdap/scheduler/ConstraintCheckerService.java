/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.scheduler;

import co.cask.cdap.api.Transactional;
import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.common.service.RetryStrategy;
import co.cask.cdap.data.dataset.SystemDatasetInstantiator;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.dataset2.MultiThreadDatasetCache;
import co.cask.cdap.data2.transaction.Transactions;
import co.cask.cdap.data2.transaction.TxCallable;
import co.cask.cdap.internal.app.runtime.schedule.ProgramSchedule;
import co.cask.cdap.internal.app.runtime.schedule.ScheduleTaskRunner;
import co.cask.cdap.internal.app.runtime.schedule.constraint.AbstractCheckableConstraint;
import co.cask.cdap.internal.app.runtime.schedule.constraint.ConstraintContext;
import co.cask.cdap.internal.app.runtime.schedule.constraint.ConstraintResult;
import co.cask.cdap.internal.app.runtime.schedule.queue.Job;
import co.cask.cdap.internal.app.runtime.schedule.queue.JobQueueDataset;
import co.cask.cdap.internal.app.runtime.schedule.store.Schedulers;
import co.cask.cdap.internal.app.runtime.schedule.trigger.PartitionTrigger;
import co.cask.cdap.internal.app.runtime.schedule.trigger.TimeTrigger;
import co.cask.cdap.internal.app.services.ProgramLifecycleService;
import co.cask.cdap.internal.app.services.PropertiesResolver;
import co.cask.cdap.internal.schedule.constraint.Constraint;
import co.cask.cdap.internal.schedule.trigger.Trigger;
import co.cask.cdap.proto.Notification;
import co.cask.cdap.proto.id.NamespaceId;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import org.apache.tephra.RetryStrategies;
import org.apache.tephra.TransactionSystemClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Polls the JobQueue, checks the jobs for constraint satisfaction, and launches them.
 */
class ConstraintCheckerService extends AbstractIdleService {
  private static final Logger LOG = LoggerFactory.getLogger(ConstraintCheckerService.class);

  private final Transactional transactional;
  private final DatasetFramework datasetFramework;
  private final MultiThreadDatasetCache multiThreadDatasetCache;
  private final Store store;
  private final ProgramLifecycleService lifecycleService;
  private final PropertiesResolver propertiesResolver;
  private final NamespaceQueryAdmin namespaceQueryAdmin;
  private final CConfiguration cConf;
  private ScheduleTaskRunner taskRunner;
  private ListeningExecutorService taskExecutorService;
  private volatile boolean stopping = false;

  @Inject
  ConstraintCheckerService(Store store,
                           ProgramLifecycleService lifecycleService, PropertiesResolver propertiesResolver,
                           NamespaceQueryAdmin namespaceQueryAdmin,
                           CConfiguration cConf,
                           DatasetFramework datasetFramework,
                           TransactionSystemClient txClient) {
    this.store = store;
    this.lifecycleService = lifecycleService;
    this.propertiesResolver = propertiesResolver;
    this.namespaceQueryAdmin = namespaceQueryAdmin;
    this.cConf = cConf;
    this.multiThreadDatasetCache = new MultiThreadDatasetCache(
      new SystemDatasetInstantiator(datasetFramework), txClient,
      NamespaceId.SYSTEM, ImmutableMap.<String, String>of(), null, null);
    this.transactional = Transactions.createTransactionalWithRetry(
      Transactions.createTransactional(multiThreadDatasetCache),
      RetryStrategies.retryOnConflict(20, 100)
    );
    this.datasetFramework = datasetFramework;
  }

  @Override
  protected void startUp() throws Exception {
    LOG.info("Starting ConstraintCheckerService.");
    taskExecutorService = MoreExecutors.listeningDecorator(
      Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("constraint-checker-task").build()));
    taskRunner = new ScheduleTaskRunner(store, lifecycleService, propertiesResolver,
                                        taskExecutorService, namespaceQueryAdmin, cConf);

    int numPartitions = Schedulers.getJobQueue(multiThreadDatasetCache, datasetFramework).getNumPartitions();
    for (int partition = 0; partition < numPartitions; partition++) {
      taskExecutorService.submit(new ConstraintCheckerThread(partition));
    }
    LOG.info("Started ConstraintCheckerService. state: " + state());
  }

  @Override
  protected void shutDown() throws Exception {
    stopping = true;
    LOG.info("Stopping ConstraintCheckerService.");
    try {
      taskExecutorService.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      if (!taskExecutorService.isTerminated()) {
        taskExecutorService.shutdownNow();
      }
    }
    LOG.info("Stopped ConstraintCheckerService.");
  }

  private class ConstraintCheckerThread implements Runnable {
    private final RetryStrategy scheduleStrategy;
    private final int partition;
    private final Deque<Job> readyJobs = new ArrayDeque<>();
    private JobQueueDataset jobQueue;
    private Job lastConsumed;

    ConstraintCheckerThread(int partition) {
      // TODO: [CDAP-11370] Need to be configured in cdap-default.xml. Retry with delay ranging from 0.1s to 30s
      scheduleStrategy =
        co.cask.cdap.common.service.RetryStrategies.exponentialDelay(100, 30000, TimeUnit.MILLISECONDS);
      this.partition = partition;
    }

    @Override
    public void run() {
      // TODO: how to retry the same jobs upon txConflict?
      jobQueue = Schedulers.getJobQueue(multiThreadDatasetCache, datasetFramework);

      while (!stopping) {
        try {
          long sleepTime = checkJobQueue();
          // Don't sleep if sleepTime returned is 0
          if (sleepTime > 0) {
            TimeUnit.MILLISECONDS.sleep(sleepTime);
          }
        } catch (InterruptedException e) {
          // sleep is interrupted, just exit without doing anything
        }
      }
    }

    /**
     * Check jobs in job queue for constraint satisfaction.
     *
     * @return sleep time in milliseconds before next fetch
     */
    private long checkJobQueue() {
      boolean emptyFetch = false;
      int failureCount = 0;
      try {
        emptyFetch = Transactions.execute(transactional, new TxCallable<Boolean>() {
          @Override
          public Boolean call(DatasetContext context) throws Exception {
            return checkJobConstraints();
          }
        });
        failureCount = 0;
      } catch (Exception e) {
        LOG.warn("Failed to check Job constraints. Will retry in next run", e);
        failureCount++;
      }

      try {
        Transactions.execute(transactional, new TxCallable<Boolean>() {
          @Override
          public Boolean call(DatasetContext context) throws Exception {
            // run any ready jobs
            return runReadyJobs();
          }
        });
      } catch (Exception e) {
        LOG.warn("Failed to launch programs. Will retry in next run", e);
        failureCount++;
      }

      // If there is any failure, delay the next fetch based on the strategy
      if (failureCount > 0) {
        // Exponential strategy doesn't use the time component, so doesn't matter what we passed in as startTime
        return scheduleStrategy.nextRetry(failureCount, 0);
      }

      // Sleep for 2 seconds if there's no jobs in the queue
      return emptyFetch ? 2000L : 0L;
    }

    private boolean checkJobConstraints() throws Exception {
      boolean emptyScan = true;

      try (CloseableIterator<Job> jobQueueIter = jobQueue.getJobs(partition, lastConsumed)) {
        Stopwatch stopWatch = new Stopwatch().start();
        // limit the batches of the scan to 1000ms
        while (!stopping && stopWatch.elapsedMillis() < 1000) {
          if (!jobQueueIter.hasNext()) {
            lastConsumed = null;
            return emptyScan;
          }
          Job job = jobQueueIter.next();
          lastConsumed = job;
          emptyScan = false;
          checkAndUpdateJob(jobQueue, job);
        }
      }
      return emptyScan;
    }

    private void checkAndUpdateJob(JobQueueDataset jobQueue, Job job) {
      if (job.getState() == Job.State.PENDING_LAUNCH) {
        return;
      }
      if (job.getState() == Job.State.PENDING_TRIGGER) {
        if (!isTriggerSatisfied(job)) {
          return;
        }
        job = jobQueue.transitState(job, Job.State.PENDING_CONSTRAINT);
      }
      // check for constraint satisfaction after trigger satisfaction
      if (!constraintsSatisfied(job)) {
        return;
      }
      jobQueue.transitState(job, Job.State.PENDING_LAUNCH);
      readyJobs.add(job);
    }

    private boolean runReadyJobs() throws IOException, DatasetManagementException {
      Iterator<Job> readyJobsIter = readyJobs.iterator();
      while (readyJobsIter.hasNext() && !stopping) {
        Job job = readyJobsIter.next();

        // We should check the stored job's state (whether it actually is PENDING_LAUNCH), because
        // the schedule could have gotten deleted in the meantime or the transaction that marked it as PENDING_LAUNCH
        // may have failed / rolled back.
        Job storedJob = jobQueue.getJob(job.getJobKey());
        if (storedJob == null) {
          continue;
        }

        if (storedJob.getState() == Job.State.PENDING_LAUNCH) {
          ProgramSchedule schedule = job.getSchedule();
          try {
            // TODO: Temporarily execute scheduled program without any checks. Need to check appSpec, scheduleSpec
            taskRunner.execute(schedule.getProgramId(), ImmutableMap.<String, String>of(),
                               ImmutableMap.<String, String>of());
            LOG.info("Successfully started program {} in schedule {}.", schedule.getProgramId(), schedule.getName());
          } catch (Exception e) {
            LOG.warn("Failed to run program {} in schedule {}. Skip running this program.",
                     schedule.getProgramId(), schedule.getName(), e);
            // don't delete the job, as it will be retried in some later iteration over the JobQueue
            continue;
          }
          // this should not have a conflict, because any updates to the job will first check to make sure that
          // it is not PENDING_LAUNCH
          readyJobsIter.remove();
          jobQueue.deleteJob(job);
          return true;
        }
      }
      return false;
    }

    private boolean isTriggerSatisfied(Job job) {
      Trigger trigger = job.getSchedule().getTrigger();
      if (trigger instanceof TimeTrigger) {
        // TimeTrigger is satisfied as soon as the Notification arrive, due to how the Notification is initially created
        return true;
      }
      if (trigger instanceof PartitionTrigger) {
        PartitionTrigger partitionTrigger = (PartitionTrigger) trigger;
        int numPartitions = 0;
        for (Notification notification : job.getNotifications()) {
          String numPartitionsString = notification.getProperties().get("numPartitions");
          numPartitions += Integer.valueOf(numPartitionsString);
        }
        return numPartitions >= partitionTrigger.getNumPartitions();
      }
      throw new IllegalArgumentException("Unknown trigger class: " + trigger.getClass());
    }

    private boolean constraintsSatisfied(Job job) {
      ConstraintContext constraintContext = new ConstraintContext(job, System.currentTimeMillis());
      for (Constraint constraint : job.getSchedule().getConstraints()) {
        if (!(constraint instanceof AbstractCheckableConstraint)) {
          // this shouldn't happen, since all Constraint implementations should extend AbstractConstraint
          throw new IllegalArgumentException("Implementation of Constraint must extend AbstractConstraint");
        }
        AbstractCheckableConstraint abstractConstraint = (AbstractCheckableConstraint) constraint;
        ConstraintResult result = abstractConstraint.check(job.getSchedule(), constraintContext);
        if (result != ConstraintResult.SATISFIED) {
          // if any of the constraints are unsatisfied, return false
          return false;
        }

      }
      return true;
    }

  }

}
