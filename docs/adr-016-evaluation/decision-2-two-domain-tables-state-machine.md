# Decision 2 - Two domain tables, a small durable state machine

## Verdict

부분 준수. `pipeline`/`task` rows, basic task lifecycle, blocked chain, and stored
`pipeline.status` projection are implemented. However, the implementation materially differs from
the external ADR schema/enums: no `TaskKind` enum, no `task.kind` column, a `task.jobId` column is
present, `ErrorCode` values differ, and recipe selection is by `PipelineType` only, not
`(type, provider)`.

## ADR requirements

- domain state tables are `pipeline` and `task`.
- current task is the lowest `seq` task in `READY` or `IN_PROGRESS`.
- later tasks remain `BLOCKED` until predecessor reaches `DONE`.
- `pipeline.status` is a stored projection written in the same transaction as the task transition
  that changes it.
- five core enums: `TaskStatus`, `PipelineStatus`, `TaskKind`, `PipelineType`, `ErrorCode`.
- conditional sixth enum: `TaskOperation`.
- `task.kind` selects executor; `operation` selects the domain action.
- recipe is a code default per `(type, provider)`.

## Evidence - implemented parts

- `pipeline` and `task` JPA tables exist:
  `src/main/java/com/bff/pipeline/entity/Pipeline.java:33`,
  `src/main/java/com/bff/pipeline/entity/Task.java:46`
- the task chain has a unique `(pipeline_id, sequence)` constraint:
  `src/main/java/com/bff/pipeline/entity/Task.java:46`
- first task is created `READY`; later tasks are created `BLOCKED`:
  `src/main/java/com/bff/pipeline/service/PipelineInserter.java:57`
- the engine reads the task chain ordered by sequence:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:63`
- `BLOCKED -> READY`, `READY -> IN_PROGRESS`, and `IN_PROGRESS -> DONE/FAILED` are driven in
  `TaskMachine`:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:77`
- pipeline projection is converged in the same `@Transactional` `advance` method:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:58`,
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:77`

## Evidence - deviations

- current task selection uses the lowest non-terminal task, not strictly the lowest
  `READY`/`IN_PROGRESS` task:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:69`.
  This means a `BLOCKED` task is selected and then unblocked by an `advance`.
- the column is `sequence`, not ADR `seq`:
  `src/main/java/com/bff/pipeline/entity/Task.java:63`
- there is no `TaskKind` enum. Executor selection uses `taskName` and `TaskTypeRegistry`:
  `src/main/java/com/bff/pipeline/entity/Task.java:66`,
  `src/main/java/com/bff/pipeline/service/TaskTypeRegistry.java:23`
- production task types are named with strings:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:31`,
  `src/main/java/com/bff/pipeline/service/ConditionCheckTask.java:25`
- `task.jobId` exists even though the external ADR schema says task has no job-id column:
  `src/main/java/com/bff/pipeline/entity/Task.java:77`
- `ErrorCode` uses `TIME_TO_LIVE_EXPIRED` and adds `UNKNOWN_TASK`; external ADR says
  `TTL_EXPIRED` and does not list `UNKNOWN_TASK`:
  `src/main/java/com/bff/pipeline/enums/ErrorCode.java:17`
- recipe is keyed by `PipelineType` only:
  `src/main/java/com/bff/pipeline/service/Recipes.java:30`

## Gaps and risks

- schema drift: `kind`/`seq`/`ttl` names in the external ADR are not implemented as written.
- enum drift: `TaskKind` is absent, and `ErrorCode` canonical values do not match.
- provider dimension is absent, so provider-specific recipes cannot be represented without further
  changes.
- the current-task definition differs from the ADR wording. The implementation may be acceptable as
  a one-step persistent unblock model, but it is not the literal rule in the external ADR.

## Test coverage

- happy path and final projection:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:88`
- `BLOCKED -> READY` chain behavior:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:102`
- failure projection and remaining-task cancellation:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:124`
- commit visibility:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTransactionTest.java:41`

Missing tests: canonical enum set, generated schema column names/absence, provider-specific recipe
selection, and strict "lowest READY/IN_PROGRESS" current-task semantics.

## Conclusion

The state-machine shape is recognizable, but the external ADR's schema and closed enum model are not
implemented faithfully.
