package com.mindcare.domain.notification.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** TRD 17장 FCM 푸시. 인증용으로 이미 초기화된 FirebaseApp을 같이 쓴다. */
@Slf4j
public class FcmPushSender implements PushSender {

    // 토큰 자체가 죽은 경우만 지운다 (일시 오류는 다음 알림 때 다시 시도)
    private static final Set<MessagingErrorCode> DEAD_TOKEN = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.SENDER_ID_MISMATCH);

    @Override
    public List<String> send(List<String> tokens, String title, String body, Map<String, String> data) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .build();
        try {
            BatchResponse response = FirebaseMessaging.getInstance(FirebaseApp.getInstance())
                    .sendEachForMulticast(message);
            List<String> dead = new ArrayList<>();
            List<SendResponse> results = response.getResponses();
            for (int i = 0; i < results.size(); i++) {
                FirebaseMessagingException error = results.get(i).getException();
                if (error != null && DEAD_TOKEN.contains(error.getMessagingErrorCode())) {
                    dead.add(tokens.get(i));
                }
            }
            log.info("push sent tokens={} success={} dead={}", tokens.size(), response.getSuccessCount(),
                    dead.size());
            return dead;
        } catch (FirebaseMessagingException e) {
            // 푸시는 부가 기능이라 실패해도 인앱 알림은 남아 있다
            log.warn("push failed code={}", e.getMessagingErrorCode());
            return List.of();
        }
    }
}
