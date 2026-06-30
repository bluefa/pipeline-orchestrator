# Wave 4 — 테스트 재타겟 + 신규 케이스

목표: 기존 jobId 단언을 response로 옮기고, 결정 D-3/D-4/D-5와 ADR §3/§5/§6를 증명하는 케이스를 추가. `mvn test` GREEN.

## 재타겟 (기존)

- `service/PipelineEngineTest.java:94, 270` — `task.getJobId()` 단언 → 최신 `task_attempt.response` 기반.
- `service/ObservationTest.java:71, 88, 103` — `jobId` 단언 → `response`.
- `client/FakeInfraManagerClient.java` — `runTerraform`이 원시 response(N개 job id 포함 가능) 반환하도록; `terraformJobStatus`를 새 입력에 맞춤.

## 신규 케이스

- [ ] **T1** N-id `response`가 Jackson 왕복(여러 job id 저장/파싱).
- [ ] **T2** 최신 attempt.response가 완료를 구동(예전 task.jobId 아님).
- [ ] **T3** stale(이전 attempt) row 무시 — 최신만 읽음.
- [ ] **T4 (D-3)** N개 중 하나 FAILED → retryable JOB_FAILED; 전부 success → DONE; 일부 running → pending.
- [ ] **T5 (D-4)** dispatch 후 response write 실패/유실 → executionTimeout → 멱등 재dispatch, failCount 공유. **로그에 유실 단계/사유 포함**을 단언(로그 캡처 또는 관측 가능한 신호).
- [ ] **T6 (D-5)** present-but-malformed response → fail 처리.
- [ ] **T7** condition poll call 실패/타임아웃(현재 TF만 커버).
- [ ] **T8** 혼합 체인 cancel: 열린 response-attempt 종료 + task/후속 CANCELLED, DONE 선행 보존, terminalized attempt의 response 보존.
- [ ] **T9** fresh-retry가 새 attempt에 distinct response, 이전 attempt response 불변.
- [ ] **T10** 스키마 가드: `task`에 `job_id` 없음 / `task_attempt`에 `response` 있음.
- [ ] **T11** task별 `maxFailCount` override.

## Wave 4 DoD

- [ ] 위 재타겟 + T1~T11 존재.
- [ ] `mvn test` GREEN(0 fail). (G7)
- [ ] 전역 DoD G1~G10 충족.
- [ ] 테스트는 `@Transactional` 금지(SKILL 정책), fake 사용(mock 지양).

## Review log
- (opus/codex final 결과 누적)
