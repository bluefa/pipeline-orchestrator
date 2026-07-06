package com.bff.pipeline.repository;

import java.time.Instant;

/**
 * terraform_result의 메타데이터 전용 투영이다(P5 attempt 인라인). 본문({@code result}, MEDIUMTEXT 최대 16MB)을
 * 일부러 제외한다 — task 상세 패널이 attempt별 result 메타를 실을 때 로그 I/O를 지불하지 않기 위해서다.
 * 본문 존재 여부는 {@code hasBody}로만 알려 주고, 실제 본문은 본문 전용 엔드포인트가 행 단위로 조회한다.
 */
public interface TerraformResultMetadata {

    int getAttemptNumber();

    String getJobId();

    boolean getSucceeded();

    boolean getTruncated();

    String getResultPath();

    boolean getHasBody();

    Instant getCreatedAt();
}
