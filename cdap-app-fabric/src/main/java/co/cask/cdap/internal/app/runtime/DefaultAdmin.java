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

package co.cask.cdap.internal.app.runtime;

import co.cask.cdap.api.Admin;
import co.cask.cdap.api.artifact.ArtifactInfo;
import co.cask.cdap.api.artifact.CloseableClassLoader;
import co.cask.cdap.api.messaging.MessagingAdmin;
import co.cask.cdap.api.messaging.TopicAlreadyExistsException;
import co.cask.cdap.api.messaging.TopicNotFoundException;
import co.cask.cdap.api.security.store.SecureStoreManager;
import co.cask.cdap.common.service.Retries;
import co.cask.cdap.common.service.RetryStrategies;
import co.cask.cdap.common.service.RetryStrategy;
import co.cask.cdap.data2.datafabric.dataset.DefaultDatasetManager;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.internal.app.runtime.artifact.DefaultArtifactManager;
import co.cask.cdap.proto.id.NamespaceId;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Implementation of Admin that delegates dataset operations to a dataset framework.
 */
public class DefaultAdmin extends DefaultDatasetManager implements Admin {

  private final SecureStoreManager secureStoreManager;
  private final MessagingAdmin messagingAdmin;
  private final RetryStrategy retryStrategy;
  private final DefaultArtifactManager defaultArtifactManager;
  private final NamespaceId namespaceId;

  /**
   * Creates an instance without messaging admin support.
   */
  public DefaultAdmin(DatasetFramework dsFramework, NamespaceId namespace, SecureStoreManager secureStoreManager,
                      DefaultArtifactManager defaultArtifactManager) {
    this(dsFramework, namespace, secureStoreManager, null, RetryStrategies.noRetry(), defaultArtifactManager);
  }

  /**
   * Creates an instance with all Admin functions supported.
   */
  public DefaultAdmin(DatasetFramework dsFramework, NamespaceId namespace,
                      SecureStoreManager secureStoreManager, @Nullable MessagingAdmin messagingAdmin,
                      RetryStrategy retryStrategy, DefaultArtifactManager defaultArtifactManager) {
    super(dsFramework, namespace, retryStrategy);
    this.secureStoreManager = secureStoreManager;
    this.messagingAdmin = messagingAdmin;
    this.retryStrategy = retryStrategy;
    this.defaultArtifactManager = defaultArtifactManager;
    this.namespaceId = namespace;
  }

  @Override
  public void putSecureData(String namespace, String name, String data,
                            String description, Map<String, String> properties) throws Exception {
    secureStoreManager.putSecureData(namespace, name, data, description, properties);
  }

  @Override
  public void deleteSecureData(String namespace, String name) throws Exception {
    secureStoreManager.deleteSecureData(namespace, name);
  }

  @Override
  public void createTopic(final String topic) throws TopicAlreadyExistsException, IOException {
    if (messagingAdmin == null) {
      throw new UnsupportedOperationException("Messaging not supported");
    }

    try {
      Retries.callWithRetries(new Retries.Callable<Void, Exception>() {
        @Override
        public Void call() throws TopicAlreadyExistsException, IOException {
          messagingAdmin.createTopic(topic);
          return null;
        }
      }, retryStrategy);
    } catch (TopicAlreadyExistsException | IOException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      // this should never happen
      throw new RuntimeException(e);
    }
  }

  @Override
  public void createTopic(final String topic,
                          final Map<String, String> properties) throws TopicAlreadyExistsException, IOException {
    if (messagingAdmin == null) {
      throw new UnsupportedOperationException("Messaging not supported");
    }

    try {
      Retries.callWithRetries(new Retries.Callable<Void, Exception>() {
        @Override
        public Void call() throws TopicAlreadyExistsException, IOException {
          messagingAdmin.createTopic(topic, properties);
          return null;
        }
      }, retryStrategy);
    } catch (TopicAlreadyExistsException | IOException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      // this should never happen
      throw new RuntimeException(e);
    }
  }

  @Override
  public Map<String, String> getTopicProperties(final String topic) throws TopicNotFoundException, IOException {
    if (messagingAdmin == null) {
      throw new UnsupportedOperationException("Messaging not supported");
    }

    try {
      return Retries.callWithRetries(new Retries.Callable<Map<String, String>, Exception>() {
        @Override
        public Map<String, String> call() throws TopicNotFoundException, IOException {
          return messagingAdmin.getTopicProperties(topic);
        }
      }, retryStrategy);
    } catch (TopicNotFoundException | IOException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      // this should never happen
      throw new RuntimeException(e);
    }
  }

  @Override
  public void updateTopic(final String topic,
                          final Map<String, String> properties) throws TopicNotFoundException, IOException {
    if (messagingAdmin == null) {
      throw new UnsupportedOperationException("Messaging not supported");
    }

    try {
      Retries.callWithRetries(new Retries.Callable<Void, Exception>() {
        @Override
        public Void call() throws TopicNotFoundException, IOException {
          messagingAdmin.updateTopic(topic, properties);
          return null;
        }
      }, retryStrategy);
    } catch (TopicNotFoundException | IOException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      // this should never happen
      throw new RuntimeException(e);
    }
  }

  @Override
  public void deleteTopic(final String topic) throws TopicNotFoundException, IOException {
    if (messagingAdmin == null) {
      throw new UnsupportedOperationException("Messaging not supported");
    }

    try {
      Retries.callWithRetries(new Retries.Callable<Void, Exception>() {
        @Override
        public Void call() throws TopicNotFoundException, IOException {
          messagingAdmin.deleteTopic(topic);
          return null;
        }
      }, retryStrategy);
    } catch (TopicNotFoundException | IOException | RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      // this should never happen
      throw new RuntimeException(e);
    }
  }

  @Override
  public List<ArtifactInfo> listArtifacts() throws IOException {
    return defaultArtifactManager.listArtifacts(namespaceId);
  }

  @Override
  public CloseableClassLoader createClassLoader(ArtifactInfo artifactInfo,
                                                @Nullable ClassLoader parentClassLoader) throws Exception {
    return defaultArtifactManager.createClassLoader(namespaceId.getNamespace(), artifactInfo, parentClassLoader);
  }
}
