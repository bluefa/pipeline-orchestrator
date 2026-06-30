# ADR-021 구현 범위 & DoD (Definition of Done)

ADR-021(claim-pull 실행 모델)의 각 Decision을 **무엇을 구현하는가(Scope)** 와 **무엇이 충족되면 완료인가(DoD)** 로
분해한다. DoD는 모두 검증 가능한(테스트 또는 코드 grep/단언으로 확인되는) 항목으로만 적는다.

- 도메인 모델(테이블/상태/유일성/생명주기/멱등성)은 [ADR-016](../adr/016-install-delete-pipeline-domain-model.md)이
  소유하며 이 작업에서 바뀌지 않는다. ADR-021은 그 도메인을 **앞으로 전진시키는 런타임**만 추가한다.
- 권위 문서는 [ADR-021](../adr/021-pipeline-execution-model.md).
- 현재 코드 기준: `PipelineEngine.advance(pipelineId)`가 단일 `@Transactional` 안에서 claim+외부호출+report를 모두
  수행한다. ADR-021은 이를 **two-transaction split**(tx1 claim → 외부호출(트랜잭션 밖) → tx2 report)으로 재구성한다.

용어: tx1 = claim 트랜잭션, tx2 = report 트랜잭션. 외부호출 = InfraManager/condition-check 호출(200ms~60s).
이 DoD는 codex DoD 리뷰 1라운드(verdict NO)의 P1/P2 지적을 모두 반영해 개정한 판본이다.

---

## Decision 1 — 워커가 DB에서 일감을 pull (leader 없음, replica 제약 없음)

**Scope**
- 오케스트레이터를 **자체 배포 서버**로 기동: 스케줄링 루프 + 워커 스레드 풀(`workerPerPod`개).
- leader election 없음, in-memory in-flight guard 없음, replica 수 제약 없음.
- 스케줄러는 due pipeline을 DB에서 반복적으로 claim하여 워커 풀에 넘긴다.
- (구현 정책, ADR 비강제) 부팅 시 루프 자동 시작 + 종료 시 graceful stop.

**DoD**
- [ ] 워커 풀 크기는 `pipeline.execution.worker-per-pod` 설정으로 주입되며 하드코딩이 아니다(코드 확인).
- [ ] 코드 어디에도 "단일 인스턴스/replicas=1" 가정이나 in-memory 전역 in-flight 집합/leader 선출이 없다(grep 확인).
- [ ] claim은 process/pod 수가 아니라 DB 행(`claimed_by/claimed_until`)으로만 조정된다(코드 확인).

## Decision 2 — `FOR UPDATE SKIP LOCKED` + lease 로 pipeline claim

**Scope**
- `pipeline` 테이블에 실행 좌표 컬럼 4개 추가: `next_due_at`, `claimed_by`, `claimed_until`, `cancel_requested(boolean)`.
- tx1 claim: due predicate(`status=RUNNING AND next_due_at<=now AND (claimed_until IS NULL OR claimed_until<now)`)를
  **`ORDER BY next_due_at`** 정렬해 한 행을 `FOR UPDATE SKIP LOCKED`로 선택 후 `claimed_by=:token, claimed_until=now+lease` 스탬프.
- `claimed_by`는 **매 claim마다 새로 생성하는 UUID(fencing token)** — 재사용되는 pod id/스레드명이 아니다.
- claim predicate 지원 인덱스: `(status, next_due_at)` + `(claimed_until)`.

**DoD**
- [ ] 두 워커가 같은 스캔을 경쟁해도 서로 다른 pipeline을 claim하며 블로킹되지 않는다(SKIP LOCKED 테스트).
- [ ] claim된 행은 `claimed_until = now + leaseDuration`, `claimed_by`(UUID)가 채워진다(테스트).
- [ ] 같은 pipeline을 lease 만료 후 재claim하면 **다른** token이 부여된다(UUID 신선도 테스트).
- [ ] claim 쿼리는 `order by p.nextDueAt`로 approximate-FIFO를 보장한다 — due가 빠른 행이 먼저 선택(테스트로 2행 중 빠른 due 우선 확인).
- [ ] **빈 claim 결과를 backlog-empty로 해석하지 않는다** — 스케줄러는 0행이어도 sweep을 끝내고 다음 폴 반복으로 진행(주석+drain 테스트).
- [ ] claim predicate 미충족 행(미래 due, live lease, 종료 상태)은 절대 claim되지 않는다(테스트 각 케이스).
- [ ] `Pipeline` 엔티티에 인덱스 `idx_pipeline_claim(status,next_due_at)`, `idx_pipeline_claimed_until(claimed_until)`이 선언돼 있다(코드 확인).

