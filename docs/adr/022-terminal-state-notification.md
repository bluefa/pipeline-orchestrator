# ADR-022: Install/Delete 파이프라인 — 종단 상태 알림(state-derived notification)

## 상태

제안됨 — 2026-07-01 (개정 2026-07-07: postCheck 분리 — `CONDITION_CHECK`로 확정·구현됨.
이 ADR은 **종단 상태 알림**만 다룬다. 개정 2026-07-09: 채널 관리를 admin 표면 대신
환경변수로. 개정 2026-07-10: 전달 동시성을 lease/fencing/two-tx에서 **행 잠금을 쥔 단일
트랜잭션**으로 단순화 — 개정 이력 참조).

[ADR-016](016-install-delete-pipeline-domain-model.md)(도메인 모델)·
[ADR-021](021-pipeline-execution-model.md)(실행 모델)의 **후속**으로, 파이프라인이 종단
(`DONE`/`FAILED`/`CANCELLED`)에 도달했음을 오퍼레이터·다운스트림에 **신뢰성 있게 알리는
경로**를 정한다. ADR-016이 유보한 사항이다(ADR-021은 관련 지표만 정의).

> **postCheck는 이 ADR 범위 밖이다.** 자기 보고 완료 판정과 별개로 end-state를 검증하는
> postCheck는 ADR-016/021의 `CONDITION_CHECK`(retry-count로 바운드되는 빠른 probe)로
> **확정·구현**되었다. recipe의 마지막 task로 `CONDITION_CHECK`를 두는 것은 ADR-016의
> **recipe 작성 규약**이지 새 메커니즘이 아니므로 별도 결정 문서가 필요 없다. 이 ADR은
> 남은 후속인 **종단 알림**에 집중한다.

**핵심 설계 방향**: 종단 전이는 **이미 `pipeline.status`에 durable하게 저장**되므로
별도 이벤트 저장소(트랜잭션 아웃박스)를 두지 않는다. 대신 `pipeline.notified_at` 마커
하나를 두고 **상태에서 알림을 파생(derive-from-state)**하며, 전달은 **행 잠금
(`FOR UPDATE SKIP LOCKED`)을 쥔 단일 트랜잭션** 안에서 수행한다(§2). 이는 ADR-016이 초기 최대 모델에서 event
outbox를 잘라낸 취지("logs/metrics + domain rows로 충분")와 일치한다.

ADR-016/021의 불변식(“DB row가 곧 상태”, at-least-once + 멱등성, 종단 상태 부활 금지,
개념 최소화)을 그대로 상속한다. 이 ADR은 `pipeline`에 **알림 메타데이터 컬럼**
`notified_at`을 더한다 — 이는 ADR-021이 `pipeline`에 실행 메타데이터(`next_due_at`,
`claimed_by` 등)를 더한 것과 **같은 범주**이며(ADR-021 §2 「Execution schema note」),
도메인 상태(`status`·enum)는 바꾸지 않는다. 알림 정책 변경은 이 ADR만 대체한다;
claim-pull 실행 모델 변경은 ADR-021 소관이다.

## 맥락

### 종단 알림 — 신뢰성 있게 “끝났다”를 전달하기

파이프라인이 `DONE`/`FAILED`/`CANCELLED`에 도달해도 오퍼레이터·다운스트림에 **신뢰성
있게 알릴 경로가 없다.** 순진한 두 가지 방법은 모두 틀렸다.

- **전이 트랜잭션 안에서 알림 호출**: 상태 전이(DB write)와 외부 알림 호출을 하나로 묶는
  **dual-write**. 알림이 성공했는데 트랜잭션이 롤백되면(또는 반대) 상태와 알림이 갈라지고,
  느린 알림 서버가 상태 전이를 막는다 — 상태 정합성이 알림 서버 가용성에 종속된다.
- **커밋 후 best-effort 알림**: 커밋과 알림 호출 사이에 크래시가 나면 알림이 **조용히
  유실**된다(복구 경로 없음).

관건은 “전이는 커밋됐는데 알림은 아직”이라는 상태를 **durable하게 기억**하고 나중에
전달하는 것이다. 그런데 그 상태는 **이미 도메인 행에 있다** — `pipeline.status`가 종단이면
“알림 대상”이라는 뜻이다. 필요한 것은 “이미 알렸는가”를 나타내는 마커 하나뿐이다.

### 규모(ADR-016/021과 동일)

대상 ~2,000개, 분 단위 job, 단일 조직 내부 도구. 이 규모는 별도 이벤트 저장소·relay·
CDC/브로커 같은 이벤트 인프라를 정당화하지 않는다.

## 결정

### 1. 종단 알림은 상태에서 파생한다(derive-from-state) — 별도 이벤트 저장소 없음

**“알림 대상 판정”은 상태 마커 `pipeline.notified_at`(nullable) 하나로 족하다** — “알렸는가”는
이 컬럼만으로 표현된다(재시도를 굴리는 backoff 컬럼 2개가 더 붙지만 그건
*전달 제어* 메타데이터다; 스키마 컬럼 총 3개는 아래 **스키마** 절). 알림 대상은 **쿼리로 파생**된다:

```
status IN ('DONE','FAILED','CANCELLED') AND notified_at IS NULL
```

“전이는 커밋됐는데 알림은 아직”은 이 술어로 완전히 표현된다 — 별도 이벤트 행을 INSERT할
필요가 없다. 종단 전이 자체가 durable하므로 **dual-write가 원천적으로 없다**(전이가
커밋돼야만 알림 대상이 되고, 커밋됐다면 반드시 대상이 된다). `notified_at`은 알림
메타데이터이지 도메인 상태가 아니다 — **파이프라인 실행 claim·도메인 전이 로직은 이를 읽지
않는다**(알림 claim 술어는 당연히 읽는다 — 그건 “알림 대상 선별”이지 도메인 전이가 아니다).
(위는 개념 술어이고, 컷오프·backoff·시도 상한 게이트를 포함한 **완전한 claim 술어는 §2**에 있다.)

알림 payload는 **이미 커밋된 `pipeline`/`task` 행에서 구성**한다(종단 종류, 실패 시
실패 task와 `error_code` 포함). 따라서 `TASK_FAILED`를 별도 이벤트로 둘 필요가 없다 —
`FAILED` 종단 알림이 실패 task 상세를 실어 나른다.

### 2. 알림 전달은 행 잠금을 쥔 단일 트랜잭션으로 처리한다(잠금이 곧 점유)

> 개정 2026-07-10 — 이전 판은 ADR-021의 메커니즘(lease + fencing 토큰 + two-tx guarded
> write-back)을 notify 전용 컬럼쌍으로 재사용했다. 오너의 단순화 결정으로 단일 트랜잭션
> 방식으로 교체했다(개정 이력 참조). 이전 설계 전문은 이 ADR의 2026-07-09 판(git 이력)에 있다.

알림 전달은 외부 호출이다. ADR-021에서 재사용하는 것은 `FOR UPDATE SKIP LOCKED` 잠금
관용구 하나뿐이고, lease·fencing 토큰·two-tx 분리는 쓰지 않는다. 종단 알림은 **별도
work-kind**로 자체 술어·인덱스·loop를 가지며, 한 건의 전달은 **트랜잭션 하나**로 끝난다:

