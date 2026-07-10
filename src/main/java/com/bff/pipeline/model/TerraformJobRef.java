package com.bff.pipeline.model;

import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;

/**
 * 한 task-attempt 안의 특정 terraform job을 가리키는 경량 핸들이다 — 관찰 테이블의 유니크 키
 * (task_id, attempt_number, job_id)와 1:1이다. 진행-시점·종결 후 관찰 recorder가 이 셋을 매번 나눠 받는 대신
 * 하나로 묶어 job의 관찰 행을 식별한다. taskId만 있으면 되는 recorder도 있고 {@code task.getOperation()}까지
 * 필요한 recorder도 있어, id가 아니라 {@link Task}를 담는다. {@code taskId()}/{@code attemptNumber()}는 자주 쓰는
 * 키 성분을 바로 꺼내는 편의 접근자다.
 */
public record TerraformJobRef(Task task, TaskAttempt attempt, String jobId) {

    public long taskId() {
        return task.getId();
    }

    public int attemptNumber() {
        return attempt.getAttemptNumber();
    }

    /** 로그 한 줄 — "task 42 attempt 1 job job-1". (엔티티 기본 toString은 식별해시라 로그에 쓸모없다.) */
    @Override
    public String toString() {
        return "task " + taskId() + " attempt " + attemptNumber() + " job " + jobId;
    }
}
