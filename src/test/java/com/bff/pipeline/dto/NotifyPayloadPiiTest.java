package com.bff.pipeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifyPayload}가 직렬화될 때 민감 정보가 새지 않는다는 규칙을 회귀 테스트로 고정한다.
 *
 * 고정하는 규칙:
 * (a) 스키마에는 허용된 10개 필드만 있다. 링크는 detailUrl 하나만 허용된다(오너 결정 2026-07-10) —
 *     파이프라인 상세 화면 주소에 id만 붙인 값이고, 대상 정보가 담긴 다른 링크 필드는 여전히 없다.
 * (b) failed_task 값은 정해진 단계 이름 목록 안에서만 나온다. 1순위는 taskDefinition(TaskDefinition
 *     상수 이름)이고, 정의가 없는 옛 행은 taskName(TaskOperation의 mechanism 값 — 부팅 시
 *     TaskTypeRegistry가 등록을 검증하는 정해진 집합)으로 대신하므로, 허용 목록은 두 집합의 합집합이다.
 *     provider나 운영자가 지은 raw 이름은 낄 수 없다.
 * (c) detailUrl(콘솔 주소라 host 단어가 정상적으로 들어간다)을 뺀 직렬화 결과에 raw 연결 식별자
 *     (host/port/credential/DB명) 패턴이 없다. toTargetRef 밖의 경로로 민감 값이 새면 이 테스트가 실패한다.
 * (d) FAILED가 아닌 payload는 failed_task/error_code가 null이다.
 * buildPayload가 sequence가 가장 앞선 FAILED task에서 값을 채우고 detailUrl을 설정된 base + id로만
 * 조립하는 동작 자체는 TerminalNotifierTest가 검증한다.
 */
class NotifyPayloadPiiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theSchemaCarriesExactlyTheTenAllowedFieldsAndOnlyTheDetailLink() {
        JsonNode json = mapper.valueToTree(failedPayload());

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "pipelineId", "type", "terminalStatus", "targetRef", "cloudProvider", "environment",
                "failedTask", "errorCode", "detailUrl", "schemaVersion");
        // 링크가 들어갈 수 있는 자리는 detailUrl 하나뿐이라는 사실을 회귀로 고정한다 —
        // url 같은 범용 링크 필드가 생기면 대상 상세가 채널로 새는 문이 열린다.
        assertThat(json.has("url")).isFalse();
    }

    @Test
    void failedTaskComesFromTheClosedTaskVocabulary() {
        Set<String> mechanismVocabulary = Arrays.stream(TaskOperation.values())
                .map(TaskOperation::mechanism)
                .collect(Collectors.toSet());
        Set<String> definitionVocabulary = Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::name)
                .collect(Collectors.toSet());

        // 대체용 이름 목록 자체가 고정돼 있고(TaskType.NAME들 — 부팅 시 TaskTypeRegistry가 등록을 검증한다)
        assertThat(mechanismVocabulary).containsExactlyInAnyOrder(
                TaskOperation.Mechanism.TERRAFORM_JOB, TaskOperation.Mechanism.CONDITION_CHECK);
        // 행에 저장되는 taskName(PipelineInserter가 definition.mechanism()에서 만드는 값)도
        // 전부 이 목록에서만 나온다 — provider나 운영자가 지은 raw 이름이 낄 자리가 없다
        assertThat(Arrays.stream(TaskDefinition.values()).map(TaskDefinition::mechanism))
                .allMatch(mechanismVocabulary::contains);
        // failed_task 허용 목록 = TaskDefinition 상수 이름들(1순위) ∪ mechanism 이름들(옛 행 대체용)
        Set<String> allowedFailedTaskVocabulary = new HashSet<>(definitionVocabulary);
        allowedFailedTaskVocabulary.addAll(mechanismVocabulary);
        assertThat(failedPayload().failedTask()).isIn(allowedFailedTaskVocabulary);
    }

    @Test
    void theSerializedJsonCarriesNoRawConnectionIdentifiersOutsideTheDetailLink() throws Exception {
        // detailUrl은 콘솔 주소라 "://"와 host 단어가 정상적으로 들어간다 — 그 필드만 빼고 나머지
        // 전체에 연결 식별자 패턴이 없어야 한다. detailUrl 자체의 형태(base + id뿐)는
        // TerminalNotifierTest가 고정한다.
        ObjectNode withoutDetailLink = mapper.valueToTree(failedPayload());
        withoutDetailLink.remove("detailUrl");
        String json = mapper.writeValueAsString(withoutDetailLink);

        assertThat(json).doesNotContain(
                "host", "port", "password", "credential", "secret", "jdbc:", "://", "@", "database", "account");
    }

    @Test
    void aNonFailedPayloadSerializesNullFailedTaskAndErrorCode() {
        NotifyPayload done = NotifyPayload.builder()
                .pipelineId(7L).type("DELETE").terminalStatus("DONE").targetRef("ts-7")
                .cloudProvider("GCP").environment("stg")
                .detailUrl("http://localhost:3001/integration/admin/pipelines/7")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();

        JsonNode json = mapper.valueToTree(done);

        assertThat(json.get("failedTask").isNull()).isTrue();
        assertThat(json.get("errorCode").isNull()).isTrue();
    }

    /** TerminalNotifier.buildPayload가 만드는 것과 같은 꼴의 FAILED payload. 값은 전부 허용 목록 안의 것이다. */
    private static NotifyPayload failedPayload() {
        return NotifyPayload.builder()
                .pipelineId(1234L)
                .type("INSTALL")
                .terminalStatus("FAILED")
                .targetRef("ts-192")   // target 키 — raw 연결 식별자가 아니다
                .cloudProvider("AWS")
                .environment("prd")
                .failedTask(TaskDefinition.AWS_SERVICE_APPLY_V1.name())   // 1순위 원천인 taskDefinition 이름
                .errorCode(ErrorCode.JOB_FAILED.name())
                .detailUrl("http://localhost:3001/integration/admin/pipelines/1234")
                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();
    }
}
