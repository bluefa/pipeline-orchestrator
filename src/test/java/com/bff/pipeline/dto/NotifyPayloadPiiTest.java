package com.bff.pipeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ADR-022 §4 PII 하드 계약을 {@link NotifyPayload} 직렬화 수준에서 고정한다 — (a) 스키마는 허용 7필드뿐이고
 * url 필드는 존재하지 않는다(민감 링크를 채널에 싣지 않도록 링크 필드 자체가 스키마에 없다; 사람용 상세는
 * pipeline_id로 권한 있는 콘솔에서 조회), (b) failed_task는 닫힌 recipe task 키 어휘에 속한다 — 1순위는
 * recipe 진실원인 taskDefinition(TaskDefinition 상수 이름), 정의가 없는 레거시/드레인 전 행은 mechanism
 * 캐시(taskName = TaskOperation mechanism, 부팅 시 TaskTypeRegistry가 등록을 검증하는 닫힌 집합)로
 * fallback하므로 허용 어휘는 두 집합의 합집합이다 — provider/운영자 유래 raw 명 금지, (c) raw 연결 식별자
 * (host/port/credential/DB명) 패턴이 직렬화 결과에 없다 — toTargetRef 밖의 경로로 민감 값이 새면 실패한다,
 * (d) 비-FAILED payload는 failed_task/error_code가 null이다. buildPayload가 sequence 최소 FAILED task에서
 * 값을 채우는 동작은 NotifyLifecycleTest가 검증한다.
 */
class NotifyPayloadPiiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theSchemaCarriesExactlyTheSevenAllowedFieldsAndNoUrl() {
        JsonNode json = mapper.valueToTree(failedPayload());

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "pipelineId", "type", "terminalStatus", "targetRef", "failedTask", "errorCode", "schemaVersion");
        assertThat(json.has("url")).isFalse();   // 허용 목록에 url은 없다 — 스키마 구조상 부재를 회귀로 고정
    }

    @Test
    void failedTaskComesFromTheClosedTaskVocabulary() {
        Set<String> mechanismVocabulary = Arrays.stream(TaskOperation.values())
                .map(TaskOperation::mechanism)
                .collect(Collectors.toSet());
        Set<String> definitionVocabulary = Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::name)
                .collect(Collectors.toSet());

        // fallback 어휘 자체가 고정돼 있고(TaskType.NAME들 — 부팅 시 TaskTypeRegistry가 등록을 검증한다)
        assertThat(mechanismVocabulary).containsExactlyInAnyOrder(
                TaskOperation.Mechanism.TERRAFORM_JOB, TaskOperation.Mechanism.CONDITION_CHECK);
        // 행에 저장되는 taskName(mechanism 캐시, PipelineInserter가 definition.mechanism()에서 파생)도
        // 전부 이 어휘에서만 나온다 — raw provider/운영자 명이 낄 자리가 없다
        assertThat(Arrays.stream(TaskDefinition.values()).map(TaskDefinition::mechanism))
                .allMatch(mechanismVocabulary::contains);
        // failed_task 허용 어휘 = TaskDefinition 상수 이름들(진실원) ∪ mechanism 어휘(레거시 행 fallback)
        Set<String> allowedFailedTaskVocabulary = new HashSet<>(definitionVocabulary);
        allowedFailedTaskVocabulary.addAll(mechanismVocabulary);
        assertThat(failedPayload().failedTask()).isIn(allowedFailedTaskVocabulary);
    }

    @Test
    void theSerializedJsonCarriesNoRawConnectionIdentifiers() throws Exception {
        String json = mapper.writeValueAsString(failedPayload());

        assertThat(json).doesNotContain(
                "host", "port", "password", "credential", "secret", "jdbc:", "://", "@", "database", "account");
    }

    @Test
    void aNonFailedPayloadSerializesNullFailedTaskAndErrorCode() {
        NotifyPayload done = NotifyPayload.builder()
                .pipelineId(7L).type("DELETE").terminalStatus("DONE").targetRef("ts-7")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();

        JsonNode json = mapper.valueToTree(done);

        assertThat(json.get("failedTask").isNull()).isTrue();
        assertThat(json.get("errorCode").isNull()).isTrue();
    }

    /** NotifyClaimer.buildPayload가 만드는 것과 같은 꼴의 FAILED payload — 값은 전부 승인된 어휘다. */
    private static NotifyPayload failedPayload() {
        return NotifyPayload.builder()
                .pipelineId(1234L)
                .type("INSTALL")
                .terminalStatus("FAILED")
                .targetRef("ts-192")   // opaque target 키 — raw 연결 식별자가 아니다
                .failedTask(TaskDefinition.AWS_SERVICE_APPLY_V1.name())   // recipe 진실원인 taskDefinition 이름
                .errorCode(ErrorCode.JOB_FAILED.name())
                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();
    }
}
