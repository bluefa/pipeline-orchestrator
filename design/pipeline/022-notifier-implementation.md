# ADR-022 구현 세부: 종단 상태 알림 (Slack) — buildable spec

> 이 문서 하나만 보고 구현할 수 있게 쓴 **구현 명세**다. 결정의 *근거*는
> [ADR-022](../../docs/adr/022-terminal-state-notification.md)에, 그 위 도메인/실행 모델은
> [ADR-016](../../docs/adr/016-install-delete-pipeline-domain-model.md)·
> [ADR-021](../../docs/adr/021-pipeline-execution-model.md)에 있다. 타깃 스택은
> **MySQL 8 + Spring Boot(JPA/Hibernate `ddl-auto: update`) + OpenFeign/RestClient**,
> 코드베이스는 `pipeline-orchestrator`(패키지 `com.bff.pipeline`).
>
> **리포 위치**: 이 문서와 ADR-022 는 `pii-agent-demo`(ADR 원본 저장소)에서 작성한다. 백엔드
> 구현은 `pipeline-orchestrator` 리포에서 이뤄지며, 그 리포는 이미 ADR-016/021 사본을
> `docs/adr/` 에 동기화해 두고 있다 — **구현 착수 시 ADR-022 + 이 문서도 같은 방식으로
> orchestrator 리포에 동기화**하면 상대 링크가 그 체크아웃에서 해석된다. 근거 문서 없이도
> 이 명세만으로 구현 가능하도록 핵심 결정은 본문에 내재화했다.

> **구현 노트(pipeline-orchestrator, 2026-07-09, 오너 결정)**: §2.2/§6의 admin 채널 관리
> (notification_channel 테이블·REST·마스킹·SSRF·테스트 전송)는 구현하지 않는다 — webhook은
> env var(`PIPELINE_NOTIFY_SLACK_WEBHOOK_URL`)로 주입하고 enable/disable은 재시작으로 반영한다
> (§4.5의 런타임 채널 가드는 부팅 `enabled` 가드로 대체). 롤아웃 컷오프는 활성 컷오프 술어
> (`pipeline.notify.enabled-after`, claim에 `last_activity_at >= :enabledAfter`)다 — **ADR-022
> §5가 이를 채택안으로 개정**되었고 §2 채널 gate도 부팅 가드/재시작 의미론으로 개정되었다
> (ADR-022 개정 이력 2026-07-09 참조). §7의 두 age 쿼리는 소비 표면(actuator/admin)이 생길 때
> 재도입하며, give-up 경보의 V1 배선은 NotifyScheduler가 `countGivenUp`을 주기(5분) 폴링해
> ERROR 로그로 승격하는 것이다. 이 노트·아래 ⛔ 마킹과 본문이 충돌하면 **개정된 ADR-022
> 본문이 정본**이다.

> **구현 노트 2(pipeline-orchestrator, 2026-07-10, 오너 결정 — 단일 트랜잭션 재설계)**:
> 전달 동시성이 lease/fencing/two-tx에서 **행 잠금을 쥔 단일 트랜잭션**으로 교체되었다
> (근거·SQL·되돌릴 조건은 ADR-022 §2 2026-07-10 개정판이 정본). 이 문서의 해당 절은
> 재작성하지 않고 ⛔로 표시만 한다 — 구체 내용의 정본은 개정된 ADR-022와 코드다:
> - §1 큰 그림·§4.2 `NotifyClaimer`·§4.4 `NotifyWriteBack`·§4.5 `NotifyScheduler` →
>   **`TerminalNotifier` 단일 클래스**(service/notify)가 잠금 → payload 조립 → Slack 호출 →
>   결과 기록을 TransactionTemplate 트랜잭션 하나로 처리. `NotifyClaim` record 삭제.
> - §2.1의 notify 전용 lease 컬럼쌍(`notify_claimed_by`/`notify_claimed_until`) → **삭제**
>   (컬럼 5→3). 전송 중임은 행 잠금이 나타내고, 실행 admission 카운트 오염 문제는 원천 소멸.
> - §4.1 `NotifyRepository` → 삭제하고 **기존 `PipelineRepository`에 질의 2개**
>   (`findNextNotifiable`/`lockNotifiable` + `countGivenUp`) 통합. 술어에서 lease 게이트 제거.
> - §3 설정 → **env 키만**(`enabled`/`slack-webhook-url`/`enabled-after`, 2026-07-10 오너 요청으로
>   `environment`/`detail-url-base` 추가 — 총 5키). 나머지 동작 값은
>   `TerminalNotifier`/`SlackNotifier` 코드 상수: `MAX_ATTEMPTS=3`(오너 지정, 기존 8),
>   재시도 간격 = 시도 횟수 × 1분(선형 — 지수+jitter 제거), sweep 주기 10초(고정 — idle 기하
>   backoff 제거), give-up 재경보 폴링 5분(유지 — 오너 지정), `CALL_TIMEOUT` 10초.
> - §5 give-up: far-future sentinel 제거 — claim 술어의 `notify_attempts < maxAttempts`가
>   유일 근거. 복구는 `notify_attempts`를 0으로 리셋.

## 0. 범위와 원칙 (작게 시작)

- **sink = Slack 하나**(V1 단일 논리 sink). Slack Incoming Webhook URL로 POST.
- **인터페이스 추상화 없음** — `NotificationSink` 같은 인터페이스를 두지 않고 `SlackNotifier`
  구체 클래스로 직접 구현한다. 다중 sink가 실제로 필요해지면 그때 추출한다(YAGNI).
- **Slack 채널은 Admin Page에서 관리** — webhook URL/활성여부를 admin UI에서 등록·수정.
  (⛔ 미구현 — 2026-07-09 오너 결정, 위 구현 노트: env var 로 대체.)
- **관측 전용, 게이팅 아님** — 알림은 pipeline/task 도메인 상태에 영향을 주지 않는다(ADR-022 불변식).
- **실행과 격리** — notify는 실행 워커풀·admission cap과 자원/회계를 공유하지 않는다.

## 1. 큰 그림

> ⛔ **대체됨(2026-07-10 오너 결정 — §0 구현 노트 2)**: 아래 tx1/외부호출/tx2 3단 구조는
> `TerminalNotifier`의 단일 트랜잭션(잠금 → 조립 → 호출 → 기록 → 커밋)으로 교체되었다.

```
NotifyScheduler (단일 데몬 loop, 실행 스케줄러와 별개)
   └─ enabled=false(또는 webhook/컷오프 미제공 → 부팅 fail-fast)면 loop 미기동 (2026-07-09 개정 — 부팅 가드)
   └─ 켜져 있으면:
        tx1  NotifyClaimer.claimOne()        — 종단·미알림 행 1개 SKIP LOCKED claim (notify 전용 lease)
        ──   SlackNotifier.deliver(payload)  — 트랜잭션 밖, RestClient read-timeout 으로 bounded
        tx2  NotifyWriteBack.record(...)      — 성공: notified_at 스탬프 / 실패: backoff / 소진: give-up
```

- **claim 단위 = 종단 pipeline 행 1개.** 발화 조건 = `종단 상태로 커밋됨 ∧ 미알림`(ADR-022 §1).
- notify 루프는 **단일 스레드로 충분**하다 — 파이프라인당 1회, 대상 ~2,000, 종단 이벤트는
  드문드문 발생. 느린 Slack 호출은 `call-timeout`으로 상한을 두므로 단일 스레드가 막히지 않는다.
