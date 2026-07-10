package com.bff.pipeline.dto;

import lombok.Builder;

/**
 * 알림에 실어 보내는 내용이다. 민감 정보 유출을 막기 위해 허용된 필드만 담는다.
 * {@code targetRef}에는 대상의 안전한 참조 값(target 키/id)만 넣는다.
 * raw hostname·계정·DB 이름 같은 민감한 연결 식별자는 절대 직렬화하지 않는다.
 * {@code failedTask}에는 정해진 목록 안의 recipe 단계 이름만, {@code errorCode}에는 승인된
 * 에러 코드 이름만 넣는다 — 둘 다 FAILED일 때만 채워지고 아니면 null이다.
 * raw 예외 메시지를 담을 필드는 스키마에 아예 없다.
 * {@code type}은 지금의 enum이 해석하지 못하는 옛 값이면 null로 읽힐 수 있고(INSTALL | DELETE | CUSTOM | null),
 * {@code cloudProvider}도 같은 이유(또는 미지정 파이프라인)로 null일 수 있다(AWS | GCP | AZURE | IDC | null).
 * {@code environment}는 설정에서 오는 배포 환경 이름(stg, prd 등)이다 — 여러 환경이 한 채널을
 * 공유할 때 알림을 구분한다.
 * {@code detailUrl}은 이 스키마에서 유일하게 허용되는 링크다. 파이프라인 상세 화면 주소
 * (설정된 base)에 파이프라인 id만 붙여 만들고(오너 결정 2026-07-10), 대상 정보가 담긴 다른
 * 링크는 여전히 금지다.
 */
@Builder
public record NotifyPayload(
        long pipelineId,
        String type,
        String terminalStatus,
        String targetRef,
        String cloudProvider,
        String environment,
        String failedTask,
        String errorCode,
        String detailUrl,
        String schemaVersion) {

    /** payload 스키마 버전. 알림을 받는 쪽이 형식이 바뀌었는지 구분하는 기준 값이다. */
    public static final String SCHEMA_VERSION = "2";
}
