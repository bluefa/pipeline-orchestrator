package com.bff.pipeline.model.terraform;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;

/**
 * 인프라가 발급한 <b>job id로 상태를 폴링</b>해 완료를 판정하는 표준 terraform job이다.
 * 현재 유일한 {@link TerraformJob} 종류이며, dispatch 응답의 각 job id 하나가 이 객체 하나에 대응한다.
 */
public record JobIdTerraformJob(String jobId) implements TerraformJob {

    @Override
    public TerraformPoll pollStatus(InfraManagerClient infraManager) {
        TerraformPoll poll = infraManager.terraformJobStatus(jobId);
        if (poll == null) {
            throw new InfraManagerClient.CallFailedException("InfraManager returned no status for job " + jobId);
        }
        return poll;
    }
}
