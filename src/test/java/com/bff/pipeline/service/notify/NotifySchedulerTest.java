package com.bff.pipeline.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.config.NotifySettings;
import com.bff.pipeline.dto.NotifyPayload;
import com.bff.pipeline.entity.NotificationChannel;
import com.bff.pipeline.model.NotifyClaim;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link NotifyScheduler}의 순수 로직 단위 테스트다({@code PipelineSchedulerTest} 스타일 — 스케줄러 스레드
 * 기동 없이 {@code deliverOne}/{@code nextDelay}를 직접 호출한다). 채널 gate(미설정/비활성이면 claim 0건),
 * 성공/실패의 write-back 라우팅(실패는 sweep을 죽이지 않고 backoff 기록으로 흡수), 빈 sweep의 geometric
 * idle backoff와 일감 발견 시 리셋을 검증한다.
 */
class NotifySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T0001/B0002/token";

    @Test
    void anInactiveChannelGateIdlesWithoutClaiming() {
        NotifyScheduler scheduler = scheduler(claimerNeverCalled(), recordingSlackNotifier(),
                new RecordingWriteBack(), channelGate(Optional.empty()));

        assertThat(scheduler.deliverOne()).isFalse();   // claim 시도 자체가 없어야 한다(claimerNeverCalled가 보증)
    }

    @Test
    void aSuccessfulDeliveryIsRoutedToOnSuccess() {
        RecordingSlackNotifier slackNotifier = recordingSlackNotifier();
        RecordingWriteBack writeBack = new RecordingWriteBack();
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                slackNotifier, writeBack, channelGate(Optional.of(configuredChannel())));

        assertThat(scheduler.deliverOne()).isTrue();

        assertThat(slackNotifier.deliveredTo).containsExactly(WEBHOOK_URL + "#42");
        assertThat(writeBack.successes).containsExactly("42:claim-token-42");
        assertThat(writeBack.failures).isEmpty();
    }

    @Test
    void aDeliveryFailureIsRoutedToOnFailureAndTheSweepStaysAlive() {
        RecordingWriteBack writeBack = new RecordingWriteBack();
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                failingSlackNotifier(new IllegalStateException("slack 5xx")),
                writeBack, channelGate(Optional.of(configuredChannel())));

        assertThat(scheduler.deliverOne()).isTrue();    // 실패해도 "일감이 있었다" — 케이던스는 pollInterval 유지

        assertThat(writeBack.failures).containsExactly("42:claim-token-42");
        assertThat(writeBack.successes).isEmpty();
    }

    @Test
    void aSuccessWriteBackFailureIsNotCountedAsADeliveryFailure() {
        List<String> failures = new ArrayList<>();
        NotifyWriteBack writeBack = new NotifyWriteBack(null, null, null) {
            @Override public void onSuccess(long pipelineId, String token) {
                throw new IllegalStateException("db down during write-back");
            }

            @Override public void onFailure(long pipelineId, String token) {
                failures.add(pipelineId + ":" + token);
            }
        };
        NotifyScheduler scheduler = scheduler(
                claimerYielding(new NotifyClaim(42L, "claim-token-42", payload(42L))),
                recordingSlackNotifier(), writeBack, channelGate(Optional.of(configuredChannel())));

        // 전달은 성공했다 — write-back 실패는 attempts를 태우지 않고 runSweep의 loop-survival WARN으로 전파된다
        // (lease가 남아 만료 후 재claim → at-least-once 중복 수용, ADR-022 §2 "전달 실패" 한정)
        assertThatThrownBy(scheduler::deliverOne).isInstanceOf(IllegalStateException.class);
        assertThat(failures).isEmpty();
    }

    @Test
    void anEmptyClaimWithAnActiveChannelIsAnIdleSweep() {
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack(), channelGate(Optional.of(configuredChannel())));

        assertThat(scheduler.deliverOne()).isFalse();
    }

    @Test
    void emptySweepsGrowGeometricallyCapAtMaxIdleSleepAndResetOnWork() {
        NotifyScheduler scheduler = scheduler(claimerYielding(), recordingSlackNotifier(),
                new RecordingWriteBack(), channelGate(Optional.empty()));

        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base 1s × 2
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(4));
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));   // maxIdleSleep 상한
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(8));
        assertThat(scheduler.nextDelay(true)).isEqualTo(Duration.ofSeconds(2));    // 일감 → pollInterval + 리셋
        assertThat(scheduler.nextDelay(false)).isEqualTo(Duration.ofSeconds(2));   // base부터 다시 성장
    }

    private static NotifySettings settings() {
        return NotifySettings.builder()
                .enabled(true)
                .pollInterval(Duration.ofSeconds(2))
                .maxIdleSleep(Duration.ofSeconds(8))
                .backoffBase(Duration.ofSeconds(1))
                .backoffMax(Duration.ofMinutes(10))
                .jitterRatio(0.0)
                .leaseDuration(Duration.ofMinutes(1))
                .callTimeout(Duration.ofSeconds(10))
                .maxAttempts(8)
                .schedulerInitialDelay(Duration.ofSeconds(10))
                .build();
    }

    private NotifyScheduler scheduler(NotifyClaimer claimer, SlackNotifier slackNotifier,
            NotifyWriteBack writeBack, NotificationChannelService channels) {
        return new NotifyScheduler(claimer, slackNotifier, writeBack, channels, null, settings(), CLOCK);
    }

    private static NotificationChannelService channelGate(Optional<NotificationChannel> channel) {
        return new NotificationChannelService(null, null) {
            @Override public Optional<NotificationChannel> activeChannel() {
                return channel;
            }
        };
    }

    private static NotificationChannel configuredChannel() {
        return NotificationChannel.builder()
                .id(NotificationChannel.SINGLETON_ID).slackWebhookUrl(WEBHOOK_URL).enabled(true).build();
    }

    private static NotifyClaimer claimerYielding(NotifyClaim... claims) {
        Deque<NotifyClaim> queue = new ArrayDeque<>(List.of(claims));
        return new NotifyClaimer(null, null, null, null) {
            @Override public Optional<NotifyClaim> claimOne() {
                return Optional.ofNullable(queue.poll());
            }
        };
    }

    private static NotifyClaimer claimerNeverCalled() {
        return new NotifyClaimer(null, null, null, null) {
            @Override public Optional<NotifyClaim> claimOne() {
                throw new AssertionError("채널 gate가 닫혀 있으면 claim을 시도하면 안 된다");
            }
        };
    }

    private static NotifyPayload payload(long pipelineId) {
        return NotifyPayload.builder()
                .pipelineId(pipelineId).type("INSTALL").terminalStatus("DONE")
                .targetRef("ts-" + pipelineId).schemaVersion(NotifyPayload.SCHEMA_VERSION)
                .build();
    }

    private static RecordingSlackNotifier recordingSlackNotifier() {
        return new RecordingSlackNotifier(null);
    }

    private static RecordingSlackNotifier failingSlackNotifier(RuntimeException failure) {
        return new RecordingSlackNotifier(failure);
    }

    /** 전달 호출을 기록하거나(성공 모사) 지정된 예외를 던지는(실패 모사) 스텁 sink. */
    private static final class RecordingSlackNotifier extends SlackNotifier {
        private final List<String> deliveredTo = new ArrayList<>();
        private final RuntimeException failure;

        private RecordingSlackNotifier(RuntimeException failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public void deliver(String webhookUrl, NotifyPayload payload) {
            if (failure != null) {
                throw failure;
            }
            deliveredTo.add(webhookUrl + "#" + payload.pipelineId());
        }
    }

    /** write-back 라우팅만 기록하는 스텁 — 어느 경로(onSuccess/onFailure)로 어떤 claim이 닫혔는지 남긴다. */
    private static final class RecordingWriteBack extends NotifyWriteBack {
        private final List<String> successes = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        private RecordingWriteBack() {
            super(null, null, null);
        }

        @Override
        public void onSuccess(long pipelineId, String token) {
            successes.add(pipelineId + ":" + token);
        }

        @Override
        public void onFailure(long pipelineId, String token) {
            failures.add(pipelineId + ":" + token);
        }
    }
}