```sql
-- 단일 tx: 잠금 → payload 조립 → 전달 → 결과 기록 → 커밋 (:now 는 주입된 앱 Clock 시각)
SELECT id FROM pipeline
 WHERE status IN ('DONE','FAILED','CANCELLED')
   AND notified_at IS NULL
   AND last_activity_at >= :enabled_at   -- 도입 컷오프(§5 채택안) — 도입 전 종단 행은 알림 범위 밖
   AND notify_attempts < :maxAttempts    -- give-up 행 배제
   AND (notify_next_at IS NULL OR notify_next_at <= :now)
 ORDER BY notify_next_at ASC, id ASC   -- NULL 선두(신규 종단), id로 deterministic tie-break
 LIMIT 1
 FOR UPDATE SKIP LOCKED;            -- MySQL8; 구현은 @Lock + lock-timeout -2
-- (같은 tx에서 커밋된 pipeline/task 행을 읽어 payload 조립)
-- (Slack HTTP 호출 — call-timeout 상한)
-- 성공: UPDATE pipeline SET notified_at = :now, notify_next_at = NULL WHERE id = :id
-- 실패: UPDATE pipeline SET notify_attempts = notify_attempts + 1,
--        notify_next_at = :now + (notify_attempts) * :retry_step   -- 상한 미달 시(증가 후 카운트)
COMMIT;
```

**행 잠금이 lease와 fencing을 대체한다.** 잠금을 쥔 채 전송하므로 다른 파드는 같은 행을
건너뛰고(SKIP LOCKED), “내 점유가 아직 유효한가”를 따로 검증할 필요가 없다 — 잠금을 쥔
트랜잭션이 곧 결과를 기록하는 트랜잭션이라, lease 만료 뒤 되살아난 stale 워커라는 상태가
구조적으로 존재하지 않는다. 전송 도중 워커가 죽으면 커밋되지 않아 아무 기록도 남지 않고,
잠금은 커넥션 종료와 함께 즉시 풀리며, 다음 스캔이 처음부터 재시도한다(lease 만료를 기다릴
필요도 없다).

**ADR-021이 two-tx 분리(“락을 외부 호출에 물리지 않는다”)를 요구한 이유는 실행 job이
분~시간 단위라 잠금을 그 시간 동안 쥘 수 없어서다.** 알림은 그 전제가 성립하지 않는다:
(a) 호출 시간 상한 = call-timeout(코드 상수 10초), (b) 파드당 notify 스레드 1개라 이런
트랜잭션은 파드당 최대 1개(N 파드면 최대 N개, SKIP LOCKED로 서로 다른 행), (c) 종단 행을
잠그려는 경쟁 작업이 없다(실행 claim은 RUNNING/PENDING만 스캔, admin 조회는 잠금 없음).
**이 전제 중 하나라도 깨지면**(독립 재시도가 필요한 다중 sink, 호출 타임아웃 대폭 증가,
종단 행을 갱신하는 배치 도입) **two-tx 분리 설계로 되돌린다** — 그 설계는 이 ADR의
2026-07-09 판과 구현 문서에 완전히 기록돼 있다.

이전 판이 notify 전용 lease 컬럼쌍을 둔 이유였던 실행 admission soft-cap 오염
(`countByClaimedUntilAfter`가 상태 무관하게 활성 lease를 셈)은 원천 소멸한다 — 알림이
lease 컬럼 자체를 쓰지 않으므로 셀 것이 없다. `:now`는 **주입된 단일 앱 `Clock` 시각**이다
(DB `now()`와 섞지 않는다 — 재시도 due 판정이 한 시계 기준이어야 함). 파드 간 시계 skew의
최악 효과는 재시도가 조금 이르거나 늦는 것뿐이다(같은 행 동시 전송은 잠금이 막는다).
**동작 값**(`maxAttempts`=3·재시도 간격·스캔 주기·call-timeout)은 **설정이 아니라 코드
상수다**(2026-07-10 오너 결정 — 운영이 바꿀 일이 없는 값을 설정으로 빼지 않는다). 운영
설정은 env 3키(`enabled`/`slack-webhook-url`/`enabled-after`)뿐이다.

종단 알림은 위의 **자체 claim 쿼리**(별도 술어)를 쓰며, 실행과는 **별도 실행 컨텍스트**에서
돌린다(§격리) — **V1은 단일 스레드 loop를 택한다**. 종단 행에는 READY task도, 의미 있는
`cancel_requested`도, slot-gate도 없으므로 ADR-021의 RUNNING 전용 분기(cancel 체크→slot
gate→execute_step)를 타지 않는다.

**실패 경로(backoff + give-up).** 전달 실패는 같은 트랜잭션 안에서 기록한다(전달 예외를
잡아 실패 기록이 롤백되지 않게 한다). **post-increment 기준**으로 `nextAttempts =
notify_attempts + 1`을 저장하고, `nextAttempts >= maxAttempts`면 give-up, 아니면
`notify_next_at = :now + nextAttempts × retry_step`(**선형** 간격 — 이전 판의 지수+jitter는
SKIP LOCKED가 파드 몰림을 어차피 직렬화하고 이 규모·횟수(3회)에 과해서 제거). give-up은
**claim 술어가 `notify_attempts < maxAttempts`로 배제**하므로 재시도가 자동으로 멈춘다
(far-future sentinel 없음 — 이전 판의 보조 표시도 제거). give-up 시 **운영 알림으로 승격**
(§3, 사람이 개입). give-up 행은 여전히 `notified_at IS NULL`이므로 파생 쿼리상 “미알림”으로
남지만, 건전성 지표 “가장 오래된 미알림 행 age”는 give-up을 제외해 정의하고(age 지표 자체는
V1 미배선 — §4 재도입 후속) give-up 행은 **별도 카운트**(§4)로 감시한다. 별도 dead-letter
테이블은 두지 않는다(파생 술어 + `notify_attempts`로 충분). **재시도 재개**는 sink를 고친 뒤
운영자가 `notify_attempts`를 0으로 리셋하면 된다 — give-up은 영구 폐기가 아니라
“자동 재시도 중단 + 사람 개입 요청”이다.

**채널 gate — 미설정/비활성 sink는 전달 시도가 아니다.** 채널(Slack webhook)은 admin 관리
표면이 아니라 **환경변수로 주입되는 부팅 고정 설정**이다(2026-07-09 오너 결정 —
`pipeline.notify.slack-webhook-url`/`pipeline.notify.enabled`; `enabled=true`면 webhook과
`enabled-after`(§5)가 필수이고 누락 시 시작 실패 — fail-fast). gate는 **부팅 시 `enabled`
가드**다 — `enabled=false`면 notify loop 자체가 올라오지 않아 claim이 없고, `notify_attempts`를
올리지 않고 backoff/give-up도 타지 않는다. 종단 행은 `notified_at IS NULL`로 **보존**되고
(발화 유실 없음), `enabled=true`로 재기동하면 그대로 소급 발화한다(§4). “전달 실패”
(→attempts++)는 **활성 채널에 실제로 호출했으나 실패**한 경우로 한정한다는 성질도 동일하게
성립한다 — 비활성 기간엔 loop가 아예 돌지 않으므로 attempts가 소진될 수 없다. 비활성 기간의
backlog 감시는 §4의 backlog age 지표 재도입 후속에 속한다.
**enable/disable 반영은 재시작이다** — 설정이 부팅 고정이므로 이전 판의 “채널 설정을 매 loop
반복마다 새로 읽어 반영 ≤1 스캔” 의미론은 적용되지 않는다. 같은 이유로 이전 판의 race 규칙
(claim 직전 gate 확인, 비활성화 경계의 in-flight 1건 수용)도 env 방식에선 해당 없다 — 런타임에
gate가 뒤집히지 않으니 “비활성 기간에 시작된 claim”이 구조적으로 없다(재시작 종료 시점의
in-flight 1건은 커밋 전 잠금 해제로 흡수되는 일반 크래시 경로와 동일).

