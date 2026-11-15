package com.mindcare.realtime;

import java.security.Principal;

/** STOMP 연결의 로그인 사용자. name은 users.id(UUID) 문자열이라 convertAndSendToUser의 대상과 같다. */
public record StompPrincipal(String name) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}
