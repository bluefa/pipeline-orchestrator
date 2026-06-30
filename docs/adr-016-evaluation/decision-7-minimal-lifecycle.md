# Decision 7 - Minimal lifecycle

## Verdict

조건부 준수. Lifecycle states and cancel/failure convergence mostly match the external ADR.
There is no `CANCELLING`, cancellation terminalizes non-terminal tasks, and terminal pipeline rows
are guarded from resurrection. The main caveat is that "two task kinds" is not closed by a
`TaskKind` enum; `TaskTypeRegistry` will auto-register any `TaskType` bean.

## ADR requirements

- two task kinds: `TERRAFORM_JOB`, `CONDITION_CHECK`.
- retry is a fresh run.
- cancel converges directly to `CANCELLED`; there is no `CANCELLING`.
- cancel terminalizes every non-terminal task:
  `BLOCKED` / `READY` / `IN_PROGRESS` -> `CANCELLED`.
- failed pipeline marks the failing task `FAILED` and the rest `CANCELLED`.
- terminal state is never resurrected.
- applying cancel to a live worker is ADR-021, not this domain model.

## Evidence - implemented parts

- `TaskStatus` has only `BLOCKED`, `READY`, `IN_PROGRESS`, `DONE`, `FAILED`, `CANCELLED`;
  no `CANCELLING`:
  `src/main/java/com/bff/pipeline/enums/TaskStatus.java:15`
- `PipelineStatus` has only `RUNNING`, `DONE`, `FAILED`, `CANCELLED`:
  `src/main/java/com/bff/pipeline/enums/PipelineStatus.java:8`
- production task names are currently `TERRAFORM_JOB` and `CONDITION_CHECK`:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:31`,
  `src/main/java/com/bff/pipeline/service/ConditionCheckTask.java:25`
- recipes currently use only those two task names:
  `src/main/java/com/bff/pipeline/service/Recipes.java:23`
- retry resets to a fresh dispatch-ready state:
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:180`
- cancel goes directly to `PipelineStatus.CANCELLED` through `finish`:
  `src/main/java/com/bff/pipeline/service/PipelineControl.java:40`
- non-terminal tasks are cancelled by `TaskCanceller`:
  `src/main/java/com/bff/pipeline/service/TaskCanceller.java:30`
- failed pipeline convergence cancels remaining non-terminal tasks:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:77`
- terminal pipeline rows are no-op in `advance`, and `finish` only updates `RUNNING` rows:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:60`,
  `src/main/java/com/bff/pipeline/repository/PipelineRepository.java:27`
- live-worker cancel is not implemented here; `PipelineControl` depends only on persistence,
  `TaskCanceller`, and `Clock`:
  `src/main/java/com/bff/pipeline/service/PipelineControl.java:27`

## Gaps and risks

- two-kind closure is not enforced. `TaskTypeRegistry` registers every `TaskType` bean:
  `src/main/java/com/bff/pipeline/service/TaskTypeRegistry.java:27`
- tests include an extra `PostCheckProbeTask` test bean, proving the registry can expand task types
  outside the two-kind ADR model:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:443`
- fresh retry is implemented by clearing `jobId`, but there is no direct test that a second external
  dispatch with a distinct job id is recorded.
- cancel tests cover created and in-progress cases, but not a mixed chain where earlier tasks are
  already `DONE` and later tasks are non-terminal.

## Test coverage

- failure cascades to remaining task cancellation:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:245`
- cancel then later `advance` does not revive:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:257`
- cancel terminalizes non-terminal tasks:
  `src/test/java/com/bff/pipeline/service/PipelineControlTest.java:52`
- cancel is idempotent / terminal row is not resurrected:
  `src/test/java/com/bff/pipeline/service/PipelineControlTest.java:65`,
  `src/test/java/com/bff/pipeline/service/PipelineControlTest.java:85`
- cancel racing with in-flight advance preserves `CANCELLED`:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTransactionTest.java:51`
- retry creates increasing attempts:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:88`

## Conclusion

Lifecycle behavior is largely aligned, but the implementation's extensible `TaskType` model weakens
the external ADR's closed two-kind decision.
