package com.bff.pipeline.exception;

import org.springframework.http.HttpStatus;

/**
 * 승인·반려 요청에 승인자 식별자가 없다. 이 값은 BFF가 검증된 세션에서 채워 보내는 것이므로 비어 있다는
 * 것은 호출 계약이 깨졌다는 뜻이고, 승인자를 모르는 결정은 기록해 봐야 감사에 쓸 수 없어 400으로 거절한다.
 */
public class ApproverRequiredException extends OrchestrationException {

    public ApproverRequiredException() {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.APPROVER_REQUIRED,
                "approver_id is required to record an approval decision");
    }
}
