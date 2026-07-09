package com.bff.pipeline.service.notify;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * 끝난(DONE, FAILED, CANCELLED) 파이프라인을 Slack으로 알리는 루프다.
 * "notify-scheduler" 데몬 스레드 하나가 {@code POLL_INTERVAL}마다 한 바퀴(sweep) 돌면서,
 * 아직 알림이 나가지 않은 파이프라인을 하나씩 처리해 밀린 게 없어질 때까지 반복한다.
 *
 * 한 건의 처리는 트랜잭션 하나로 끝난다:
 * 행 잠금(다른 서버가 잠근 행은 건너뜀) → 보낼 내용 조립 → Slack 호출 → 결과 기록 → 커밋.
 * Slack 호출이 트랜잭션 안에 있는 것은 의도된 설계다. 잠금을 쥔 채 호출하므로
 * 다른 서버가 같은 행을 동시에 보낼 수 없고, 커밋 전에 서버가 죽으면 아무 기록도 남지 않아
 * 다음 sweep이 처음부터 재시도한다 — 별도의 점유 컬럼이나 토큰 없이 행 잠금 하나로
 * 유실 방지와 중복 방지를 모두 얻는다. 이 방식이 성립하는 조건: 호출 시간에 상한이 있고
 * ({@code SlackNotifier.CALL_TIMEOUT}), 스레드가 pod당 1개라 이런 트랜잭션이 pod당 최대 1개이며,
 * 끝난 파이프라인 행을 잠그려는 다른 작업이 없다. 이 조건이 깨지면(전송처 추가, 타임아웃 대폭 증가,
 * 종단 행을 갱신하는 배치 도입) 전송을 트랜잭션 밖으로 빼는 분리 설계로 바꿔야 한다.
 *
 * 전송이 실패하면 시도 횟수를 1 올리고 다음 재시도 시각을 {@code 횟수 × RETRY_DELAY_STEP} 뒤로 잡는다.
 * {@code MAX_ATTEMPTS}번 실패하면 자동 재시도를 멈추고(give-up) ERROR 로그를 남긴다.
 * 그 뒤로는 사람이 개입해야 한다 — DB에서 {@code notify_attempts}를 0으로 되돌리면 다시 보낸다.
 * 멈춘 건이 잊히지 않도록 {@code GIVE_UP_ALERT_POLL_INTERVAL}마다 개수를 세서
 * 0보다 크면 ERROR 로그로 다시 경보한다.
 *
 * Slack이 실제로는 받았는데 커밋 전에 서버가 죽는 아주 드문 타이밍에는 같은 알림이 두 번 갈 수 있다.
 * 유실(장애를 못 알림)이 중복보다 나쁘다고 보고 최소 한 번 전달을 택했다. 그래서 메시지에
 * pipeline_id를 항상 넣어 사람이 중복임을 알아볼 수 있게 한다.
 *
 * 알림 기능이 꺼져 있으면({@code pipeline.notify.enabled=false}, 기본값) 루프가 아예 돌지 않는다.
 * 꺼진 동안 끝난 파이프라인은 미전송 상태로 쌓여 있다가, 켜고 재기동하면 도입 시각
 * ({@code enabledAfter}) 이후에 끝난 것들만 그때 나간다.
 * 자세한 배경은 ADR-022 참조.
 */
@Slf4j
@Component
public class TerminalNotifier {

    /** 이 횟수만큼 전송에 실패하면 자동 재시도를 멈추고 사람 개입을 기다린다. */
    public static final int MAX_ATTEMPTS = 3;
    /** 재시도 간격의 단위. n번째 실패 후에는 n × 이 값 뒤에 다시 시도한다(1분 → 2분). */
    static final Duration RETRY_DELAY_STEP = Duration.ofMinutes(1);
    /** sweep 주기. 새로 끝난 파이프라인은 최대 이 시간 안에 알림이 나간다. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    /** 부팅 직후 첫 sweep까지의 대기 시간. 애플리케이션이 완전히 뜨기 전에 도는 것을 피한다. */
    static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
    /** 자동 재시도가 멈춘 건의 재경보 주기. sweep 주기와 무관하게 이 간격마다 한 번만 개수를 센다. */
    static final Duration GIVE_UP_ALERT_POLL_INTERVAL = Duration.ofMinutes(5);

    private final PipelineRepository pipelineRepository;
    private final TaskRepository taskRepository;
    private final SlackNotifier slackNotifier;
    private final NotifySettings settings;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "notify-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private Instant nextGiveUpAlertPollAt;

