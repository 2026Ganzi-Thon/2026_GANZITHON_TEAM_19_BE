package com.mindcare.domain.notification.push;

import java.util.List;
import java.util.Map;

/**
 * 푸시 발송. 운영은 FcmPushSender, Firebase 키가 없는 로컬·CI는 아무것도 안 보내는 구현.
 * 반환값: 더 이상 쓸 수 없는 토큰 (브라우저 알림 해제·만료). 호출한 쪽이 지운다.
 */
public interface PushSender {

    List<String> send(List<String> tokens, String title, String body, Map<String, String> data);
}
