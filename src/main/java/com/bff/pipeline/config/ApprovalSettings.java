package com.bff.pipeline.config;

import java.time.Duration;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Apply 승인 게이트의 설정 모음이다. application.yml의 {@code pipeline.approval.*} 키에서 읽고 env로 주입한다.
 *
 * {@code enabled}가 지배하는 범위는 딱 하나다 — 새 파이프라인이 어느 레시피로 만들어지는가.
 * 꺼져 있으면(기본값) 승인 단계가 없는 기존 레시피로 만들어지고, 켜면 승인 단계가 포함된 레시피로
 * 만들어진다. 게이트를 실행하는 쪽(상태 값, 태스크 타입 등록, claim 조건, 상태 전이)은 이 값과 무관하게
 * 항상 살아 있다. 이유는 전환 때문이다: 켰다가 다시 끄는 순간 이미 승인을 기다리던 파이프라인이 영영
 * 잡히지 않게 되면 안 된다.
 *
 * {@code timeout}은 승인을 기다리는 상한이다. 이 시간 안에 아무도 결정하지 않으면 게이트는 만료로
 * 실패하고, 다시 하려면 파이프라인을 재시작한다. 관리자 근무 시간·교대를 감안해 운영이 조정할 값이라
 * 코드 상수가 아니라 설정으로 둔다.
 */
@Builder
@ConfigurationProperties(prefix = "pipeline.approval")
public record ApprovalSettings(
        boolean enabled,
        @DefaultValue("PT24H") Duration timeout) {

    public ApprovalSettings {
        if (timeout == null || !timeout.isPositive()) {
            throw new IllegalArgumentException(
                    "pipeline.approval.timeout must be a positive duration (got " + timeout + ")");
        }
    }
}
