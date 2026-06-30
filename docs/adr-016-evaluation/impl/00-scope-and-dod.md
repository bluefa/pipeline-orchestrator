# ADR-016 response-model 전환 — 구현 범위 & DoD (마스터)

> 이 문서는 reviewer가 작업 결과를 판정하는 **기준**이다. reviewer는 (a) 이 문서의 DoD, (b) 최신 ADR-016,
> (c) owner가 확정한 결정들 — 을 근거로 wave별 결과를 리뷰한다.

## 0. 컨텍스트 / 근거 파일

- **최신 ADR-016 (spec, source of truth):** `docs/adr/016-install-delete-pipeline-domain-model.md`
  (= pii-agent-demo `ed97ec0340c902162e9ca53f3e6c0db04c43ab36`로 동기화됨).
- **근거 평가 문서 (나 + codex/subagent 작성):**
  `docs/adr-016-evaluation/decision-1~7-*.md`, `CHANGES-response-model.md`, `_original-adr-016-pii-agent-demo.md`.
- **대상 코드:** `pipeline-orchestrator` `feat/adr-016-response-model` 브랜치 (base `main` @ `44d66e6`).
- **규칙:** `AGENTS.md` + `.claude/skills/spring-java21/SKILL.md` (생성자 주입, 주입된 Clock, 가드 CAS,
  한국어 클래스 헤더 주석/영어 식별자, 약어 금지, JPA 생성 스키마(Flyway 금지), exception-strategy 두 실패 종류 분리).

## 1. 목표 (한 줄)

job 핸들을 도메인 `task.job_id`에서 제거하고, dispatch 결과 원시 **`response`(text)** 를 `task_attempt`에 저장한다.
완료 판정은 **최신 `task_attempt`를 읽는 `check(attempt, task)`** 로 하고, 각 task 종류가 자기 `response`를 역직렬화한다.
이는 잘못 구현된 `task.jobId`/단일-핸들 모델을 **대체**한다.

## 2. owner 확정 결정 (LOCKED — reviewer는 이 기준으로 본다)

| # | 항목 | 결정 |
|---|---|---|
| D-1 | completion seam | ADR대로 `check(attempt, task)`; 각 종류가 자기 `response` 역직렬화. |
| D-2 | `response` vs `dispatch_response_code/summary` | **단일 `response`(text)로 흡수.** 별도 코드/summary 컬럼 없음 (최신 ADR Schema와 일치). |
| D-3 | N개 job id 완료 집계 | TerraformTask 내부 규칙: **N개 전부 success → DONE; 하나라도 FAILED → retryable `JOB_FAILED`; 아직 running & executionTimeout 전 → pending.** |
| D-4 | dispatch 후 DB write 실패(결과 유실) | dispatch 카운트 **공유**(failCount 예산 같이 소모) → executionTimeout fallthrough → 멱등 재dispatch. **실패 사유를 정확히 로깅**(어느 단계에서 왜 유실됐는지). |
| D-5 | present-but-malformed `response` | **fail 처리** (유실=fallthrough와 구분; 파싱 불가 응답은 즉시 실패 경로). |
| D-6 | 잘못 구현 잔재 | 유지할 이유 없음 — 즉시 제거. (`task.jobId`, `recordJobId`, write-only 관찰 javadoc 등) |

## 3. 범위 (IN)

- 도메인 `task`에서 `job_id` 제거.
- `task_attempt`: `job_id` → `response`(text). `dispatch_response_code/summary` 제거.
- `TaskType.check`(및 필요한 곳) → 최신 attempt 접근; 각 종류가 `response` 역직렬화(Jackson, 새 의존성 X, Response 클래스 계층 X).
- `InfraManagerClient.runTerraform` → 단일 job id String 대신 **원시 response** 반환.
- 엔진: `poll`이 최신 attempt 로드 후 `check`에 전달; 유실(D-4)/malformed(D-5) 분기 + 로깅; jobId 미러링/리셋 제거.
- 관찰: `recordJobId`→`recordResponse`; 완료용 "최신 attempt 읽기" 경로(write-only 계약 완화, ADR §3 invariant 1대로 "최신 row만, 완료만").
- cancel: 열린 attempt를 먼저 종료하는 seam **유지**.
- 테스트: jobId→response 재타겟 + 신규 케이스.

