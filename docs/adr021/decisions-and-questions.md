# ADR-021 구현 — 자율 진행 중 내린 결정 & 확인이 필요한 항목

자리를 비운 동안(약 7시간) 자율로 진행하면서 내린 판단과, 돌아온 뒤 확인이 필요한 항목을 기록한다.
각 항목은 **무엇을/왜** 그렇게 했는지와, 다른 선택을 원할 경우 **되돌리는 비용**을 적는다.

## D-A. 작업 베이스를 새 브랜치(off `origin/main`)로 시작 — stale 브랜치 재사용 안 함 ✅ 결정함

- **발견**: 기존 `feat/adr-021-execution-model` 브랜치에 이미 10라운드 리뷰 캠페인을 거친 실행모델 구현(스케줄러/워커/claimer/reporter,
  ~2100줄)이 있었다. 그러나 이 브랜치는 ADR-016 response-model 리팩터(PR #5/#7, `TaskMachine→TaskStateMachine`,
  `Observations→ObservationRecorder`, `task_attempt.job_id → response` text, `service/terraform/`)가 머지되기 **전의 베이스**에 있다.
- **판단**: 도메인 의미가 바뀐 파일(특히 `TaskMachine`)을 가로지르는 blind rebase는 잘못된 도메인 위에 자동 머지될 위험이 크다.
  그래서 `origin/main`에서 새 브랜치 `feat/adr-021-claim-pull-execution`을 따고, 기존 브랜치를 **검증된 설계 참조**로만 활용해
  구조를 새 도메인에 맞게 재구현한다.
- **되돌리기**: 기존 브랜치를 rebase해서 살리고 싶다면 가능하나 충돌 해소 비용이 큼. 현재 선택이 더 안전하다고 판단.
- **확인 요청**: 이 방향이 맞는지. (기존 `feat/adr-021-execution-model` 브랜치/워크트리는 건드리지 않고 보존해 둠.)

## D-D. `docs/acceptance-criteria.md` 재조정 — 후속 문서 작업으로 분리 ✅ 결정함(오너)

- **발견**: acceptance-criteria.md 전체(참조 36곳)가 claim-pull 이전 설계(`PipelineEngine.advance` 단일 진입점,
  `finish()` CAS, `PipelineEngineTest`/`PipelineEngineTransactionTest`)로 작성돼 있다. 해당 클래스/테스트는 코드에서
  이미 사라졌고(현재: `PipelineWorker.process`→`StepRunner.runStep`→`StepReporter.writeBack`, FOR UPDATE 하 write-back,
  `PipelineExecutionTest`) 참조가 전부 stale이다. **판정 기준(criteria) 자체는 유효**하고 클래스/테스트 매핑만 낡음.
- **판단**: 이 staleness는 이 PR이 만든 게 아니라 이전 설계의 잔재이고 코드 정확성과 무관하다. 머지 직전 급조 재작성은
  오히려 부정확할 위험 → 별도 후속 문서 작업으로 분리한다. 문서 상단에 stale 배너를 달아 오해를 방지한다.
- **후속 범위**: 36개 참조를 현재 claim-pull 구조/현재 테스트명으로 매핑. `PipelineEngineTransactionTest`가 커버하던
  tx-가시성/@Version 경합 항목(A3/H4)은 현재 동등 테스트 유무를 확인해 재서술 또는 커버리지 보강.

## D-C. ADR-016 §4 반전: 중복 create → 기존 반환(idempotent) 대신 409 Conflict ✅ 결정함(오너)

- **배경**: 트리거의 실제 호출 경로가 확정됨 — **운영자가 web admin 페이지에서 "try" 버튼을 누르는 사람 콜**.
  자동 시스템의 at-least-once 재전달 경로가 아니다.
- **결정**: 같은 target에 이미 활성 실행이 있으면 → `PipelineAlreadyActiveException`(409 /
  `ORCHESTRATION_PIPELINE_ALREADY_ACTIVE`)로 거절. (기존: 기존 활성 실행을 그대로 반환하는 멱등 no-op.)
  - silent return-existing은 machine 클라이언트의 재전달 흡수용인데, 사람 콜에선 409 "이미 실행 중"이 더 정직·명확하다
    (사람은 409를 읽고 이해; 멱등 재해석 불필요).
  - 부수 정리: 재시도 소진용 `PipelineCreationConflictException` 제거, `insert`의 active-target 아닌 무결성 위반은
    `PipelinePersistenceException`(500)으로 감싸 raw 예외가 컨트롤러까지 새지 않게 함. `findByActiveTarget`은 dead code라 제거.
- **ADR-021 영향 없음**: single-owner 불변식은 `active_target` 유니크 제약이 보장하며 create 반환 방식과 무관.
  워커는 create를 부르지 않고 기존 실행을 claim한다. §5의 dispatch 멱등성(InfraManager)도 무관하게 유지.
- **되돌리기**: 반환-멱등으로 돌리려면 create에서 위반 시 `findByActiveTarget` 조회·반환으로 복구(저비용). ADR-016 §4 문구도 함께 복원.

## D-B. 구현 중 내린 세부 결정 (리뷰 라운드 누적)

### D-B1. 재시도 케이던스: retry 시 `nextCheckAt = now + pollingInterval` ✅ 결정함
- 현재 main의 `TaskStateMachine.retryOrFail`은 `nextCheckAt=null`(즉시 재실행)이었다. ADR-021 claim 루프에서는
  즉시 due → 즉시 재claim → 즉시 재dispatch가 InfraManager를 난타한다. 그래서 retry 시 `pollingInterval`만큼
  지연한다(재dispatch는 멱등이라 안전). 도메인 의미(READY로 복귀, failCount 증가)는 그대로.
