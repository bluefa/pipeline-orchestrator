package com.bff.pipeline.client;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.TaskOperation;

/**
 * 테스트용 스크립터블(scriptable) {@link InfraManagerClient}이다. 각 tick 전에 동작을 설정하고 결과
 * 상태를 검증하는 방식으로 사용한다. 이 프로젝트는 Mockito 대신 fake를 사용한다(spring-java21 skill §4
 * 참조).
 */
public final class FakeInfraManagerClient implements InfraManagerClient {

    @FunctionalInterface
    public interface Dispatch {
        String run();
    }

    @FunctionalInterface
    public interface Poll {
        TerraformPoll run();
    }

    @FunctionalInterface
    public interface Check {
        boolean run();
    }

    private Dispatch dispatch = () -> "[\"job-1\"]";
    private Poll poll = TerraformPoll::running;
    private Check check = () -> false;
    private CloudProvider cloudProvider = CloudProvider.AWS;

    public void onCloudProvider(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public void onDispatch(Dispatch dispatch) {
        this.dispatch = dispatch;
    }

    public void onPoll(Poll poll) {
        this.poll = poll;
    }

    public void onCheck(Check check) {
        this.check = check;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        return dispatch.run();
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
        return poll.run();
    }

    @Override
    public boolean checkCondition(String target, TaskOperation operation) {
        return check.run();
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        return cloudProvider;
    }
}
