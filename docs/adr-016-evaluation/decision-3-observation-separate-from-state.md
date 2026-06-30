# Decision 3 - Observation is separate from state

## Verdict

부분 준수이지만 핵심 불일치가 있다. `task_attempt` and `task_check` tables exist and
`task_check` is update-in-place, but completion does not read the latest attempt/check result.
Instead, Terraform completion polls InfraManager with `task.jobId`. The implementation therefore
does not match the external ADR's state/observation boundary.

## ADR requirements

- `task_attempt` and `task_check` carry diagnostics.
- they also hold the result that `check(attempt, task)` reads to decide completion.
- the reconciler reads only the latest attempt/check row, and only for completion.
- claim, scheduling, and pipeline transitions never read observation tables.
- missing latest result falls through to `executionTimeout` and re-dispatch.
- `task_attempt.job_ids` stores the set of job ids from a dispatch.
- `task_check` is at most one row per attempt and is updated in place.
- observation adds no domain column and no enum.

## Evidence - implemented parts

- `task_attempt` exists and has a unique `(task_id, attempt_number)` constraint:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:34`
- `task_check` exists and has a unique `task_attempt_id` constraint:
  `src/main/java/com/bff/pipeline/entity/TaskCheck.java:31`
- `task_check` stores counters and last-observed diagnostic values:
  `src/main/java/com/bff/pipeline/entity/TaskCheck.java:48`
- `Observations.recordCheck` loads or creates the single check row and updates counters in place:
  `src/main/java/com/bff/pipeline/service/Observations.java:62`
- pipeline transition and scheduling read `pipeline`/`task` only:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:58`,
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:73`,
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:77`

## Evidence - deviations

- `task_attempt` stores a single `jobId`, not `job_ids`:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:54`
- `task` also stores `jobId`, making job id part of the domain state row:
  `src/main/java/com/bff/pipeline/entity/Task.java:77`
- `TaskType.check` receives only `(target, task)`, not `(attempt, task)`:
  `src/main/java/com/bff/pipeline/model/TaskType.java:36`
- `TaskMachine.poll` calls `type.check(target, task)` directly:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:100`
- `TerraformTask.check` polls using `task.getJobId()`:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:59`
- `ConditionCheckTask.check` calls the external condition directly and does not read observation:
  `src/main/java/com/bff/pipeline/service/ConditionCheckTask.java:47`
- if the current attempt row is missing, observation writes are no-ops; there is no path that treats
  missing observation as "wait until executionTimeout then re-dispatch":
  `src/main/java/com/bff/pipeline/service/Observations.java:62`,
  `src/main/java/com/bff/pipeline/service/Observations.java:81`
- an extra observation enum exists (`CheckSignal`). It is not persisted as an enum column, but the
  external ADR says observation adds no enum:
  `src/main/java/com/bff/pipeline/enums/CheckSignal.java:12`

## Gaps and risks

- the job handle is domain state (`task.jobId`), not observation-only data.
- N job ids from one dispatch cannot be represented.
- latest-attempt-only completion is not implemented.
- losing `task_attempt`/`task_check` does not cause the ADR-prescribed timeout-and-re-dispatch recovery;
  the task can continue using `task.jobId`.
- response code/summary fields exist but are not populated by the current code:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:63`,
  `src/main/java/com/bff/pipeline/entity/TaskCheck.java:58`

## Test coverage

- one attempt row on happy path:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:71`
- one attempt row per retry:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:88`
- `task_check` update-in-place:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:103`
- tests currently lock in the drift by asserting `task.jobId` behavior:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:89`,
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`

Missing tests: latest attempt/check drives completion, stale attempts are ignored, missing latest
observation falls through to execution timeout, and N job ids are preserved.

## Conclusion

Observation tables are present, but the external ADR's most important observation invariant is not
implemented. Completion is task-row/job-id based, not latest-attempt-result based.