**실행 워커풀과 격리.** 느린/죽은 sink가 파이프라인 실행을 굶기지 않도록, 종단 알림은
파이프라인 실행과 **다른 실행 컨텍스트**에서 돌린다. **V1은 단일 스레드 notify loop**로
시작하고(격리 + backlog 직렬 드레인, §4), 처리량이 필요해지면 확장한다 — 단, 스레드를
늘리면 §2의 전제 (b)(파드당 잠금-내-호출 트랜잭션 1개)가 바뀌므로 two-tx 분리 재도입과
함께 검토한다. 종단 알림 클레임은 ADR-021의 `runningPipelineCap`/`slotCap`에 **계상하지
않는다**(그 캡은 `status='RUNNING'`만 센다).

**보장:**
- **at-least-once *시도* + give-up 전까지 무유실**(≠ 무조건 전달). 정확히는 **미전달 종단
  행을 durable하게 검출**하고 성공까지 재시도하되 `maxAttempts`에서 멈춘다 — 따라서 **실제
  전달은 sink 회복 + 성공 재시도(또는 운영자 reset — DB 수정 — 후 replay)에 조건부**다(give-up 후에도
  “조용한 유실”은 없다: §4의 give-up 경보가 사람을 부른다). `notified_at`은 **한 번만 찍히는
  상태 마커**(파이프라인당 종단 알림 *상태* 1개)이지만 **성공 전달 시도는 at-least-once**
  — 전달 성공 후 커밋 전 크래시, 또는 sink는 받았는데 응답이 타임아웃으로 끊긴 모호로
  **중복 전달이 가능**하다. 따라서 **소비자는 멱등해야
  한다**(`pipeline_id`로 dedupe — 파이프라인당 종단은 하나이므로 `pipeline_id`만으로 충분;
  불변식 4 참조). **단, V1 sink인 Slack 웹훅은 프로그램적 dedupe를 못 한다** — 그래서
  V1에서 “멱등”은 **중복 메시지를 그대로 노출하고 사람이 `pipeline_id`로 상관(correlate)**하는
  것을 뜻한다(중복 Slack 메시지 수용, 구현 문서). 기계적 dedupe가 필요한 **프로그램적
  다운스트림 소비자**가 붙을 때 그쪽이 `pipeline_id`로 dedupe한다 — 그 계약이 위 문장이다.
  순서: 파이프라인당 알림 상태가 1개라 파이프라인 내부 순서 문제는 없고, 같은 target의
  이전/이후 파이프라인 알림은 `pipeline_id` 키로 소비자가 구분한다.
- **동시 전달 없음.** 전송 중인 행은 잠겨 있어 다른 파드가 건너뛴다 — 같은 행을 두 파드가
  동시에 보내는 경로가 없고, stale 워커의 뒤늦은 기록이라는 상태도 없다(잠금을 쥔
  트랜잭션이 곧 기록 트랜잭션). 중복은 위 bullet의 두 경우(커밋 전 크래시·응답 타임아웃
  모호)로 좁혀진다.
- **`notified_at`의 의미(sink별)**: **V1 Slack에서는 “설정된 sink가 성공 HTTP 응답(2xx)을
  반환”**을 뜻한다 — Slack Incoming Webhook의 2xx는 *접수*이지 강한 durable-receipt 계약이
  아니다(그 이상은 Slack 책임). “durable ack”이 실제로 필요하면 그 계약을 제공하는 sink나
  durable bridge sink를 도입할 때 `notified_at` 의미를 그 수준으로 올린다. 어느 경우든
  “모든 다운스트림이 봤다”는 아니다(sink 내부 fan-out 신뢰성은 sink 책임).

**V1은 단일 논리 sink(Slack 웹훅 하나)**를 가정한다. 서로 독립적으로 재시도돼야 하는 다중
sink가 실제로 필요해지면 per-sink 전달 상태(또는 그때 비로소 작은 outbox)를 도입한다 —
지금은 만들지 않는다.

### 3. 운영 알림(worker-outage/queue-wait)은 이 메커니즘 밖 — 기존 metrics/alerting

`WORKER_OUTAGE`/`QUEUE_WAIT`는 상태 전이가 아니라 **지표 임계**에서 나오며(도메인 행이
없다), 원자성을 물릴 상태 전이도 없다. ADR-021이 이미 정의한 지표(lease-expired reclaim
count, due-pipeline lag)에 대한 **임계 알림으로, 조직이 이미 운영하는 metrics/alerting
스택**에서 처리한다. 이 ADR의 상태-파생 알림 경로에 억지로 태우지 않는다(그렇게 하면
도메인 행 없는 이벤트를 위해 범용 이벤트 저장소를 되살려야 한다). 알림 flapping 방지를
위한 dedupe 키/윈도우·open/resolve는 그 스택의 규약을 따른다.

### 4. 보장과 한계(수용)

- **exactly-once 없음.** at-least-once + 멱등 소비자로 충분하다(ADR-016 §5와 같은 이유).
  2PC/분산 트랜잭션은 도입하지 않는다.
