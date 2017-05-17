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

package co.cask.cdap.internal.app.runtime.schedule.store;

import co.cask.cdap.api.data.DatasetContext;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.schedule.RunConstraints;
import co.cask.cdap.api.schedule.Schedule;
import co.cask.cdap.api.schedule.ScheduleSpecification;
import co.cask.cdap.data2.datafabric.dataset.DatasetsUtil;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.app.runtime.schedule.ProgramSchedule;
import co.cask.cdap.internal.app.runtime.schedule.constraint.ConcurrencyConstraint;
import co.cask.cdap.internal.app.runtime.schedule.queue.JobQueueDataset;
import co.cask.cdap.internal.app.runtime.schedule.trigger.StreamSizeTrigger;
import co.cask.cdap.internal.app.runtime.schedule.trigger.TimeTrigger;
import co.cask.cdap.internal.schedule.StreamSizeSchedule;
import co.cask.cdap.internal.schedule.TimeSchedule;
import co.cask.cdap.internal.schedule.constraint.Constraint;
import co.cask.cdap.internal.schedule.trigger.Trigger;
import co.cask.cdap.proto.ProtoConstraint;
import co.cask.cdap.proto.ProtoTrigger;
import co.cask.cdap.proto.ScheduleDetail;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.StreamId;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Common utility methods for scheduling.
 */
public class Schedulers {
  public static final String STORE_TYPE_NAME = ProgramScheduleStoreDataset.class.getName();
  public static final DatasetId STORE_DATASET_ID = NamespaceId.SYSTEM.dataset("schedule.store");
  public static final DatasetId JOB_QUEUE_DATASET_ID = NamespaceId.SYSTEM.dataset("job.queue");

  public static final Type SCHEDULE_DETAILS_TYPE = new TypeToken<List<ScheduleDetail>>() { }.getType();

  public static String triggerKeyForPartition(DatasetId datasetId) {
    return "partition:" + datasetId.getNamespace() + '.' + datasetId.getDataset();
  }

  public static JobQueueDataset getJobQueue(DatasetContext context, DatasetFramework dsFramework) {
    try {
      return DatasetsUtil.getOrCreateDataset(context, dsFramework, JOB_QUEUE_DATASET_ID,
                                             JobQueueDataset.class.getName(), DatasetProperties.EMPTY);
    } catch (DatasetManagementException | IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static ProgramScheduleStoreDataset getScheduleStore(DatasetContext context, DatasetFramework dsFramework) {
    try {
      return DatasetsUtil
        .getOrCreateDataset(context, dsFramework, STORE_DATASET_ID, STORE_TYPE_NAME, DatasetProperties.EMPTY);
    } catch (DatasetManagementException | IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static ProgramSchedule toProgramSchedule(Schedule schedule, ProgramId programId,
                                                  Map<String, String> properties) {
    Trigger trigger;
    if (schedule instanceof TimeSchedule) {
      TimeSchedule timeSchedule = (TimeSchedule) schedule;
      trigger = new TimeTrigger(((TimeSchedule) timeSchedule).getCronEntry());
    } else {
      StreamSizeSchedule streamSchedule = (StreamSizeSchedule) schedule;
      StreamId streamId = new StreamId(programId.getNamespace(), streamSchedule.getStreamName());
      trigger = new StreamSizeTrigger(streamId, streamSchedule.getDataTriggerMB());
    }
    Integer maxConcurrentRuns = schedule.getRunConstraints().getMaxConcurrentRuns();
    List<Constraint> constraints = maxConcurrentRuns == null ? ImmutableList.<Constraint>of() :
      ImmutableList.<Constraint>of(new ConcurrencyConstraint(maxConcurrentRuns));
    return new ProgramSchedule(schedule.getName(), schedule.getDescription(),
                               programId, properties, trigger, constraints);
  }

  /**
   * Convert a list of program schedules into a list of schedule details.
   */
  public static List<ScheduleDetail> toScheduleDetails(List<ProgramSchedule> schedules) {
    return Lists.transform(schedules, new Function<ProgramSchedule, ScheduleDetail>() {
      @Nullable
      @Override
      public ScheduleDetail apply(@Nullable ProgramSchedule input) {
        return input == null ? null : input.toScheduleDetail();
      }
    });
  }

  /**
   * Convert a list of schedule details to a list of schedule specifications, for backward compatibility.
   *
   * Schedules with triggets other than time or stream size triggers are ignored, so are run constraints
   * other than concurrency consraints (these were not supported by the legacy APIs).
   */
  public static List<ScheduleSpecification> toScheduleSpecs(List<ScheduleDetail> details) {
    List<ScheduleSpecification> specs = new ArrayList<>();
    for (ScheduleDetail detail : details) {
      if (detail.getTrigger() instanceof ProtoTrigger.TimeTrigger) {
        ProtoTrigger.TimeTrigger trigger = ((ProtoTrigger.TimeTrigger) detail.getTrigger());
        RunConstraints constraints = RunConstraints.NONE;
        if (detail.getConstraints() != null) {
          for (Constraint runConstraint : detail.getConstraints()) {
            if (runConstraint instanceof ProtoConstraint.ConcurrenyConstraint) {
              constraints = new RunConstraints(
                ((ProtoConstraint.ConcurrenyConstraint) runConstraint).getMaxConcurrency());
            }
          }
        }
        specs.add(new ScheduleSpecification(
          new TimeSchedule(detail.getName(),
                           detail.getDescription(),
                           trigger.getCronExpression(),
                           constraints),
          detail.getProgram(),
          detail.getProperties()));
      }
    }
    return specs;
  }
}
