package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.TaskStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Task} 행의 영속성 계층이다. {@code findByPipelineIdOrderBySequenceAsc}는 pipeline의 task를 체인 순서로
 * 돌려주고, 엔진은 그 중 sequence가 가장 낮은 비종료(non-terminal) task를 현재 task로 고른다.
 *
 * {@code countByConsumesTerraformSlotIsTrueAndStatus}는 ADR-021 슬롯 soft-gate를 받쳐준다. 슬롯을 소비하는
 * dispatch 전에, 행에 캐시된 {@code consumes_terraform_slot} 플래그가 참인 {@code IN_PROGRESS} 점유 수를 읽어
 * {@code terraformSlotCap}과 견준다. 카탈로그 변경·정의 삭제와 무관하게 정확히 센다(설계 §4). 단순 count-read라
 * overshoot는 허용한다.
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByPipelineIdOrderBySequenceAsc(Long pipelineId);

    int countByConsumesTerraformSlotIsTrueAndStatus(TaskStatus status);
}