- 멀티 파드에서도 안전: `FOR UPDATE SKIP LOCKED` + notify lease가 파드 간 이중 전달을 막는다.
- **Slack은 dedupe하지 않는다.** at-least-once라 드물게(전달 성공 후 tx2 커밋 전 크래시/lease 만료)
  **같은 종단 메시지가 채널에 두 번 보일 수 있다.** V1은 이 드문 중복을 **수용**한다(Slack Incoming
  Webhook에는 idempotency 키가 없다). 각 메시지에 `pipeline_id`를 실어 사람이 눈으로 구분하게 하고,
  자동 dedupe가 실제로 필요해지면 Slack 앞에 멱등 브리지 sink를 두는 건 후속(V1 비범위).

## 2. DB 변경 (JPA 엔티티, 손으로 쓰는 SQL 없음)

`ddl-auto: update`가 엔티티 애노테이션에서 스키마를 만든다. **`Pipeline` 엔티티에 필드 5개 + 인덱스 1개**를 더하고,
**설정 엔티티 `NotificationChannel` 1개**를 새로 만든다(⛔ 후자는 미구현 — §2.2, 채널은 env var;
⛔ 전자는 2026-07-10 재설계로 **필드 3개**로 축소 — lease 컬럼쌍 삭제, §0 구현 노트 2).

### 2.1 `Pipeline` 에 추가

```java
// ── ADR-022 종단 알림 메타데이터 (도메인 상태 아님; reconciler/claim/전이가 읽지 않는다) ──
@Column(name = "notified_at")
private Instant notifiedAt;              // 전달 완료(sink ack) 마커. non-null 이면 알림 대상에서 빠짐

@Column(name = "notify_next_at")
private Instant notifyNextAt;            // 실패 backoff 게이트(다음 재시도 시각). give-up 시 far-future

@Column(name = "notify_attempts", nullable = false)
@Builder.Default
private int notifyAttempts = 0;          // backoff 지수·give-up 임계 계산

// ⛔ 아래 lease 컬럼쌍은 삭제됨(2026-07-10 — 행 잠금이 점유를 대체, §0 구현 노트 2)
@Column(name = "notify_claimed_by", length = 36)
private String notifyClaimedBy;

@Column(name = "notify_claimed_until")
private Instant notifyClaimedUntil;
```

`@Table(indexes = { ... })` 의 **기존 인덱스 배열에 한 줄 append**(배열 교체 금지 — 기존 4개
인덱스 + `active_target` 유일 제약 유지):

```java
// ponytail: ~2,000행 규모엔 (notified_at, notify_next_at) 복합이면 충분. MySQL8은 부분(filtered)
// 인덱스가 없으므로 status 필터는 옵티마이저에 맡긴다. 대규모로 커지면 재검토.
@Index(name = "idx_pipeline_notify", columnList = "notified_at, notify_next_at")
```

> **`active_target` 유일 제약과 무관** — notify 컬럼은 알림 메타데이터일 뿐 도메인 불변식과 얽히지 않는다.

### 2.2 새 엔티티 `NotificationChannel` (단일 행 설정)

> ⛔ **미구현(2026-07-09 오너 결정)** — 이 엔티티/테이블은 만들지 않는다; 채널은 env var로
> 관리한다. §0 구현 노트와 ADR-022 개정 이력 참조.

V1은 **단일 논리 sink**이므로 이 테이블은 사실상 1행이다(`id=1` 고정). admin이 이 1행을 수정한다.

```java
@Entity
@Table(name = "notification_channel")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor(access = AccessLevel.PRIVATE) @Builder
public class NotificationChannel {

    // 단일 sink 이므로 고정 PK. upsert 는 항상 이 id 를 쓴다.
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;                        // 항상 SINGLETON_ID

    /** Slack Incoming Webhook URL. secret — GET 응답에서 마스킹한다(§6). */
    @Column(name = "slack_webhook_url", length = 512)
    private String slackWebhookUrl;

    /** admin 표시용 별칭(예: "#infra-alerts"). 전송 라우팅엔 쓰지 않음(webhook 이 채널을 결정). */
    @Column(name = "channel_label", length = 128)
    private String channelLabel;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

## 3. 설정 (`pipeline.notify.*`)

`ExecutionSettings` 와 같은 방식으로 `@ConfigurationProperties` record + fail-fast 검증.

> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: 설정은 env 3키(`enabled`/`slack-webhook-url`/
> `enabled-after`)만 남고, 아래 코드의 나머지 키(pollInterval·backoff·lease·call-timeout·
> max-attempts 등)는 전부 코드 상수로 이동했다(`MAX_ATTEMPTS=3` 등 값도 변경).

```java
@Builder
@ConfigurationProperties(prefix = "pipeline.notify")
public record NotifySettings(
        boolean enabled,
        Duration pollInterval,
        Duration maxIdleSleep,
        Duration backoffBase,
        Duration backoffMax,
        double jitterRatio,
        Duration leaseDuration,
        Duration callTimeout,
        int maxAttempts,
        Duration schedulerInitialDelay) {

    public NotifySettings {
        requirePositive(pollInterval, "pipeline.notify.poll-interval");
        requirePositive(maxIdleSleep, "pipeline.notify.max-idle-sleep");
        requirePositive(backoffBase, "pipeline.notify.backoff-base");
        requirePositive(backoffMax, "pipeline.notify.backoff-max");
        requirePositive(leaseDuration, "pipeline.notify.lease-duration");
        requirePositive(callTimeout, "pipeline.notify.call-timeout");
        requirePositive(schedulerInitialDelay, "pipeline.notify.scheduler-initial-delay");
        if (maxAttempts < 1) throw new IllegalArgumentException("pipeline.notify.max-attempts must be >= 1");
        if (jitterRatio < 0.0 || jitterRatio > 1.0)
            throw new IllegalArgumentException("pipeline.notify.jitter-ratio must be within [0,1]");
        // backoffBase 는 delivery-실패 backoff(§4.4)와 idle-sleep seed(§4.5) 두 곳에 쓰인다.
        // backoffBase > backoffMax 면 지수 backoff 가 즉시 clamp 돼 무의미해지므로 막는다.
        if (backoffMax.compareTo(backoffBase) < 0)
            throw new IllegalArgumentException("pipeline.notify.backoff-max must be >= backoff-base");
        // 같은 이유(ADR-021 Decision 5): lease 가 호출 타임아웃보다 짧으면 정상 운영 중에도
        // write-back(tx2)이 만료된 lease 로 no-op 되는 병리가 생긴다.
        if (leaseDuration.compareTo(callTimeout) <= 0)
            throw new IllegalArgumentException("pipeline.notify.lease-duration must exceed call-timeout");
    }
    // requirePositive: ExecutionSettings 와 동일 구현
}
```

`PipelineConfig` 에 `@EnableConfigurationProperties` 로 `NotifySettings.class` 추가.

`application.yml`:

```yaml
pipeline:
  notify:
    enabled: true
    poll-interval: PT2S            # 일감 있을 때 loop 케이던스
    max-idle-sleep: PT10S          # 빈 sweep backoff 상한
    backoff-base: PT5S             # 전달 실패 backoff 기준(지수)
    backoff-max: PT10M
    jitter-ratio: 0.2
    lease-duration: PT1M           # notify claim lease (> call-timeout 강제)
    call-timeout: PT10S            # Slack HTTP read timeout
    max-attempts: 8                # 이 횟수 실패하면 자동 재시도 중단 → 운영 에스컬레이션
    scheduler-initial-delay: PT10S
