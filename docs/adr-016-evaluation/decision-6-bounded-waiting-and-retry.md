# Decision 6 - Bounded waiting and retry

## Verdict

부분 충족. Retry loop, task-level execution timeout, `failCount`, and `maxFailCount` are
implemented for Terraform and call failures. However, the external ADR's canonical
`TTL_EXPIRED` value is not used, condition TTL is terminal without incrementing `failCount` or
retrying, and per-call timeout duration/enforcement is only represented as a client exception
contract, not implemented inside this module.

## ADR requirements

- `fail_count` per task.
- failed dispatch or poll increments it.
- below `maxFailCount`, the task re-runs as a fresh run.
- at or above `maxFailCount`, the task becomes `FAILED`.
- two deadlines: per-call timeout and per-task `executionTimeout` for Terraform / `ttl` for
  condition.
- deadlines map to canonical `ErrorCode` values, not separate states.
- no circuit breaker.

## Evidence - implemented parts

- `failCount`, `executionTimeout`, `timeToLive`, `pollingInterval`, and `maxFailCount` exist on
  `Task`:
  `src/main/java/com/bff/pipeline/entity/Task.java:79`,
  `src/main/java/com/bff/pipeline/entity/Task.java:91`
- defaults are in `PipelineSettings`:
  `src/main/java/com/bff/pipeline/PipelineSettings.java:21`
- external call failures map to `CALL_TIMEOUT` or `CHECK_ERROR` and call `retryOrFail`:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:148`
- `retryOrFail` increments `failCount`, fails at/above max, or resets to `READY` for a fresh run:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:180`
- Terraform failed poll and execution timeout are retryable failures:
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:65`,
  `src/main/java/com/bff/pipeline/service/TerraformTask.java:68`
- no circuit-breaker dependency or state was found.

## Evidence - deviations

- external ADR canonical value is `TTL_EXPIRED`; code uses `TIME_TO_LIVE_EXPIRED`:
  `src/main/java/com/bff/pipeline/enums/ErrorCode.java:17`
- `ConditionCheckTask` returns terminal failure for TTL expiry:
  `src/main/java/com/bff/pipeline/service/ConditionCheckTask.java:51`
- terminal failures call `failOutright`, not `retryOrFail`, so condition TTL does not increment
  `failCount` and does not retry:
  `src/main/java/com/bff/pipeline/service/TaskMachine.java:133`
- per-call timeout is modeled by `InfraManagerClient.CallTimeoutException`:
  `src/main/java/com/bff/pipeline/client/InfraManagerClient.java:14`
  but there is no timeout duration or enforcement implementation in this ADR-016 module.

## Gaps and risks

- canonical persisted/API error value drift (`TTL_EXPIRED` vs `TIME_TO_LIVE_EXPIRED`).
- literal ADR reading says failed poll increments `fail_count`; condition TTL expiry does not.
- condition TTL retry semantics differ from the ADR wording. This may be intentional, but if so it
  belongs in the ADR, not only in implementation.
- per-call timeout behavior depends on the future/outer InfraManager adapter.

## Test coverage

- Terraform retry then fail at max:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:124`
- dispatch call failure and call timeout:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:137`,
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:162`
- poll timeout observation:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:191`
- execution timeout:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:210`
- condition TTL currently expects `TIME_TO_LIVE_EXPIRED` terminal failure:
  `src/test/java/com/bff/pipeline/service/PipelineEngineTest.java:233`

Missing tests: condition call failure/timeout, task-specific `maxFailCount` override, first
execution-timeout failure followed by a fresh dispatch, and canonical error-code compatibility with
the external ADR.

## Conclusion

The bounded retry mechanism is mostly there for Terraform/call failures, but condition TTL and
canonical error-code semantics do not match the external ADR exactly.
