package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.config.InstallEventSettings;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * GCP Pub/Sub 구독에서 자동 설치 이벤트를 받아 {@link InstallEventHandler}에 넘기는 수신부다. 설정
 * ({@code pipeline.install-event.*})이 꺼져 있으면(기본값) 아무것도 만들지 않는다. 켜져 있으면 애플리케이션이 완전히
 * 뜬 뒤 스트리밍 구독을 시작하고 종료 때 멈춘다. 인증은 실행 환경의 기본 자격증명(ADC)이다.
 *
 * <p>메시지 하나의 처리가 정상 판정으로 끝나면 ack하고, 예외로 끝나면(provider 조회 실패, DB 장애) nack해 Pub/Sub이
 * 다시 전달하게 둔다. 영구히 실패하는 메시지를 끊는 것은 구독 쪽 설정(dead-letter, 최대 전달 횟수)의 몫이다.
 * 파이프라인 중복 생성은 target당 활성 하나 제약이 막으므로 재전달은 ALREADY_ACTIVE 기록으로 끝난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstallEventSubscriber {

    static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final InstallEventHandler handler;
    private final InstallEventSettings settings;
    private Subscriber subscriber;

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!settings.enabled()) {
            log.info("install event subscriber disabled (pipeline.install-event.enabled=false)");
            return;
        }
        ProjectSubscriptionName name = ProjectSubscriptionName.of(settings.projectId(), settings.subscription());
        subscriber = Subscriber.newBuilder(name, this::receive).build();
        subscriber.startAsync().awaitRunning();
        log.info("install event subscriber started subscription={}", name);
    }

    @PreDestroy
    void stop() {
        if (subscriber == null) {
            return;
        }
        subscriber.stopAsync();
        try {
            subscriber.awaitTerminated(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException stillRunning) {
            log.warn("install event subscriber did not stop within {}", STOP_TIMEOUT);
        }
    }

    void receive(PubsubMessage message, AckReplyConsumer reply) {
        try {
            handler.handle(message.getMessageId(), message.getData().toStringUtf8());
            reply.ack();
        } catch (RuntimeException failure) {
            log.warn("install event handling failed, leaving message for redelivery message={}",
                    message.getMessageId(), failure);
            reply.nack();
        }
    }
}