```

> **maxNotifyCount = `max-attempts`(기본 8).** 넘으면 `notify_next_at` 을 far-future 로 밀어 자동
> 재시도를 멈추고 ERROR 로그 + 지표로 사람이 개입하게 한다(§5).

> **신규 키 2개(2026-07-09 오너 결정 — 실제 `application.yml` 정합).** 위 record/yml 표본에
> 더해 `NotifySettings` 는 다음을 갖는다:
> `slack-webhook-url`(Slack Incoming Webhook URL — **secret**, env
> `PIPELINE_NOTIFY_SLACK_WEBHOOK_URL` 로만 주입하고 로그·응답에 원문을 찍지 않는다)과
> `enabled-after`(ISO-8601 `Instant` 도입 컷오프 — claim 술어 `last_activity_at >= enabled-after`,
> ADR-022 §5 채택안). **`enabled=true` 면 둘 다 필수**로 compact constructor 가 fail-fast 한다.
> `enabled` 기본은 `${PIPELINE_NOTIFY_ENABLED:false}` 이고, `enabled-after` 는 빈 문자열/sentinel
> 기본값을 두지 않는다(컷오프 무력화 = 레거시 종단 전부 발화) — 켜는 배포가 실제 도입 시각을
> env/yml 로 명시 제공한다.

## 4. Claim / 전달 / write-back

### 4.1 `NotifyRepository` (Spring Data JPA)

`PipelineRepository` 의 claim 패턴을 그대로 따른다(`@Lock(PESSIMISTIC_WRITE)` + `@QueryHints`
lock-timeout `-2` → MySQL8에서 `FOR UPDATE SKIP LOCKED` 렌더, H2에선 무시).

> ⛔ **spec 편차(2026-07-09 오너 결정)** — 아래 코드의 두 age 쿼리
> (`oldestUnnotifiedAt`/`oldestDeliveryPending`)는 삭제(소비 표면 도입 시 재도입 — §7 참조)이고,
> claim 술어에는 활성 컷오프 `and p.lastActivityAt >= :enabledAfter`가 추가되었다(§0 구현 노트).
>
> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: `NotifyRepository` 인터페이스 자체가 삭제되고
> 질의 2개가 기존 `PipelineRepository`로 통합되었다. 술어에서 lease 게이트
> (`notify_claimed_until` 비교)도 제거되었다 — 전송 중임은 행 잠금이 나타낸다.

```java
public interface NotifyRepository extends JpaRepository<Pipeline, Long> {

    /** tx1 진입 질의 — 알림 가능한 종단·미알림 행 하나를 SKIP LOCKED 로 잠가 가져온다. */
    default Optional<Pipeline> findNextNotifiable(Instant now, int maxAttempts) {
        return lockNotifiable(now, maxAttempts, Limit.of(1)).stream().findFirst();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    @Query("select p from Pipeline p "
         + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
         + "                   com.bff.pipeline.enums.PipelineStatus.FAILED, "
         + "                   com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
         + "and p.notifiedAt is null "
         + "and p.notifyAttempts < :maxAttempts "        // give-up 행은 재클레임 금지(안전장치; far-future 의존 X)
         + "and (p.notifyNextAt is null or p.notifyNextAt <= :now) "
         + "and (p.notifyClaimedUntil is null or p.notifyClaimedUntil < :now) "
         + "order by p.notifyNextAt asc, p.id asc")   // NULL first + 결정적 tie-break
    List<Pipeline> lockNotifiable(@Param("now") Instant now,
                                  @Param("maxAttempts") int maxAttempts, Limit limit);

    // tx2 행 잠금은 기존 PipelineRepository.findByIdForUpdate 를 재사용한다(여기 중복 정의하지 않음).

    // 지표 1: terminal_notification_backlog_age — 비활성 채널 backlog 까지 포함한 총 미알림 age.
    // min(lastActivityAt) 을 종단 시각으로 쓰는 건, 종단 행은 terminalize 후 다시 쓰이지 않아
    // (ADR-021 불변식) lastActivityAt == 종단 시각이 되기 때문(그때만 유효).
    // give-up 행(notifyAttempts >= maxAttempts)은 notifiedAt 이 영원히 null 이라 age 를 무한 오염하므로
    // 제외한다(ADR-022 §4). give-up 은 별도로 countGivenUp() 으로 감시한다.
    @Query("select min(p.lastActivityAt) from Pipeline p "
         + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
         + "                   com.bff.pipeline.enums.PipelineStatus.FAILED, "
         + "                   com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
         + "and p.notifiedAt is null "
         + "and p.notifyAttempts < :maxAttempts")
    Optional<Instant> oldestUnnotifiedAt(@Param("maxAttempts") int maxAttempts);

    // 지표 2: notify_delivery_pending_age — "전달 정체" age. backlog 과 달리 **due 행만**
    // 본다(미도래 backoff 행 제외) — 건강한 재시도 대기를 전달 막힘으로 오인하지 않게. 이 쿼리를
    // V1 부터 쓰고, 경보는 활성 채널일 때만 평가한다(비활성 시 suppress; ADR-022 §4).
    @Query("select min(p.lastActivityAt) from Pipeline p "
         + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
         + "                   com.bff.pipeline.enums.PipelineStatus.FAILED, "
         + "                   com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
         + "and p.notifiedAt is null "
         + "and p.notifyAttempts < :maxAttempts "
         + "and (p.notifyNextAt is null or p.notifyNextAt <= :now)")
    Optional<Instant> oldestDeliveryPending(@Param("maxAttempts") int maxAttempts, @Param("now") Instant now);

    // give-up 행 수(사람 개입 필요 신호). maxAttempts 도달 후에도 notifiedAt 이 null 인 종단 행.
    @Query("select count(p) from Pipeline p "
         + "where p.status in (com.bff.pipeline.enums.PipelineStatus.DONE, "
         + "                   com.bff.pipeline.enums.PipelineStatus.FAILED, "
         + "                   com.bff.pipeline.enums.PipelineStatus.CANCELLED) "
         + "and p.notifiedAt is null "
         + "and p.notifyAttempts >= :maxAttempts")
    long countGivenUp(@Param("maxAttempts") int maxAttempts);
}
```

### 4.2 `NotifyClaimer` (tx1)

> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: 별도 claim 트랜잭션·점유 토큰 발급은 없다.
> `TerminalNotifier.deliverOne()`이 잠금과 payload 조립을 같은 트랜잭션에서 수행한다
> (payload 조립 로직 자체는 그대로 `TerminalNotifier.buildPayload`로 이동).

```java
@Component
public class NotifyClaimer {
    private final NotifyRepository repo;
    private final TaskRepository taskRepo;   // 실패 task 조회용(failedTask/errorCode 는 Task 에 있다)
    private final NotifySettings settings;
    private final Clock clock;
    // 생성자 주입

    @Transactional
    public Optional<NotifyClaim> claimOne() {
        Instant now = clock.instant();
        // 2026-07-09 개정: 도입 컷오프(enabledAfter)를 술어 인자로 전달한다(ADR-022 §5 채택안).
        return repo.findNextNotifiable(now, settings.maxAttempts(), settings.enabledAfter()).map(p -> {
            String token = UUID.randomUUID().toString();
            p.setNotifyClaimedBy(token);
            p.setNotifyClaimedUntil(now.plus(settings.leaseDuration()));
            // payload 는 행이 로드된 tx1 안에서 구성한다(이미 커밋된 pipeline/task 행에서).
            return new NotifyClaim(p.getId(), token, buildPayload(p));
        });
    }