## Decision 3 — Two-transaction split

**Scope**
- claim(tx1) → 외부호출(**어떤 트랜잭션에도 들어있지 않음**, tx1 commit 이후) → report(tx2)로 분리.
- 외부호출 구간 동안 어떤 행 락도 보유하지 않는다.

**DoD**
- [ ] 외부호출을 수행하는 컴포넌트(`StepRunner`)에 `@Transactional`이 없고, 워커(`PipelineWorker`)도 외부호출을 감싸는 트랜잭션을 열지 않는다(코드 확인).
- [ ] tx1(claim, `PipelineClaimer`)과 tx2(report, `StepReporter`)는 서로 다른 빈의 독립 `@Transactional` 메서드다(코드 확인).
- [ ] **외부호출 도중 claim 행 락이 보유되지 않음을 동작으로 증명**: 외부호출이 진행 중인 동안 별도 스레드/트랜잭션이 같은 pipeline 행을 읽거나 cancel을 커밋할 수 있다(`PipelineWorkerTransactionTest`: 외부호출 중 cancel 커밋 시나리오 = 락 미보유 증거).

## Decision 4 — Guarded write-back (ownership-guarded)

**Scope**
- tx2 report는 pipeline 행을 `FOR UPDATE`로 잠그고 **token-only** 소유권(`claimed_by=:token`, ADR Decision 4)을 검증할 때만 기록(실패 시 전체 no-op). lease 만료만으로는 거부하지 않는다 — fencing은 토큰만으로 충분(재claim=새 토큰, cancel=토큰 clear).
- tx2 고정 순서: (1) `findByIdForUpdate`로 행 잠금; (2) 소유권 검증(`ownsClaim`, 토큰 일치), 실패 시 no-op; (3) `cancel_requested`를 **같은 락 아래** 읽기; (4) task/pipeline status 기록 — 플래그 set이면 비종료 task 전부 + pipeline을 CANCELLED, 아니면 정상 현재-task 전이 + converge + BLOCKED 후속 승격; (5) claim 해제 + `next_due_at` 재설정.
- **생성 시 `next_due_at` 시딩**: `PipelineCreator`/`PipelineInserter`가 생성 트랜잭션에서 `next_due_at=now`, `cancel_requested=false`로 세팅(없으면 `next_due_at<=now` 조건에서 영원히 unclaimed).
- dispatch는 `task_attempt`를 쓰고 poll은 `task_check`를 갱신하되 **검증된 pipeline claim 아래(tx2)** 에서만 한다(task 레벨 claim 없음).
- 완료 판정은 최신 attempt 위 코드레벨 `check(attempt, task)` 한 번(ADR-016 §3) — claim/스케줄링/전이는 attempt를 읽지 않는다.

**DoD**
- [ ] token 불일치 시 tx2가 task/pipeline 상태를 바꾸지 않고 no-op한다(stale straggler 테스트).
- [ ] tx2 5단계 순서가 코드에 구현돼 있고, 특히 `cancel_requested`를 `FOR UPDATE` 락 **획득 후** 읽는다(코드 확인 + 외부호출 중 cancel 커밋이 tx2에서 관찰돼 CANCELLED를 덮어쓰지 않는 테스트).
- [ ] 성공 report 후 `claimed_by`/`claimed_until`이 null로 풀리고 `next_due_at`이 현재 task의 `nextCheckAt`(또는 now)로 전진한다(테스트).
- [ ] **생성된 pipeline은 `next_due_at=now`로 시딩되어 즉시 claim 가능**하다(생성 직후 claim되는 테스트).
- [ ] 현재 task가 DONE이면 같은 tx2에서 다음 BLOCKED task가 READY로 전이된다(테스트).
- [ ] dispatch가 `task_attempt.response`를 기록하고 poll이 `task_check`를 갱신함을 tx2 경로로 확인(테스트).
- [ ] 최신 attempt response 유실 시 즉시 실패가 아니라 executionTimeout fallthrough → 멱등 재dispatch(failCount 공유) — ADR-016 도메인 동작 유지(테스트).
- [ ] `task` 테이블에 `claimed_by` 컬럼이 **없다**(코드 확인).

## Decision 5 — Lease 만료를 통한 크래시 복구

