# LIN-30 [A6] 시작-지연 대기 상태 `PipelineStatus.PENDING` — 요구사항 및 구현 범위

> 구현 착수 전 합의용 문서. 리뷰어(codex 포함)가 **무엇을·왜·어디까지** 바꾸는지 코드보다 먼저 파악하기 위한 것.
> 코드 착수 전 이 문서에 대한 codex 리뷰를 받고, 지적이 없을 때 구현으로 넘어간다.

## 0. 메타

| 항목 | 값 |
|---|---|
| 이슈 | LIN-30 — 시작 지연 대기 상태를 별도로 표현(`PipelineStatus.PENDING`), RUNNING과 구분 |
| 작업 브랜치 | `feat/lin-30-pending-status` (base = `origin/main` `e3d6664`) |
| 베이스 브랜치 | **`origin/main`** — start-delay(LIN-17)·enum converter(LIN-28) 모두 머지돼 있음 (0.1 정정) |
| 선행 이슈 | LIN-17 start-delay(PR #23 `df35129`), LIN-28 persisted-enum converter(PR #24 `e3d6664`) — 둘 다 main에 있음 |
| 설계 출처 | Linear LIN-30 코멘트(ADR-016/021 확정 규칙) — 단, ADR 원문·PR #532는 `pii-agent-demo` 저장소. 4장에서 이 저장소 기준으로 재정합 |

### 0.1 베이스 브랜치 정정 (초판 오류)

**초판은 베이스를 `lin-17-18-27`(로컬, `75a26f1`)로 잡았다 — 잘못이었다.** `origin/main`을 확인하지 않고 로컬 detached HEAD(`760afb7`)로 main 상태를 추정한 실수다. 실제 `origin/main`(`e3d6664`)에는:
- **PR #23 `df35129`** — start-delay + custom recipe (LIN-17/18/27)이 이미 머지돼 있다.
- **PR #24 `e3d6664`** — persisted enum 컬럼에 converter 패턴 적용 (LIN-28). `Pipeline.status`가 `@Convert(PipelineStatusConverter)`로 varchar 저장된다.

→ LIN-30은 **`origin/main` 위에 직접** 분기한다. 스택도, 나중 리베이스도 불필요. 내가 touch한 파일 중 `origin/main`과 `75a26f1`이 다른 건 `Pipeline.java`(LIN-28) 하나뿐이라, 리베이스는 그 파일만 origin/main 것으로 취하면 끝났다. **enum varchar 저장은 LIN-28이 이미 해결**했으므로 LIN-30은 엔티티/스키마를 건드리지 않는다(4.1 참고).

---

## 1. 배경

LIN-17이 시작 지연을 도입했다. 지연은 sleep이 아니라 **스케줄링**으로 구현된다 — 생성 시 `nextDueAt = now + startDelay`(미래)로 시딩하면, claim 술어 `next_due_at <= now`에 걸리지 않아 첫 Task가 dispatch되지 않는다(`PipelineInserter.java:61`).

문제는 **상태 표현**이다. 이 대기 창 동안 `PipelineStatus`에 별도의 "대기" 값이 없어 상태가 `RUNNING`으로 보인다(`PipelineInserter.java:57`이 무조건 `RUNNING` 시딩). 운영자 화면에서 "지연 대기 중"과 "실제 실행 중"을 구분할 수 없다.

기능은 정상이다(대기 중 취소는 unclaimed → Case A로 즉시 CANCELLED, dispatch 0회). 이 이슈는 **상태 표현만** 다룬다.

## 2. 목표

- 대기 창을 `RUNNING`과 구분되는 별도 상태 `PipelineStatus.PENDING`으로 표현한다.
- 지연 경과 후 **첫 claim 시점에** `PENDING → RUNNING`으로 전이한다.
- 상태 추가로 인해 깨질 수 있는 모든 status 하드코딩(claim / cancel / 통계 / terminalize 판정)을 함께 손본다.
- 회귀 없음: `mvn test` PASS, one-active-per-target 불변식 유지.

## 3. 확정 설계 규칙 (변경 불가 — ADR-016/021 코멘트)

**규칙 A — 전이 지점 = claim 트랜잭션(tx1), dispatch가 아니다.**
`PENDING → RUNNING`은 lease를 찍는 **바로 그 트랜잭션**에서 `status = RUNNING`을 함께 쓴다. "dispatch 시점 전이"가 아니라 "claim 시점 전이"여야 하는 이유:
- claim holder가 **유일한 status writer**라는 불변식이 유지된다.
- "live claim인데 아직 PENDING"인 창(취소 유실 위험)이 생기지 않는다.

이 저장소에서 tx1은 `PipelineClaimer.claimOneDue()`(`PipelineClaimer.java:38-51`, `@Transactional`)이다. 별도 UPDATE 쿼리가 아니라, `lockClaimableDuePipelines`가 `FOR UPDATE`로 잠근 관리 엔티티에 `setClaimedBy/setClaimedUntil`을 dirty-write → tx1 커밋 시 한 UPDATE로 flush하는 구조다. 여기에 `setStatus(RUNNING)` 한 줄을 더하면 status·lease가 **원자적으로 같은 UPDATE**에 실린다. (규칙 A 충족)

**규칙 B — 전이 시 `next_due_at`을 건드리지 않는다.**
지연은 생성 시 seed(`now + startDelay`)로 강제한다. claim UPDATE는 lease·status만 쓰고, 다음 `next_due_at` advance는 tx2(`StepReporter.releaseClaim`)에서 스텝 결과 기준으로만 한다. (`now()` 리셋은 Case B 취소 경로지 전이 경로가 아니다.)

## 4. 이 저장소 기준 재정합 — 리뷰어 필독 (이슈 ADR 코멘트와의 차이)

이슈 코멘트의 설계는 `pii-agent-demo`(PR #532) ADR 기준이다. 이 저장소는 몇 가지를 **다른 방식으로** 이미 강제하고 있어, 코멘트 체크리스트 중 일부는 **N/A**다. 정확히 짚는다:

### 4.1 인덱스·제약·enum 저장 — 변경 0건 (전부 이미 해결됨)
코멘트는 "partial index 2곳"을 요구하지만, **이 저장소엔 Flyway/Liquibase/`.sql`이 하나도 없다.** 스키마는 JPA 생성(`application.yml`: 운영 `ddl-auto: update`, 테스트 `create-drop`).
- claim 인덱스는 **partial이 아니라 일반 복합** `@Index(columnList="status, next_due_at")`. status가 선두 컬럼이라 `IN (RUNNING,PENDING)` 술어를 그대로 탄다. 변경 불필요.
- one-active-per-target도 `status='RUNNING'` partial이 아니라 **`active_target` 컬럼의 일반 UNIQUE**. 비종단이면 `active_target=target`, 종단이면 `NULL`. 변경 불필요.
- **enum varchar 저장 — LIN-28(PR #24)이 이미 해결.** `origin/main`의 `Pipeline.status`는 `@Convert(PipelineStatusConverter)`로 **varchar(16)** 저장이다(native MySQL enum 아님). converter의 read는 엄격한 `valueOf`(status는 null 열화 금지 — `TaskOperation`의 `find→null`과 다름). 새 값 `PENDING`은 `status.name()`/`valueOf`로 자동 처리되어 **엔티티·스키마 변경이 전혀 필요 없다.**

> 초판은 base를 stale(`75a26f1`)하게 잡아 이 컬럼을 native enum으로 오판하고 `@JdbcTypeCode(VARCHAR)`를 추가했다(codex R1 P0). `origin/main`엔 LIN-28 converter가 있어 불필요 — 리베이스 시 제거했다. 0.1 참고.

**검증:** 생성 DDL이 `status varchar(16) not null`, `mvn test` 168 PASS로 확인.

→ **엔티티/인덱스/제약/스키마 변경 0건.** LIN-30은 순수 동작 변경이다.

### 4.2 one-active-per-target 불변식 — **자동 유지**
불변식은 status가 아니라 `active_target` UNIQUE가 짊어진다. `PipelineInserter`가 생성 시 `.activeTarget(target)`을 **무조건** 채우므로(`PipelineInserter.java:58`), PENDING 행도 슬롯을 점유한다. 같은 target 중복 create → `409 ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`가 status와 **무관하게** 성립한다(`PipelineCreator.java:156-168`이 제약 위반을 409로 번역). isTerminal만 올바르면 종단화 시 `active_target`이 정상 해제된다.

### 4.3 admin status 필터 — **자동 충족**
`GET /api/v1/pipelines`의 `@RequestParam PipelineStatus status`(`PipelineController.java:58`)는 enum 바인딩이라 `?status=PENDING`이 상수 추가만으로 동작한다. `search` JPQL도 `(:status is null or p.status = :status)`(`PipelineRepository.java:87-98`)로 파라미터화돼 있어 코드 변경 없음. **컨트롤러 변경 0건.**

### 4.4 `requestCancel`(Case B) — **`status='RUNNING'` 유지**
PENDING 파이프라인은 claim된 적이 없다(claimedBy null). 그래서 취소 시 `cancelIfIdle`(Case A) 가드 `(claimed_by IS NULL OR expired)`를 통과해 **항상 Case A로 즉시 CANCELLED**된다. Case B(`requestCancel`)에 도달하는 건 이미 claim되어 `RUNNING`으로 전이된 뒤뿐이다. 따라서 `requestCancel`의 `status='RUNNING'` 가드는 **그대로 둔다**(PENDING 추가 불필요). — 코멘트와 동일 결론.

### 4.5 `StepReporter`의 RUNNING 가드 2곳 — **변경하지 않는다**
`promoteBlockedSuccessor`(`StepReporter.java:135`)와 `releaseClaim`(`:148`)의 `== RUNNING` 가드는 write-back(tx2) 안에서만 실행된다. write-back은 claim된 파이프라인에만 도달하고, claim은 이미 `PENDING → RUNNING` 전이를 마친 뒤다. 즉 이 지점에서 status ∈ {RUNNING, DONE, FAILED, CANCELLED}이고 **PENDING은 절대 나타나지 않는다.** `== RUNNING`은 여기서 "아직 종단 아님"을 정확히 뜻하므로 유지한다. (RUNNING 하드코딩이지만 의도적으로 남기는 항목 — 6장에 명시.)

### 4.6 로컬 ADR 문서 — 아직 PENDING 없음
이 저장소의 `docs/adr/016-*.md`·`021-*.md`는 "비종단 == RUNNING 하나"를 전제로 서술돼 있다. PENDING 결정은 `pii-agent-demo` ADR/PR #532에서 비준됐다. 코드↔문서 정합을 위해 **로컬 ADR-021에 PENDING 상태·전이 규칙을 최소 분량으로 추가**한다(5.3). 코드가 우선, 문서는 얇게.

---

## 5. 구현 범위 — production 변경 (파일별)

착수 전 확정 범위. `file:line`은 베이스 `75a26f1` 기준.

### 5.1 상태·전이 (핵심)

| # | 파일 | 변경 |
|---|---|---|
| — | `entity/Pipeline.java` | **변경 없음.** enum varchar 저장은 `origin/main`의 `PipelineStatusConverter`(LIN-28)가 이미 처리 — PENDING은 그 converter로 자동 저장/조회된다(4.1). |
| 1 | `enums/PipelineStatus.java` | 상수 `PENDING` 추가. **`isTerminal()`을 `this != RUNNING` → 명시적 종단 허용목록 `this == DONE || this == FAILED || this == CANCELLED`로** 수정(`:18`). 클래스 javadoc(`:3-6`)을 "비종단 = RUNNING·PENDING"으로 갱신(plain text). `TaskStatus.isTerminal()`(`TaskStatus.java:29`)이 이미 이 허용목록 패턴 — 그것과 맞춘다. |
| 2 | `service/lifecycle/PipelineInserter.java` | 초기 상태 분기. `startDelay.isPositive()`면 `status=PENDING, nextDueAt=now+startDelay`; 아니면(0/음수) **fast path** `status=RUNNING, nextDueAt=now`(전이 1회 절약). `:57`·`:61` 수정, javadoc(`:28-30`) 갱신. |
| 3 | `service/execution/PipelineClaimer.java` | `claimOneDue`의 `.map(...)`에 `pipeline.setStatus(PipelineStatus.RUNNING);` 추가(`:46-49` 사이). 이미 RUNNING이면 값 no-op. 규칙 A대로 lease와 같은 tx1 UPDATE에 실린다. javadoc에 전이 1줄 명시. |

### 5.2 status 하드코딩 스윕

| # | 파일 | 변경 |
|---|---|---|
| 4 | `repository/PipelineRepository.java` | `lockClaimableDuePipelines`(`:49`): `status = RUNNING` → `status in (RUNNING, PENDING)`. `cancelIfIdle`(`:61`): 가드 `status = RUNNING` → `status in (RUNNING, PENDING)`. `findNearestClaimableDueAt`(`:72`): `status = RUNNING` → `status in (RUNNING, PENDING)`(claim 술어와 status 집합 일치 유지 → idle-sleep 상한이 PENDING due를 반영). **`requestCancel`(`:67`)은 변경 없음**(4.4). JPQL 내 FQN enum 리터럴은 no-inline-fqn 예외라 기존 표기 유지. |
| 5 | `service/query/PipelineQueryService.java` | `liveStatistics`(`:88-95`): running은 `countByStatus(RUNNING)` 유지, **PENDING count 신규 노출**(`countByStatus(PENDING)`). `statistics`(`:97-109`): `EnumMap`에서 **PENDING 버킷** 추가로 읽고 **total에 합산**(현재 total은 running+failed+done+cancelled라 PENDING이 누락되면 과소계상). |
| 6 | `dto/pipeline/LivePipelineStatistics.java` | `pending_pipeline_count`(long) 필드 추가. |
| 7 | `dto/pipeline/PipelineStatistics.java` | `pending_count`(long) 필드 추가. |

> DTO 구성 방식(6·7): 두 record는 현재 positional `new`로 생성된다. `PipelineStatistics`는 필드 추가 시 인접 `long`이 5개(running/failed/done/cancelled/**pending**)가 되어 인자 뒤바뀜이 컴파일에 안 잡힌다 — 하네스 **dto-builder** 룰 대상. → 두 record에 `@Builder`를 얹고 호출부를 named builder로 바꾼다(스왑 위험 제거). 추가 필드 없는 다른 필드는 그대로.

### 5.3 문서(얇게)

| # | 파일 | 변경 |
|---|---|---|
| 8 | `docs/adr/021-pipeline-execution-model.md` (+필요 시 `016`) | PENDING 상태·claim 시점 전이·`next_due_at` 불변 규칙을 짧게 추가. 상태 머신 서술의 "비종단=RUNNING"을 "RUNNING·PENDING"으로 정정. |
| 9 | 관련 javadoc | `PipelineControl.java:20,28`·`Pipeline.java:29`·`PipelineClaimer.java:60`·`PipelineStatistics.java:9`의 "RUNNING 가드/현재 RUNNING" 서술을 PENDING 포함으로 정정(비행동, plain text). |

## 6. 의도적으로 변경하지 않는 것 (리뷰어가 "빠뜨렸다"고 오해하지 않도록)

- **`StepReporter.java:135,148`의 `== RUNNING`** — write-back은 PENDING에 도달 불가(4.5). 유지.
- **`requestCancel`의 `status='RUNNING'`** — PENDING은 Case A로만 취소됨(4.4). 유지.
- **`PipelineClaimer.atRunningCapacity`**(`:62-64`) — status가 아니라 활성 lease(`countByClaimedUntilAfter`)로 센다. PENDING(미claim)은 lease가 없어 cap에 안 잡히는 게 맞다. 유지.
- **`dueLagMillis`**(`PipelineQueryService.java:169`) — `isTerminal()` 수정만으로 자동 정합: PENDING은 비종단이고 `nextDueAt`이 미래라 `max(0, now-nextDueAt)=0`. 코드 변경 없음.
- **`PipelineSummary`/`PipelineDetail`의 `status`** — enum pass-through라 PENDING이 그대로 직렬화. 변경 없음(AC #1 자동 충족).
- **인덱스·제약·컨트롤러** — 4.1~4.3대로 0건(status 컬럼 매핑은 4.1대로 1건 변경, 인덱스/제약은 무변경).
- **`Pipeline.type`·`Pipeline.cloudProvider`** — 이들도 `@Enumerated(STRING)` native enum이라 같은 잠재 함정을 갖지만, 이번엔 값을 추가하지 않으므로 건드리지 않는다(YAGNI). 훗날 값 추가 시 status와 같은 varchar 강제가 필요하다는 점만 기록.

## 7. 테스트 계획 (P0/P1 코너케이스 → 구체 테스트)

원칙: 하네스 **no-hidden-test-tx** 준수(`@Transactional(NOT_SUPPORTED)`), `MutableClock`으로 시간 제어, 실제 경로(create→claim→run) 우선. 결정적으로 재현 가능한 것과 진짜 동시성이 필요한 것을 구분해 명시한다.

### 기존 `PipelineStartDelayTest` 보강
- `firstTaskDoesNotDispatchBeforeTheDelayElapses`: 생성 직후 **`status == PENDING`** 단언 추가 (AC#1, P0#4).
- `firstTaskDispatchesOnceTheDelayElapses`: dispatch 후 리로드해 **`PENDING → RUNNING` 전이** 단언 추가 (AC#2).
- `cancelDuringTheWaitRunsNoTaskAtAll`: 취소 **직전 `status == PENDING`** 단언 추가(현재는 취소 후 CANCELLED만 확인) (P0#5).

### 신규 (P0 — 없으면 머지 불가)
| P0 | 테스트 | 방식 |
|---|---|---|
| #3 fast path | `startDelay=ZERO` → 생성 즉시 `RUNNING`, `nextDueAt==now`, 즉시 claim, **PENDING 행 부재** | 결정적. `startDelay(ZERO)` Wiring |
| #2 isTerminal 회귀 | PENDING 파이프라인 있는 target에 중복 create → `409`(`OrchestrationErrorCode` 참조, 리터럴 금지) | 결정적 |
| #6 전이 후 크래시 | claim(→RUNNING+lease) 후 첫 task 실행 없이 lease 만료 → 시계 전진 → reclaim → 첫 task dispatch (PENDING에 멈추지도, RUNNING서 유실도 안 됨) | 결정적(`MutableClock`으로 lease 만료 강제) |
| #7 next_due_at 불변 | claim/전이 후 `nextDueAt`이 생성 seed 그대로(claim이 `now()`로 덮지 않음) | 결정적 |
| #1 전이↔취소 경합 | 두 결과를 **분기 로직으로** 검증: (a) claim 선점 → 이후 cancel은 Case B(플래그만, status RUNNING) → 워커가 CANCELLED 1회 적용; (b) cancel 선점(PENDING·미claim) → Case A 즉시 CANCELLED, 이후 claim 0건·dispatch 0회 | (a)(b) 각각 결정적 단일스레드로 두 브랜치 검증. **진짜 동시 인터리빙(양 lock order)은 H2 제약**(SKIP LOCKED 미지원 → 일반 FOR UPDATE)으로 신뢰성 있는 재현이 어려워, 로직 분기 검증으로 대체하고 한계를 코멘트로 남긴다 |

### 신규 (P1 — 권장)
| P1 | 테스트 | 방식 |
|---|---|---|
| #10 집계 정확성 | 전이 전 `liveStatistics`: pending=1·running=0; 전이 후 pending=0·running=1. `statistics` total에 PENDING 반영 | 결정적. (PENDING 픽스처를 `PipelineQueryServiceTest`에 넣으면 `:251` 헬퍼 `activeTarget = status==RUNNING ? target : null`을 `!isTerminal()` 기준으로 고칠 것) |
| #8 RUNNING 재claim no-op | 만료 RUNNING 재claim 시 `status='RUNNING'` no-op, 이전 토큰 tx2 거부(fencing) | 대체로 결정적 |
| #9 다중워커 SKIP LOCKED | 같은 due PENDING을 정확히 하나만 claim | H2 제약으로 best-effort. 한계 명시 |

### 회귀 방어(중요)
- **기존 테스트 전수 영향 없음(확인함):** create→RUNNING을 즉시 단언하는 테스트(`PipelineSoftCapTest:88` 등)는 모두 `startDelay(Duration.ZERO)`를 쓴다 → fast path로 여전히 `RUNNING`. PENDING을 쓰는 건 `PipelineStartDelayTest`(15s)뿐. 그래도 `mvn test` 전체로 최종 확인한다.
- **status 저장 검증:** `origin/main`의 `PipelineStatusConverter`(LIN-28)로 `pipeline.status`는 `varchar(16)`이고, PENDING이 그대로 저장/조회됨을 생성 DDL + `mvn test` 168 PASS로 확인.

## 8. 하네스/컨벤션 준수

- **guarded-CAS**: claim의 `setStatus(RUNNING)`은 blind `findById→set→save`가 아니라, status 술어(`IN (RUNNING,PENDING)`)로 필터한 `PESSIMISTIC_WRITE` 잠금 아래의 write다 — ADR-021 "FOR UPDATE + 검증된 단일 writer가 CAS를 대체" 관용구(`StepReporter` javadoc `:28-29`). 잠금이 곧 가드.
- **exhaustive-switch**: `PipelineStatus`를 분기하는 `switch`는 코드에 없음(스윕 확인). 추가 안 함.
- **no-inline-fqn**: JPQL `@Query` 내 enum FQN은 예외 — 기존 표기 유지.
- **no-html-javadoc / no-abbreviations / list-get-first / error-code-enum**: 신규·수정 코드 전부 준수. 커밋 전 `bash scripts/recurring-check.sh --staged` + `recurring-review` 에이전트 게이트 실행.

## 9. 수용 기준 (이슈 원문)

- [ ] 생성 직후 지연 창 동안 상태가 `RUNNING`이 아닌 `PENDING`으로 조회된다.
- [ ] 지연 경과 후 첫 claim 시 `RUNNING`으로 전이된다.
- [ ] 대기 상태에서 취소 시 즉시 `CANCELLED`, Task dispatch 0회(회귀 없음).
- [ ] claim / cancel / 통계 / terminalize의 status 가드가 PENDING을 올바르게 처리한다.
- [ ] one-active-per-target 불변식 유지, `mvn test` PASS.

## 10. 범위 밖 (out of scope)

- 프론트 표시(대안의 파생 표시) — 백엔드 상태 구분이 본 이슈의 해결.
- `startDelay` 값·설정 자체의 변경(LIN-17 소관).
- PENDING에 별도 운영자 표시명(한글 라벨) 부여 — status enum은 그대로 직렬화. 표시명 규약이 필요하면 후속.
- 진짜 2-스레드 동시성 회귀 테스트 하네스 도입(H2 SKIP LOCKED 한계) — 7장의 분기 검증으로 갈음.
