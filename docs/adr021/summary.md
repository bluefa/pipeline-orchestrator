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

- 빌드: `mvn -o test` → **54 tests, BUILD SUCCESS**.
- 리뷰 캠페인 (opus 3라운드 + codex 다회):
  - **opus R1**(NPE 인증): MERGE-READY YES — 도달 가능 NPE 0건. P2 단순화 2건 반영(releaseClaim 죽은 null-branch, scheduler min DRY).
  - **opus R2**(적대적 동시성): MERGE-READY YES — terminal resurrection/lost-update/claim-safety/liveness 모두 불가 입증. P2 반영(죽은 finish() 제거, @Version doc 정정).
  - **opus R3**(전체 정합성): MERGE-READY YES(코드 blocker 0) — P1 2건은 **문서 drift**(exception-strategy/cases가 삭제된 PipelineEngine.advance 참조) → 수정. P2(ExecutionSettings fail-fast 테스트 추가, javadoc 링크 정정) → 반영.
  - **codex**: CLI가 큰 코드베이스 탐색 시 결론을 못 내는 현상이 있어, `xhigh + inline diff` 방식으로 결론 유도.
    - **R1d**: NO(4×P1) — 유효 2건(next_due_at NOT NULL, TimeBounded @ConditionalOnBean) 반영, 오탐 2건(claimToken/attempt NPE) 근거와 함께 기각(opus가 안전 확인). 방어 가드 1건 추가.
    - **R2**: YES(P2 2건: StepReporter 헤더 token-only, DoD finish() 표현) → 반영.
    - **R3**: (campaign 종료 시 기재).
  - **종료 기준 충족**: 코드 P0/P1 0건. 남은 지적은 모두 문서/주석 정합성이며 반영 완료.

## 베이스/토폴로지 (중요)

기존 `feat/adr-021-execution-model` 브랜치(10라운드 캠페인)는 ADR-016 response-model 리팩터 **이전** 베이스라
충돌 위험이 커서, `origin/main`에서 새 브랜치 `feat/adr-021-claim-pull-execution`을 따고 그 브랜치를 **검증된 설계
참조**로만 써서 새 도메인 위에 재구현했다. 기존 브랜치/워크트리는 보존했다. (decisions-and-questions D-A)

## 확인이 필요한 항목 (decisions-and-questions.md 참조)

- **D-A**: 새 브랜치로 재구현한 토폴로지 결정이 맞는지.
- **D-B1**: retry 시 `nextCheckAt = now + pollingInterval` 지연(난타 방지) — 별도 retry-backoff 키가 나을지.
- **D-B2**: 429/503 **차등** back-pressure(Retry-After) 미구현(일반 back-pressure는 동작). 예외 어휘 확장 필요 여부.
- **D-B3**: 운영 메트릭(Micrometer) 이번 범위 포함 여부.
