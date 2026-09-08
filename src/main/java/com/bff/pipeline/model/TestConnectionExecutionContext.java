package com.bff.pipeline.model;

import com.bff.pipeline.enums.CloudProvider;
import java.time.Instant;
import lombok.Builder;

/** 외부 호출 전에 짧게 읽은 연결 테스트의 불변 실행 맥락이다. 실행 행 유실은 조회 서비스의 Optional 반환값으로 구분한다. */
@Builder
public record TestConnectionExecutionContext(String requestKey, String executionVersion,
        Instant deadlineAt, CloudProvider provider, String target) { }