    private NotifyPayload buildPayload(Pipeline p) {
        String failedTask = null, errorCode = null;
        if (p.getStatus() == PipelineStatus.FAILED) {
            // 실패 task = sequence 최소의 FAILED task. TaskRepository.findByPipelineIdOrderBySequenceAsc 재사용.
            Task failed = taskRepo.findByPipelineIdOrderBySequenceAsc(p.getId()).stream()
                    .filter(t -> t.getStatus() == TaskStatus.FAILED).findFirst().orElse(null);
            if (failed != null) {
                // failedTask 는 닫힌 recipe task 키만(ADR-022 §4). getTaskName() 이 provider/운영자
                // 유래 raw 명을 담을 수 있으면 승인 코드로 매핑해 넣는다(NotifyPayloadPiiTest 가 검출).
                failedTask = failed.getTaskName();
                errorCode = failed.getErrorCode() == null ? null : failed.getErrorCode().name();
            }
        }
        // type 은 write-once 캐시라 미해석 옛 값이 null 로 열화할 수 있다 → null-guard 필수(NPE 방지).
        String type = p.getType() == null ? null : p.getType().name();   // INSTALL | DELETE | CUSTOM | null
        // targetRef 의 canonical 안전 소스 = 파이프라인의 **opaque target 키**(target-source
        // 식별자). raw hostname/account/DB명·연결 상세(host/port/credential)는 payload 에 절대
        // 직렬화하지 않는다(MUST NOT, ADR-022 §4). 아래 toTargetRef 가 유일한 매핑 지점이며,
        // 이 규칙은 NotifyPayloadPiiTest(§11)가 강제한다 — raw 식별자가 새면 테스트가 실패한다.
        return new NotifyPayload(p.getId(), type, p.getStatus().name(),
                toTargetRef(p), failedTask, errorCode, "1");
    }

    /** 파이프라인 대상 → opaque 알림 참조. V1: 이미 opaque 한 target 키를 그대로 쓴다.
     *  target 필드가 raw 식별자를 담게 되면 여기서 해싱/치환해 opaque 핸들만 내보내도록 바꾼다
     *  (유일한 변경 지점). 연결 상세는 여기서도 접근하지 않는다. */
    private String toTargetRef(Pipeline p) {
        return p.getTarget();   // opaque target 키 가정. 아니게 되면 이 한 곳만 고친다.
    }
}
```

`NotifyClaim(long pipelineId, String token, NotifyPayload payload)`. `buildPayload` 는 tx1 안에서
호출돼 이미 커밋된 pipeline/task 행만 읽는다(도메인 상태 변경 없음).

> **실행과의 격리(중요):** notify 는 `notify_claimed_by/until` 을 쓰고 실행의 `claimed_by/until` 은
> 건드리지 않는다. 실행의 admission soft-cap 은 `countByClaimedUntilAfter` 로 세는데, 만약 notify 가
> `claimed_until` 을 공유하면 종단 행의 notify lease 가 그 카운트를 부풀려 실행 처리량을 깎는다.
> 전용 컬럼쌍으로 이 오염을 원천 차단한다(§8).
> 종단 행은 실행 claim 술어(RUNNING/PENDING 한정)에 절대 안 걸리므로, 두 lease 가 같은 행에서
> 경합할 일도 없다.

### 4.3 `NotifyPayload` + `SlackNotifier` (외부 호출)

> ⛔ **일부 대체됨(2026-07-10 2차, 오너 요청 — 알림 내용 확장)**: 아래 코드의 payload는
> 7필드/`schemaVersion "1"` 기준이다. 현행은 **10필드/`"2"`** — `cloudProvider`(nullable enum 이름)·
> `environment`(설정의 배포 환경 태그)·`detailUrl`(콘솔 상세 링크, 설정 base + pipeline id만으로
> 조립하는 유일 허용 링크)이 추가됐다. Slack 메시지도 머리글에 `[stg]`/`[prd]` 태그와
> "상세 보기" 링크가 붙고, 필드에 `cloud_provider`가 추가되며 `target_ref`의 표시 라벨은
> `target_source`다(payload 필드명은 유지). 정본은 개정된 ADR-022 §4와 코드
> (`NotifyPayload`/`SlackNotifier.toSlackMessage`/`TerminalNotifier.buildPayload`)다.

**PII 최소화 — 허용 필드만**(ADR-022 §4). 그 외 민감 상세는 싣지 않는다.

```java
public record NotifyPayload(
        long pipelineId,
        String type,            // INSTALL | DELETE | CUSTOM | null(열화)
        String terminalStatus,  // DONE | FAILED | CANCELLED
        String targetRef,       // 대상의 opaque 참조(target 키/id). raw host/account/DB명 금지(MUST NOT) — ADR-022 §4
        String failedTask,      // FAILED 일 때만, 아니면 null
        String errorCode,       // FAILED 일 때만, 아니면 null
        String schemaVersion) { // 상수 "1"
}
```

`SlackNotifier` — 인터페이스 없이 구체 클래스. Spring `RestClient` 로 webhook 에 POST,
`call-timeout` 을 connect/read timeout 으로 건다(별도 스레드풀 불필요 — HTTP 클라이언트가 상한을 소유).

**RestClient 빈은 `PipelineConfig` 에 명시적으로 둔다**(Boot는 `RestClient.Builder` 만 자동구성하므로
타임아웃이 걸린 빈을 직접 만든다):

```java
// PipelineConfig
@Bean
public RestClient notifyRestClient(NotifySettings settings) {
    var f = new SimpleClientHttpRequestFactory();
    int ms = (int) settings.callTimeout().toMillis();
    f.setConnectTimeout(ms);
    f.setReadTimeout(ms);
    return RestClient.builder().requestFactory(f).build();
}
```

```java
@Component
public class SlackNotifier {
    private final RestClient notifyRestClient;   // @Qualifier("notifyRestClient")

    /** 실패(비2xx/타임아웃/IO)면 예외 → 호출자(NotifyScheduler)가 잡아 tx2 backoff. */
    public void deliver(String webhookUrl, NotifyPayload p) { post(webhookUrl, toSlackMessage(p)); }

    // ⛔ 미구현(2026-07-09 오너 결정): 아래 deliverTest/TEST_MESSAGE(admin 테스트 전송, §6)는
    //    만들지 않는다 — admin 표면이 없다.
    private static final String TEST_MESSAGE = ":bell: PII 파이프라인 알림 채널 테스트 메시지";

    /** admin 테스트 전송 — 실제 pipeline 없이 고정 메시지. */
    public void deliverTest(String webhookUrl) {
        post(webhookUrl, Map.of("text", TEST_MESSAGE));
    }

    private void post(String webhookUrl, Object message) {   // core
        notifyRestClient.post().uri(webhookUrl)
              .contentType(MediaType.APPLICATION_JSON)
              .body(message)
              .retrieve().toBodilessEntity();     // 비2xx → RestClientException
    }
    // toSlackMessage(NotifyPayload): 아래 형식의 Map 을 만든다.
}
```

Slack 메시지 형식(간단·읽기 쉬운 텍스트; blocks 는 나중에):

`target_ref` 는 **opaque 참조만**(raw host/account/DB명 금지, ADR-022 §4). 어떤 대상인지
사람이 확인해야 하면 `id 1234` 링크 뒤 권한 화면에서 조회한다 — Slack 본문엔 안 흘린다.

```json
{
  "text": ":white_check_mark: *Pipeline DONE* — INSTALL (id 1234)",
  "attachments": [{
    "color": "good",
    "fields": [
      {"title": "type",       "value": "INSTALL",  "short": true},
      {"title": "status",     "value": "DONE",     "short": true},
      {"title": "target_ref", "value": "tgt_9f3a", "short": false}
    ]
  }]
}
```

- `DONE` → `:white_check_mark:`/`good`, `FAILED` → `:x:`/`danger`(+`failed_task`/`error_code` 필드),
  `CANCELLED` → `:no_entry:`/`warning`. `pipeline_id` 는 항상 포함(중복 메시지를 사람이 식별하는 키).

### 4.4 `NotifyWriteBack` (tx2, guarded)

> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: 별도 write-back 트랜잭션·fencing 가드는 없다.
> 결과 기록은 전송과 같은 트랜잭션에서 하고, 잠금을 쥔 트랜잭션이 곧 기록 트랜잭션이라
> stale 워커 상태가 존재하지 않는다. backoff는 선형(시도 횟수 × 1분), far-future sentinel 없음.

```java
@Component
public class NotifyWriteBack {
    private final PipelineRepository pipelines;   // tx2 행 잠금은 기존 findByIdForUpdate 재사용
    private final NotifySettings settings;
    private final Clock clock;

