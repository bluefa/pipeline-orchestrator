package com.bff.pipeline.model.terraform;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;

/**
 * 인프라가 발급한 <b>job id로 상태를 폴링</b>해 완료를 판정하는 표준 terraform job이다.
 * 현재로선 유일한 {@link TerraformJob} 종류이며, dispatch 응답의 job id 하나가 이 객체 하나에 대응한다.
 *
 * <p>상태 조회 API 경로가 operation(apply/destroy)마다 다르므로, job은 자신을 만든 {@code operation}을 함께 지녀
 * 폴링 시 InfraManager에 전달한다.
 */
public record JobIdTerraformJob(String jobId, TaskOperation operation) implements TerraformJob {

    @Override
    public TerraformPoll pollStatus(InfraManagerClient infraManager) {
        TerraformPoll poll = infraManager.terraformJobStatus(jobId, operation);
        if (poll == null) {
            throw new InfraManagerClient.CallFailedException("InfraManager returned no status for job " + jobId);
        }
        return poll;
    }
}