- **소비자 계약**: (a) 멱등 dedupe 키(`pipeline_id`, 파이프라인당 종단 1개) 보관 —
  **최소 보관 기간 = max(파이프라인 행 보존기간, 최대 재시도 horizon + 수동 replay 창)**
  (여기서 “수동 replay 창”은 운영자가 정하는 값 — give-up 후 운영자가 재개할 수 있는 최대
  기간; 보통 파이프라인 행 보존기간에 묶는다).
  중복 전달은 워커 크래시(전달 성공~커밋 사이)·타임아웃 모호·수동 재시도 리셋에서 나므로
  그 창을 넘겨 보관해야 안전하다. (V1 Slack sink는 사람 상관이라 이 정식 계약은 **이후 도입될
  프로그램적 sink에 적용**된다.) **`pipeline_id` dedupe는 id가 환경 간 전역 유일하고 재사용
  되지 않을 때만 성립**한다 — 아카이브/복원·멀티환경에서 같은 Slack/웹훅을 공유하면 payload에
  `environment`/`tenant`를 넣어 dedupe 범위를 한정한다(V1 단일환경 가정).
  (b) payload에 `schema_version` 포함, (c) **PII 최소화(하드 계약)** — 이 시스템은 PII-인접
  인프라를 다루므로 payload는 **허용 필드만**: `pipeline_id`, `type`(INSTALL/DELETE/CUSTOM,
  미해석 열화 시 null 허용 — 구현 `NotifyPayload`와 정합), `terminal_status`, `target_ref`,
  실패 시 `failed_task`/`error_code`, `schema_version`.
  **`target_ref`는 대상의 opaque 참조여야 하며, raw hostname·account·DB명 등 민감 연결
  식별자는 payload에 직렬화하지 않는다(MUST NOT)** — “지양”이 아니라 금지다. **참조는
  canonical opaque 소스(전용 매핑 지점 `toTargetRef`, 구현 §4.2)에서 나와야 하며 “아무 target
  필드나 그대로”가 아니다** — 그 필드가 민감 값을 담게 되면 매핑에서 opaque 핸들로 치환한다.
  **payload allowlist에 `url`은 없다** — 사람이 봐야 할 실제 대상 상세는 알림 본문이 아니라
  `pipeline_id`로 **권한 있는 콘솔**에서 조회한다(민감 링크를 채널에 싣지 않도록 링크 필드
  자체를 payload에서 뺀다). 이 규칙은 구현 문서 `NotifyPayload`/Slack 템플릿에도
  동일하게 강제된다(그 외 민감 상세는 싣지 않는다). **특히 Slack 본문에 raw 예외 메시지/스택을
  넣지 않고 승인된 `error_code`로 매핑된 값만 노출**한다(예외 텍스트는 흔한 우발적 PII 유출 경로).
  **`failed_task`도 닫힌 recipe task 키/enum만 허용**한다 — provider/운영자 유래 raw task 명이
  아니라 정해진 식별자여야 하며, task 명이 닫혀 있지 않으면 `error_code`처럼 승인 코드로 매핑한다.
- **알림 지연.** *새 작업 검출* 지연은 스캔/idle 주기로 바운드되지만, **실제 전달 지연은
  거기에 call-timeout·backoff·backlog 드레인이 더해진다**(스캔 주기 하나로 단정하지 않는다).
  저지연 wake-up은 필요해지면 durable 파생 위에 힌트로 얹을 수 있으나(아래 대안) 지금은 불필요.
  (타깃 MySQL8은 Postgres `LISTEN/NOTIFY`가 없으므로, 필요 시 in-process 신호나 메시지 브로커를
  검토 — V1 범위 밖.)
- **수동 replay/reset은 Slack 중복을 만들 수 있다.** give-up 복구로 운영자가 재시도를 재개하면,
  Slack엔 dedupe가 없으므로 **이전에 이미 보인 알림과 중복 메시지**가 나갈 수 있다(사람이
  `pipeline_id`로 상관; 수용).
- **미설정/비활성 sink → 적체 후 소급 발화.** 채널이 없거나 비활성이면 종단 행은
  `notified_at IS NULL`로 **적체**되고(발화 자체가 유실되진 않음 — 파생 모델의 이점),
  채널을 (재)활성화하면 — env 방식에선 webhook과 함께 `enabled=true`로 재기동(§2) —
  backlog가 한꺼번에 발화한다. 짧은 sink 다운타임 뒤엔 “그동안 뭐가
  끝났나”를 받는 이점이지만, 오래 꺼져 있었으면 **알림 폭주**가 될 수 있다. 내부 도구
  규모에서 폭주는 수용 가능하다. **V1 notifier는 단일 스레드 loop라 backlog도 한 건씩 직렬
  전달**되므로(§2 격리) “한꺼번에 발화”가 순간 폭주가 아니라 스캔 주기에 맞춰 드레인된다 —
  이것이 V1의 burst 상한. **`notified_at`을 backfill해 옛 backlog를 “ack”로 덮지 않는다** —
  `notified_at`은 “해당 sink 계약상 성공 ack(V1 Slack은 2xx 접수)”만 뜻하므로(§2), 미전달 행에
  이를 찍으면 마커 의미가 “전달됨↔운영자가 버림”으로 오염돼 놓친 알림을 숨긴다. 운영자가 정말 옛 backlog를
  억제해야 하면 **별도 suppression 마커**(`notification_suppressed_at`/`_by`/`_reason`, 감사
  로그 필수)로 모델링하지 `notified_at`을 재활용하지 않는다(V1 범위 밖, 필요 시 도입).
- **알림 전용 지표(두 age를 구분한다 — V1은 정의만, 배선은 재도입 후속)**: 두 age의 종단
  시각 기준은 **`pipeline.last_activity_at`**(종단 후 다시 쓰이지 않아 = 종단 시각, ADR-021
  불변식)이다. 채널 활성/비활성으로 의미가 갈리므로 한 지표로 뭉치지 않는다. **두 age 쿼리는
  V1에 두지 않는다** — 소비 표면(actuator/관리 조회)이 없어 dead code가 되므로(2026-07-09
  오너 결정), actuator 등 소비 표면을 도입하는 후속에서 아래 정의대로 재도입한다.
  - `notify_delivery_pending_age` — **활성 채널일 때만** 평가하는 “전달 정체” age
    (미알림 + give-up 제외 + `notify_next_at <= now`). 이게 커지면 sink/전달이 막힌 것 →
    경보. **채널 비활성 동안은 평가/경보를 suppress**한다(정체가 아니라 gate 때문).
  - `terminal_notification_backlog_age` — 비활성 채널 backlog까지 포함한 총 미알림 age
    (채널이 얼마나 오래 꺼져 있었나를 본다).
  - **V1이 실제로 배선하는 것**: give-up 수(`countGivenUp`) — TerminalNotifier가 주기 폴링해
    0 초과면 ERROR 로그로 승격한다(아래 give-up 경보 bullet). notify 재시도/실패는 구조화
    전달 로그(§결과)로 남는다. ADR-021 워커 지표만으로는 알림 정체를 볼 수 없으므로 별도로
    둔다.
- **give-up 경보는 필수(MUST).** give-up 행은 자동 재시도가 멈추고 pending-age 지표에서도
  빠지므로, **감시하지 않으면 종단 알림이 조용히 영구 유실**된다 — 이 설계의 최고 위험
  경로다. 따라서 **`countGivenUp > 0`이면 반드시 담당자(파이프라인 운영자)에게 경보**한다:
  - **신호(정규 소스 = DB 파생)**: 경보의 **정규 소스는 durable DB 술어**
    `count(status IN 종단 AND notified_at IS NULL AND notify_attempts >= maxAttempts) > 0`
    (=`countGivenUp`)를 **주기 폴링**하는 것이다 — 로그는 유실·수집 누락·경보 배선 전 발생이
    가능하므로 정규 소스로 삼지 않는다. **V1 배선**: TerminalNotifier가 `countGivenUp`을 주기
    폴링해 0 초과면 **ERROR 로그로 승격**하고, 조직 metrics/alerting 스택(§3)이 같은 DB 술어를
    폴링해 page한다 — give-up 발생 시점의 개별 `ERROR` 로그는 **진단 보조**(2차)다. actuator
    도입 후에는 이 DB gauge(`notify.giveup.total`)에 임계 경보를 건다.
  - **심각도/라우팅**: 운영 알림 경로(§3)와 동일 스택, page 대상은 파이프라인 운영자.
  - **복구 절차**: sink를 고친 뒤 운영자가 해당 행의 `notify_attempts`를 0으로 리셋해
    재시도를 재개(§2).
  - **운영 수용 기준(배포 게이트)**: notifier를 프로덕션에서 켜기 전, 위 경보의 **쿼리/로그
    패턴·담당자·배포 점검이 실제로 존재**해야 한다 — give-up 경보 없이 켜면 조용한 유실
    위험을 그대로 지므로, 이 경보 배선은 “있으면 좋음”이 아니라 **가동 전제**다.

