package com.bff.pipeline.repository;

import com.bff.pipeline.entity.Task;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Task} 행(row)의 영속성 계층이다. {@code findByPipelineIdOrderBySequenceAsc}는 pipeline의 task 목록을
 * 체인 순서(chain order)로 반환한다. 엔진은 그 중 sequence 값이 가장 낮은 비종료(non-terminal) task를 현재 task로
 * 선택한다.
 */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByPipelineIdOrderBySequenceAsc(Long pipelineId);
}
