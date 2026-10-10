package com.nordicframtiden.chat;

import com.google.api.core.ApiFutures;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirebaseChatPushSenderTest {

  private static FirebaseMessagingException fcmError(MessagingErrorCode code) {
    FirebaseMessagingException error = mock(FirebaseMessagingException.class);
    when(error.getMessagingErrorCode()).thenReturn(code);
    return error;
  }

  private static void sendFailingWith(Throwable error, ChatPushSubscriptionRepository subscriptions) {
    FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    when(messaging.sendAsync(any(Message.class))).thenReturn(ApiFutures.immediateFailedFuture(error));
    new FirebaseChatPushSender(messaging, subscriptions).send("fid-gone", Map.of("type", "chat.message"));
  }

  @Test
  void unregisteredInstallationIsDeletedLikeApnsBadDeviceToken() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);

    sendFailingWith(fcmError(MessagingErrorCode.UNREGISTERED), subscriptions);

    verify(subscriptions).deleteByFirebaseInstallationId("fid-gone");
  }

  @Test
  void senderIdMismatchIsDeleted() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);

    sendFailingWith(fcmError(MessagingErrorCode.SENDER_ID_MISMATCH), subscriptions);

    verify(subscriptions).deleteByFirebaseInstallationId("fid-gone");
  }

  @Test
  void transientOrPayloadErrorsKeepTheSubscription() {
    ChatPushSubscriptionRepository subscriptions = mock(ChatPushSubscriptionRepository.class);

    sendFailingWith(fcmError(MessagingErrorCode.UNAVAILABLE), subscriptions);
    sendFailingWith(fcmError(MessagingErrorCode.INVALID_ARGUMENT), subscriptions);
    sendFailingWith(new IllegalStateException("network"), subscriptions);

    verify(subscriptions, never()).deleteByFirebaseInstallationId(any());
  }
}