- **확인 요청**: 이 지연이 적절한지(별도 retry backoff 키를 두는 게 나을지). 현재는 기존 `pollingInterval` 재사용.

### D-B2. 429/503 status-code별 back-pressure는 V1 deferred (일반 back-pressure는 이미 구현됨) ✅ 결정함
- ADR "Safety mechanisms" 절은 429/503 시 `next_due_at`을 밀어 back off하라고 한다.
- **이미 충족되는 부분**: 호출 실패(429/503 포함)는 `CallFailedException`→`CHECK_ERROR`→`retryOrFail`로 처리되고,
  retry는 `nextCheckAt=now+pollingInterval`로 설정 → `releaseClaim`이 `next_due_at`을 그만큼 전진시킨다.
  즉 **외부 실패 시 next_due_at을 밀어 back off하는 ADR 의도는 (pollingInterval 단위로) 이미 작동한다.**
- **deferred인 부분**: `InfraManagerClient`의 닫힌 예외 어휘(`CallTimeout`/`CallFailed`/`CallInterrupted`)는 HTTP
  status code를 운반하지 않으므로 **429/503에만 더 큰/Retry-After 기반 backoff**를 주는 차등 처리는 못 한다.
  그건 예외 어휘 확장(`CallRejectedException(retryAfter)`)이 필요 → ADR-016 예외 전략 변경이라 V1 범위를 넘는다.
  멱등성으로 정확성은 유지(여분 호출만). codex DoD 리뷰는 이 차등 부재를 P1로 봤으나, 일반 back-pressure가
  작동하므로 ADR 의도와 모순되지 않는다고 판단.
- **확인 요청**: 429/503 차등 backoff(Retry-After 존중)를 V1에 넣을지. 넣는다면 예외 어휘 확장 동의 필요.

### D-B3. 운영 메트릭은 observability follow-up으로 deferred ✅ 결정함
- ADR "Operational reference"의 metric 카탈로그(claim QPS, stale-report discard, reclaim, slot retry,
  overshoot, latency 등)는 reference catalog이며, 이 데모 repo엔 Micrometer/메트릭 인프라가 없다.
  코어 claim-pull 정확성에 집중하고 메트릭은 후속으로 둔다.
- **확인 요청**: 메트릭(Micrometer counter/gauge)을 이번 범위에 포함할지.

### D-B5. 리뷰 라운드1 반영 & codex 오탐 판정 ✅ 결정함
- **opus R1**(MERGE-READY YES): `releaseClaim`의 죽은 null-branch 제거, scheduler `min()` DRY화 — 반영.
- **opus R2 적대적 동시성**(MERGE-READY YES): terminal resurrection/lost-update/claim-safety/liveness 모두 깨지지 않음.
  반영: 죽은 `PipelineRepository.finish()` 제거(종료 전이는 tx2 FOR UPDATE setter + `cancelIfIdle`로 이동),
  `Task.@Version` javadoc 정정(이제 순서 보장은 pipeline 행 잠금이 제공, `@Version`은 방어적 다중화).
- **codex R1d**(MERGE-READY NO, 4×P1) 판정:
  - P1 `claimToken.equals` NPE → **오탐**(claimToken은 UUID라 구조적으로 non-null; opus가 안전 확인). 그래도 public
    메서드라 `claimToken != null &&` 방어 가드만 추가.
  - P1 IN_PROGRESS null attempt NPE → **오탐**. `TerraformTask.check`가 null attempt를 lost-response(executionTimeout
    fallthrough)로 처리(D-4 설계, 테스트로 증명). 코드 변경 없음.
  - P1 `next_due_at IS NULL` unclaimable vs 주석 → **유효**. `next_due_at`을 `NOT NULL` 컬럼으로 만들고(항상 시딩됨)
    주석 정정.
  - P1 `@Primary` 데코레이터 delegate 누락 → **유효**. `@ConditionalOnBean(name="infraManagerDelegate")`로 만들어
    delegate가 없으면(데모/테스트) 생성되지 않게 함(컨텍스트 기동 무손상).
  - P2 shutdown이 in-flight future를 await하지 않음 → **안전(문서화)**. shutdownNow가 sweep을 인터럽트하면 drain
    future가 cancel되고, 인터럽트된 워커는 크래시와 동치로 lease 만료 reclaim된다(정확성 무손상).

### D-B6. H2 SKIP LOCKED 미검증 (Testcontainers follow-up) — 확인 요청
- claim 쿼리는 MySQL에서 `FOR UPDATE SKIP LOCKED`, H2에서는 일반 `FOR UPDATE`(블로킹)로 렌더링된다(repo 기존 한계).
  따라서 "두 워커가 서로 다른 행을 non-blocking claim" 동작은 H2 스위트로는 검증되지 않는다(단일 스레드 claim/lease는 검증).
- **확인 요청**: 실제 MySQL(Testcontainers) 동시 claim 테스트를 CI에 추가할지.

### D-B4. per-call timeout 데코레이터(`TimeBoundedInfraManagerClient`) 포함 ✅ 결정함
- ADR Decision 5의 `apiCallTimeout`을 실제로 강제하려면 호출별 타임아웃 데코레이터가 필요하다. `@Primary`로
  delegate(`infraManagerDelegate`)를 감싸 별도 스레드풀에서 실행하고 `TimeoutException→CallTimeoutException` 변환.
- 프로덕션 HTTP delegate 빈은 이 repo에 아직 없음(데모) — 통합 테스트는 fake delegate를 제공. 현재 main도
  프로덕션 InfraManager 빈이 없으므로 컨텍스트 기동 조건을 악화시키지 않는다.
