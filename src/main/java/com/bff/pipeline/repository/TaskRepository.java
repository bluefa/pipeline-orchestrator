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
 * <p><b>슬롯 게이트 카운트 (ADR-021, S2):</b>
 * {@code countByTaskNameAndStatus}는 Terraform 잡(job) 슬롯 소프트 게이트(slotCap)를 위해
 * 특정 {@code taskName}과 {@code status} 조합에 해당하는 task 수를 반환한다. 주로
 * {@code TERRAFORM_JOB} task 이름과 {@code IN_PROGRESS} 상태로 호출되어 현재 점유 중인 TF 슬롯 수를
 * 계산한다(ADR-021 Decision 7). {@code idx_task_name_status} 인덱스({@code task_name, status})가
 * 이 카운트 조회를 커버한다.
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByPipelineIdOrderBySequenceAsc(Long pipelineId);

    int countByTaskNameAndStatus(String taskName, TaskStatus status);
}
