package com.mindcare.global.config;

import com.mindcare.realtime.StompAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * TRD 14-1·14-5: STOMP 전송 설정. 세션 당사자 권한은 각 도메인 service가 확인하고,
 * 연결 인증·구독 제한은 StompAuthInterceptor가 한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final String[] allowedOrigins;
    private final StompAuthInterceptor stompAuthInterceptor;

    public WebSocketConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins,
            StompAuthInterceptor stompAuthInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.stompAuthInterceptor = stompAuthInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // TRD 14-5: heart-beat 10초/10초
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(wsHeartbeatTaskScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // TRD 14-5: STOMP 프레임 16KB
        registration.setMessageSizeLimit(16 * 1024)
                .setSendTimeLimit(10_000)
                .setSendBufferSizeLimit(512 * 1024);
    }

    // 스프링 내부 빈 이름(messageBrokerTaskScheduler)과 겹치면 안 된다
    @Bean
    public TaskScheduler wsHeartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setPoolSize(1);
        return scheduler;
    }
}
