package com.nordicframtiden.chat;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.firebase.enabled", havingValue = "true")
public class FirebaseChatPushSender implements ChatPushSender {
  private static final Logger log = LoggerFactory.getLogger(FirebaseChatPushSender.class);
  private final FirebaseMessaging messaging;
  private final ChatPushSubscriptionRepository subscriptions;

  @Autowired
  public FirebaseChatPushSender(FirebaseNotificationProperties properties,
                                ChatPushSubscriptionRepository subscriptions) throws IOException {
    this(messaging(properties), subscriptions);
  }

  FirebaseChatPushSender(FirebaseMessaging messaging, ChatPushSubscriptionRepository subscriptions) {
    this.messaging = messaging;
    this.subscriptions = subscriptions;
  }

  private static FirebaseMessaging messaging(FirebaseNotificationProperties properties) throws IOException {
    FirebaseOptions options = FirebaseOptions.builder()
        .setCredentials(GoogleCredentials.getApplicationDefault())
        .setProjectId(properties.getProjectId())
        .build();
    FirebaseApp app = FirebaseApp.getApps().stream().findFirst()
        .orElseGet(() -> FirebaseApp.initializeApp(options));
    return FirebaseMessaging.getInstance(app);
  }

  @Override
  public void send(String firebaseInstallationId, Map<String, String> data) {
    try {
      Message message = Message.builder()
          .setFid(firebaseInstallationId)
          .putAllData(data)
          .build();
      ApiFutures.addCallback(messaging.sendAsync(message), new ApiFutureCallback<>() {
        @Override public void onSuccess(String result) {
          log.debug("Firebase chat notification completed");
        }
        @Override public void onFailure(Throwable error) {
          if (isPermanentlyUndeliverable(error)) {
            // Like APNs 410/BadDeviceToken: the installation is gone (app
            // uninstalled, ID rotated or from another project), so stop
            // targeting it instead of failing on every message.
            subscriptions.deleteByFirebaseInstallationId(firebaseInstallationId);
            log.debug("Removed unregistered Firebase chat subscription");
            return;
          }
          log.warn("Firebase chat notification could not be delivered: {}",
              error.getClass().getSimpleName());
        }
      }, MoreExecutors.directExecutor());
    } catch (RuntimeException error) {
      log.warn("Firebase chat notification could not be queued: {}",
          error.getClass().getSimpleName());
    }
  }

  /**
   * UNREGISTERED and SENDER_ID_MISMATCH mean the target can never receive
   * pushes from this project. INVALID_ARGUMENT is deliberately excluded: FCM
   * also uses it for malformed payloads, which says nothing about the device.
   */
  static boolean isPermanentlyUndeliverable(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof FirebaseMessagingException messagingError) {
        MessagingErrorCode code = messagingError.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH;
      }
    }
    return false;
  }
}
