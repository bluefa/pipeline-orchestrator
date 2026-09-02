package com.bff.pipeline.config;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 자동 설치 이벤트 구독 설정이다. application.yml의 {@code pipeline.install-event.*} 키에서 읽고, 값은 모두
 * 환경변수로 주입한다. 대상 단계가 4단계로 넘어가는 순간 발행되는 이벤트를 GCP Pub/Sub 구독으로 받아
 * 오케스트레이터가 스스로 설치 파이프라인을 여는 기능(InstallEventSubscriber)의 스위치다.
 *
 * 각 설정의 의미:
 * - {@code enabled}: 구독 스위치. 끄면(기본값) 구독 클라이언트를 만들지 않는다. 부팅 때 한 번 읽으므로
 *   켜고 끄는 변경은 재시작해야 반영된다.
 * - {@code projectId}: 구독이 속한 GCP 프로젝트 id.
 * - {@code subscription}: 구독 이름(토픽이 아니라 subscription). 인증은 실행 환경의 기본 자격증명(ADC)을 쓴다.
 *
 * 켜는 배포는 projectId와 subscription을 반드시 함께 줘야 하고, 빠뜨리면 서버가 뜨는 시점에 어느 키가
 * 문제인지 메시지에 담아 바로 실패시킨다. 꺼진 배포는 전부 생략할 수 있다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline.install-event")
public record InstallEventSettings(
        boolean enabled,
        String projectId,
        String subscription) {

    public InstallEventSettings {
        if (enabled) {
            requireNonBlank(projectId, "pipeline.install-event.project-id");
            requireNonBlank(subscription, "pipeline.install-event.subscription");
        }
    }

    private static void requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    property + " must be set when pipeline.install-event.enabled is true");
        }
    }
}
