package com.bff.pipeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * status·condition 응답의 위임 {@code @JsonCreator}가 결측·JSON null·잘못된 타입·여분 필드 body를 NPE 없이
 * 안전하게 디코드하는지 못 박는다(원문 캡처 경계의 견고성). 실 디코더 경로와 동일한 plain {@link ObjectMapper}로
 * 직접 역직렬화해 Feign 경계 마스킹 없이 확인한다 — 어떤 결측도 null로 흘러 하류(toPoll/poll)가 CallFailed로 닫는다.
 */
class RawResponseCaptureDecodeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── TerraformJobStatusResponse: 위임 creator ──

    @Test
    void terraformCapturesFullBodyAndExtractsTypedFields() throws Exception {
        String body = "{\"terraformState\":\"COMPLETED\",\"failReason\":null,\"id\":7,\"type\":\"APPLY\"}";

        TerraformJobStatusResponse r = mapper.readValue(body, TerraformJobStatusResponse.class);

        assertThat(r.terraformState()).isEqualTo("COMPLETED");
        assertThat(r.failReason()).isNull();
        assertThat(r.raw()).isEqualTo(body);   // 타입 필드가 안 읽는 id/type까지 원문 보존
    }

    @Test
    void terraformMissingFieldsDecodeToNullNotNpe() throws Exception {
        TerraformJobStatusResponse r = mapper.readValue("{}", TerraformJobStatusResponse.class);

        assertThat(r.terraformState()).isNull();
        assertThat(r.failReason()).isNull();
        assertThat(r.raw()).isEqualTo("{}");
    }

    @Test
    void terraformExplicitNullStateDecodesToNullNotNpe() throws Exception {
        TerraformJobStatusResponse r = mapper.readValue("{\"terraformState\":null}", TerraformJobStatusResponse.class);

        assertThat(r.terraformState()).isNull();   // isNull() 가드가 MissingNode.asText()="" 함정을 피한다
    }

    @Test
    void terraformJsonNullBodyIsHandledWithoutNpe() throws Exception {
        // JSON null body → 그래이스풀: null DTO이거나 terraformState=null인 DTO. 어느 쪽이든 toPoll이 CallFailed로
        // 닫는다(NPE 아님). fromBody가 NPE를 던지면 이 줄에서 에러로 드러난다.
        TerraformJobStatusResponse r = mapper.readValue("null", TerraformJobStatusResponse.class);

        assertThat(r == null || r.terraformState() == null).isTrue();
    }

    // ── NetworkReadyResponse: 위임 creator ──

    @Test
    void conditionCapturesFullBodyAndTypedMet() throws Exception {
        NetworkReadyResponse r = mapper.readValue("{\"met\":true,\"detail\":\"x\"}", NetworkReadyResponse.class);

        assertThat(r.met()).isTrue();
        assertThat(r.raw()).isEqualTo("{\"met\":true,\"detail\":\"x\"}");   // 타입 DTO가 안 읽는 detail까지 보존
    }

    @Test
    void conditionMissingOrWrongTypeMetDecodesToNullNotNpe() throws Exception {
        // 결측·문자열·숫자 모두 met=null로 흘러 어댑터가 CallFailed로 닫는다(NOT_MET 둔갑·NPE 없음).
        assertThat(mapper.readValue("{}", NetworkReadyResponse.class).met()).isNull();
        assertThat(mapper.readValue("{\"met\":\"nope\"}", NetworkReadyResponse.class).met()).isNull();
        assertThat(mapper.readValue("{\"met\":1}", NetworkReadyResponse.class).met()).isNull();
        assertThat(mapper.readValue("{\"met\":null}", NetworkReadyResponse.class).met()).isNull();
    }

    @Test
    void conditionJsonNullBodyIsHandledWithoutNpe() throws Exception {
        NetworkReadyResponse r = mapper.readValue("null", NetworkReadyResponse.class);

        assertThat(r == null || r.met() == null).isTrue();
    }
}
