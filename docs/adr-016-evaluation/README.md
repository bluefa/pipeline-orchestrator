# ADR-016 Implementation Evaluation - `pipeline-orchestrator/main`

이 문서는 `pipeline-orchestrator`의 최신 `main`이 사용자가 지정한 외부 ADR 원문을
얼마나 구현했는지 Decision별로 평가한 결과이다.

- 평가 대상 코드: `pipeline-orchestrator/main` @ `44d66e6`
  (`Merge pull request #1 from bluefa/feat/adr-016-pipeline-domain-model`)
- 기준 ADR: `bluefa/pii-agent-demo/main`
  `docs/adr/016-install-delete-pipeline-domain-model.md` @ `3a232ea`
- 검증 방식: Decision 1-7 각각을 별도 subagent에 위임해 검토하고, 최종 문서는 취합자가
  코드와 테스트를 재확인해 정리했다.
- 주의: 이 repository 안의 `docs/adr/016-install-delete-pipeline-domain-model.md`는 구현에
  맞춰 후속 수정된 내용이 섞여 있다. 이 평가는 로컬 ADR이 아니라 위 외부 ADR 원문을
  기준으로 한다.

## Verdict at a glance

| Decision | Title | Verdict | 핵심 판단 |
|---|---|---|---|
| 1 | The database is the only state | 충족 | 진행 상태 권위는 DB row에 있고, dispatch 핸들은 `task_attempt.response`에 둔다. |
| 2 | Two domain tables, a small durable state machine | 부분 준수 | 상태 전이와 stored projection은 구현됐지만 `TaskKind`, `kind`, canonical enum, provider recipe가 다르다. |
| 3 | Observation is separate from state | 준수 | observation table이 있고 completion이 최신 attempt(`task_attempt.response`)를 코드 레벨 `check(attempt, task)`로 읽어 판정한다. |
| 4 | One active pipeline per target | 서비스 계층 충족, endpoint 미구현 | `active_target` unique로 핵심 불변식은 구현. REST trigger endpoint는 없다. |
| 5 | Correctness rests on idempotency, not exactly-once | 합치 | `DISPATCHING` 없음 + dispatch response를 `task_attempt.response`에 저장하고 attempt-result 기반 completion, lost observation은 executionTimeout 후 재디스패치로 복구한다. |
| 6 | Bounded waiting and retry | 부분 충족 | retry loop는 있으나 `TTL_EXPIRED` 이름, condition TTL retry/fail_count, per-call timeout 강제는 다르다. |
| 7 | Minimal lifecycle | 조건부 준수 | lifecycle 전이는 대체로 맞다. 다만 task kind 폐쇄성이 `TaskKind` enum으로 강제되지 않는다. |

## Bottom line

외부 ADR 원문 기준으로는 "제대로 구현되었다"고 보기 어렵다. 구현의 큰 줄기인 DB-backed
state machine, 하나의 `advance(pipelineId)`, terminal/cancel 수렴, per-target active uniqueness는
상당히 구현되어 있다. ADR-016의 핵심 설계였던 observation/result 경계도 현재 구현이
따른다 — dispatch response를 `task_attempt.response`에 저장하고 최신 attempt 위 코드 레벨
`check(attempt, task)`로 completion을 판정한다.

남은 주요 차이는 두 가지다.

1. 외부 ADR은 `TaskKind` enum과 `task.kind`를 말하지만, 현재 구현은 `task_name` 문자열과
   `TaskTypeRegistry`를 사용한다. 이는 로컬 ADR에는 post-ADR note로 정당화되어 있지만,
   지정된 외부 ADR 원문에는 없는 변경이다.
2. canonical values가 다르다. 대표적으로 `TTL_EXPIRED` 대신 `TIME_TO_LIVE_EXPIRED`가 쓰이고,
   `UNKNOWN_TASK`가 추가되었다.

따라서 현재 `main`은 "ADR-016 원문 구현"이라기보다 "ADR-016을 구현하면서 일부 결정을
로컬 문서에 맞게 재해석한 버전"에 가깝다.

## Per-decision documents

- [Decision 1 - The database is the only state](decision-1-database-is-the-only-state.md)
- [Decision 2 - Two domain tables, a small durable state machine](decision-2-two-domain-tables-state-machine.md)
- [Decision 3 - Observation is separate from state](decision-3-observation-separate-from-state.md)
- [Decision 4 - One active pipeline per target](decision-4-one-active-pipeline-per-target.md)
- [Decision 5 - Correctness rests on idempotency, not exactly-once](decision-5-idempotency-not-exactly-once.md)
- [Decision 6 - Bounded waiting and retry](decision-6-bounded-waiting-and-retry.md)
- [Decision 7 - Minimal lifecycle](decision-7-minimal-lifecycle.md)
