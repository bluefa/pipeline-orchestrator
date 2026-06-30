package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Task} 행(row)의 영속성 계층이다. {@code findByPipelineIdOrderBySequenceAsc}는 pipeline의 task 목록을
 * 체인 순서(chain order)로 반환한다. 엔진은 그 중 sequence 값이 가장 낮은 비종료(non-terminal) task를 현재 task로
 * 선택한다.
 *
 * <p>{@code countByTaskNameAndStatus}는 ADR-021 TF slot soft-gate를 뒷받침한다 — TF dispatch 전에
 * {@code (TERRAFORM_JOB, IN_PROGRESS)} 점유 수를 읽어 {@code slotCap}과 비교한다(count-read이므로 overshoot 허용).
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByPipelineIdOrderBySequenceAsc(Long pipelineId);

    int countByTaskNameAndStatus(String taskName, TaskStatus status);
}