### 5. 롤아웃(최초 도입) — 레거시 종단 행 소급 발화 방지

상태-파생이므로 **`notified_at`을 라이브에 추가하는 순간 기존의 모든 종단 파이프라인이
“미알림”이 된다** — 그대로 켜면 notifier가 **레거시 종단 전부를 Slack에 소급 발화**(폭주)한다.
피처 도입 전 종단 행은 **알림 대상이 아니므로**, 도입은 **컷오프**로 이들을 범위에서 뺀다:

- **V1 채택(2026-07-09 오너 결정): 활성 컷오프 술어.** claim 술어에
  `AND last_activity_at >= :enabled_at`을 더해 backfill 없이 컷오프한다. `:enabled_at`은
  config `pipeline.notify.enabled-after`로 주입하며 **`enabled=true`면 필수**(누락 시 시작
  실패 — fail-fast; 빈 문자열·sentinel 기본값 금지 — 컷오프가 무력화되면 레거시 전부가
  발화한다). 종단 행은 terminalize 후 다시 쓰이지 않아 `last_activity_at`이 곧 종단
  시각이므로(ADR-021 불변식) 이 술어가 “도입 전에 종단된 행”을 정확히 배제한다. **이 config는
  상시 유지해야 한다** — 지우면 레거시 종단 전부가 다시 알림 대상이 된다(backfill
  마이그레이션이 없는 방식의 대가). DB 컬럼을 건드리지 않으므로 `notified_at`의 의미(“실제
  전달 ack”)는 오염 없이 유지된다.
- **대안(미채택): 도입 시점 컷오프 backfill.** 배포 시 1회
  `UPDATE pipeline SET notified_at = :enabled_at WHERE status IN 종단 AND notified_at IS NULL`로
  기존 종단 행을 “처리됨”으로 표시하는 방식. config 상시 유지가 필요 없다는 이점이 있으나
  미채택 — 이 repo는 수기 SQL 마이그레이션을 금지하고(AGENTS.md: 스키마는 JPA 애노테이션에서만
  생성, Flyway/raw SQL 금지), admin 채널 관리 표면이 제거되어(§2) backfill을 걸 배포 트리거
  지점도 없다. 채택했다면 backfill된 값은 “전달됨”이 아니라 **“알림 범위 밖(도입 전)”**을
  뜻하므로, §4가 금지한 런타임 suppression-backfill과 구별되는 문서화된 일회성 마이그레이션으로
  기록해야 했을 것이다.

어느 쪽이든 **“기존 종단 replay”는 기본이 아니다** — 정말 필요하면 명시적 opt-in으로 좁은
기간만 replay한다(활성 컷오프 방식에선 `enabled-after`를 과거로 옮기는 것이 곧 replay opt-in
이므로 그 변경을 배포 기록으로 남긴다).

## 고려한 대안

| 대안 | 판정 | 이유 |
|---|---|---|
| **A. 상태 파생 + `notified_at`(행 잠금 단일 트랜잭션 전달)** | **채택** | 이벤트가 이미 도메인 행에 있어 dual-write 없음; 행 잠금 하나가 점유·기록 유효성을 함께 보장(§2); **알림 전달 상태 테이블·relay·pruner 0, 새 테이블 0**(채널 설정은 env — §2, 스키마 절). |
| A′. 상태 파생 + claim/lease/fencing/two-tx 재사용(2026-07-09 판 채택안) | 대체됨(2026-07-10) | 정확성은 동등하나, lease 컬럼쌍·fencing 토큰·write-back 분리·그 가드 테스트가 전부 “호출 시간이 잠금을 쥘 수 없을 만큼 길다”는 전제를 위한 것인데 알림(상한 10초 호출)에는 그 전제가 없다(§2). 전제가 생기면(다중 sink·긴 타임아웃·종단 행 갱신 배치) 이 판으로 되돌린다. |
| B. 트랜잭션 아웃박스(별도 `event_outbox` + relay) | 기각 | 이벤트가 이미 `pipeline.status`에 있어 별도 저장소가 불필요; relay는 외부 전달에 lease가 필요한데 "SKIP LOCKED만"으로는 락을 외부 호출에 물리거나(ADR-021 §3 위반) 이중 전달이 남음; 다중 sink·poison·pruner를 새로 떠안음; ADR-016이 이미 잘라낸 메커니즘. |
| C. 전이 트랜잭션 내 동기 알림 호출 | 기각 | dual-write; 느린/실패 알림이 상태 전이를 롤백·차단; 상태 정합성이 알림 서버에 종속. |
| D. 커밋 후 best-effort 알림 | 기각 | 커밋~알림 사이 크래시로 조용히 유실; 재시도·복구 없음. |
| E. CDC/브로커(Debezium/Kafka) | 기각 | 규모 대비 운영 비용 과다. 이미 DB를 소유하므로 “상태 스캔 파생”이 같은 아이디어의 경량판이고 그것으로 충분. |
| F. 저지연 wake-up 힌트(브로커/in-process 신호) | 유보(선택) | 상태-파생을 대체하진 못하나, 스캔 폴링 대신 저지연 wake-up 힌트로 얹을 수 있다. 타깃 MySQL8엔 Postgres `LISTEN/NOTIFY`가 없어 in-process 신호나 브로커가 후보. 지연이 문제될 때 도입, V1 불필요. |
| G. 알림 상태를 `pipeline` 컬럼이 아닌 1:1 사이드카 테이블(`pipeline_notification`)로 분리 | 기각 | “핵심 aggregate 오염 회피”가 동기지만, 파이프라인당 정확히 1행이라 실질은 컬럼과 동형이고 claim마다 join·수명주기(고아 행 정리) 부담만 는다. 알림 메타데이터는 ADR-021이 실행 메타데이터(`claimed_by` 등)를 `pipeline`에 둔 것과 **같은 범주**이며(도메인 상태 컬럼 아님), 종단 파생 claim이 같은 행을 이미 잠그므로 별 테이블의 이득이 없다. 다중 sink가 실제로 필요해지면 그때 per-sink 상태 테이블로 분리(§2 말미)—그 전엔 불필요. |

## 결과

### 좋은 점

- **신뢰성 있는 종단 알림**을 dual-write·별도 저장소 없이 얻는다 — 이벤트가 이미 도메인
  행에 있고, 전달은 행 잠금을 쥔 단일 트랜잭션이 점유와 기록 유효성을 함께 보장한다(§2).
- **relay-lease 딜레마가 원천 소멸.** 알림이 `pipeline` 행 작업이라 행 잠금이 곧 점유다 —
  별도 relay가 lease를 재발명할 필요도, notify 전용 lease 컬럼도 없다.
