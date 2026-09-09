package com.bff.pipeline.service.task;

import com.bff.pipeline.client.InstallationOperationsClient.Recommendation;
import com.bff.pipeline.client.InstallationOperationsClient.VerifiedContext;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.entity.TaskConfirmationInput;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.model.ConfirmationInput;
import com.bff.pipeline.model.ConfirmationInput.ExecutionContext;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskConfirmationInputRepository;
import com.bff.pipeline.utils.HttpResponses;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task별 입력의 생성·짧은 읽기·최초 캡처를 담당한다. 입력 쓰기는 외부 호출 밖에서, 생성 또는 점유 확인을 마친
 * 상태 기록 트랜잭션에만 참여한다. 서로 다른 Task에 같은 정의가 반복돼도 키와 본문은 공유하지 않는다.
 * provider 유실은 빈 읽기 결과로, 입력행 유실은 별도의 실행 맥락으로 구분하여 호출 전에 종결할 수 있게 한다.
 * 입력 키 파생식은 정의 V1의 계약이며 배포 이후에도 바꾸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TaskConfirmationInputs {
    private final TaskConfirmationInputRepository inputs;
    private final PipelineRepository pipelines;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialize(Task task, boolean applyNlbSecurityGroup) {
        if (task.getOperation() != TaskOperation.CONFIRM_RESOURCES_FROM_RECOMMENDATION) return;
        inputs.save(TaskConfirmationInput.builder().taskId(task.getId()).requestKey(requestKey(task))
                .applyNlbSecurityGroup(applyNlbSecurityGroup).build());
    }

    @Transactional(readOnly = true)
    public Optional<ExecutionContext> read(Task task) {
        return pipelines.findById(task.getPipelineId()).map(Pipeline::getCloudProvider)
                .map(provider -> readContext(task, provider));
    }

    private ExecutionContext readContext(Task task, CloudProvider provider) {
        return inputs.findByTaskId(task.getId())
                .<ExecutionContext>map(input -> new ExecutionContext.WithInput(provider, snapshot(input)))
                .orElseGet(() -> new ExecutionContext.WithoutInput(provider));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long capture(Task task, Recommendation recommendation) {
        TaskConfirmationInput input = inputs.findByTaskId(task.getId())
                .orElseThrow(() -> new IllegalStateException("Confirmation input disappeared during write-back"));
        if (input.getRecommendationBody() != null) {
            if (!input.getRecommendationBody().equals(recommendation.exchange().body())) {
                throw new IllegalStateException("Confirmation input is write-once");
            }
            return input.getId();
        }
        input.setRecommendationBody(recommendation.exchange().body());
        input.setBodyDigest(HttpResponses.digest(recommendation.exchange().body()));
        input.setApprovalVersion(recommendation.context().approvalVersion());
        input.setTargetGeneration(recommendation.context().targetGeneration());
        input.setSourceAttemptNumber(task.getFailCount() + 1);
        input.setHttpStatusCode(recommendation.exchange().statusCode());
        input.setResponseContentType(recommendation.exchange().contentType());
        input.setResponseReceivedAt(recommendation.exchange().receivedAt());
        input.setCapturedAt(clock.instant());
        inputs.save(input);
        return input.getId();
    }

    /** 입력 정의 V1의 고정 파생식이다. 배포나 재시도에서도 저장된 Task의 멱등 정체성을 바꾸지 않는다. */
    public static String requestKey(Task task) {
        return "confirmation-input:v1:task:" + task.getId();
    }

    private static ConfirmationInput snapshot(TaskConfirmationInput input) {
        return ConfirmationInput.builder().id(input.getId()).requestKey(input.getRequestKey())
                .applyNlbSecurityGroup(input.isApplyNlbSecurityGroup()).body(input.getRecommendationBody())
                .digest(input.getBodyDigest()).context(VerifiedContext.builder().approvalVersion(input.getApprovalVersion())
                        .targetGeneration(input.getTargetGeneration()).build()).build();
    }
}