    public TerminalNotifier(PipelineRepository pipelineRepository, TaskRepository taskRepository,
            SlackNotifier slackNotifier, NotifySettings settings, TransactionTemplate transactionTemplate,
            Clock clock) {
        this.pipelineRepository = pipelineRepository;
        this.taskRepository = taskRepository;
        this.slackNotifier = slackNotifier;
        this.settings = settings;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.nextGiveUpAlertPollAt = clock.instant();   // 첫 sweep에서 즉시 1회 경보 폴링한다
    }

    @PostConstruct
    void start() {
        if (!settings.enabled()) {
            log.info("terminal notifier disabled (pipeline.notify.enabled=false)");
            return;
        }
        loop.scheduleWithFixedDelay(this::runSweep, INITIAL_DELAY.toMillis(), POLL_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
    }

    /**
     * 한 바퀴: 멈춘 건 경보를 확인하고, 보낼 파이프라인이 없어질 때까지 한 건씩 처리한다.
     * 예외는 여기서 잡아 WARN으로 남긴다 — scheduleWithFixedDelay는 예외가 새어 나가면
     * 이후 실행을 전부 멈추기 때문에, 한 바퀴의 실패가 루프를 죽이지 않게 막는다.
     * 전달 실패가 아닌 예외(버그, DB 순단)가 한 건 처리 중에 나면 그 바퀴가 통째로 끝나고
     * 다음 바퀴가 같은 행부터 다시 시작한다 — 행 단위로 건너뛰지 않는 이유는, 그런 예외는
     * 조용히 넘어갈 일이 아니라 반복 WARN으로 드러나야 하는 문제이기 때문이다.
     */
    void runSweep() {
        try {
            alertOnGivenUpBacklog();
            while (deliverOne()) {
                // 밀린 알림을 이 바퀴에서 전부 배수한다. 실패한 행은 재시도 시각이 미래로
                // 잡혀 이 바퀴에서 다시 잡히지 않으므로 이 반복은 반드시 끝난다.
            }
        } catch (RuntimeException sweepFailure) {
            log.warn("notify sweep failed", sweepFailure);
        }
    }

    /**
     * 알림 한 건을 트랜잭션 하나로 처리한다. 반환값은 "일감이 있었는가"다.
     * 성공하면 {@code notified_at}에 시각을 적어 그 파이프라인을 알림 대상에서 영구히 빼고,
     * 남아 있던 재시도 예약({@code notify_next_at})을 지운다.
     * 실패하면 시도 횟수와 다음 재시도 시각을 적는다 — 전달 실패 예외는 트랜잭션 안에서 잡아서
     * 실패 기록이 롤백되지 않고 커밋되게 한다. catch가 감싸는 범위는 전달 호출 하나,
     * 잡는 종류는 SlackNotifier가 던지는 RestClientException 하나뿐이다.
     * 시도 횟수는 "실제로 호출했는데 실패했다"일 때만 올려야 하기 때문이다. 그래서 조립이나
     * 기록 단계에서 난 예외, 전달 코드의 버그(RestClientException이 아닌 예외)는
     * 트랜잭션을 롤백시키고 runSweep의 WARN으로 올라간다.
     */
    boolean deliverOne() {
        return Boolean.TRUE.equals(transactionTemplate.execute(transactionStatus -> {
            Optional<Pipeline> next = pipelineRepository.findNextNotifiable(
                    clock.instant(), MAX_ATTEMPTS, settings.enabledAfter());
            if (next.isEmpty()) {
                return false;
            }
            Pipeline pipeline = next.get();
            NotifyPayload payload = buildPayload(pipeline);
            try {
                slackNotifier.deliver(settings.slackWebhookUrl(), payload);
            } catch (RestClientException deliveryFailed) {   // harness-allow: targeted-catch — 전송 경계: SlackNotifier는 전달 실패를 비밀값(webhook 주소)을 지운 RestClientException으로만 던지고, 여기서 잡아 재시도 기록·자동 재시도 중단으로 수렴시킨다. 그 외 예외는 버그라서 잡지 않는다(트랜잭션 롤백 후 runSweep의 WARN으로 드러난다). 더 큰 알림은 give-up 경보가 맡는다.
                recordFailure(pipeline, deliveryFailed);
                return true;
            }
            log.info("notify delivered pipeline={} status={} attempt={} sink=slack resp_class=2xx",
                    pipeline.getId(), pipeline.getStatus(), pipeline.getNotifyAttempts() + 1);
            pipeline.setNotifiedAt(clock.instant());
            pipeline.setNotifyNextAt(null);   // 남아 있던 재시도 예약을 지운다(나중에 원인 분석할 때 헷갈리지 않게)
            return true;
        }));
    }

    /**
     * 전송 실패를 같은 트랜잭션 안에서 기록한다. 시도 횟수를 1 올리고,
     * 상한 아래면 다음 재시도 시각을 {@code 횟수 × RETRY_DELAY_STEP} 뒤로 잡는다.
     * 상한에 닿으면 재시도 시각을 비우고 ERROR 로그를 남긴다 — 재시도가 멈추므로 이 컬럼에
     * 남은 값은 더 이상 의미가 없고, 그 행을 다시 잡히지 않게 막는 것은 조회 조건의
     * 시도 횟수 비교다.
     */
    private void recordFailure(Pipeline pipeline, RestClientException deliveryFailed) {
        int attempts = pipeline.getNotifyAttempts() + 1;
        pipeline.setNotifyAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            pipeline.setNotifyNextAt(null);
            log.error("notify give-up pipeline={} status={} after {} attempts sink=slack — 자동 재시도 중단. "
                    + "운영자 개입 필요(notify_attempts를 0으로 리셋하면 재전송)",
                    pipeline.getId(), pipeline.getStatus(), attempts);
        } else {
            pipeline.setNotifyNextAt(clock.instant().plus(RETRY_DELAY_STEP.multipliedBy(attempts)));
        }
        // 예외 객체를 로그에 통째로 넘기지 않는다 — SlackNotifier가 지운 메시지(응답 분류)만 싣는다.
        // 원본 예외의 메시지에는 webhook 주소(비밀값)가 들어갈 수 있었고, 그 차단이 SlackNotifier의 계약이다.
        log.warn("notify delivery failed pipeline={} status={} attempt={} sink=slack resp_class=\"{}\"",
                pipeline.getId(), pipeline.getStatus(), attempts, deliveryFailed.getMessage());
    }