- **움직이는 부품 최소.** 알림 전달용 새 테이블·relay·pruner·이벤트 taxonomy 없음
  (`pipeline`에 알림 메타데이터 컬럼 + 스캔 술어/분기 하나, 새 테이블 없음). **V1 sink는
  Slack** 하나이며, 채널 설정은 환경변수로 주입한다(§2 — admin 관리 표면 없음).
- **ADR-016 취지와 일치.** 잘라냈던 outbox를 되살리지 않고, “도메인 행 + logs/metrics”
  원칙을 지킨다. 도메인 상태(`status`·enum)는 불변.

### 수용하는 비용

- **`pipeline`에 알림 메타데이터 컬럼 3개 추가**(`notified_at`/`notify_next_at`/
  `notify_attempts`, ADR-021 실행 컬럼과 동일 범주). 종단-미알림 파이프라인을 집는 claim
  술어·인덱스·notify loop가 하나씩 늘어난다.
- **Slack 호출 동안(상한 10초) DB 커넥션 1개와 행 잠금 1개를 점유**한다(파드당 최대 1건).
  §2의 세 전제가 지켜지는 동안만 수용 가능한 비용이며, 전제가 깨지면 two-tx 분리로 되돌린다.
- **at-least-once → 멱등 소비자 필수**(`pipeline_id`만으로 dedupe — 파이프라인당 종단 1개;
  `terminal_status`는 payload 정보이지 dedupe 키가 아니다).
- **알림 지연.** 새 작업 *검출* 지연 = 스캔/idle 주기(실제 전달 지연엔 call-timeout·backoff·
  backlog 드레인이 더해짐, §4). 저지연이 필요하면 §4의 wake-up 힌트를 나중에 도입.
- **다중 독립 sink는 V1 범위 밖** — 필요해질 때 per-sink 상태를 도입.
- **알림 전달을 실행 워커풀에서 격리**해야 한다(전용 풀/loop 또는 notify 클레임 상한) —
  느린 sink가 파이프라인 실행을 굶기지 않도록. relay를 없앤 대가로 이 격리를 명시적으로
  져야 한다(§2).
- **회귀(수용): per-event 감사 추적 상실.** outbox는 이벤트당 durable 행을 남겼지만 이제
  파이프라인당 `notified_at` 1개뿐 — “무엇을 언제 몇 번 보냈나”는 로그/지표로만 재구성.
  이 감사-손실을 운영적으로 견디려면 **구조화 전달 로그가 최소 `pipeline_id`·`terminal_status`·
  `attempt`·sink 응답 분류(2xx/4xx/5xx/timeout)를 반드시 포함**하고, **해당 로그가 조회 가능한
  보존기간 동안 검색 가능**해야 한다(로그가 짧게 순환되거나 검색 불가면 “사후 재구성”은 실제로
  보장되지 않는다). 내부 도구 수준에서 수용하며, 규제/감사 요건이 생기면 재검토.
- **회귀(수용): 종단만·1회성.** 상태 파생은 durable 종단에서만 발화하므로 중간 이벤트
  (“시작됨”·“step N 완료”·“task 재시도”)나 성공한 파이프라인 내 **transient task 실패**는
  이 경로로 나가지 않는다 — 그런 신호는 metrics/logs 소관. 진행 알림이 제품 요구가 되면
  이 모델로는 부족하다(그때 per-event 마커=outbox 재도입 필요).

## 스키마

**종단 알림** — 모두 ADR-022 소유 알림 메타데이터 컬럼으로, ADR-021의 실행
컬럼(`next_due_at`/`claimed_by`/`claimed_until`/`cancel_requested`)과 같은 범주다.
도메인 상태 컬럼이 아니다. 점유(lease) 컬럼은 없다 — 전송 중임은 행 잠금이 나타낸다(§2).

- `pipeline.notified_at`(nullable) — 종단 알림 전달 완료(sink ack) 마커.
- `pipeline.notify_next_at`(nullable) — 실패 backoff 게이트(다음 재시도 시각).
- `pipeline.notify_attempts`(int, default 0) — 재시도 간격 계산·give-up 임계 판정용.
- 새 테이블 없음(알림 전달용). claim 술어는 §2(종단 + `notified_at IS NULL` + 컷오프 +
  backoff 게이트 + give-up 배제).
- **인덱스**: MySQL8은 부분(filtered) 인덱스가 없으므로 복합 인덱스
  `(notified_at, notify_next_at)`로 미알림 종단 행 스캔/정렬을 덮는다(status·`notify_attempts`
  필터와 `id` tie-break는 인덱스로 완전히 커버되지 않고 옵티마이저/힙에
  맡긴다 — `id` 정렬은 논리적 결정성이지 인덱스-커버가 아니다; `active_target` 유일 제약이 부분
  인덱스를 컬럼으로 대체하는 것과 같은 제약). ~2,000행 규모에 충분하며, 대규모로 커지면 재검토.
- **채널 설정 테이블 없음** — Slack 채널 설정은 테이블이 아니라 환경변수다(2026-07-09 오너
  결정): `pipeline.notify.slack-webhook-url`(env `PIPELINE_NOTIFY_SLACK_WEBHOOK_URL`, secret —
  로그·응답에 원문을 찍지 않는다)·`pipeline.notify.enabled`·`pipeline.notify.enabled-after`(§5).
  초기 구현 명세가 두었던 `notification_channel` 단일 행 테이블은 채택하지 않는다(§2 채널 gate).

**불변식**

1. `notified_at`(및 `notify_next_at`/`notify_attempts`)은 알림/진단 메타데이터 — reconciler·
   claim·스케줄링·전이의 **의미**에 관여하지 않는다(claim 술어가 이 컬럼을 읽는 것은
   “알림 대상 선별”이지 도메인 전이가 아니다).
2. 종단 알림 대상은 **커밋된 종단 상태에서 파생**된다 — 전이가 durable해야만 알림 대상이
   되고, durable하면 반드시 대상이 된다(dual-write 없음, 유실 없음).
3. 알림 메타데이터 손상/롤백은 pipeline/task **도메인 상태**를 오염시키지 않는다.
   최악의 경우는 재전달(멱등 소비자가 흡수) 또는 재클레임(delay)일 뿐 부정확이 아니다.
4. **파이프라인당 종단 알림은 정확히 1회**라는 성질은 ADR-016의 **종단 상태 불변성**
   (종단 도달 후 부활 금지)에 의존한다 — 종단 행이 다시 `RUNNING`으로 되돌아가 재종단할 수
   없으므로, 한 번 찍힌 `notified_at`이 “두 번째 종단”을 잘못 억제할 여지가 없다. (파생 읽기
   모델인 ADR-023 ProcessStatus의 회귀는 `pipeline.status` 도메인 상태와 무관하다.)
