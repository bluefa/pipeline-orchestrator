package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import com.bff.pipeline.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림을 보낼 파이프라인 하나를 점유하는 트랜잭션이다. 흐름은 이렇다.
 * 끝난 상태인데 아직 알림이 나가지 않은 파이프라인 하나를 잠가서 가져온다(다른 서버가 잠근 행은 건너뛴다).
 * 점유 확인용 토큰(UUID)을 새로 발급해 {@code notify_claimed_by}에 적고,
 * 점유 만료 시각을 {@code notify_claimed_until}에 적는다.
 * 이 토큰은 나중에 전송 결과를 기록할 때 "이 점유가 아직 유효한가"를 확인하는 열쇠다.
 * 점유한 서버가 죽어도 만료 시각이 지나면 다른 서버가 이어받는다.
 * 조회 조건에는 설정의 도입 시각({@code settings.enabledAfter()})을 넘긴다 —
 * 그보다 먼저 끝난 옛 파이프라인은 알림 대상이 아니다.
 * 보낼 내용(payload)은 행을 가져온 같은 트랜잭션 안에서, 이미 커밋된 pipeline/task 행만 읽어 만든다.
 * 도메인 상태(파이프라인 진행 상태 등)는 아무것도 바꾸지 않는다.
 *
 * 실행 쪽과의 분리(중요): 알림은 {@code notify_claimed_by/until} 컬럼만 쓰고,
 * 실행이 쓰는 {@code claimed_by/until}은 건드리지 않는다.
 * 실행 쪽은 동시 실행 수를 제한할 때({@code countByClaimedUntilAfter}) 파이프라인 상태와 무관하게
 * "점유가 살아 있는 행"을 전부 센다. 그래서 컬럼을 같이 쓰면 끝난 파이프라인의 알림 점유가
 * 그 수에 섞여 들어가 실행 처리량을 깎는다. 전용 컬럼을 쓰면 이 문제가 아예 생기지 않는다.
 * 또 끝난 파이프라인은 실행 조회(RUNNING/PENDING만 본다)에 절대 걸리지 않으므로,
 * 두 점유가 같은 행에서 부딪힐 일도 없다.
 *
 * FAILED 파이프라인이면 순서(sequence)가 가장 앞선 FAILED task에서 {@code failedTask}와
 * {@code errorCode}를 채운다. FAILED가 아니면 둘 다 null이다. failedTask 값은 레시피의 어느 단계인지
 * 정확히 가리키는 taskDefinition을 우선 쓰고, 그 값이 없는 옛 행은 taskName으로 대신한다.
 *
 * 보안 규칙(반드시 지킬 것): {@code targetRef}는 전용 변환 지점 {@link #toTargetRef}에서만 만들고,
 * raw hostname·계정·DB 이름 같은 민감한 연결 식별자는 payload에 절대 싣지 않는다.
 * 이 규칙은 NotifyPayloadPiiTest가 테스트로 강제한다.
 */
@Component
public class NotifyClaimer {

    private final NotifyRepository notifyRepository;
    private final TaskRepository taskRepository;
    private final NotifySettings settings;
    private final Clock clock;

    public NotifyClaimer(NotifyRepository notifyRepository, TaskRepository taskRepository,
            NotifySettings settings, Clock clock) {
        this.notifyRepository = notifyRepository;
        this.taskRepository = taskRepository;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Optional<NotifyClaim> claimOne() {
        Instant now = clock.instant();
        return notifyRepository.findNextNotifiable(now, settings.maxAttempts(), settings.enabledAfter())
                .map(pipeline -> {
                    String token = UUID.randomUUID().toString();
                    pipeline.setNotifyClaimedBy(token);
                    pipeline.setNotifyClaimedUntil(now.plus(settings.leaseDuration()));
                    return new NotifyClaim(pipeline.getId(), token, buildPayload(pipeline));
                });
    }

    /**
     * 허용된 필드만 실은 payload를 만든다.
     * type은 생성할 때 한 번 쓰고 다시 안 바꾸는 값이라, 지금의 enum이 해석하지 못하는 옛 값은 null로
     * 읽힌다 — null 확인 없이 {@code .name()}을 부르면 NPE가 나므로 확인이 필수다.
     * failedTask에는 정해진 목록 안의 단계 이름만 나간다. 레시피의 어느 단계인지 정확히 가리키는
     * taskDefinition(TaskDefinition 상수 이름)을 우선 쓰고, 정의가 비어 있는 옛 행은 taskName으로
     * 대신한다({@link #toFailedTaskKey}). errorCode는 승인된 {@link ErrorCode} 이름만 나간다.
     */
    private NotifyPayload buildPayload(Pipeline pipeline) {
        Optional<Task> failedTask = pipeline.getStatus() == PipelineStatus.FAILED
                ? firstFailedTask(pipeline.getId())
                : Optional.empty();
        return NotifyPayload.builder()
                .pipelineId(pipeline.getId())
                .type(pipeline.getType() == null ? null : pipeline.getType().name())
                .terminalStatus(pipeline.getStatus().name())
                .targetRef(toTargetRef(pipeline))
                .failedTask(failedTask.map(NotifyClaimer::toFailedTaskKey).orElse(null))
                .errorCode(failedTask.map(Task::getErrorCode).map(ErrorCode::name).orElse(null))
                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();
    }

    /**
     * 실패한 task에서 알림에 실을 단계 이름을 고른다.
     * taskDefinition(TaskDefinition 상수 이름)은 레시피의 어느 단계인지 정확히 가리키므로 우선한다.
     * taskName은 실행 방식만 나타내는 값이라 여러 단계가 같은 이름을 공유한다
     * (예: 한 레시피의 모든 terraform 단계가 TERRAFORM_JOB). 그래서 taskDefinition이 없는 옛 행에서만
     * 대신 쓴다. 두 값 모두 enum에서 나오는 정해진 이름 목록이라 임의 문자열이 끼지 못한다
     * (NotifyPayloadPiiTest가 강제한다).
     */
    private static String toFailedTaskKey(Task failed) {
        return failed.getTaskDefinition() != null ? failed.getTaskDefinition() : failed.getTaskName();
    }

    /** 실패한 task란 sequence가 가장 앞선 FAILED task를 말한다. 기존 체인 조회(findByPipelineIdOrderBySequenceAsc)를 그대로 쓴다. */
    private Optional<Task> firstFailedTask(Long pipelineId) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId).stream()
                .filter(task -> task.getStatus() == TaskStatus.FAILED)
                .findFirst();
    }

    /**
     * 파이프라인의 대상을 알림에 실을 참조 값으로 바꾼다.
     * 지금(V1)은 target 키(target-source 식별자)가 그 자체로 아무것도 드러내지 않는 안전한 값이라 그대로 쓴다.
     * 나중에 target 필드가 raw 식별자를 담게 되면 여기서 해싱이나 치환으로 가려서 내보내도록 바꾼다 —
     * 바꿀 곳은 이 메서드 하나뿐이다.
     * 연결 상세(host/port/credential)는 여기서도 읽지 않는다. 민감 정보를 알림에 싣지 않는 것이 하드 규칙이다.
     */
    private static String toTargetRef(Pipeline pipeline) {
        return pipeline.getTarget();
    }
}
