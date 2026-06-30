# ADR-016 → `task_attempt.response` 모델 전환 — 변경 계획 (summary)

승인된 방향: **job 핸들을 도메인 `task.jobId`에서 떼어내 `task_attempt.response`(generic text)에 저장하고, 완료 판정을
최신 `task_attempt`를 읽는 코드 레벨 `check(attempt, task)`로 수행한다.** 각 `TaskType`이 자기 response를
역직렬화(이미 깔린 Jackson, 새 의존성 X, Response 클래스 계층 미리 만들지 않음 — YAGNI). 이 방향은 원본 ADR의
`job_ids`(N개 set)와 현재 구현의 `task.jobId`를 **둘 다 supersede**한다. **ADR 먼저 고치고 → 코드.**

- 평가 대상: `pipeline-orchestrator/main` @ `44d66e6`
- 기준 ADR: 동기화된 canonical `docs/adr/016-...md` (== pii-agent-demo `3a232ea`)
- 방법: Decision 1~7 각각 별도 subagent로 평가 + 변경점 도출 후 취합.

## Decision별 판정 (response 모델 관점)

| Decision | 판정 | response 모델 영향 |
|---|---|---|
| 1 DB가 유일한 상태 | 유지됨, 오히려 깔끔해짐 | `task`가 컬럼 1개(job_id) 줄고, 완료의 read-set이 최신 attempt로 이동 |
| 2 두 도메인 테이블 + 상태 머신 | 핵심 불합치(단일 job_id on task) | `task.job_id` 제거가 **load-bearing**. + 별도 canonical 항목들 |
| 3 관찰과 상태 분리 | **핵심 불일치(invariant 1 뒤집힘)** | 가장 큰 변경. 관찰이 완료 입력원이 됨(최신 row만, 완료만) |
| 4 target당 활성 pipeline 1개 | 서비스 충족 / endpoint 미구현 | **무관**(orthogonal) |
| 5 idempotency, not exactly-once | 방향은 충족(`DISPATCHING` 없음, reclaim 안 함) | 데이터/완료 모델만 교체 |
| 6 유계 대기·재시도 | 코어 충족 / canonical drift | 새 "유실→executionTimeout fallthrough" 경계 추가 |
| 7 최소 lifecycle | 충족, lifecycle-중립 | cancel이 열린 attempt를 먼저 종료하는 seam 유지가 핵심 |

## 변경 항목 (영역별 취합)

### A. ADR 문서 (먼저)
- §3/§5/Schema: `task_attempt.job_ids`(set) → **`response`(text)**. "job-id set and responses" 문구 → "raw external
  `response`(may contain N job ids), 각 TaskType이 역직렬화". `task`에 job-id 컬럼 없음 문구는 유지.
- invariant 3: "최신 결과 유실 → `executionTimeout` → 멱등 재dispatch"는 이미 문구 있음 — **이제 실제 구현돼야 함**.
- §6: condition TTL은 **terminal-by-design**임을 명시(=`fail_count` 증가시키는 "failed poll"과 구분). per-call timeout은
  ADR-021(`apiCallTimeout`) 소유, 016은 `CALL_TIMEOUT`만 영속.
- 본 변경이 원본 `job_ids` + 현재 `task.jobId`를 supersede함을 명시.
- (repo 밖) ADR-021 §4의 "`job_ids` set" 문구도 같이 drift — 표기만 맞춰야 함(flag).

### B. JPA 스키마 / 엔티티
- `entity/Task.java:77` — `private String jobId;` **삭제**(+ javadoc :38). → "task가 줄어듦".
- `entity/TaskAttempt.java:54` — `jobId` → `private String response;` (text/CLOB). javadoc(:23-31)에서 "쓰기 전용/절대
  안 읽음" 제거, "최신 row가 `check`의 입력" 명시.
- `dispatch_response_code/summary`(:63-64) 처리는 owner 결정(아래 Q2).

### C. 서비스 / 클라이언트
- `model/TaskType.java:36-41` — `check`(및 필요시 `execute`/`postCheck`)가 **최신 attempt(또는 response)** 에 접근하도록
  seam 변경. (signature를 `check(attempt, task)`로 바꾸기 vs `TerraformTask`에 "최신 attempt 읽기 port" 부여 — Q1)
- `client/InfraManagerClient.java:29` — `runTerraform`이 단일 `String jobId` 대신 **원시 response**(N ids 포함 가능) 반환.
  `terraformJobStatus`(:32) 입력도 새 완료 경로가 주는 값으로.
- `service/TerraformTask.java:49-72` — `execute`는 `task.setJobId` 대신 raw response를 attempt에 기록; `check`는 최신
  `response`를 Jackson 파싱해 N ids 폴링. null/blank guard 유지.
- `service/ConditionCheckTask.java:47` — 새 signature만 반영(폴링 대상 response 없음).
- `service/Observations.java:49,55-60` — `recordJobId`→`recordResponse`; `beginAttempt`가 `task.getJobId()` 복사 중단;
  완료용 "최신 attempt 조회" read 경로 추가(write-only 계약 완화).
