package com.bff.pipeline.enums;

import java.util.Optional;

/**
 * 자동 설치 이벤트 한 건을 받아 어떻게 처리했는지의 결과다. 이벤트마다 정확히 하나가 install_event 행에 남는다.
 */
public enum InstallEventOutcome {
    /** 설치 파이프라인을 새로 열었다. 행의 pipeline_id가 그 파이프라인이다. */
    STARTED,
    /** 같은 대상에 이미 진행 중인 파이프라인이 있어 열지 않았다. 행의 pipeline_id가 막고 있던 파이프라인이다. */
    ALREADY_ACTIVE,
    /** 이벤트의 cloud provider가 자동 설치 허용 대상(Azure, IDC)이 아니라 보류했다. */
    PROVIDER_HELD,
    /** 본문을 읽을 수 없거나(JSON 아님, 필수 필드 누락) 값이 계약과 맞지 않아 아무것도 하지 않았다. 행의 reason에 이유가 있다. */
    INVALID;

    /** 저장된 이름을 상수로 해석한다. 미해석은 empty — InstallEventOutcomeConverter가 읽기에 쓴다. */
    public static Optional<InstallEventOutcome> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InstallEventOutcome.valueOf(name));
        } catch (IllegalArgumentException notAnOutcome) {
            return Optional.empty();
        }
    }
}