5. **producer-side 보존 제약(중요).** 알림은 독립 이벤트 행 없이 `pipeline` 행에서 파생되므로,
   **retention/archive/pruner는 `notified_at IS NULL`인 종단 행(give-up 포함)을 삭제·아카이브
   하면 안 된다(MUST NOT)** — 그러면 알림이 조용히 소멸한다. 알림이 나가지 않은 행을 정리해야
   하면 먼저 **명시적·감사되는 suppression 마커**(§4)를 쓴 뒤에만 정리한다. 종단 행 보존을
   요구한다면 **producer 최소 보존창 ≥ (알림 재시도 horizon + 수동 replay 창)**을 지켜야 한다.

## 알림/신호 분류

**현재 범위** — 종단 상태에서 파생, 행 잠금을 쥔 단일 트랜잭션(§2)으로 전달:

| 신호 | 파생 조건 | payload 요지 |
|---|---|---|
| 파이프라인 완료 | `status = DONE` & `notified_at IS NULL` | pipeline id, type, `target_ref`(opaque) |
| 파이프라인 실패 | `status = FAILED` & 〃 | 위 + 실패 task와 `error_code` |
| 파이프라인 취소 | `status = CANCELLED` & 〃 | pipeline id, type, `target_ref`(opaque) |

- **운영 알림**(worker-outage/queue-wait) — 이 경로가 아니라 기존 metrics/alerting(§3).

## 링크

- [ADR-016](016-install-delete-pipeline-domain-model.md) — 이 ADR이 얹는 도메인 모델
  (event outbox를 “Costs we accept”로 잘라낸 원출처 — 이 ADR은 그 취지를 이어 상태 파생을
  택한다). `notified_at` 등 알림 메타데이터 컬럼은 ADR-022 소유.
- [ADR-021](021-pipeline-execution-model.md) — 실행 모델. 알림이 재사용하는 것은 `SKIP
  LOCKED` 잠금 관용구뿐이고, lease/fencing/two-tx는 알림에 쓰지 않는다(§2 — 그 장치가 필요한
  이유였던 “긴 호출” 전제가 알림엔 없음). postCheck로 확정된 `CONDITION_CHECK`의 실행
  의미(retry-count 바운드 poll)도 여기 있다.
- [022-notifier-implementation.md](../../design/pipeline/022-notifier-implementation.md) —
  이 결정의 **구현 세부 명세**(Slack sink, MySQL8/Spring, 엔티티·설정·claim/전달/write-back).
  그 문서만 보고 구현 가능하도록 작성 — 단, 그 문서의 admin 채널 관리(§2.2/§6)와 두 age
  쿼리(§7)는 미채택이다(2026-07-09 오너 결정; 그 문서 §0 구현 노트·이 ADR 개정 이력 참조).
- [adr-016-history.md](../../design/pipeline/adr-016-history.md) — event outbox 등 최대
  모델 요소가 재범위 축소로 정리된 경위.

## 용어

- **derive-from-state(상태 파생)** — 별도 이벤트 저장소 없이, 도메인 행의 상태
  (`status` 종단 + `notified_at IS NULL`)에서 알림 대상을 쿼리로 파생하는 방식. 이벤트가
  이미 상태에 있으므로 dual-write가 없다.
- **notified_at** — 파이프라인당 종단 알림 전달 완료 마커(알림 메타데이터, 도메인 상태
  아님). 한 번 찍히면 그 파이프라인은 알림 대상에서 빠진다.
- **dual-write** — DB write와 외부 부작용(알림 호출)을 한 트랜잭션 경계 안에서 함께
  시도해 부분 실패 시 갈라지는 안티패턴. 상태 파생이 이를 제거한다.

## 개정 이력

- 2026-07-01: 생성. ADR-016(Costs we accept)이 유보한 종단 알림과, 당시 함께 다루던
  postCheck 간극을 얹는 후속 결정으로 작성.
- 2026-07-01: 문서 리뷰 반영(codex/sonnet) — 인용 정확도, 범위 스코핑, 이벤트 집합 분리,
  용어 정리.
- 2026-07-01: **설계 리뷰 반영(codex xhigh 77 / opus 72 / 복잡성 over-engineered)**.
  트랜잭션 아웃박스(별도 `event_outbox` + relay + pruner)를 **상태 파생 + `notified_at`
  으로 대체** — 세 리뷰가 독립적으로 같은 대안(상태에서 파생)에 수렴했고, relay가 외부
  전달에 lease가 필요한 모순·다중 sink·poison·pruner를 한 번에 제거하며 ADR-016이 outbox를
  잘라낸 취지와 일치. 운영 알림은 기존 metrics/alerting으로 분리. `TASK_FAILED` 별도
  이벤트 제거(실패 종단 알림에 포함). 소비자 계약(schema_version·PII 최소화) 명시.
- 2026-07-01: **재설계 재리뷰 반영(codex xhigh 86 / opus 85)**. 알림 경로를 ADR-021 수준
  으로 완전 명세: 전체 claim SQL(종단 + `notified_at IS NULL` + backoff/lease 게이트),
  실패 backoff+give-up 경로(`notify_next_at`/`notify_attempts`), 실행 워커풀과의 격리,
  종단 알림을 별도 work-kind로(RUNNING 분기 앞에서 분기), “verbatim 재사용”→“메커니즘 재사용”
  으로 정정, at-least-once(중복 가능) vs `notified_at` 1회 마커 구분. 회귀를 명시적 비용
  으로 기록(per-event 감사 상실·종단만·transient task 실패 신호 상실). payload 허용 필드·
  알림 전용 지표 추가.