**Scope**
- 크래시한 워커의 claim은 `claimed_until < now`가 되면 다음 스캔이 자동 reclaim. leader/저널/수동개입 없음.
- 운영 불변식(튜닝): `leaseDuration > maxApiCallTimeout + poolQueueWait + safetyMargin`(큐 대기도 lease 시간을 소모).
- **코드 강제 가능 부분집합**: `ExecutionSettings`가 부팅 시 `leaseDuration > apiCallTimeout`(필요 최소조건)을 강제. `poolQueueWait/safetyMargin`은 정량 측정 불가라 운영 튜닝 책임으로 둔다(주석/문서 명시).

**DoD**
- [ ] lease 만료된 claim이 다음 claim 스캔에서 due로 다시 선택돼 **다른 token**으로 reclaim된다(테스트).
- [ ] 부팅 시 `leaseDuration <= apiCallTimeout`이면 키 이름과 함께 fail-fast(설정 검증 테스트).
- [ ] reclaim에 별도 복구 경로/저널/leader가 없다(코드 확인). 재claim 후 옛 토큰 straggler의 tx2는 토큰 불일치(`ownsClaim`)로 no-op(테스트).

## Decision 6 — Cancel: idle은 즉시, running은 cooperative

**Scope**
- Admin/API path(`PipelineControl.cancel`, 별도 트랜잭션)가 cancel 발행. 한 가지 사실로 분기: **지금 워커가 이 pipeline을 돌리는가?**
- Case A (claim 없음/lease 만료): API가 직접 terminal `status=CANCELLED` 기록 **+ claim clear**(단일 UPDATE `cancelIfIdle`, 가드 `status='RUNNING' AND (claimed_by IS NULL OR claimed_until<now)`), 이후 비종료 task 전부 CANCELLED.
- Case B (live lease, `cancelIfIdle`가 0행): API는 `cancel_requested=true, next_due_at=now`만 기록(`requestCancel`, 가드 `status='RUNNING'`). claim 보유 워커가 안전지점(claim 직후 `loadStepContext`/tx2 `report`)에서 플래그를 읽어 스스로 CANCELLED 적용 후 claim 해제.
- **용어 정정**: cancel API 두 경로 모두 `status='RUNNING'` 가드를 가진다(멱등성·종료행 보호). ADR이 말하는 "no `status` guard needed"는 **워커 tx2 write-back**이 `status=:expected` 가드 없이도 안전하다는 의미다(case별 단일 status writer). 둘을 혼동하지 않는다.

**DoD**
- [ ] idle/만료-lease pipeline cancel은 워커 왕복 없이 즉시 CANCELLED + 비종료 task 전부 CANCELLED + `active_target` clear + claim clear(테스트 2케이스: claim 없음 / lease 만료).
- [ ] live-lease pipeline cancel은 status를 직접 쓰지 않고 `cancel_requested=true, next_due_at=now`만 기록한다(테스트).
- [ ] claim 보유 워커가 claim 직후/ tx2 안에서 플래그를 보면 전체를 CANCELLED로 수렴한다(각 안전지점 테스트).
- [ ] **claim vs Case A 경합**: 먼저 커밋한 쪽이 이긴다 — 워커가 claim한 뒤엔 `cancelIfIdle`가 0행→Case B로 폴백(테스트).
- [ ] GC-paused straggler는 Case A가 token을 지운 뒤(또는 재claim이 토큰을 바꾼 뒤) tx2 토큰 불일치로 resurrection 불가(테스트).
- [ ] 워커 tx2 write-back에 `status=:expected` 가드가 없다(코드 확인) — 종료행 보호는 cancel의 RUNNING 가드 + 단일 writer로 달성.
- [ ] (수용 엣지, 비-DoD 문서화) 큐 대기 중 cancel 지연은 lease 한도, 최종 pre-dispatch 체크 후 도착한 cancel은 외부호출 1회 허용(멱등) — `decisions-and-questions.md`/주석에 명시.

## Decision 7 — Admission control & TF slot gate (soft caps)