    @Transactional
    public void onSuccess(long pipelineId, String token) {
        guarded(pipelineId, token, p -> {
            p.setNotifiedAt(clock.instant());
            p.setNotifyClaimedBy(null);
            p.setNotifyClaimedUntil(null);
            p.setNotifyNextAt(null);   // stale backoff 메타데이터 제거(postmortem 혼선 방지)
        });
    }

    @Transactional
    public void onFailure(long pipelineId, String token) {
        guarded(pipelineId, token, p -> {
            int attempts = p.getNotifyAttempts() + 1;
            p.setNotifyAttempts(attempts);
            if (attempts >= settings.maxAttempts()) {
                // give-up: 재클레임 배제는 lockNotifiable 의 notifyAttempts < maxAttempts 가 담당(1차).
                // far-future 는 보조 표시(정렬/가시성)일 뿐 give-up 의 근거가 아니다.
                p.setNotifyNextAt(clock.instant().plus(Duration.ofDays(3650)));
                log.error("notify give-up pipeline={} after {} attempts", pipelineId, attempts);
            } else {
                p.setNotifyNextAt(clock.instant().plus(backoff(attempts)));
            }
            p.setNotifyClaimedBy(null);
            p.setNotifyClaimedUntil(null);
        });
    }

    /** findByIdForUpdate 로 잠그고 token 일치 + 아직 미알림일 때만 apply (stale-straggler fencing).
     *  success·failure write-back 둘 다 이 가드를 탄다 — stale worker 의 실패 write-back 이
     *  다른 worker 의 성공(notifiedAt 스탬프) 후 attempts/backoff 를 오염시키는 것을 막는다. */
    private void guarded(long id, String token, Consumer<Pipeline> mutate) {
        pipelines.findByIdForUpdate(id).ifPresent(p -> {
            // 토큰 불일치 = lease 만료 후 재claim; notifiedAt != null = 다른 worker 가 이미 성공 → 둘 다 no-op.
            if (token.equals(p.getNotifyClaimedBy()) && p.getNotifiedAt() == null) mutate.accept(p);
        });
    }

    private Duration backoff(int attempts) {   // 지수 + jitter, backoffMax 상한
        long base = settings.backoffBase().toMillis() * (1L << Math.min(attempts - 1, 20));
        long capped = Math.min(base, settings.backoffMax().toMillis());
        double f = ThreadLocalRandom.current().nextDouble(-1, 1) * settings.jitterRatio();
        return Duration.ofMillis(Math.max(1L, Math.round(capped * (1 + f))));
    }
}
```

### 4.5 `NotifyScheduler` (단일 데몬 loop)

> ⛔ **미구현(2026-07-09 오너 결정)** — 아래 코드의 런타임 채널 가드
> (`NotificationChannelService.activeChannel()`)는 **부팅 `enabled` 가드로 대체**되었다
> (disabled면 loop 자체가 뜨지 않고, webhook은 `NotifySettings.slackWebhookUrl()`을 쓴다).
> §0 구현 노트와 ADR-022 개정 이력 참조.
>
> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: 클래스 이름은 `TerminalNotifier`, sweep은
> 고정 10초 주기(`scheduleWithFixedDelay` — idle 기하 backoff 제거)이고, 한 sweep에서
> 밀린 알림이 없어질 때까지 배수한다. 부팅 가드·give-up 재경보 폴링(5분)은 그대로다.

`PipelineScheduler` 를 축소 모델링 — 워커 풀 fan-out 없이 **단일 스레드**. 채널 미설정/비활성이면 claim 안 함.

```java
@Component
public class NotifyScheduler {
    private final NotifyClaimer claimer;
    private final SlackNotifier slack;
    private final NotifyWriteBack writeBack;
    private final NotificationChannelService channels;
    private final NotifySettings settings;

    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notify-scheduler"); t.setDaemon(true); return t;
    });
    private Duration idleBackoff;

    @PostConstruct void start() {
        if (!settings.enabled()) return;   // master switch off → 아예 안 돈다
        idleBackoff = settings.backoffBase();
        loop.schedule(this::runSweep, settings.schedulerInitialDelay().toMillis(), MILLISECONDS);
    }
    @PreDestroy void stop() { loop.shutdownNow(); }

    void runSweep() {
        boolean worked = false;
        try { worked = deliverOne(); }
        catch (RuntimeException e) { log.warn("notify sweep failed", e); }
        finally {
            Duration delay;
            if (worked) { idleBackoff = settings.backoffBase(); delay = settings.pollInterval(); }
            else        { delay = nextIdle(); }              // idleBackoff 리셋 후 pollInterval (PipelineScheduler 동형)
            if (!loop.isShutdown()) {                        // @PreDestroy 종료 후 재예약 → RejectedExecutionException 방지
                loop.schedule(this::runSweep, delay.toMillis(), MILLISECONDS);
            }
        }
    }

    /** 채널 있으면 한 건 claim→전달→기록. 반환: 일감이 있었나. */
    boolean deliverOne() {
        Optional<NotificationChannel> ch = channels.activeChannel();
        if (ch.isEmpty()) { return false; }                 // 미설정/비활성 → idle (backlog age 지표가 드러냄)
        Optional<NotifyClaim> claim = claimer.claimOne();
        if (claim.isEmpty()) return false;
        NotifyClaim c = claim.get();
        try {
            slack.deliver(ch.get().getSlackWebhookUrl(), c.payload());
            writeBack.onSuccess(c.pipelineId(), c.token());
        } catch (RuntimeException deliveryFailed) {
            log.warn("notify delivery failed pipeline={}", c.pipelineId(), deliveryFailed);
            writeBack.onFailure(c.pipelineId(), c.token());
        }
        return true;
    }

    private Duration nextIdle() {   // 빈 sweep geometric backoff (PipelineScheduler 와 동형, 단순화)
        idleBackoff = min(idleBackoff.multipliedBy(2), settings.maxIdleSleep());
        return idleBackoff;
    }
}
```

> **단일 스레드 근거(ponytail):** notify 는 파이프라인당 1회, 저빈도. 한 번에 한 건 직렬 처리로 충분하고
> Slack 호출은 `call-timeout` 으로 상한. 처리량이 실제로 부족해지면 그때 워커 풀 fan-out(PipelineScheduler
> 처럼)으로 올린다.

## 5. 실패·재시도·give-up 규칙

> ⛔ **일부 대체됨(2026-07-10 — §0 구현 노트 2)**: 지수 backoff+jitter → 선형(시도 횟수 × 1분),
> `max-attempts` 8 → 상수 `MAX_ATTEMPTS=3`, far-future sentinel 제거, "lease 만료" 중복 경로는
> "전달 성공~커밋 사이 크래시"로 좁혀짐. give-up 복구는 `notify_attempts`를 0으로 리셋.

| 상황 | tx2 처리 | 재시도 |
|---|---|---|
| 전달 성공 | `notified_at = now`, notify lease 해제 | 대상에서 영구 제외 |
| 전달 실패(비2xx/타임아웃/IO), `attempts < max` | `attempts++`, `notify_next_at = now + backoff` | backoff 후 재claim |
| 전달 실패, `attempts >= max` | `attempts++`, `notify_next_at = now + 3650d`, **ERROR 로그** | 자동 중단(사람 개입) |
| lease 만료 후 지연 도착 tx2 | 토큰 불일치 → **no-op** | 다른 워커가 이미 처리/재시도 |

- **at-least-once**: 전달 성공 후 tx2 커밋 전 크래시/타임아웃/lease 만료 → 중복 전달 가능.
  exactly-once 보장 안 함. **Slack은 자동 dedupe하지 않는다** — 같은 종단 메시지가 채널에
  드물게 두 번 보일 수 있고 V1은 이를 수용한다(§1). `pipeline_id`는 사람이 눈으로 중복을 식별하는
  키일 뿐, Slack이 제거해 주는 게 아니다. 자동 dedupe가 필요하면 Slack 앞 멱등 브리지(후속).
- **give-up 복구**: admin 이 수동으로 `notify_next_at`/`notify_attempts` 를 리셋하면 재시도된다
  (전용 admin 액션은 V1 비범위 — DB 수정 또는 후속).

## 6. Admin: Slack 채널 관리

> ⛔ **미구현(2026-07-09 오너 결정)** — 이 절 전체(REST·DTO·SSRF·마스킹·테스트 전송·frontend
> 카드)는 구현하지 않는다; 채널은 env var로 관리한다. §0 구현 노트와 ADR-022 개정 이력 참조.

### 6.1 Orchestrator REST (신규 `NotificationChannelController`)

기존 컨트롤러 패턴(`@RestController`, 인바운드 접두어 **`/api/v1`**, `GlobalAdvice` 예외 처리) 따른다.
단일 sink 이므로 단수 리소스.

```
GET  /api/v1/admin/notification-channel   → ChannelView { channelLabel, enabled, webhookConfigured, webhookMasked, updatedAt }
PUT  /api/v1/admin/notification-channel   ChannelUpsert { channelLabel, enabled, slackWebhookUrl? } → ChannelView (upsert, 항상 id=1)
POST /api/v1/admin/notification-channel/test → TestResult { delivered: bool, error?: string }  (항상 200)
```

DTO(전부 record, `dto` 패키지). **이 repo 는 글로벌 snake_case 매퍼가 없어 DTO 마다 `@JsonProperty`
로 snake_case 를 명시해야 한다**(기존 컨벤션, ADR-019 casing 경계). 주의: `DtoSnakeCaseSerializationTest`
는 **자동 발견이 아니라 손으로 나열한 목록**이라 신규 DTO 를 자동으로 지키게 해주지 않는다 —
**세 신규 DTO 케이스를 그 테스트에 명시적으로 추가**해야 한다(§11). 각 필드에 `@JsonProperty`:

```java
public record ChannelUpsert(
        @JsonProperty("channel_label") String channelLabel,
        @JsonProperty("enabled") Boolean enabled,        // Boolean(원시형 아님) — 생략 시 null 로 구분, 검증에서 필수화
        @JsonProperty("slack_webhook_url") String slackWebhookUrl) {}