- 2026-07-07: **postCheck 분리 — ADR 범위를 종단 알림으로 축소.** postCheck는 ADR-016/021의
  `CONDITION_CHECK`(retry-count 바운드 probe)로 확정·구현되어 별도 결정이 불필요해졌고,
  recipe 마지막 task 배치는 ADR-016 recipe 규약에 속한다. 이에 postCheck 결정·대안·스키마·
  용어 항목을 이 ADR에서 제거하고(도메인 상태 부활 금지 등 관련 불변식은 ADR-016 §7이 계속
  보유) 파일명을 `022-terminal-state-notification.md`로 변경. origin/main(#532 PENDING 포함)
  위로 rebase.
- 2026-07-07: **구현 명세 추가 + notify lease 전용 쌍으로 정정.** buildable 구현 문서
  ([022-notifier-implementation.md](../../design/pipeline/022-notifier-implementation.md))
  작성(Slack sink, 인터페이스 없이, admin 채널 관리, MySQL8/Spring 실코드 정합). 실코드 확인
  중 발견: 실행 admission soft-cap(`countByClaimedUntilAfter`)이 상태 무관하게 활성 lease를
  세므로 ADR-021 `claimed_by`/`claimed_until` 재사용은 종단 행 notify lease로 실행 캡을 오염시킨다
  → notify 전용 lease 쌍(`notify_claimed_by`/`notify_claimed_until`)으로 분리(컬럼 3→5).
  MySQL8 부분 인덱스 부재 반영(복합 인덱스), `LISTEN/NOTIFY`는 Postgres 전용이라 저지연 옵션은
  구현 문서에서 제외.
- 2026-07-08: **리뷰 수렴 하드닝(codex medium×9 + high×1, opus 최종 98/merge-ready).** 코어
  설계는 유지한 채 계약 정확도·ADR↔구현 정합·운영성만 다졌다. give-up 경보 정규 소스는 DB
  파생 `countGivenUp` 폴링으로 ADR·구현 §7 일치(로그는 진단 보조). 주요 확정 사항:
  - **fencing 대칭·give-up 배제**: 성공/실패 write-back 모두 `id + notify_claimed_by(token)
    + notified_at IS NULL` 가드; claim 술어에 `notify_attempts < maxAttempts`(give-up 배제를
    sentinel이 아니라 술어로), backoff는 post-increment.
  - **채널 gate**: 미설정/비활성 sink는 claim 안 함(idle) → attempts/give-up 미소진, backlog
    보존; gate는 claim 직전 확인(in-flight는 정상 마무리), 채널 설정 무캐시(반영 ≤1 스캔).
  - **PII 하드 계약**: `target_ref`(opaque, canonical 소스 = 전용 `toTargetRef` 매핑 +
    `NotifyPayloadPiiTest` 강제), raw host/account/DB명·예외 텍스트·**raw `failed_task` 명**
    직렬화 금지(MUST NOT; `failed_task`는 닫힌 recipe 키/승인 코드만), 사람용 상세는 권한 화면.
  - **give-up 무유실**: give-up은 조용한 유실 위험 → `countGivenUp > 0` 경보 필수(MUST, 배포
    게이트), 복구=admin reset. 지표는 pending age(활성 채널 한정, **due-only 쿼리**
    `oldestDeliveryPending`)와 backlog age(`oldestUnnotifiedAt`)를 **별도 쿼리로 V1부터** 분리.
  - **계약 정직화**: “at-least-once *시도* + give-up 전까지 무유실(무조건 전달 아님)”,
    `notified_at`(V1)=“sink 2xx 접수”, dedup 보관 = `max(행 보존, 재시도 horizon + replay)`
    (V1 Slack은 사람 상관, 정식 계약은 프로그램적 sink), Slack 중복 수용.
  - **producer-side 보존**: 알림은 독립 이벤트 행이 없으므로 **retention/pruner는 `notified_at
    IS NULL` 종단 행(give-up 포함)을 삭제 금지**(불변식 5) — 안 그러면 알림이 조용히 소멸;
    정리하려면 감사 suppression 마커 선행. 구조화 전달 로그는 `pipeline_id`·`terminal_status`·
    `attempt`·`sink`·응답 분류 포함(ADR↔구현 §7 정합).
  - **롤아웃(§5)**: `notified_at` 도입 시 레거시 종단 전부가 “미알림”이 되어 소급 폭주 →
    도입 시점 컷오프 backfill(1회 마이그레이션, 런타임 suppression과 구별)로 도입 이후 종단만
    발화. give-up 경보 **정규 소스 = DB 파생 `countGivenUp` 폴링**(로그는 2차 진단).
  - **소소한 정정 2**: 성공 write-back이 `notify_next_at`도 clear(stale backoff 제거),
    `pipeline_id` dedupe는 환경 전역유일 전제(멀티환경 시 `environment` 포함), payload에 `url`
    없음(콘솔에서 `pipeline_id`로 조회).
  - **소소한 정정**: dedup 키 `pipeline_id`만, claim `WHERE id`·`ORDER BY … id` tie-break,
    단일 앱 `Clock` 일관(+멀티파드 skew 가정), 대안 G(사이드카 테이블) 기각, “verbatim”→
    “메커니즘” 재사용, `type` CUSTOM/null 정합, §1 “실행 claim” 한정 문구, 구조화 로그.
- 2026-07-09: **오너 결정 — 채널 관리를 admin 표면(`notification_channel` + REST) 대신
  환경변수로.** 채널(Slack webhook)은 env var(`PIPELINE_NOTIFY_SLACK_WEBHOOK_URL` 등
  `pipeline.notify.*`)로 주입하고 새 테이블을 두지 않는다(스키마 절 개정). §5 채택안을
  **활성 컷오프 술어**(`pipeline.notify.enabled-after`, claim에 `last_activity_at >= :enabled_at`,
  `enabled=true` 시 필수·fail-fast)로 교체하고 도입 시점 컷오프 backfill은 대안으로
  강등(AGENTS.md 수기 SQL 금지 + admin 표면 제거로 backfill 트리거 지점 부재). §2 채널 gate를
  **부팅 enabled 가드/재시작 반영** 의미론으로 개정(매 loop 재읽기·반영 ≤1 스캔·claim 직전
  race 규칙은 env 방식에서 해당 없음 — 비활성 기간 backlog 보존·attempts 미소진 성질은 동일
  성립). §4 두 age 지표(`notify_delivery_pending_age`/`terminal_notification_backlog_age`)는
  소비 표면(actuator/관리 조회) 도입 시 재도입으로 조정하고, give-up 경보 V1 배선은
  NotifyScheduler의 `countGivenUp` 주기 폴링 ERROR 승격으로 명시(경보 MUST·배포 게이트는
  유지). pipeline-orchestrator 구현과 정합. (원본 repo `pii-agent-demo` 사본에는 추후 동일
  개정 필요.)
- 2026-07-10: **오너 결정 — 전달 동시성을 단일 트랜잭션(잠금-내-전송)으로 단순화.**
  “단순 알림치고 코드가 너무 많다”는 오너 피드백으로 재검토한 결과, lease 컬럼쌍·fencing
  토큰·two-tx guarded write-back은 “호출이 길어 잠금을 쥘 수 없다”는 ADR-021의 전제를 위한
  장치인데 알림(상한 10초 호출)에는 그 전제가 없음을 확인. §2를 **행 잠금을 쥔 단일
  트랜잭션**(잠금 → payload 조립 → Slack 호출 → 결과 기록 → 커밋)으로 교체 — 잠금이 lease와
  fencing을 대체하고, stale 워커 상태가 구조적으로 소멸하며, 실행 admission 카운트 오염
  문제도 원천 소멸(스키마 컬럼 5→3: `notify_claimed_by`/`notify_claimed_until` 제거).
  이전 채택안은 대안 A′로 강등하고 되돌릴 조건(다중 sink·긴 타임아웃·종단 행 갱신 배치)을
  §2에 명시. backoff는 지수+jitter → 선형(재시도 간격 = 시도 횟수 × 1분), give-up far-future
  sentinel 제거(claim 술어의 attempts 비교가 유일 근거), `maxAttempts`는 8 → **3**(오너 지정).
  동작 값(재시도·주기·타임아웃)은 설정에서 코드 상수로 이동 — 운영 설정은 env 3키만. give-up
  백로그 재경보(5분 폴링 ERROR 승격)는 유지(오너 지정). 구현: NotifyScheduler/NotifyClaimer/
  NotifyWriteBack/NotifyClaim/NotifyRepository → `TerminalNotifier` 단일 클래스 + 기존
  `PipelineRepository` 질의 2개로 통합. 운영 노트: 이전 판 코드를 이미 띄웠던 환경에는
  `notify_claimed_by`/`notify_claimed_until` 컬럼이 잔존한다(`ddl-auto: update`는 컬럼을 drop하지
  않음) — 새 코드가 읽지 않으므로 무해하고, 정리는 선택이다. (원본 repo `pii-agent-demo`
  사본에는 추후 동일 개정 필요.)
