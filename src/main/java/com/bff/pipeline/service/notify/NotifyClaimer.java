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
 * notify claim 트랜잭션 — 알림 가능한 종단·미알림 pipeline 하나를 SKIP LOCKED로 잡아 notify 전용 fencing
 * token과 lease({@code notify_claimed_by/until})를 찍는다(ADR-022 §2). payload는 행이 로드된 같은 claim
 * 트랜잭션 안에서 이미 커밋된 pipeline/task 행만 읽어 구성한다 — 도메인 상태는 어떤 것도 바꾸지 않는다.
 *
 * 실행과의 격리(중요): notify는 {@code notify_claimed_by/until}만 쓰고 실행의 {@code claimed_by/until}은
 * 건드리지 않는다. 실행의 admission soft-cap은 {@code countByClaimedUntilAfter}로 활성 lease를 상태 무관하게
 * 세므로, lease 컬럼을 공유하면 종단 행의 notify lease가 그 카운트를 부풀려 실행 처리량을 깎는다 — 전용
 * 컬럼쌍이 이 오염을 원천 차단한다. 종단 행은 실행 claim 술어(RUNNING/PENDING 한정)에 절대 안 걸리므로
 * 두 lease가 같은 행에서 경합할 일도 없다.
 *
 * FAILED 파이프라인의 payload는 sequence 최소의 FAILED task에서 {@code failedTask}/{@code errorCode}를
 * 채운다(비-FAILED는 null) — failedTask는 recipe 진실원인 taskDefinition 우선, 열화 행은 mechanism 캐시
 * fallback. PII 하드 계약(ADR-022 §4): {@code targetRef}는 전용 매핑 지점
 * {@link #toTargetRef}에서만 나오고, raw hostname·account·DB명 등 민감 연결 식별자는 payload에 절대
 * 직렬화하지 않는다(MUST NOT) — NotifyPayloadPiiTest가 이 규칙을 강제한다.
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
        return notifyRepository.findNextNotifiable(now, settings.maxAttempts())
                .map(pipeline -> {
                    String token = UUID.randomUUID().toString();
                    pipeline.setNotifyClaimedBy(token);
                    pipeline.setNotifyClaimedUntil(now.plus(settings.leaseDuration()));
                    return new NotifyClaim(pipeline.getId(), token, buildPayload(pipeline));
                });
    }

    /**
     * 허용 필드만 싣는 payload를 구성한다. type은 write-once 캐시라 미해석 옛 값이 null로 열화할 수 있어
     * null-guard가 필수다(NPE 방지). failedTask는 닫힌 recipe task 키(ADR-022 §4)만 나간다 — recipe 정체성의
     * 진실원인 taskDefinition(TaskDefinition 상수 이름)을 우선하고, 정의가 비어 있는 레거시/드레인 전 행은
     * mechanism 캐시인 taskName으로 fallback한다({@link #toFailedTaskKey}). errorCode는 승인된
     * {@link ErrorCode} 이름만 나간다.
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
     * 실패 task → 닫힌 recipe task 키. taskDefinition이 recipe 정체성의 진실원(TaskDefinition 상수 이름)이라
     * 우선하고, taskName은 mechanism 캐시라 여러 step이 같은 값을 공유하므로(예: 한 recipe의 모든 terraform
     * step이 TERRAFORM_JOB) 정의가 없는 레거시/드레인 전 행의 fallback으로만 쓴다. 두 어휘 모두 enum 유래의
     * 닫힌 집합이다(NotifyPayloadPiiTest가 강제).
     */
    private static String toFailedTaskKey(Task failed) {
        return failed.getTaskDefinition() != null ? failed.getTaskDefinition() : failed.getTaskName();
    }

    /** 실패 task = sequence 최소의 FAILED task. 기존 체인 조회(findByPipelineIdOrderBySequenceAsc)를 재사용한다. */
    private Optional<Task> firstFailedTask(Long pipelineId) {
        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipelineId).stream()
                .filter(task -> task.getStatus() == TaskStatus.FAILED)
                .findFirst();
    }

    /**
     * 파이프라인 대상 → opaque 알림 참조. V1: 이미 opaque한 target 키(target-source 식별자)를 그대로 쓴다.
     * target 필드가 raw 식별자를 담게 되면 여기서 해싱/치환해 opaque 핸들만 내보내도록 바꾼다(유일한 변경
     * 지점). 연결 상세(host/port/credential)는 여기서도 접근하지 않는다(ADR-022 §4 MUST NOT).
     */
    private static String toTargetRef(Pipeline pipeline) {
        return pipeline.getTarget();
    }
}
