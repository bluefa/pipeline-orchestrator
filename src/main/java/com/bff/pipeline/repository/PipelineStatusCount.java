package com.bff.pipeline.repository;

import com.bff.pipeline.enums.PipelineStatus;

/** status별 pipeline 집계 투영(P2 기간 통계). */
public interface PipelineStatusCount {

    PipelineStatus getStatus();

    long getCount();
}
