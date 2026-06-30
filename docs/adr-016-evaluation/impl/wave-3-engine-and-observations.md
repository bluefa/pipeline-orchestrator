# Wave 3 — 엔진 배선 + 관찰 + 종류별 행위 (D-3/D-4/D-5)

목표: Wave 2 계약 위에서 완료 경로를 최신 `response` 기반으로 재배선하고, 결정 D-3/D-4/D-5를 구현한다.

## 변경 파일 & 작업

- `service/ObservationRecorder.java`
  - `recordJobId` → **`recordResponse(task, rawResponse)`**: dispatch 직후 최신 attempt에 `response` 기록.
  - `beginAttempt`에서 `task.getJobId()` 복사 제거.
  - 완료용 "최신 attempt 읽기"는 repo 메서드로 노출(엔진이 호출). write 경로의 no-op(없으면) 유지하되, **read 경로는 '없음'을 엔진에 신호**해야 함(D-4 분기용).
- `service/TaskStateMachine.java`
  - `poll`(:100-): 최신 attempt 로드 → `type.check(target, task, attempt)` 호출.
  - **D-4 (결과 유실)**: 최신 attempt의 `response`가 **없음**(dispatch 후 write 실패/크래시) → 즉시 CHECK_ERROR 아님 → **executionTimeout fallthrough**(deadline 도달 시 retryable `EXECUTION_TIMEOUT` → `retryOrFail`로 failCount 공유). **반드시 `log.warn`으로 '어느 단계(dispatch 후 write)에서 왜 유실됐는지' 명시.**
  - **D-5 (malformed-present)**: `response`는 있으나 역직렬화 실패 → **fail 처리**(유실과 구분; `applyFailure`/`failOutright` 경로, 사유 로깅).
  - `markInProgress`(:170-171) jobId 미러링 제거; `retryOrFail`(:189) `setJobId(null)` 제거. fresh-run은 새 attempt 행으로 성립.
  - `dispatch`: `execute`가 돌려준 원시 response를 `ObservationRecorder.recordResponse`로 기록(write 실패 시 D-4 경로).
- `service/TerraformTask.java`
  - `execute`: `task.setJobId` 제거 → 원시 dispatch response 반환(엔진이 기록). null/blank → `CallFailedException` 유지.
  - `check(target, task, attempt)`: `attempt.response`를 **Jackson 파싱**해 N개 job id 추출(새 의존성 X, Response 클래스 X). 각 job 폴링 후 **D-3 집계**: 전부 success→`SUCCEEDED`; 하나라도 failed→`failedRetryable(JOB_FAILED)`; 아직 running & executionTimeout 전→`pending`; executionTimeout 도달→`failedRetryable(EXECUTION_TIMEOUT)`. 파싱 실패→D-5(fail).
- `service/ConditionCheckTask.java`
  - 새 시그니처 반영(`response` 없음 — no-op execute 유지).
- `service/TaskCanceller.java`
  - **유지**: 열린 attempt를 `CANCELLED`로 먼저 종료(:33) → task `CANCELLED`. (response 보존, 비종료 attempt가 완료 read에 안 잡히게.)

## Wave 3 DoD

- [ ] 완료가 최신 `task_attempt.response` 기반(`task.jobId` 미사용). (G2)
- [ ] D-3 집계 구현. (G3)
- [ ] D-4: 유실→executionTimeout→재dispatch, failCount 공유, **로깅**. (G4)
- [ ] D-5: malformed→fail. (G5)
- [ ] `task.jobId`/`recordJobId`/jobId 미러링 잔재 0 (`grep -rn "jobId\|getJobId\|setJobId\|recordJobId" src/main` → response 모델 외 0). (G6)
- [ ] `mvn -q -DskipTests compile` (main) 컴파일 OK. (테스트는 Wave 4.)
- [ ] cancel seam 유지(열린 attempt 먼저 종료).

## Review log
- (opus/codex 결과 누적)
