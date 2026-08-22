package com.bff.pipeline.service;

import com.bff.pipeline.config.ApprovalSettings;
import com.bff.pipeline.service.task.ApprovalGateTask;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 슬라이스 테스트에 승인 게이트 기반 빈을 공급한다. 이 빈들이 없으면 컨텍스트가 아예 뜨지 않는다 —
 * 레시피 카탈로그가 설정을 주입받고, 태스크 타입 레지스트리가 모든 operation에 대응하는 구현이 등록됐는지
 * 부팅에서 검사하기 때문이다. 그 검사는 설정을 꺼도 우회되지 않는다(꺼도 게이트 실행 경로는 항상 살아
 * 있어야 한다는 규칙이 여기서도 그대로 적용된다).
 *
 * 기본값은 꺼짐이라 이 설정을 그대로 쓰는 테스트는 승인 단계가 없는 기존 레시피로 실행을 만든다.
 * 게이트를 실제로 거치는 테스트는 자기 컨텍스트에서 켜진 설정 빈을 따로 선언한다.
 */
@TestConfiguration
public class ApprovalTestWiring {

    @Bean
    public ApprovalSettings approvalSettings() {
        return ApprovalSettings.builder().enabled(false).timeout(Duration.ofHours(24)).build();
    }

    @Bean
    public ApprovalGateTask approvalGateTask(ApprovalSettings approvalSettings, Clock clock) {
        return new ApprovalGateTask(approvalSettings, clock);
    }
}
