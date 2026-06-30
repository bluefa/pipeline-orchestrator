# ADR-021 구현 요약 (자율 진행 결과)

자리를 비운 동안 ADR-021(claim-pull 실행 모델)을 DoD-우선 방식으로 구현했다. 이 문서는 **무엇을 했고,
어떻게 검증했고, 무엇을 확인받아야 하는지**를 한눈에 정리한다. 상세 결정/확인 항목은
[decisions-and-questions.md](decisions-and-questions.md), 범위/DoD는 [implementation-dod.md](implementation-dod.md).

## 한 일 (요청한 순서대로)

1. **ADR-021을 Decision별 구현범위 + DoD로 분해** → `implementation-dod.md`.
2. **DoD를 codex로 2라운드 리뷰** → round1 NO/ round2 NO의 P1/P2를 모두 반영(특히 ADR과 어긋났던
   live-lease ownership을 **token-only**로 단순화, 생성 시 `next_due_at` 시딩 누락 등 실제 구현 요구사항 발굴).
3. **DoD 기반으로 실제 구현** (two-transaction claim-pull):
   - 설정: `ExecutionSettings`(`pipeline.execution.*`, fail-fast 검증, `lease > apiCallTimeout`).
   - 스키마: `Pipeline`에 `next_due_at/claimed_by/claimed_until/cancel_requested` + claim 인덱스 2개.
   - tx1: `PipelineClaimer`(FOR UPDATE SKIP LOCKED + UUID fencing token + admission soft-cap).
   - phase-A: `StepRunner`(외부호출 경계, 트랜잭션 없음, 닫힌 어휘만 `StepOutcome`으로 번역).
   - tx2: `StepReporter`(token-only guarded write-back, `cancel_requested` under lock, BLOCKED 후속 승격).
   - `TaskStateMachine.applyOutcome`(외부호출 제거, `StepOutcome`→태스크 전환; retry는 `pollingInterval` 지연).
   - `PipelineWorker`(claim→run→report 조립, 트랜잭션 없음), `PipelineScheduler`(적응형 backoff 루프).
   - `PipelineControl`(cancel Case A 즉시 / Case B cooperative), `TimeBoundedInfraManagerClient`(per-call timeout).
4. **테스트 49개 green** (e2e + claim/lease/cancel/softcap + scheduler 단위 + decorator).
5. **codex + opus 리뷰 3라운드씩** — 라운드별 결과는 아래.

## 검증 결과

- 빌드: `mvn -o test` → **49 tests, BUILD SUCCESS**.
- 리뷰 라운드: (campaign 종료 후 채움)
  - codex R1/R2/R3, opus R1/R2/R3 결과 요약 + 반영 내역.

## 베이스/토폴로지 (중요)

기존 `feat/adr-021-execution-model` 브랜치(10라운드 캠페인)는 ADR-016 response-model 리팩터 **이전** 베이스라
충돌 위험이 커서, `origin/main`에서 새 브랜치 `feat/adr-021-claim-pull-execution`을 따고 그 브랜치를 **검증된 설계
참조**로만 써서 새 도메인 위에 재구현했다. 기존 브랜치/워크트리는 보존했다. (decisions-and-questions D-A)

## 확인이 필요한 항목 (decisions-and-questions.md 참조)

- **D-A**: 새 브랜치로 재구현한 토폴로지 결정이 맞는지.
- **D-B1**: retry 시 `nextCheckAt = now + pollingInterval` 지연(난타 방지) — 별도 retry-backoff 키가 나을지.
- **D-B2**: 429/503 **차등** back-pressure(Retry-After) 미구현(일반 back-pressure는 동작). 예외 어휘 확장 필요 여부.
- **D-B3**: 운영 메트릭(Micrometer) 이번 범위 포함 여부.
