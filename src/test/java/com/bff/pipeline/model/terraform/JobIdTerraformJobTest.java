package com.bff.pipeline.model.terraform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.TaskOperation;
import org.junit.jupiter.api.Test;

/**
 * {@link JobIdTerraformJob}이 자기 job id로 상태를 조회하고, InfraManager가 상태를 돌려주지 못하면
 * (null) 비즈니스 결과가 아니라 호출 실패({@link InfraManagerClient.CallFailedException})로 신호함을 검증한다.
 */
class JobIdTerraformJobTest {

    @Test
    void pollStatusReturnsTheInfraStatusForItsJobId() {
        TerraformPoll expected = TerraformPoll.success();
        TerraformJob job = new JobIdTerraformJob("job-7", TaskOperation.APPLY_NETWORK);

        assertThat(job.pollStatus(infraReturning("job-7", expected))).isEqualTo(expected);
    }

    @Test
    void pollStatusTreatsAMissingStatusAsACallFailure() {
        TerraformJob job = new JobIdTerraformJob("job-7", TaskOperation.APPLY_NETWORK);

        assertThatThrownBy(() -> job.pollStatus(infraReturning("job-7", null)))
                .isInstanceOf(InfraManagerClient.CallFailedException.class)
                .hasMessageContaining("job-7");
    }

    private InfraManagerClient infraReturning(String expectedJobId, TerraformPoll status) {
        return new InfraManagerClient() {
            @Override
            public String runTerraform(String target, TaskOperation operation) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
                assertThat(jobId).isEqualTo(expectedJobId);
                return status;
            }

            @Override
            public boolean checkCondition(String target, TaskOperation operation) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.bff.pipeline.enums.CloudProvider cloudProvider(String target) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
