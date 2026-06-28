package com.bff.pipeline.im;

import com.bff.pipeline.domain.TaskOperation;

/**
 * A scriptable {@link ImClient} for tests — set the behavior before a tick, then assert the
 * resulting state (the project uses fakes, not Mockito; see the spring-java21 skill §4).
 */
public final class FakeImClient implements ImClient {

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

    private Dispatch dispatch = () -> "job-1";
    private Poll poll = TerraformPoll::running;
    private Check check = () -> false;

    public void onDispatch(Dispatch dispatch) {
        this.dispatch = dispatch;
    }

    public void onPoll(Poll poll) {
        this.poll = poll;
    }

    public void onCheck(Check check) {
        this.check = check;
    }

    /** Block past the per-call timeout, to drive a CALL_TIMEOUT. */
    public static void sleepPastTimeout() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String runTerraform(String target, TaskOperation operation) {
        return dispatch.run();
    }

    @Override
    public TerraformPoll terraformJobStatus(String jobId) {
        return poll.run();
    }

    @Override
    public boolean checkCondition(String target, TaskOperation operation) {
        return check.run();
    }
}
