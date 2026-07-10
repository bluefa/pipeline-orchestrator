package com.bff.pipeline.repository;

import java.time.Instant;

/**
 * terraform_job_state의 메타데이터 전용 투영이다(P5 attempt 인라인). 응답 원문({@code last_response}, TEXT)을
 * 일부러 제외한다 — task 상세 패널이 attempt별 job 상태를 실을 때 원문 I/O를 지불하지 않기 위해서다
 * ({@code terraform_result}의 본문-없는 메타 투영과 같은 선례). 원문은 per-job 상태 엔드포인트가 행 단위로 조회한다.
 */
public interface TerraformJobStateMetadata {

    int getAttemptNumber();

    String getJobId();

    String getLastState();

    String getLastFailReason();

    String getLastError();

    int getPollCount();

    Instant getLastPolledAt();
}