public record ChannelView(
        @JsonProperty("channel_label") String channelLabel,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("webhook_configured") boolean webhookConfigured,
        @JsonProperty("webhook_masked") String webhookMasked,
        @JsonProperty("updated_at") Instant updatedAt) {}

public record TestResult(
        @JsonProperty("delivered") boolean delivered,
        @JsonProperty("error") String error) {}
```

- **webhook 은 secret**: `GET` 은 **절대 원문을 반환하지 않는다** — `webhookConfigured` +
  `webhookMasked`(예: `https://hooks.slack.com/…/xxxx` 뒤 4자만).
- **upsert 필드 규칙(명시)**: `slack_webhook_url` 이 **null 또는 blank 이면 기존 값 유지**,
  값이 오면 검증(아래 SSRF) 후 교체. `enabled` 는 **필수** — `Boolean` 이 null(생략)이면
  `InvalidNotificationWebhookException`(400)으로 거절(원시 `boolean` 은 생략을 조용히 false 로
  만들므로 쓰지 않는다). 어떤 경우든 성공 시 `updated_at` 갱신.
- **upsert 구현(수동 @Id)**: `id` 는 `@GeneratedValue` 가 아니라 `SINGLETON_ID` 고정이므로,
  `findById(SINGLETON_ID)` 로 **로드-또는-생성**(`orElseGet(() -> new …(SINGLETON_ID))`) 후 필드
  변경 → `save`. (set-id 엔티티를 곧장 `save` 하면 Hibernate 가 detached 로 보고 select-then-insert
  merge 를 하므로, 먼저 load 해 명확히 한다.)
- **SSRF 방어(보안, 필수)**: `upsert` 는 webhook URL 을 `java.net.URI` 로 파싱해 검증한다 —
  `"https".equalsIgnoreCase(scheme)` **AND** `"hooks.slack.com".equalsIgnoreCase(host)`(정확 일치;
  `contains`/`endsWith` 금지 — `hooks.slack.com.evil` 우회) **AND** userinfo 없음
  (`https://hooks.slack.com@evil/` 차단). 위반 시 `InvalidNotificationWebhookException`(400).
  "admin 이 넣는 값이라 안전"에 기대지 않는다(서버가 임의 URL 로 POST 하면 SSRF).
- **에러 매핑(`GlobalAdvice` 정합)**: `OrchestrationException` 은 abstract 이고 기존 패턴은
  status/code 를 실은 **구체 서브클래스**다. 신규로 하나 정의한다 —
  `class InvalidNotificationWebhookException extends OrchestrationException` 이 `HttpStatus.BAD_REQUEST`
  + `OrchestrationErrorCode.INVALID_NOTIFICATION_WEBHOOK`(enum 값 추가)를 싣는다. `upsert` 검증
  실패 시 이걸 던지면 `GlobalAdvice` 의 단일 `OrchestrationException` 핸들러가 4xx 로 매핑한다
  (`PipelineNotFoundException` 등과 동형).
  **`test` 는 예외로 실패를 알리지 않는다** — `SlackNotifier.deliverTest` 의 `RestClientException` 을
  잡아 `TestResult{delivered:false, error:message}` 로 **200** 반환(probe 결과지 서버 오류가 아님).
  채널 미설정 상태의 `test` 는 `{delivered:false, error:"channel not configured"}`.
- `NotificationChannelService.activeChannel()` = `enabled && slackWebhookUrl != null` 인 행 반환
  (없으면 empty → scheduler idle).

### 6.2 Frontend Admin Page (`pii-agent-demo`, Next.js) — **별도 repo, 백엔드 빌드 비차단**

> 이 절은 **다른 저장소**(`pii-agent-demo`) 작업이다. §2~§6.1(orchestrator 백엔드)은 이 절 없이도
> 독립적으로 빌드·배포 가능하다. 프론트는 §6.1 계약(REST shape)만 소비한다.