## 4. 범위 밖 (OUT — 별도 PR, 이번에 건드리지 않음)

> 잘못 구현이 아니라 "canonical 표기/구조 정렬" 항목. response 모델과 무관하므로 scope-creep 방지 위해 제외.

- `TaskKind` enum + `task.kind` (현재 `task_name`+`TaskTypeRegistry`) / 그로 인한 `UNKNOWN_TASK`.
- 네이밍: `seq`/`ttl`/`kind`, `ErrorCode.TIME_TO_LIVE_EXPIRED`→`TTL_EXPIRED`.
- recipe `(type)`→`(type, provider)`.
- REST trigger endpoint.
- `pipeline.active_target` ADR Schema 노트.
- `CheckSignal` enum 제거(ADR "no enum") — 별도 판단. (이번엔 유지, 단 신규 코드에서 확산 금지.)

각 항목은 `CHANGES-response-model.md` 마지막 절에 근거와 함께 기록됨.

## 5. 전역 DoD (모든 wave 완료 시 충족)

- [ ] **G1** `docs/adr/016` Schema/§3/§5와 코드가 일치: `task`에 `job_id` 없음, `task_attempt.response`(text) 존재, `dispatch_response_*` 없음.
- [ ] **G2** 완료 판정이 `task.jobId`가 아니라 **최신 `task_attempt.response`** 를 읽는 `check(attempt, task)`로 이뤄진다. 엔진은 관찰 테이블을 **완료 목적, 최신 row만** 읽는다(ADR §3 invariant 1).
- [ ] **G3** D-3 집계 규칙대로 N-id 완료가 동작(테스트로 증명).
- [ ] **G4** D-4: 결과 유실 → executionTimeout fallthrough → 멱등 재dispatch, failCount 공유, **로그에 유실 단계/사유 명시**(테스트 + 로그 단언).
- [ ] **G5** D-5: malformed-present response → fail 처리(테스트).
- [ ] **G6** `task.jobId` 및 관련 미러링/리셋/`recordJobId` 잔재 0 (grep으로 확인).
- [ ] **G7** `mvn test` GREEN. 신규 케이스(§6 wave-4 목록) 전부 존재.
- [ ] **G8** SKILL.md §6 체크리스트 통과(생성자 주입/Clock/약어 금지/한국어 클래스 헤더 등).
- [ ] **G9** OUT 항목을 건드리지 않음(diff 확인).
- [ ] **G10** opus + codex 리뷰에서 P0/P1 0건.

## 6. Wave 구성 (파일별)

| Wave | 파일 | 산출물 | 리뷰 |
|---|---|---|---|
| 1 | `wave-1` (=ADR sync + 본 문서들) | ADR ed97ec0 동기화, scope/DoD/wave 문서 | (plan sanity) |
| 2 | `wave-2-domain-model-and-seam.md` | 엔티티(`Task`/`TaskAttempt`) + 계약(`TaskType`/`InfraManagerClient`/repo) | opus/codex |
| 3 | `wave-3-engine-and-observations.md` | `TaskMachine`/`Observations`/`TerraformTask`/`ConditionCheckTask`/`TaskCanceller` 배선 + D-3/D-4/D-5 | opus/codex |
| 4 | `wave-4-tests.md` | 테스트 재타겟 + 신규, `mvn test` GREEN | opus/codex (final) |

각 wave: **구현 → `mvn test`(가능 지점) → opus/codex 리뷰 → P0/P1 수정 → 다음 wave.**

## 7. 리뷰 프로토콜

- **opus 리뷰**: `agent-skills:code-reviewer`(5축) + `recurring-review`(가드 CAS/예외 타겟팅/인터페이스 정당성 — `.claude/review-ledger.md`).
- **codex 리뷰**: `/codex-review` 스킬 (gpt-5.5, xhigh) — 동시성/트랜잭션/완료 경로 교차검증. P0/P1 없을 때까지 라운드 반복.
- 리뷰는 본 문서 DoD + LOCKED 결정(§2) + 최신 ADR을 기준으로 한다. 각 wave 리뷰 결과/수정은 해당 wave 문서 하단 "Review log"에 누적.
