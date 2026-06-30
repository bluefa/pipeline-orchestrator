# Decision 1 - The database is the only state

## Verdict

대체로 충족. 진행 상태의 권위가 DB row에 있다는 Decision 1의 핵심은 구현되어 있다.
`src/main`에는 progress를 소유하는 in-memory runner, queue, scheduler, worker state가 없고,
`PipelineEngine.advance(pipelineId)`는 매 호출마다 DB에서 `pipeline`과 `task` row를 다시 읽는다.

단, `task.jobId`가 domain state table에 들어간 점은 Decision 1 자체보다 Decision 3/5와 더 직접
충돌하는 인접 리스크다. DB-only에는 맞지만, 외부 ADR의 "job ids live in task_attempt" 설계와는
맞지 않는다.

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
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:77`
- terminal pipeline transition is a guarded DB update over a `RUNNING` row:
  `src/main/java/com/bff/pipeline/repository/PipelineRepository.java:27`
- creation writes the pipeline and task chain in a transaction:
  `src/main/java/com/bff/pipeline/service/PipelineInserter.java:42`

## Gaps and risks

- `task.jobId` is persisted in the domain `task` row:
  `src/main/java/com/bff/pipeline/entity/Task.java:77`. `TerraformTask` stores and polls with
  this value:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:49`.
  This is still DB-backed state, but it contradicts the external ADR's later decision that job ids
  belong to `task_attempt`, not to `task`.
- there is no true process-restart integration test. The closest test reuses the same Spring test
  context and confirms stored `jobId` repoll:
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

Decision 1 itself is mostly implemented. The follow-on ADR semantics around where job ids live are
not, and those are covered in Decision 3 and 5.