기존 admin 파이프라인 영역(LIN-20/25)에 **"알림 채널" 설정 카드** 1개 추가. BFF/orchestrator 프록시
경로 규약(admin orchestrator proxy)에 맞춰 Next route → orchestrator(`/api/v1/admin/notification-channel`)로 프록시.

- 필드: **Slack Webhook URL**(입력; 저장 후엔 마스킹 표시, 재입력 시 교체), **채널 라벨**(표시용),
  **활성 토글**, **[테스트 전송] 버튼**(→ `POST …/test`, 결과 토스트).
- 계약 shape 는 §6.1 그대로. casing 경계는 ADR-019 규약(route 에서 검증/변환).
- webhook URL 은 UI 에도 원문을 다시 내려주지 않는다(마스킹). 최초 1회 입력만 평문.

## 7. 관측(지표/로그)

`spring-boot-starter-actuator`/Micrometer 는 현재 pom 에 없다. 단, **give-up 경보의 정규 소스는
로그가 아니라 DB 파생 폴링**이다(ADR-022 §4). V1 관측은 아래로 구성한다:

- **give-up 경보(필수·정규 소스 = DB 폴링, 배포 게이트)** — actuator 가 없어도 **`countGivenUp
  (maxAttempts)` 리포지토리 술어를 주기 폴링하는 인터럽 잡**(예: `@Scheduled` 로 N 분마다 조회)을
  두어 `> 0` 이면 담당자에게 page 한다(구현된 V1 배선: `NotifyScheduler` 가 sweep 안에서 5분
  주기로 `countGivenUp` 을 폴링해 `> 0` 이면 ERROR 로그로 승격 — 조직 스택은 같은 DB 술어를
  폴링한다). 로그는 유실·수집 누락·배선 전 발생이 가능하므로 정규
  경보 소스로 삼지 않는다. **이 DB 폴링 경보 배선은 notifier 프로덕션 가동 전제**다. actuator
  도입 후에는 같은 술어를 `notify.giveup.total` gauge 로 승격한다.
- **구조화 로그(감사 재구성 보조, ADR-022 §결과)** — success/failure/give-up 모두 최소
  `pipeline_id`·`terminal_status`·`attempt`·`sink`(=slack)·응답 분류(`resp_class`: 2xx/4xx/5xx/timeout,
  실패 시 error class)를 포함한다(give-up 로그는 **진단 보조**이지 정규 경보 소스가 아니다):
  - `notify delivered pipeline={} status={} attempt={} sink=slack resp_class=2xx`(INFO)
  - `notify delivery failed pipeline={} status={} attempt={} sink=slack resp_class={}`(WARN)
  - `notify give-up pipeline={} status={} after {} attempts sink=slack`(ERROR, 진단용).
- **두 age 를 별도 쿼리로 구분**(ADR-022 §4, V1 부터): `oldestUnnotifiedAt(maxAttempts)` =
  **총 backlog age**(`terminal_notification_backlog_age`, 비활성 채널 backlog 포함).
  `oldestDeliveryPending(maxAttempts, now)` = **due 행만** 본 “전달 정체” age
  (`notify_delivery_pending_age`, 미도래 backoff 행 제외 — 건강한 재시도 대기를 전달 막힘으로
  오인하지 않게). **“전달 정체” 경보는 후자를 쓰고, 활성 채널일 때만 평가**한다(비활성 시 suppress).
  gauge 노출은 후속(YAGNI)이나 쿼리는 처음부터 둘로 나눈다. `countGivenUp(maxAttempts)` 는 give-up 수(사람 개입 필요).
  > ⛔ **삭제(2026-07-09 오너 결정)** — 두 age 쿼리는 소비 표면(actuator/관리 조회)이 없어
  > 두지 않는다; 그 표면 도입 시 위 정의대로 재도입(개정 ADR-022 §4). `countGivenUp` 만 남는다.
- actuator 를 추가하면 gauge 노출: `notify.backlog.oldest.age.seconds`(총),
  `notify.delivery.pending.age.seconds`(활성 채널 한정), `notify.attempts.total`(counter),
  `notify.giveup.total`(gauge=`countGivenUp`). **도입은 후속**(YAGNI).

## 8. 설계 판단 기록 (구현 중 갈린 지점)

- **notify 전용 lease 컬럼쌍 분리(≠ ADR-021 재사용).** ADR-022 §2/스키마는 "claimed_by/until 재사용"
  으로 서술하나, 실제 코드의 admission soft-cap `PipelineRepository.countByClaimedUntilAfter(now)` 가
  **상태 무관**하게 활성 lease 를 센다. 재사용하면 종단 행의 notify lease 가 이 카운트를 부풀려 **실행
  처리량을 깎는다.** 전용 `notify_claimed_by/until` 로 격리하는 게 정확하고 실행 코드를 안 건드린다
  (대안: `countByClaimedUntilAfter` 에 `status in (RUNNING,PENDING)` 필터 추가로 재사용 — 실행 회계
  질의를 건드리므로 기각). → **ADR-022 스키마/§2 갱신 완료**(컬럼 3 → 5, "재사용" → notify 전용 lease "전용 쌍").
  > ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: 이 판단 자체가 무의미해졌다 — 단일 트랜잭션
  > 재설계로 lease 컬럼이 아예 없어(컬럼 5 → 3), 실행 회계에 섞일 값 자체가 존재하지 않는다.
- **인터페이스 없음** — `SlackNotifier` 구체 클래스. 다중 sink 필요 시 추출(현재 비범위).
- **단일 sink** — `notification_channel` 1행(⛔ 2026-07-09 이후 채널 저장은 테이블이 아니라
  env var — §0 노트). 다중·독립 재시도 sink 는 ADR-022 가 유보(per-sink 상태).
- **부분 인덱스 없음(MySQL8)** — `active_target` 유일 제약과 같은 제약. 복합 인덱스 + 소규모로 흡수.

## 9. 패키지 배치 (레이어 규칙 준수)

기존 레이어 규칙에 맞춰 배치한다(record/DTO 가 `service` 로 새지 않게):

| 클래스 | 패키지 |
|---|---|
| `NotifySettings` | `config` (+ `PipelineConfig` 에 `@EnableConfigurationProperties`·RestClient 빈) |
| `NotificationChannel`(엔티티), `Pipeline`(필드 추가) | `entity` |
| `NotifyRepository`, (재사용) `TaskRepository` | `repository` |
| `NotifyPayload`(Slack 로 직렬화=transport) | `dto` |
| `NotifyClaim`(내부 handoff 값, 전송 안 함) | `model` |
| `NotifyClaimer`, `NotifyWriteBack`, `NotifyScheduler`, `SlackNotifier`, `NotificationChannelService` | `service`(실행 하위 패턴 따르면 `service.notify`) |
| `NotificationChannelController` | `controller` |
| `ChannelUpsert`/`ChannelView`/`TestResult` (전부 `@JsonProperty` snake_case) | `dto` |
| `InvalidNotificationWebhookException`(extends `OrchestrationException`) | `exception` |
| `OrchestrationErrorCode.INVALID_NOTIFICATION_WEBHOOK` (enum 값 추가) | 기존 `exception` |

> ⛔ **미구현(2026-07-09 오너 결정)** — 표의 `NotificationChannel`·`NotificationChannelService`·
> `NotificationChannelController`·`ChannelUpsert`/`ChannelView`/`TestResult`·
> `InvalidNotificationWebhookException`/`INVALID_NOTIFICATION_WEBHOOK` 행은 만들지 않는다
> (채널은 env var — §0 구현 노트 참조).
>
> ⛔ **대체됨(2026-07-10 — §0 구현 노트 2)**: `NotifyRepository` 행은 기존 `PipelineRepository`로
> 통합, `NotifyClaim` 행은 삭제, `NotifyClaimer`/`NotifyWriteBack`/`NotifyScheduler` 행은
> `TerminalNotifier`(`service.notify`) 하나로 통합.