**Scope**
- `runningPipelineCap`: **soft pickup target**, 생성이 아니라 **claim**을 게이트. cap 비교는 **현재 활성 claim 수**(`claimed_until > now`)이며 전체 RUNNING 행 수가 아니다. 초과분은 `RUNNING`인 채 unclaimed로 남고 `next_due_at` 순서로 나중에 픽업. `QUEUED`/`WAITING_SLOT` 상태 없음. count-read라 overshoot(`M+C-1`) 허용.
- `slotCap`(TF slot gate): TF *slot* = Terraform 잡 실행 점유(분 단위)로 API 호출 동시성(ms~s)과 **다른 자원**. count = `(TERRAFORM_JOB, IN_PROGRESS)` task 수. 불가하면 task를 `READY`로 두고 `next_due_at=now+slotRetry` 설정 후 **claim 해제**. soft, overshoot 허용.
- 하드캡(admission counter-CAS, `tf_slot_counter`, InfraManager-side admission)은 **deferred** — 구현하지 않는다.
- DB 폴링 부하 제어: 빈 claim 후 즉시 재시도 금지, geometric jitter 백오프(`backoffBase→backoffMax`), `sleep=min(nearestDueAt-now, maxIdleSleep)`, jitter로 동기화 깨우기 방지.

**DoD**
- [ ] cap(활성 claim 수 기준) 초과 시 새 claim을 받지 않고 초과 pipeline은 `RUNNING`+unclaimed로 남는다(soft-cap 테스트).
- [ ] **생성은 cap에 게이트되지 않는다** — cap 초과 상태에서도 `PipelineCreator.create`는 즉시 `RUNNING`을 만든다(테스트).
- [ ] cap 비교가 `countByClaimedUntilAfter(now)`(활성 claim)이며 전체 RUNNING count가 아님을 코드로 확인 + overshoot 허용을 주석(`M+C-1`)에 명시.
- [ ] TF slot 불가 시 task는 `READY` 유지, `next_due_at` 전진, claim 해제(slot-full 테스트).
- [ ] 하드캡/`QUEUED`/`WAITING_SLOT`/admission counter/`tf_slot_counter` 테이블·상태가 **없다**(코드 확인).
- [ ] 빈 claim 후 backoff가 geometric하게 증가하고 work 발견 시 `pollInterval`로 reset, jitter는 `jitterRatio` 이내(backoff 단위 테스트).
- [ ] idle sleep은 `min(nearestDueAt-now, maxIdleSleep)`로 상한되며, nearest-due 조회 실패 시 uncapped fallback로 루프를 살린다(테스트).

---

## 횡단 DoD (전 Decision 공통)

- [ ] 전체 빌드 `mvn test` green, 신규 실행모델 테스트 포함.
- [ ] **NPE 경계 점검(열거식, 검증 가능)**: ① claim 결과 빈 `List`/`Optional`; ② `findByIdForUpdate` 빈 결과; ③ `currentAttempt` 부재; ④ poll `null` 상태(→CallFailed); ⑤ `nextCheckAt`/`nearestDueAt` null; ⑥ `claimToken` null-안전 비교. 각 항목에 대응하는 null-guard가 코드에 있고 테스트/리뷰로 확인.
- [ ] ADR-016 불변식 불변: one-active-per-target, RUNNING-guarded `finish()` CAS, attempt write-only(완료 read만), `@Version` 낙관락.
- [ ] 설정값(lease/worker/cap/backoff/slot/timeout/jitter/idle)은 모두 `application.yml`의 `pipeline.execution.*`에 있고 코드 상수가 아니다(`ExecutionSettings` 바인딩).
- [ ] (리뷰 게이트, DoD 아님) clean code — codex/opus 3라운드에서 P0/P1 0건, 더 적은 라인 표현 합의.

## 명시적 비범위 (V1 deferred — 구현하지 않음)

- 하드 admission/slot cap의 counter-CAS, `tf_slot_counter`, InfraManager-side 취소/admission, `QUEUED`/`WAITING_SLOT` 상태, leader election, 복구 저널.
- **429/503 → `next_due_at` 전진 back-pressure**: `InfraManagerClient`의 닫힌 예외 어휘는 HTTP status code를 운반하지 않으므로(429/503은 `CallFailedException`→`CHECK_ERROR`로 일반 재시도) status-code별 back-pressure는 어휘 확장이 필요 → V1 deferred. `decisions-and-questions.md` D-B 참조.
- **운영 메트릭 카탈로그**(claim QPS, stale-report discard, reclaim count, slot retry, overshoot, latency 등): ADR "Operational reference"는 reference catalog이며 이 데모 repo엔 메트릭 인프라가 없다 → observability follow-up으로 deferred.
- ADR "Knobs" 표의 `activePodCount`/`maxReplicas`는 오토스케일러/배포 파라미터로 앱 코드 밖(런타임 설정) → 비범위.
