package com.bff.pipeline.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * terraform dispatch 응답의 형태를 아는 단 한 곳이다. InfraManager는 던진 job id의 JSON 배열을 돌려주고,
 * 그 문자열은 태스크를 실행 중으로 옮기는 트랜잭션에서 attempt 행에 그대로 저장된다.
 *
 * 읽는 쪽이 둘이라 여기 모은다. 폴 집계는 무엇을 폴할지 알기 위해 읽고, 승인 요약은 "그 시도가 던진 job이
 * 전부 몇 개였나"의 근거로 읽는다. 두 곳이 각자 파싱하면 응답 형태가 바뀔 때 한쪽만 고쳐질 수 있는데,
 * 그때 요약 쪽은 예외도 실패도 없이 조용히 "검증 불가"로만 남아 아무도 눈치채지 못한다.
 *
 * 해석만 하고 판단하지 않는다 — 빈 배열이나 빈 id를 어떻게 다룰지는 읽는 쪽 사정이라 여기서 정하지 않는다.
 */
public final class TerraformDispatchResponse {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JOB_IDS = new TypeReference<>() { };

    private TerraformDispatchResponse() {
    }

    /**
     * 응답 본문에서 job id 목록을 읽는다. 형태가 어긋나면 {@link JsonProcessingException}을 던져, 그것을
     * 무엇으로 볼지(태스크 실패로 볼지, 요약 검증 불가로 볼지)는 부르는 쪽이 정하게 한다.
     */
    public static List<String> jobIds(String response) throws JsonProcessingException {
        List<String> jobIds = OBJECT_MAPPER.readValue(response, JOB_IDS);
        return jobIds == null ? List.of() : jobIds;
    }
}