## 10. 구현 순서 (슬라이스)

> ⛔ **일부 대체됨(2026-07-10 — §0 구현 노트 2)**: 1번은 3필드, 2번의 yml 키는 3키만,
> 4번의 `NotifyRepository`/`NotifyClaimer`/`NotifyWriteBack`과 6번의 `NotifyScheduler`는
> `PipelineRepository` 질의 2개 + `TerminalNotifier`로 통합.

1. `Pipeline` 5필드 + `idx_pipeline_notify` 추가 → 앱 부팅(`ddl-auto: update`)으로 컬럼 생성 확인.
2. `NotifySettings` + yml 키 + `PipelineConfig` 등록 → 잘못된 값에 fail-fast 되는지 확인.
3. `NotificationChannel` 엔티티 + `NotificationChannelService`(activeChannel/upsert/mask) + 컨트롤러.
   (⛔ 미구현 — 2026-07-09 오너 결정, 채널은 env var. §0 구현 노트 참조.)
4. `NotifyRepository`(claim/guard/countGivenUp — age 쿼리 2종은 소비 표면 도입 시, §7 ⛔ 참조)
   + `NotifyClaimer`(tx1) + `NotifyWriteBack`(tx2).
5. `SlackNotifier`(RestClient, 타임아웃) + payload 빌더(PII 허용 필드만).
6. `NotifyScheduler`(단일 loop, 부팅 enabled 가드 — 2026-07-09 개정, §4.5 ⛔ 참조).
7. **(별도 repo `pii-agent-demo`)** Frontend admin 카드 + Next 프록시 route — 백엔드(1~6) 빌드와 독립.
   (⛔ 미구현 — 2026-07-09 오너 결정, admin 채널 표면 없음. §0 구현 노트 참조.)
8. 테스트(§11).

## 11. 테스트 체크리스트 (H2 MySQL-mode, `@DataJpaTest`/단위)

> ⛔ **일부 대체됨(2026-07-10 — §0 구현 노트 2)**: fencing·lease·격리(`countByClaimedUntilAfter`)
> 항목은 검증 대상 자체가 사라져 삭제. 대신 단일 트랜잭션 설계의 핵심 검증은
> "전달 실패 기록(attempts/next_at)이 롤백되지 않고 커밋되는가"다. 협력자도
> `TerminalNotifier` + `PipelineRepository`/`TaskRepository`로 단순화.

**슬라이스 셋업(리포 필수 패턴, `PipelineSoftCapTest` 미러):** repository/claim/write-back 테스트는
`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + **`@Transactional(propagation = NOT_SUPPORTED)`**
(래핑 tx 비활성 — 없으면 tx1/tx2 가 한 물리 tx 로 합쳐져 토큰 fencing·lease 재claim 을 못 본다) +
협력자 `@Import`(`NotifyClaimer`, `NotifyWriteBack`, `NotifyRepository`, `PipelineRepository`,
`TaskRepository`) + `Wiring @TestConfiguration`(고정 `Clock` 과 `NotifySettings` 를 빈으로 제공 —
`@ConfigurationProperties`·`PipelineConfig` 는 슬라이스에 안 실린다). `@BeforeEach` 로 상태 정리.
**`SlackNotifier` 는 슬라이스 밖 순수 단위 테스트**로(스텁 `RestClient`) 검증한다.


- **claim 술어**: 종단·미알림·due 행만 잡고, `notified_at != null`/미도래 `notify_next_at`/유효 lease/
  **give-up(`notify_attempts >= maxAttempts`)** 행은 스킵(attempts 술어가 give-up 을 배제하는지 명시 검증).
- **tx2 fencing**: 토큰 불일치 **또는 이미 `notified_at != null`** 인 write-back 은 no-op
  (stale worker 가 타 worker 성공 후 attempts 를 못 건드림 — success·failure 양쪽 guard).
- **backoff/give-up**: `attempts` 증가·`notify_next_at` 전진, `max-attempts` 도달 시 ERROR 로그 +
  이후 claim 에서 재선택 안 됨(far-future 아니라 attempts 술어로).
- **격리**: notify lease 스탬프가 `countByClaimedUntilAfter`(실행 캡)에 **안 잡힘**(전용 컬럼 검증).
- **채널 가드**: 미설정/비활성이면 claim 0건(scheduler idle).
  > ⛔ **미구현(2026-07-09 오너 결정)** — 부팅 `enabled` 가드로 대체(disabled 면 loop 미기동);
  > 검증 대상은 `NotifyScheduler.start()` 의 enabled 분기다.
- **payload PII (`NotifyPayloadPiiTest`)**: 허용 필드만 직렬화하고 다음을 명시 단언한다 —
  (a) 직렬화 JSON 에 raw 연결 식별자(host/port/credential/DB명 패턴)가 **없음**(`toTargetRef` 외
  경로로 민감 값이 새면 실패), (b) `failed_task` 값이 **닫힌 recipe 키 집합에 속함**(raw provider/
  운영자 명이면 실패), (c) payload 에 **`url` 필드가 없음**(스키마 구조상 부재를 회귀 방지로 고정).
  `FAILED` 는 `buildPayload` 가 sequence 최소 FAILED task 에서 `failed_task`/`error_code` 를 채운다
  (비-FAILED 는 null).
- **webhook 마스킹**: `GET` 응답에 원문 webhook 이 없다(마스킹만).
  > ⛔ **미구현(2026-07-09 오너 결정)** — admin GET 이 없다(채널은 env var; secret 은 로그·응답에 원문 금지).
- **SSRF 검증**: 비-https·비-정확일치 host·userinfo 포함 webhook `PUT` 은 typed 400 으로 거절.
  > ⛔ **미구현(2026-07-09 오너 결정)** — upsert 엔드포인트가 없다(webhook 은 배포 env 로만 주입).
- **DTO snake_case**: 세 신규 DTO(`ChannelUpsert`/`ChannelView`/`TestResult`) 케이스를
  `DtoSnakeCaseSerializationTest` 에 **명시적으로 추가**(자동 발견 아님).
  > ⛔ **미구현(2026-07-09 오너 결정)** — 세 채널 DTO 를 만들지 않는다.
- **upsert 필드 규칙**: `slack_webhook_url` null/blank → 기존 유지, 값 → 교체; `enabled` 생략(null) → 400;
  최초 upsert 는 `SINGLETON_ID` 로 insert.
  > ⛔ **미구현(2026-07-09 오너 결정)** — upsert 가 없다(채널은 env var).
- **test 엔드포인트**: 전달 실패해도 200 + `{delivered:false, error}`; 미설정이면 `channel not configured`.
  > ⛔ **미구현(2026-07-09 오너 결정)** — 테스트 전송 엔드포인트가 없다.
- **at-least-once**: 성공 후 tx2 전 크래시 모사 → 재전달(중복 Slack 메시지 수용, `pipeline_id` 로 식별).

## 12. 링크

- [ADR-022](../../docs/adr/022-terminal-state-notification.md) — 결정/근거(상태 파생 알림).
- [ADR-021](../../docs/adr/021-pipeline-execution-model.md) — claim/lease/two-tx 원본 패턴.
- [ADR-016](../../docs/adr/016-install-delete-pipeline-domain-model.md) — 도메인 상태·종단·관측.
