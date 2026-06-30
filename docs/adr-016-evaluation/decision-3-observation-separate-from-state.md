# Decision 3 - Observation is separate from state

## Verdict

준수. `task_attempt` and `task_check` tables exist and `task_check` is update-in-place, and
completion reads the latest attempt result. The dispatch response is stored in `task_attempt.response`
(raw text), and Terraform completion runs a code-level `check(attempt, task)` over the latest attempt
rather than polling a domain job column. The implementation matches the external ADR's
state/observation boundary.

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
- `ObservationRecorder.recordCheck` loads or creates the single check row and updates counters in place:
  `src/main/java/com/bff/pipeline/service/ObservationRecorder.java:62`
- pipeline transition and scheduling read `pipeline`/`task` only:
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:58`,
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:73`,
  `src/main/java/com/bff/pipeline/service/PipelineEngine.java:77`

## Evidence - completion reads the latest attempt

- `task_attempt` stores the dispatch `response` (raw text); a Terraform dispatch's N job ids ride in
  that text, not in a domain `task` column:
  `src/main/java/com/bff/pipeline/entity/TaskAttempt.java:58`
- `task` no longer stores a `jobId`; the dispatch handle lives only on `task_attempt.response`:
  `src/main/java/com/bff/pipeline/entity/Task.java:38`
- `TaskType.check` receives `(target, task, attempt)` and decides completion from the latest attempt:
  `src/main/java/com/bff/pipeline/model/TaskType.java:51`
- `TaskStateMachine.poll` loads the current attempt and calls `type.check(target, task, attempt)`:
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:112`,
  `src/main/java/com/bff/pipeline/service/TaskStateMachine.java:113`
- `TerraformTask.check` deserializes the latest attempt's `response`, not a domain job column:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:80`
- a missing latest `response` (lost write / crash) falls through to `executionTimeout` and re-dispatch
  rather than being silently swallowed:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:106`

## Evidence - remaining minor notes

- an extra observation enum exists (`CheckSignal`). It is not persisted as an enum column, but the
  external ADR says observation adds no enum:
  `src/main/java/com/bff/pipeline/enums/CheckSignal.java:12`

## Gaps and risks

- response code/summary fields on `task_check` exist but are not populated by the current code (kept
  for a future HTTP adapter):
  `src/main/java/com/bff/pipeline/entity/TaskCheck.java:58`

## Test coverage

- one attempt row on happy path:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:71`
- one attempt row per retry:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:88`
- `task_check` update-in-place:
  `src/test/java/com/bff/pipeline/service/ObservationTest.java:103`
- tests assert the response model — completion is driven by the latest `task_attempt.response`:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:94`,
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:270`
- a lost latest `response` rides `executionTimeout` and re-dispatches:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:359`

## Conclusion

Observation tables are present and the external ADR's central observation invariant is implemented:
completion is latest-attempt-result based (`check(attempt, task)` over `task_attempt.response`), not
tied to a domain job column.
