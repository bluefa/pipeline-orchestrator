# Post-PR#18 코드 리뷰 — 구현 수정 계획 (2026-07-02)

`origin/main`(= PR #18 `feat: pipeline admin REST API (P1-P10)` 머지 직후, 2c89394) 전체를
`.claude/skills/spring-java21/SKILL.md` §1–§6과 `.claude/review-ledger.md`의 promoted 패턴을
게이트로 삼아 리뷰한 결과다. 실행 엔진(claim → run → writeBack)과 도메인 모델은 여러 라운드의
리뷰를 거쳐 이미 규칙에 수렴해 있고, **수정 대상은 대부분 이번에 새로 들어온 REST 계층(PR #18)** 이다.
동작(계약) 변경은 없다 — 전부 규칙 위반 교정과 가독성/스키마 보강이다.

각 항목: 위치 → 무엇이 문제인가(어느 규칙) → 어떻게 고치는가.

## P1 — promoted 규칙 위반 (필수 수정)

### 1. list-get-first: `chain.get(chain.size() - 1)` / `.get(0)`

- `service/query/PipelineQueryService.toDetail` — `chain.get(chain.size() - 1)` (finalSequence 계산)
- `service/query/PipelineQueryServiceTest` :112 `page.getContent().get(0)`, :169/:170 `detail.attempts().get(0)`

ledger **list-get-first**(promoted, rule): List의 첫/마지막 읽기는 Java 21 `getFirst()`/`getLast()`.
`scripts/recurring-check.sh`가 테스트 3건을 실제로 잡았다(main 쪽 `.get(size-1)`은 grep 시그널 밖이라
사람이 잡아야 하는 케이스).

**수정**: `chain.getLast()`, `getFirst()`로 교체.

### 2. optional-idiom: `currentTask(...)`가 `.orElse(null)`로 열화

`service/query/PipelineQueryService.currentTask`가 `Optional`을 `.orElse(null)`로 풀고, 호출부
`toDetail`이 `current == null ? null : ...`을 3번 반복한다. ledger **optional-idiom**(promoted):
Optional은 `map`/`filter`/`orElseThrow`로 소비하고, null로 열화한 뒤 `== null` 재검사하지 않는다.

**수정**: `currentTask`가 `Optional<Task>`를 반환하고 호출부는 `current.map(Task::getSequence).orElse(null)`
꼴로 소비한다. (`PipelineWorker.loadStepContext`의 기존 2건은 nullable record 필드를 채우는 승인된
예외 — ledger changelog 참조 — 로 그대로 둔다.)

### 3. index-coverage: PR #18 admin 질의 — 검토 결과 이미 충족 (수정 불필요)

ledger **index-coverage**(promoted) 게이트로 PR #18의 신규 질의(`findByTarget`,
`findFirstByTargetOrderByCreatedAtDescIdDesc`, `countByStatusSince`, `search`)를 점검했다.
확인 결과 PR #18이 이미 `Pipeline` 엔티티에 `idx_pipeline_status_created (status, created_at)`와
`idx_pipeline_target_created (target, created_at)`를 함께 추가해 두어 전부 커버된다
(`cloud_provider`는 low-cardinality 필터라 인덱스 불요). **수정 없음** — 리뷰 통과 기록만 남긴다.

### 4. 패키지 규칙: `advice/`는 허용 패키지 집합에 없다

AGENTS.md #6(하드 룰)의 레이어 패키지는 `entity / enums / dto / model / service / client /
controller / repository / utils`이고 "controller — REST advice"라고 명시한다. ledger watch-list에
"doc ↔ code 불일치, owner 결정 대기"로 기록돼 있었는데, PR #18로 `controller/` 패키지가 실제로
생기면서 `advice/GlobalAdvice`만 규칙 밖 패키지에 남았다.

**수정**: `GlobalAdvice`를 `controller/`로 이동(테스트 `GlobalAdviceTest` 동반 이동), ledger
watch-list 행을 해소 처리. AGENTS.md 문안과 코드가 일치하는 방향으로 정리하는 것이며, owner가
`advice/`를 선호하면 되돌리기는 한 줄 이동이다.

## P2 — clean-code 수정

### 5. 19-인자 위치 기반 DTO 생성 → `@Builder` (watch-list 2회째 → 승격)

`PipelineQueryService.toDetail`이 `new PipelineDetail(19개 인자)`, `taskDetail`이
`new TaskDetail(18개 인자)`를 위치 기반으로 호출한다. 인접 동형 인자(Instant×3, Integer×4,
boolean×2)라 자리 하나 밀리면 컴파일은 통과하고 값만 뒤섞이는 전형적 사고 지점이다.
ledger watch-list "DTO built with a positional `new` → `@Builder`"(ADR-021 retro #3, 1회)의
**두 번째 발생**이므로 harness 규칙에 따라 promoted로 승격한다.

**수정**: `PipelineDetail`/`TaskDetail`에 `@Builder`를 붙이고 두 호출부를 named builder로 전환.
ledger에 승격 기록.

### 6. `long[]{done, total}` 매직 인덱스 + static mutable 배열 상수

`PipelineQueryService`의 `taskCounts`/`summarize`가 `long[]`의 `[0]`=done, `[1]`=total 규약과
`private static final long[] NO_TASKS`(배열은 불변이 아니므로 사실상 static mutable state, §5.12)로
집계를 나른다. §5.1 의도 드러내는 이름 위반.

**수정**: private record `TaskProgressCount(long done, long total)`로 교체(중첩 타입이라 파일 수 불변).
집계는 `groupingBy + teeing(filtering DONE, summingLong)`으로 스트림 한 번에 계산한다(owner의
stream 선호).

### 7. `pipeline.execution.*` 키 하나만 `@Value`로 바인딩

`PipelineScheduler` 생성자의 `@Value("${pipeline.execution.scheduler-initial-delay:PT5S}")`.
스킬 §2: 묶인 설정은 `@ConfigurationProperties`로 — 같은 prefix의 나머지 11개 키는 전부
`ExecutionSettings`가 소유하는데 이 키만 흩어져 있고, fail-fast 검증도 우회한다.

**수정**: `ExecutionSettings.schedulerInitialDelay`로 편입(+`requirePositive` 검증), 생성자 인자
제거. 테스트 6곳의 builder에 값 추가, `FeignDelegateWiringTest`의 property 오버라이드는 그대로 동작.

### 8. `TaskStateMachine.retryOrFail`의 `clock.instant()` 2회 호출

`readyAt`과 `nextCheckAt` 기준 시각이 (이론상) 갈라질 수 있고, 같은 클래스 `markInProgress`는
이미 `now` 하나로 통일하는 스타일이다.

**수정**: `Instant now` 지역변수로 통일.

### 9. 종료 파이프라인의 `dueLagMillis`가 무한히 자란다

`toDetail`의 `dueLagMillis = now - nextDueAt`(0 클램프)는 스케줄링 지연 지표인데, 종료된
pipeline은 `nextDueAt`이 마지막 값에 멈춰 있어 시간이 지날수록 의미 없는 거대 값이 노출된다.

**수정**: terminal status면 0으로 계산(지연은 RUNNING에만 의미가 있다).

## P3 — 테스트 품질 (테스트 리뷰 에이전트 발견)

### 10. 미사용 import 6건 (§5.10 dead code)

- `PipelineExecutionTest`: `InfraManagerClient`, `StepOutcome`, `Optional`
- `ObservationTest`: `ExecutionSettings`, `PipelineSettings`
- `PipelineSchedulerTest`: `InfraManagerClient`

**수정**: 삭제.

### 11. 이름이 주장하는 것을 검증하지 않는 테스트 (discriminating-test-assert)

- `PipelineQueryServiceTest.list_filtersByStatusAndComputesProgressWithoutNPlusOne` — N+1 여부를
  아무것도 측정하지 않는다. **수정**: 이름에서 `WithoutNPlusOne` 제거(쿼리 수 계측은 과함).
- `PipelineSchedulerTest.drainEndsOnAClaimFailureWithoutHammering` — "난타하지 않음"을 검증하지
  않는다(재시도 N번 해도 통과). **수정**: 던지는 claimer에 호출 카운터를 두고
  `claimAttempts == 1`을 단언.
- `ObservationTest`/`PipelineExecutionTest`의 `runUntilTerminal` — 20폴 안에 종료 못 하면 조용히
  반환해 실패 지점이 뒤로 밀린다. **수정**: 루프 소진 시 `fail("pipeline did not reach a terminal
  state in 20 polls")`.

### 12. 스위트 내 유일한 underscore 테스트 네이밍

`PipelineQueryServiceTest`만 `detail_missingPipeline_throwsNotFound` 식 underscore 이름(9건)이고
나머지 전부 camelCase 행위 이름이다(§6 "주변 스타일과 일치").

**수정**: camelCase로 일괄 rename.

### 13. `PipelineUniquenessTest` 클래스 javadoc이 실제 범위보다 좁다

7개 중 4개는 uniqueness가 아니라 생성 계약(빈 target 400, provider 조회 실패 503 등) 테스트다.

**수정**: 클래스 javadoc을 "생성 계약 + active_target 유일성"으로 넓힌다(클래스 rename은 diff 대비
이득이 작아 보류).

## 반영하지 않은 것 (검토 후 의도적 보류)

- `PipelineWorker.loadStepContext`의 `.orElse(null)` 2건 — nullable record 필드 채우기, ledger에
  기록된 승인 예외.
- `StepRunner.runExternalCall`의 boolean 파라미터 — ledger에 "deliberate, recorded exception".
- `FakeInfraManagerClient`의 bespoke functional interface 3개 — 이름이 역할 문서화를 겸함, borderline.
- 컨트롤러의 `period` 파싱 2회 — `Converter` 등록은 파일 추가 대비 이득 없음(YAGNI).
- `TimeBoundedInfraManagerClientTest`의 real-sleep — 진짜 타임아웃 역학 검증이라 Clock로 대체 불가
  (에이전트도 acceptable 판정).

## 검증 계획

1. `mvn test` 그린 (베이스라인 113 tests → 동일 이상).
2. `bash scripts/recurring-check.sh <changed files>` 무발견.
3. diff가 status transition/exception boundary를 건드리므로 `recurring-review` 에이전트 통과.
4. codex 교차 리뷰 (P0/P1 없을 때까지).
5. ledger 갱신: dto-builder 승격, advice 패키지 행 해소, changelog 추가.
