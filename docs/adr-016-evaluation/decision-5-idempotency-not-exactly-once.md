# Decision 5 - Correctness rests on idempotency, not exactly-once

## Verdict

합치. The implementation has no `DISPATCHING` state and retry/re-dispatch relies on
idempotency, and the external ADR's core data model for dispatch results is implemented. The dispatch
response (which for `TERRAFORM_JOB` carries the N job ids) is stored as raw text in
`task_attempt.response`, not on a domain `task` column, and completion runs a code-level
`check(attempt, task)` over the latest attempt data.

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
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:92`,
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:169`
- retry prepares a fresh run by incrementing `failCount`, setting status to `READY`, and clearing
  `nextCheckAt`; the fresh run records a new `task_attempt` with its own `response` (no domain handle
  to reset):
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:180`
- attempt identity is `(task_id, attempt_number)`:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:34`,
  `src/main/java/com/bff/pipeline/service/ObservationRecorder.java:99`

## Evidence - dispatch result model

- domain `task` no longer carries a `jobId`; the dispatch handle lives on `task_attempt.response`:
  `src/main/java/com/bff/pipeline/entity/Task.java:38`
- InfraManager dispatch returns the raw response `String`; for `TERRAFORM_JOB` it is a JSON array
  carrying the N job ids, stored verbatim:
  `src/main/java/com/bff/pipeline/client/InfraManagerClient.java:28`
- `task_attempt` stores that raw `response` text:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:58`
- `TerraformTask.execute` returns the raw dispatch response; the engine records it on the attempt:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:70`
- `TerraformTask.check` reads the latest attempt's `response`, not a domain column:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:80`
- `TaskType.check` accepts the attempt and decides completion from it:
  `src/main/java/com/bff/pipeline/model/TaskType.java:51`
- the engine reads the latest attempt only for completion; claim/scheduling/pipeline transitions still
  read `pipeline`/`task` only:
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:112`

## Gaps and risks

- the N job ids of a Terraform dispatch are carried inside the opaque `response` text rather than as a
  typed `job_ids` set; the engine treats the text as opaque and each `TaskType` deserializes its own.
- tests assert the response model — completion driven by the latest `task_attempt.response`, and crash
  resume re-polls by the stored `response`:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:94`,
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`

## Test coverage

- no-dispatching happy path:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:88`
- retry/fail at max:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:124`
- crash resume by stored response:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`
- one attempt per retry:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:88`

Missing tests: job-id set storage, latest attempt result drives completion, missing latest result
re-dispatches after timeout, and prior jobs are ignored but not reclaimed.

## Conclusion

The idempotent retry direction is present, and the external ADR's correctness mechanism is too: the
implementation relies on the latest attempt result in observation (`task_attempt.response` read by a
code-level `check(attempt, task)`), not on a domain `task.jobId`.