- `service/TaskMachine.java` — `poll`(:100-105)이 최신 attempt 로드 후 `check`에 전달; **유실/없음 → 즉시 `CHECK_ERROR`가
  아니라 `executionTimeout` fallthrough**(Q 핵심); `markInProgress`(:170-171)의 jobId 미러링 제거; `retryOrFail`(:189)
  `setJobId(null)` 제거(필드 소멸 — 단 fresh-run 리셋 자체는 유지).
- `service/TaskCanceller.java:33` — **유지**. cancel이 열린 attempt를 `CANCELLED`로 먼저 종료 → 이후 "최신 attempt" 읽기가
  `CANCELLED` task의 비종료 attempt를 읽는 일이 없도록 보장.
- `enums/CheckSignal.java` — canonical "관찰은 enum 추가 안 함"과 충돌. 제거 vs 명시적 waiver(Q9).

### D. 테스트
- `task.jobId`/`TaskAttempt.jobId` 단언 → `response`로 재타겟: `PipelineEngineTest.java:94,270`, `ObservationTest.java:71,88,103`, `FakeInfraManagerClient`.
- 추가: (a) N-id response Jackson 왕복, (b) 최신 attempt가 완료 구동, (c) stale(이전) attempt 무시, (d) 최신 response
  유실 → `executionTimeout` → 멱등 재dispatch, (e) condition poll 실패/타임아웃, (f) task별 `maxFailCount` override,
  (g) 혼합 체인 cancel(열린 response-attempt 종료, response 보존), (h) fresh-retry가 새 attempt에 distinct response.
- 스키마 가드 테스트: `task`에 `job_id` 없음 / `task_attempt`에 `response` 있음.

## Owner가 결정해야 할 것 (response 모델 내부)

1. **Seam 선택(진짜 분기):** `TaskType.check(attempt, task)` signature 변경(ADR 문구와 일치) vs 인터페이스 최소 유지를
   위해 `TerraformTask`에 "최신 attempt response read-port" 부여(D2 권고). **코딩 전 확정.**
2. **`response` vs `dispatch_response_code/summary`:** 흡수 통합 vs 공존(HTTP status 분리 유지).
3. **N-id 완료 집계 규칙:** 한 response의 N개 job id가 "다 성공해야 done"인지, "하나라도 실패면 retryable fail"인지 — ADR이
   "code-level check"만 말하고 집계 규칙 미명시.
4. **present-but-malformed response:** 파싱 실패 시 유실처럼 `executionTimeout` fallthrough인지, 즉시 fail인지.
5. **관찰 read-계약 반전(cross-decision):** Decision 3의 "관찰은 절대 안 읽음"이 설계상 깨짐(최신 attempt를 완료용으로 읽음).
   의도 확인 + Decision 3 문서 갱신(안 그러면 평가끼리 충돌).
6. **유실 재dispatch가 `failCount` 예산 공유?** 공유면 지속 유실이 `maxFailCount`에서 task를 FAILED(유한하지만 일시적 infra
   유실이 정상 install을 죽일 수 있음). 미공유면 별도 카운트. ADR이 현재 둘을 혼동.
7. **`response` 타입/크기 상한** (text/CLOB + bounded summary 정책).
8. **"latest attempt" 키 일관성:** `(task.id, failCount+1)`. 막 dispatch된 retry는 response가 아직 null — `started_at`/
   `execution_timeout` 게이트로 조기 timeout 방지 확인.
9. **`CheckSignal` 제거 vs ADR 문구 완화**("persisted observation enum 컬럼 없음" 정도로).

## response 모델과 무관 — 별도로 rule 할 canonical 정렬 항목

> 아래는 response 변경에 **포함시키지 말 것**(scope-creep). 별도 결정/PR.

- `TaskKind` enum + `task.kind` vs 현재 `task_name`+`TaskTypeRegistry`(open set). 이로 인해 생긴 `UNKNOWN_TASK`(canonical
  enum엔 없음)도 함께. (Decision 2/7)
- 컬럼/값 네이밍: `seq`/`ttl`/`kind`, `ErrorCode.TIME_TO_LIVE_EXPIRED` → canonical `TTL_EXPIRED`(영속값 — 마이그레이션 주의). (Decision 2/6)
- recipe 키를 `(type)` → `(type, provider)`로 확장(현재 provider 축 부재). (Decision 2)
- REST trigger endpoint: 현재 `src/main`에 없음. ADR-021/API 스코프로 확정하고 "그 endpoint는 `PipelineCreator.create`를
  호출"만 계약으로 둘지. (Decision 4)
- `pipeline.active_target` 컬럼을 ADR Schema 노트에 한 줄 추가(스키마-엔티티 괴리 오해 방지). (Decision 4)

## 권장 순서
1. ADR-016 개정(§3/§5/Schema/invariant + §6 TTL/per-call 명시) → 리뷰.
2. 스키마(엔티티) 변경 + seam 결정(Q1) 반영.
3. 서비스/클라이언트 배선 + 유실 fallthrough.
4. 테스트 재타겟 + 신규 케이스.
5. `mvn test` + (선택) codex-review로 동시성/트랜잭션/완료 경로 교차검증.

> 별첨: 원본 ADR 사본 `_original-adr-016-pii-agent-demo.md`, Decision별 상세 `decision-1~7-*.md`.
