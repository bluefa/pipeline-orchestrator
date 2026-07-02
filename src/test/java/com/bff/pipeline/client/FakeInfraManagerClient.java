package com.bff.pipeline.client;

import com.bff.pipeline.dto.ConditionPoll;
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

    @FunctionalInterface
    public interface Result {
        String run();
    }

    /** job id별로 다른 폴 결과가 필요한 다중 job 시나리오용 — 설정 시 {@link Poll}보다 우선한다. */
    @FunctionalInterface
    public interface PollByJob {
        TerraformPoll run(String jobId);
    }

    private Dispatch dispatch = () -> "[\"job-1\"]";
    private Poll poll = TerraformPoll::running;
    private PollByJob pollByJob;
    private Check check = () -> false;
    private Result result = () -> "terraform: ok";
    private CloudProvider cloudProvider = CloudProvider.AWS;

    public void onCloudProvider(CloudProvider cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public void onDispatch(Dispatch dispatch) {
        this.dispatch = dispatch;
    }

    public void onPoll(Poll poll) {
        this.poll = poll;
        this.pollByJob = null;
    }

    public void onPollByJob(PollByJob pollByJob) {
        this.pollByJob = pollByJob;
    }

    public void onCheck(Check check) {
        this.check = check;
    }

    public void onResult(Result result) {
        this.result = result;
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        return dispatch.run();
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId, TaskOperation operation) {
        return pollByJob != null ? pollByJob.run(jobId) : poll.run();
    }

    @Override
    public String terraformJobResult(String jobId, TaskOperation operation) {
        return result.run();
    }

    @Override
    public ConditionPoll checkCondition(String target, TaskOperation operation) {
        boolean met = check.run();
        return new ConditionPoll(met, "{\"met\":" + met + "}");
    }

    @Override
    public CloudProvider cloudProvider(String target) {
        return cloudProvider;
    }
}