    /**
     * 허용된 필드만 실은 payload를 만든다.
     * type은 생성할 때 한 번 쓰고 다시 안 바꾸는 값이라, 지금의 enum이 해석하지 못하는 옛 값은 null로
     * 읽힌다 — null 확인 없이 {@code .name()}을 부르면 NPE가 나므로 확인이 필수다.
     * FAILED 파이프라인이면 순서(sequence)가 가장 앞선 FAILED task에서 failedTask와 errorCode를 채우고,
     * 아니면 둘 다 null이다.
     *
     * 보안 규칙(반드시 지킬 것): {@code targetRef}는 전용 변환 지점 {@link #toTargetRef}에서만 만들고,
     * raw hostname·계정·DB 이름 같은 민감한 연결 식별자는 payload에 절대 싣지 않는다.
     * 이 규칙은 NotifyPayloadPiiTest가 테스트로 강제한다.
     */
    NotifyPayload buildPayload(Pipeline pipeline) {
        Optional<Task> failedTask = pipeline.getStatus() == PipelineStatus.FAILED
                ? firstFailedTask(pipeline.getId())
                : Optional.empty();
        return NotifyPayload.builder()
                .pipelineId(pipeline.getId())
                .type(pipeline.getType() == null ? null : pipeline.getType().name())
                .terminalStatus(pipeline.getStatus().name())
                .targetRef(toTargetRef(pipeline))
                .failedTask(failedTask.map(TerminalNotifier::toFailedTaskKey).orElse(null))
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

    /**
     * 자동 재시도가 멈춘 건의 재경보다. 폴링 주기가 지났으면 개수를 세고, 0보다 크면 ERROR 로그를 남긴다.
     * give-up 순간의 ERROR 한 번을 운영자가 놓쳐도, 처리될 때까지 이 경보가 반복돼 잊히지 않는다.
     * 다음 폴링 시각은 개수 조회가 성공한 뒤에 미룬다 — 조회가 실패하면(DB 순단 등) 5분을
     * 기다리지 않고 다음 sweep에서 바로 다시 시도한다.
     */
    private void alertOnGivenUpBacklog() {
        Instant now = clock.instant();
        if (now.isBefore(nextGiveUpAlertPollAt)) {
            return;
        }
        long givenUpCount = pipelineRepository.countGivenUp(MAX_ATTEMPTS);
        nextGiveUpAlertPollAt = now.plus(GIVE_UP_ALERT_POLL_INTERVAL);
        if (givenUpCount > 0) {
            log.error("notify give-up backlog count={} sink=slack — 자동 재시도가 멈춘 종단 알림. "
                    + "운영자 개입 필요(notify_attempts를 0으로 리셋하면 재전송)", givenUpCount);
        }
    }
}
