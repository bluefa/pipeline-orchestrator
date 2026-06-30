# Decision 5 - Correctness rests on idempotency, not exactly-once

## Verdict

부분 불합치. The implementation has no `DISPATCHING` state and retry/re-dispatch relies on
idempotency, but the external ADR's core data model for dispatch results is not implemented.
The code uses a single domain `task.jobId` rather than storing an N-job-id set and response result
in `task_attempt`, and completion does not run a code-level `check(attempt, task)` over latest
attempt data.

## ADR requirements

- no `DISPATCHING` state.
- duplicate dispatch may occur; idempotency makes it correct.
- crash between "InfraManager started job" and "recorded attempt result" heals by re-dispatch.
- one `TERRAFORM_JOB` dispatch returns a set of N job ids.
- job-id set and responses are recorded in `task_attempt`.
- task completion is code-level `check(attempt, task) -> done?`, not a domain job column.
- if the result is lost, `executionTimeout` triggers a fresh re-dispatch.
- prior jobs are not reclaimed; `(task_id, attempt_no)` is logical attempt identity only.

## Evidence - implemented parts

- `TaskStatus` has no `DISPATCHING`:
  `src/main/java/com/bff/pipeline/enums/TaskStatus.java:15`
- `READY` dispatch moves straight to `IN_PROGRESS`:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:92`,
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:169`
- retry prepares a fresh run by incrementing `failCount`, setting status to `READY`, clearing
  `jobId`, and clearing `nextCheckAt`:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:180`
- attempt identity is `(task_id, attempt_number)`:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:34`,
  `src/main/java/com/bff/pipeline/service/Observations.java:99`

## Evidence - deviations

- domain `task` has `jobId`:
  `src/main/java/com/bff/pipeline/entity/Task.java:77`
- InfraManager dispatch returns a single `String`, not a set:
  `src/main/java/com/bff/pipeline/client/InfraManagerClient.java:28`
- `task_attempt` has single `jobId`, not `job_ids`:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:54`
- `TerraformTask.execute` writes `task.jobId`:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:49`
- `TerraformTask.check` polls with `task.jobId`:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:59`
- `TaskType.check` does not accept an attempt/result object:
  `src/main/java/com/bff/pipeline/model/TaskType.java:36`
- `Observations` explicitly describes observation as write-only and not read by the engine:
  `src/main/java/com/bff/pipeline/service/Observations.java:15`

## Gaps and risks

- N job ids cannot be represented.
- losing `task_attempt` does not force `executionTimeout` and fresh re-dispatch; progress can keep
  using `task.jobId`.
- completion semantics are tied to a domain job column, which the external ADR explicitly avoids.
- dispatch response code/summary fields exist but have no current recording path:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:63`
- current tests assert the divergent model by checking `task.getJobId()` and crash resume by stored
  job id:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:94`,
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`

## Test coverage

- no-dispatching happy path:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:88`
- retry/fail at max:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:124`
- crash resume by stored job id:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`
- one attempt per retry:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:88`

Missing tests: job-id set storage, latest attempt result drives completion, missing latest result
re-dispatches after timeout, and prior jobs are ignored but not reclaimed.

## Conclusion

The idempotent retry direction is present, but the external ADR's correctness mechanism is not. The
implementation relies on `task.jobId`, while the ADR relies on latest attempt result in observation.
