# Decision 1 - The database is the only state

## Verdict

대체로 충족. 진행 상태의 권위가 DB row에 있다는 Decision 1의 핵심은 구현되어 있다.
`src/main`에는 progress를 소유하는 in-memory runner, queue, scheduler, worker state가 없고,
`PipelineEngine.advance(pipelineId)`는 매 호출마다 DB에서 `pipeline`과 `task` row를 다시 읽는다.

dispatch 핸들은 더 이상 `task` row의 domain column이 아니라 `task_attempt.response`(raw text)에
저장되며, 완료는 최신 attempt 위의 코드 레벨 `check(attempt, task)`로 판정한다. 따라서 DB-only일
뿐 아니라 외부 ADR의 "job ids live in task_attempt" 설계와도 일치한다.

## ADR requirements

- pipeline progress state lives in database rows.
- the runner/execution model is stateless with respect to progress.
- restart/resume reads rows and continues.
- ADR-021 execution mechanics are outside this repository.

## Evidence

- `Pipeline` is a JPA entity for the `pipeline` row, including `status`, `target`, timestamps,
  and the local `activeTarget` projection:
  `src/main/java/com/bff/pipeline/entity/Pipeline.java:32`
- `Task` is a JPA entity for the `task` row, including `status`, `failCount`, `errorCode`,
  timestamps, scheduling, and deadline fields:
  `src/main/java/com/bff/pipeline/entity/Task.java:45`
- `PipelineEngine.advance(Long pipelineId)` reads the pipeline by id, skips terminal rows,
  then reads the task chain ordered by sequence:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:58`
- current task selection is derived from persisted task row state, not from an in-memory
  cursor:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:69`
- task transitions are performed from `Task.status` and persisted through repositories:
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:77`
- terminal pipeline transition is a guarded DB update over a `RUNNING` row:
  `src/main/java/com/bff/pipeline/repository/PipelineRepository.java:27`
- creation writes the pipeline and task chain in a transaction:
  `src/main/java/com/bff/pipeline/service/PipelineInserter.java:42`

## Gaps and risks

- there is no true process-restart integration test. The closest test reuses the same Spring test
  context and confirms re-poll from the stored `task_attempt.response`:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`.
- ADR-021 runner statelessness cannot be proven here beyond absence of runner state in this repo.

## Test coverage

- state-machine happy path, retry/fail, cancel, and blocked-to-ready behavior:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:88`
- production-like commit visibility:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTransactionTest.java:41`
- cancel race does not revive terminal state:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTransactionTest.java:51`

## Conclusion

Decision 1 itself is implemented, and the follow-on ADR semantics around where job ids live (on
`task_attempt.response`, not on `task`) are now implemented too, as covered in Decision 3 and 5.
