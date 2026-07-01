package com.bff.pipeline.repository;

import com.bff.pipeline.enums.TaskStatus;

/** pipeline별·status별 task 집계 투영(목록 진행 N/M 배치 계산, N+1 회피). */
public interface PipelineTaskStatusCount {

    Long getPipelineId();

    TaskStatus getStatus();

    long getCount();
}
