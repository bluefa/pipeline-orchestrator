package com.bff.pipeline.model;

import com.bff.pipeline.client.InstallationOperationsClient.TestConnectionPollResponse;
import java.util.Objects;

/**
 * 연결 테스트 관찰 한 번을 상태 기록 트랜잭션으로 전달한다. 정상 상태, 통신 실패, 상관관계 등의 업무 실패를
 * 구분해 정상 본문을 오류로 덮지 않게 한다. 이 값의 생성은 DB 쓰기를 수반하지 않는다.
 */
public sealed interface TestConnectionObservation {
    record Observed(TestConnectionPollResponse response) implements TestConnectionObservation {
        public Observed { Objects.requireNonNull(response, "response"); }
    }
    record CallFailed(StepOutcome.CallFailure failure) implements TestConnectionObservation {
        public CallFailed { Objects.requireNonNull(failure, "failure"); }
    }
    record Failed(HttpTaskResult result) implements TestConnectionObservation {
        public Failed {
            Objects.requireNonNull(result, "result");
            if (result.success()) throw new IllegalArgumentException("failed observation must carry a failure");
        }
    }
}
