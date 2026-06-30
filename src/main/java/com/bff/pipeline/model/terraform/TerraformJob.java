package com.bff.pipeline.model.terraform;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;

/**
 * 한 번의 dispatch가 만든 N개 terraform job 중 <b>하나</b>를 나타낸다. 각 job은 자신의 완료 상태를
 * <em>스스로</em> 조회한다 — 즉 자기 InfraManager 호출과 그 해석을 직접 소유한다. {@link TerraformTask}는
 * 응답을 job 목록으로 역직렬화한 뒤 각 job의 {@link #pollStatus} 결과를 task 단위로 집계한다
 * (whole-task 재시도: 하나라도 실패하면 task 전체가 재시도된다).
 *
 * <p>job 종류가 늘어나면(예: job id로 폴링하지 않는 job) 집계 로직을 고치는 게 아니라 이 인터페이스의
 * 새 구현을 추가한다 — 완료 판정 시나리오는 전적으로 각 구현의 몫이다.
 */
public interface TerraformJob {

    /**
     * 이 job 하나의 완료 상태를 조회한다. 구현은 자기 방식대로 InfraManager를 호출하고 결과를
     * {@link TerraformPoll}(finished/succeeded)로 정규화해 돌려준다. <em>호출 실패</em>만
     * {@code RuntimeException}으로 신호한다(비즈니스 결과는 예외가 아니다).
     */
    TerraformPoll pollStatus(InfraManagerClient infraManager);
}
