# Wave 2 — 도메인 모델 + 완료 seam

목표: 데이터 모델과 계약을 ADR(ed97ec0)에 맞춘다. (배선/행위는 Wave 3.)

## 변경 파일 & 작업

- `entity/Task.java`
  - `private String jobId;` (:77) **삭제** + javadoc(:38-39)에서 jobId 문구 제거.
- `entity/TaskAttempt.java`
  - `private String jobId;` (:54) → `private String response;` (text/CLOB; `@Column(columnDefinition = "text")`).
  - `dispatchResponseCode`/`dispatchResponseSummary` (:63-64) **삭제** (ADR Schema에서 제거됨).
  - javadoc: "쓰기 전용/절대 안 읽음" 제거 → "최신 row가 완료 `check`의 입력. 각 종류가 `response` 역직렬화."
- `model/TaskType.java`
  - `check(target, task)` → **`check(target, task, attempt)`** (최신 `TaskAttempt` 전달). `execute`는 원시 response를 돌려주도록 시그니처 조정(반환 String) — 엔진이 `Observations`로 attempt.response에 기록.
  - javadoc: 각 종류가 자기 `response`를 역직렬화함을 명시.
- `client/InfraManagerClient.java`
  - `String runTerraform(...)` → **원시 dispatch response(String, N개 job id 포함 가능)** 반환. javadoc 갱신.
  - `terraformJobStatus(...)`: 입력을 새 완료 경로가 주는 값으로(파싱된 job id 단위 폴링 유지).
- `repository/TaskAttemptRepository.java`
  - 완료용 "최신 attempt 조회" 쿼리 추가: `findTopByTaskIdOrderByAttemptNumberDesc(Long taskId)` (또는 `(taskId, failCount+1)` 키 조회 재사용). 완료 경로 전용.

## Wave 2 DoD

- [ ] `task`에 `job_id` 컬럼/필드 없음. `task_attempt`에 `response`(text) 있고 `dispatch_response_*` 없음. (G1)
- [ ] `TaskType` 계약이 최신 attempt를 받는다(D-1). `execute`가 원시 response를 산출.
- [ ] `InfraManagerClient.runTerraform`이 원시 response 반환.
- [ ] 최신 attempt 조회 repo 메서드 존재.
- [ ] SKILL.md: 약어 금지/한국어 클래스 헤더/생성자 주입 유지. (G8)
- [ ] (이 wave 단독으로는 컴파일 안 될 수 있음 — Wave 3에서 배선 후 `mvn test`.)

## Review log
- (작성 후 opus/codex 결과 누적)
