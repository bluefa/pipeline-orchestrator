package com.bff.pipeline.model;

import com.bff.pipeline.client.InstallationOperationsClient.VerifiedContext;
import com.bff.pipeline.enums.CloudProvider;
import java.util.Optional;
import lombok.Builder;

/** 외부 호출 전에 짧게 읽은 입력의 불변 사본이다. null 본문은 아직 조회하지 않은 정상 생성 상태다. */
@Builder
public record ConfirmationInput(Long id, String requestKey, boolean applyNlbSecurityGroup, String body,
        String digest, VerifiedContext context) {
    /** provider를 읽은 실행 맥락이며, 입력행의 존재 여부를 별도 값으로 구분한다. */
    public sealed interface ExecutionContext {
        CloudProvider provider();
        Optional<ConfirmationInput> input();

        record WithoutInput(CloudProvider provider) implements ExecutionContext {
            public Optional<ConfirmationInput> input() {
                return Optional.empty();
            }
        }

        record WithInput(CloudProvider provider, ConfirmationInput confirmationInput) implements ExecutionContext {
            public Optional<ConfirmationInput> input() {
                return Optional.of(confirmationInput);
            }
        }
    }
}
