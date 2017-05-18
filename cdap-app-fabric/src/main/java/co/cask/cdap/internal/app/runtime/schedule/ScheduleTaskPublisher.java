/*
 * Copyright © 2014-2016 Cask Data, Inc.
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

package co.cask.cdap.internal.app.runtime.schedule;

import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.app.store.Store;
import co.cask.cdap.common.ApplicationNotFoundException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.internal.UserErrors;
import co.cask.cdap.internal.UserMessages;
import co.cask.cdap.internal.app.runtime.ProgramOptionConstants;
import co.cask.cdap.messaging.MessagingService;
import co.cask.cdap.messaging.client.StoreRequestBuilder;
import co.cask.cdap.proto.Notification;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.ScheduleId;
import co.cask.cdap.proto.id.TopicId;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Task publisher that sends notification for a triggered schedule.
 */
public final class ScheduleTaskPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduleTaskPublisher.class);

  private static final Gson GSON = new Gson();

  private final MessagingService messagingService;
  private final TopicId topicId;
  private final Store store;

  public ScheduleTaskPublisher(Store store, MessagingService messagingService, TopicId topicId) {
    this.store = store;
    this.messagingService = messagingService;
    this.topicId = topicId;
  }

  /**
   * Publish notification for the triggered schedule
   *
   * @param scheduleId       {@link ScheduleId} of the triggered schedule
   * @param logicalStartTime the scheduled time the trigger fired for
   */
  public void publishNotification(ScheduleId scheduleId, long logicalStartTime)
    throws Exception {

    ApplicationSpecification appSpec = store.getApplication(scheduleId.getParent());
    if (appSpec == null) {
      throw new ApplicationNotFoundException(scheduleId.getParent());
    }

    Map<String, String> properties = new HashMap<>();
    properties.put(ProgramOptionConstants.SCHEDULE_ID, scheduleId.toString());
    properties.put(ProgramOptionConstants.LOGICAL_START_TIME, Long.toString(logicalStartTime));

    Notification notification = new Notification(Notification.Type.TIME, properties);
    messagingService.publish(StoreRequestBuilder.of(topicId).addPayloads(GSON.toJson(notification)).build());
  }
}
