/*
 * Copyright © 2016 Cask Data, Inc.
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

package co.cask.cdap.internal.app.namespace;

import co.cask.cdap.api.data.stream.StreamSpecification;
import co.cask.cdap.api.metrics.MetricStore;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.config.DashboardStore;
import co.cask.cdap.config.PreferencesStore;
import co.cask.cdap.data.stream.service.StreamHandler;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.transaction.queue.QueueAdmin;
import co.cask.cdap.data2.transaction.stream.StreamAdmin;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.schedule.Scheduler;
import co.cask.cdap.internal.app.services.ApplicationLifecycleService;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.impersonation.Impersonator;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Implementation of {@link NamespaceResourceDeleter} used in local mode.
 */
public class LocalNamespaceResourceDeleter extends AbstractNamespaceResourceDeleter {

  private static final Logger LOG = LoggerFactory.getLogger(LocalNamespaceResourceDeleter.class);

  private final StreamAdmin streamAdmin;
  private final StreamHandler streamHandler;

  @Inject
  LocalNamespaceResourceDeleter(Impersonator impersonator, Store store, PreferencesStore preferencesStore,
                                DashboardStore dashboardStore, DatasetFramework dsFramework, QueueAdmin queueAdmin,
                                MetricStore metricStore, Scheduler scheduler,
                                ApplicationLifecycleService applicationLifecycleService,
                                ArtifactRepository artifactRepository,
                                StorageProviderNamespaceAdmin storageProviderNamespaceAdmin,
                                MessagingService messagingService, StreamAdmin streamAdmin,
                                StreamHandler streamHandler) {
    super(impersonator, store, preferencesStore, dashboardStore, dsFramework, queueAdmin, metricStore,
          scheduler, applicationLifecycleService, artifactRepository, storageProviderNamespaceAdmin, messagingService);
    this.streamAdmin = streamAdmin;
    this.streamHandler = streamHandler;

  }

  @Override
  protected void deleteStreams(NamespaceId namespaceId) throws Exception {
    // delete all streams
    List<StreamSpecification> streams = streamAdmin.listStreams(namespaceId);
    for (StreamSpecification specification : streams) {
      streamHandler.deleteStream(namespaceId.getNamespace(), specification.getName());
    }
  }
}
