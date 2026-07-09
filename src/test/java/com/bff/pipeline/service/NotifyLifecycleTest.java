package com.bff.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.enums.ErrorCode;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.enums.TaskDefinition;
import com.bff.pipeline.enums.TaskOperation;
import com.bff.pipeline.enums.TaskStatus;
import com.bff.pipeline.model.NotifyClaim;
import com.bff.pipeline.repository.NotifyRepository;
import com.bff.pipeline.repository.PipelineRepository;
import com.bff.pipeline.repository.TaskRepository;
import com.bff.pipeline.service.notify.NotifyClaimer;
import com.bff.pipeline.service.notify.NotifyWriteBack;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-022 notify claim/write-back 생명주기 검증이다({@code PipelineSoftCapTest} 패턴 미러 — 래핑 트랜잭션을
 * 끄고 claim 트랜잭션과 write-back 트랜잭션이 실제로 분리 커밋되게 한다). claim 술어(종단·미알림·due·
 * lease 가용·give-up 배제·도입 컷오프), NULL notify_next_at 우선의 결정적 순서, FAILED payload의 실패 task
 * 채움(taskDefinition 우선, 레거시 행은 mechanism fallback), 토큰 fencing(성공·실패 양쪽 no-op — 실패
 * no-op은 empty 반환), 지수 backoff와 give-up(ERROR 로그 포함), 실행 admission 카운트와의 격리,
 * at-least-once(lease 만료 후 재claim = 중복 전달 수용), 그리고 활성 컷오프 술어(설정
 * {@code enabledAfter}, ADR-022 §5 대안 — 도입 전 종단 행의 소급 발화 방지)를 검증한다. 기존 시나리오
 * 행들의 lastActivityAt은 전부 컷오프({@code ENABLED_AFTER}) 이후라 컷오프의 영향을 받지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotifyClaimer.class, NotifyWriteBack.class, NotifyLifecycleTest.Wiring.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotifyLifecycleTest {

    private static final Instant START = Instant.parse("2026-07-09T00:00:00Z");
    /** 알림 도입 시점 컷오프 — 기본 savePipeline 행(lastActivityAt = START-1분)이 전부 이후가 되는 고정값. */
    private static final Instant ENABLED_AFTER = START.minus(Duration.ofDays(1));
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(5);
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Autowired private NotifyClaimer claimer;
    @Autowired private NotifyWriteBack writeBack;
    @Autowired private NotifyRepository notifyRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private MutableClock clock;

    @AfterEach
    void clean() {
        taskRepository.deleteAll();
        pipelineRepository.deleteAll();
        clock.set(START);
    }

    @Test
    void claimTakesOnlyTerminalUnnotifiedRowsAndStampsTheNotifyLease() {
        Pipeline done = savePipeline("nt-done", PipelineStatus.DONE);
        Pipeline cancelled = savePipeline("nt-cancelled", PipelineStatus.CANCELLED);
        savePipeline("nt-running", PipelineStatus.RUNNING);   // 비종단 → 알림 대상 아님

        NotifyClaim first = claimer.claimOne().orElseThrow();
        NotifyClaim second = claimer.claimOne().orElseThrow();

        assertThat(first.pipelineId()).isEqualTo(done.getId());       // 같은 NULL notify_next_at → id tie-break
        assertThat(second.pipelineId()).isEqualTo(cancelled.getId());
        assertThat(claimer.claimOne()).isEmpty();                     // RUNNING은 절대 claim되지 않는다

        Pipeline claimed = reload(done);
        assertThat(claimed.getNotifyClaimedBy()).isEqualTo(first.token());
        assertThat(claimed.getNotifyClaimedUntil()).isEqualTo(START.plus(LEASE));
        assertThat(first.payload().terminalStatus()).isEqualTo(PipelineStatus.DONE.name());
    }

    @Test
    void claimSkipsNotifiedNotYetDueLeasedAndGivenUpRows() {
        savePipeline("nt-notified", PipelineStatus.DONE,
                builder -> builder.notifiedAt(START.minusSeconds(5)));
        savePipeline("nt-backoff-waiting", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.plusSeconds(30)));
        savePipeline("nt-live-lease", PipelineStatus.DONE,
                builder -> builder.notifyClaimedBy("live-claim-token").notifyClaimedUntil(START.plusSeconds(30)));
        // give-up 행은 notify_next_at이 due(null)여도 attempts 술어만으로 배제된다(far-future 의존 아님)
        savePipeline("nt-given-up", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(MAX_ATTEMPTS));

        assertThat(claimer.claimOne()).isEmpty();
    }

    @Test
    void freshTerminalsWithNullNotifyNextAtClaimBeforeDueBackoffRows() {
        Pipeline retried = savePipeline("nt-retried", PipelineStatus.DONE,
                builder -> builder.notifyNextAt(START.minusSeconds(10)));   // due한 backoff 재시도 행
        Pipeline freshLowId = savePipeline("nt-fresh-a", PipelineStatus.DONE);
        Pipeline freshHighId = savePipeline("nt-fresh-b", PipelineStatus.DONE);

        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(freshLowId.getId());   // NULL 선두
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(freshHighId.getId());  // id tie-break
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(retried.getId());
    }

    @Test
    void aFailedPipelineFillsThePayloadFromTheLowestSequenceFailedTask() {
        Pipeline failed = savePipeline("nt-failed", PipelineStatus.FAILED);
        saveTask(failed.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);
        saveTask(failed.getId(), 1, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.FAILED, ErrorCode.JOB_FAILED);
        saveTask(failed.getId(), 2, TaskDefinition.NETWORK_READY_V1, TaskStatus.FAILED, ErrorCode.CHECK_ERROR);

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        // failed_task = recipe 진실원인 taskDefinition(상수 이름) — mechanism은 여러 step이 공유해 단계를 못 가린다
        assertThat(payload.failedTask()).isEqualTo(TaskDefinition.AWS_SERVICE_APPLY_V1.name());   // sequence 최소 FAILED
        assertThat(payload.errorCode()).isEqualTo(ErrorCode.JOB_FAILED.name());
        assertThat(payload.terminalStatus()).isEqualTo(PipelineStatus.FAILED.name());
        assertThat(payload.type()).isEqualTo(PipelineType.INSTALL.name());
        assertThat(payload.targetRef()).isEqualTo("nt-failed");    // opaque target 키 그대로 — 연결 상세 없음
        assertThat(payload.schemaVersion()).isEqualTo(NotifyPayload.SCHEMA_VERSION);

        Set<String> closedTaskKeys = Arrays.stream(TaskDefinition.values())
                .map(TaskDefinition::name)
                .collect(Collectors.toSet());
        assertThat(payload.failedTask()).isIn(closedTaskKeys);     // 닫힌 recipe task 키 집합에 속함(PII 계약)
    }

    @Test
    void aLegacyFailedTaskWithoutADefinitionFallsBackToTheMechanismTaskName() {
        Pipeline failed = savePipeline("nt-failed-legacy", PipelineStatus.FAILED);
        taskRepository.save(Task.builder()   // 드레인 전 레거시 행 모사 — taskDefinition이 없다
                .pipelineId(failed.getId())
                .sequence(0)
                .taskName(TaskOperation.AWS_SERVICE_TF_APPLY.mechanism())
                .operation(TaskOperation.AWS_SERVICE_TF_APPLY)
                .status(TaskStatus.FAILED)
                .errorCode(ErrorCode.JOB_FAILED)
                .failCount(0)
                .build());

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        // fallback도 enum 유래 닫힌 어휘(mechanism)라 PII 계약을 지킨다
        assertThat(payload.failedTask()).isEqualTo(TaskOperation.Mechanism.TERRAFORM_JOB);
    }

    @Test
    void aNonFailedPipelinePayloadCarriesNoFailedTaskOrErrorCode() {
        Pipeline done = savePipeline("nt-done-clean", PipelineStatus.DONE);
        saveTask(done.getId(), 0, TaskDefinition.AWS_SERVICE_APPLY_V1, TaskStatus.DONE, null);

        NotifyPayload payload = claimer.claimOne().orElseThrow().payload();

        assertThat(payload.failedTask()).isNull();
        assertThat(payload.errorCode()).isNull();
    }

    @Test
    void successWriteBackStampsNotifiedAtClearsLeaseAndBackoffAndExcludesTheRow() {
        Pipeline done = savePipeline("nt-success", PipelineStatus.DONE,
                builder -> builder.notifyAttempts(1).notifyNextAt(START.minusSeconds(10)));   // 재시도 중이던 행

        NotifyClaim claim = claimer.claimOne().orElseThrow();
        writeBack.onSuccess(claim.pipelineId(), claim.token());

        Pipeline after = reload(done);
        assertThat(after.getNotifiedAt()).isEqualTo(START);
        assertThat(after.getNotifyClaimedBy()).isNull();
        assertThat(after.getNotifyClaimedUntil()).isNull();
        assertThat(after.getNotifyNextAt()).isNull();          // stale backoff 메타데이터도 비운다

        clock.advance(Duration.ofHours(1));
        assertThat(claimer.claimOne()).isEmpty();              // notified 행은 영구 제외
    }

    @Test
    void aMismatchedTokenWriteBackIsANoOpOnBothPaths() {
        Pipeline done = savePipeline("nt-fenced", PipelineStatus.DONE);
        NotifyClaim claim = claimer.claimOne().orElseThrow();

        writeBack.onSuccess(claim.pipelineId(), "not-the-claim-token");
        // fencing no-op은 attempts를 돌려주지 않는다 — 호출자가 attempt=stale-no-op으로 로깅하는 근거
        assertThat(writeBack.onFailure(claim.pipelineId(), "not-the-claim-token")).isEmpty();

        Pipeline after = reload(done);
        assertThat(after.getNotifiedAt()).isNull();
        assertThat(after.getNotifyAttempts()).isZero();
        assertThat(after.getNotifyClaimedBy()).isEqualTo(claim.token());   // 현 lease 그대로
    }

    @Test
    void writeBackAfterAnotherWorkerAlreadySucceededIsANoOpEvenWithAMatchingToken() {
        Pipeline done = savePipeline("nt-already-notified", PipelineStatus.DONE,
                builder -> builder.notifiedAt(START.minusSeconds(5)).notifyClaimedBy("straggler-token"));

        writeBack.onFailure(done.getId(), "straggler-token");
        writeBack.onSuccess(done.getId(), "straggler-token");

        Pipeline after = reload(done);
        assertThat(after.getNotifyAttempts()).isZero();                          // attempts/backoff 오염 없음
        assertThat(after.getNotifyNextAt()).isNull();
        assertThat(after.getNotifiedAt()).isEqualTo(START.minusSeconds(5));      // 재스탬프 없음
    }

    @Test
    void aStaleTokenFromAnExpiredLeaseCannotWriteBackAfterReclaim() {
        Pipeline done = savePipeline("nt-stale-straggler", PipelineStatus.DONE);
        NotifyClaim expired = claimer.claimOne().orElseThrow();

        clock.advance(LEASE.plusSeconds(1));
        NotifyClaim reclaimed = claimer.claimOne().orElseThrow();
        assertThat(reclaimed.pipelineId()).isEqualTo(expired.pipelineId());
        assertThat(reclaimed.token()).isNotEqualTo(expired.token());

        writeBack.onFailure(expired.pipelineId(), expired.token());   // 뒤늦게 도착한 stale write-back
        Pipeline afterStale = reload(done);
        assertThat(afterStale.getNotifyAttempts()).isZero();          // no-op — 새 소유자를 오염시키지 못함
        assertThat(afterStale.getNotifyClaimedBy()).isEqualTo(reclaimed.token());

        writeBack.onSuccess(reclaimed.pipelineId(), reclaimed.token());
        assertThat(reload(done).getNotifiedAt()).isNotNull();         // 현 소유자는 정상 기록
    }

    @Test
    void failureWriteBacksAdvanceBackoffExponentiallyThenGiveUpAndStayExcluded() {
        Pipeline done = savePipeline("nt-backoff-cycle", PipelineStatus.DONE);

        NotifyClaim claim = claimer.claimOne().orElseThrow();
        assertThat(writeBack.onFailure(claim.pipelineId(), claim.token())).hasValue(1);   // post-increment 반환
        Pipeline after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(1);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(BACKOFF_BASE));   // base × 2^0
        assertThat(after.getNotifyClaimedBy()).isNull();                                     // lease 해제

        clock.advance(BACKOFF_BASE);   // backoff 경과 → due
        claim = claimer.claimOne().orElseThrow();
        assertThat(writeBack.onFailure(claim.pipelineId(), claim.token())).hasValue(2);
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(2);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(BACKOFF_BASE.multipliedBy(2)));   // 지수 전진

        clock.advance(BACKOFF_BASE.multipliedBy(2));
        claim = claimer.claimOne().orElseThrow();
        ListAppender<ILoggingEvent> capturedLogs = attachWriteBackListAppender();
        try {
            writeBack.onFailure(claim.pipelineId(), claim.token());   // MAX_ATTEMPTS번째 실패 → give-up
        } finally {
            detachWriteBackListAppender(capturedLogs);
        }
        after = reload(done);
        assertThat(after.getNotifyAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(after.getNotifyNextAt()).isEqualTo(clock.instant().plus(NotifyWriteBack.GIVE_UP_FAR_FUTURE));
        assertThat(notifyRepository.countGivenUp(MAX_ATTEMPTS)).isEqualTo(1);
        // give-up은 ERROR로 즉시 드러난다(진단 보조 — 정규 경보 소스는 countGivenUp 폴링, ADR-022 §4)
        assertThat(capturedLogs.list).anySatisfy(loggingEvent -> {
            assertThat(loggingEvent.getLevel()).isEqualTo(Level.ERROR);
            assertThat(loggingEvent.getFormattedMessage())
                    .contains("give-up", "after " + MAX_ATTEMPTS + " attempts");
        });

        clock.advance(NotifyWriteBack.GIVE_UP_FAR_FUTURE.plusSeconds(1));   // far-future마저 지나도
        assertThat(claimer.claimOne()).isEmpty();                 // attempts 술어가 재선택을 막는다
    }

    @Test
    void theNotifyLeaseDoesNotInflateTheExecutionAdmissionCount() {
        savePipeline("nt-isolated", PipelineStatus.DONE);

        claimer.claimOne().orElseThrow();   // notify lease 스탬프

        assertThat(pipelineRepository.countByClaimedUntilAfter(clock.instant())).isZero();   // 실행 캡 무오염
    }

    @Test
    void aCrashAfterDeliveryBeforeWriteBackAllowsReclaimAfterLeaseExpiry() {
        savePipeline("nt-crash-window", PipelineStatus.DONE);
        NotifyClaim delivered = claimer.claimOne().orElseThrow();   // 전달은 성공, write-back 전 크래시 모사(onSuccess 생략)

        assertThat(claimer.claimOne()).isEmpty();                   // lease 유효 동안은 이중 claim 없음

        clock.advance(LEASE.plusSeconds(1));
        NotifyClaim reclaimed = claimer.claimOne().orElseThrow();   // 만료 후 재claim → 중복 전달 가능(at-least-once 수용)
        assertThat(reclaimed.pipelineId()).isEqualTo(delivered.pipelineId());
        assertThat(reclaimed.token()).isNotEqualTo(delivered.token());
    }

    @Test
    void aTerminalRowThatPredatesTheAdoptionCutoffIsNeverClaimed() {
        // 도입(enabledAfter) 전에 종단된 레거시 행 — 종단·미알림·due지만 알림 범위 밖이다(ADR-022 §5 대안)
        savePipeline("nt-legacy-done", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER.minusSeconds(1)));
        savePipeline("nt-legacy-failed", PipelineStatus.FAILED,
                builder -> builder.lastActivityAt(ENABLED_AFTER.minus(Duration.ofDays(30))));

        assertThat(claimer.claimOne()).isEmpty();   // 레거시 종단 행의 소급 발화(폭주)가 없다
    }

    @Test
    void aTerminalRowAtOrAfterTheAdoptionCutoffIsClaimed() {
        // 경계 포함(>=) — 정확히 컷오프 시각에 종단된 행부터 알림 대상이다
        Pipeline atCutoff = savePipeline("nt-at-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER));
        Pipeline afterCutoff = savePipeline("nt-after-cutoff", PipelineStatus.DONE,
                builder -> builder.lastActivityAt(ENABLED_AFTER.plusSeconds(1)));

        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(atCutoff.getId());
        assertThat(claimer.claimOne().orElseThrow().pipelineId()).isEqualTo(afterCutoff.getId());
    }

    private Pipeline savePipeline(String target, PipelineStatus status) {
        return savePipeline(target, status, builder -> { });
    }

    private Pipeline savePipeline(String target, PipelineStatus status,
            Consumer<Pipeline.PipelineBuilder> customize) {
        Pipeline.PipelineBuilder builder = Pipeline.builder()
                .type(PipelineType.INSTALL)
                .target(target)
                .status(status)
                .createdAt(START.minus(Duration.ofMinutes(10)))
                .lastActivityAt(START.minus(Duration.ofMinutes(1)))
                .nextDueAt(START)
                .cancelRequested(false);
        customize.accept(builder);
        return pipelineRepository.save(builder.build());
    }

    private void saveTask(Long pipelineId, int sequence, TaskDefinition definition, TaskStatus status,
            ErrorCode errorCode) {
        taskRepository.save(Task.builder()
                .pipelineId(pipelineId)
                .sequence(sequence)
                .taskName(definition.mechanism())      // PipelineInserter와 동일 파생: taskName = mechanism 캐시
                .operation(definition.operation())
                .taskDefinition(definition.name())     // recipe 진실원 — failed_task의 1순위 원천
                .status(status)
                .errorCode(errorCode)
                .failCount(0)
                .build());
    }

    private Pipeline reload(Pipeline pipeline) {
        return pipelineRepository.findById(pipeline.getId()).orElseThrow();
    }

    /** write-back 로그를 캡처하는 Logback ListAppender — give-up ERROR의 구조화 필드를 단언하는 데 쓴다. */
    private static ListAppender<ILoggingEvent> attachWriteBackListAppender() {
        Logger writeBackLogger = (Logger) LoggerFactory.getLogger(NotifyWriteBack.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        writeBackLogger.addAppender(listAppender);
        return listAppender;
    }

    private static void detachWriteBackListAppender(ListAppender<ILoggingEvent> listAppender) {
        ((Logger) LoggerFactory.getLogger(NotifyWriteBack.class)).detachAppender(listAppender);
    }

    @TestConfiguration
    static class Wiring {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        NotifySettings notifySettings() {
            return NotifySettings.builder()
                    .enabled(true)
                    .pollInterval(Duration.ofSeconds(2))
                    .maxIdleSleep(Duration.ofSeconds(10))
                    .backoffBase(BACKOFF_BASE)
                    .backoffMax(Duration.ofMinutes(5))
                    .jitterRatio(0.0)   // backoff 시각을 결정적으로 단언하기 위해 jitter 없음
                    .leaseDuration(LEASE)
                    .callTimeout(Duration.ofSeconds(10))
                    .maxAttempts(MAX_ATTEMPTS)
                    .schedulerInitialDelay(Duration.ofSeconds(10))
                    .slackWebhookUrl(WEBHOOK_URL)
                    .enabledAfter(ENABLED_AFTER)   // START 이전 고정값 — 기존 시나리오 행은 전부 컷오프 이후
                    .build();
        }
    }
}
