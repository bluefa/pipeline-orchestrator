# Terraform Job 실패 원인·result 노출 — 변경 명세 (PR #32, 2026-07-06)

브랜치 `feat/terraform-result-exposure`. 설계 근거는 `terraform-client-and-postcheck-design.md` §4.5
(P5 메타 인라인 + P11 본문 전용 엔드포인트), 렌더링 주의는 §4.6.

두 기능을 담는다:

1. **failure_detail** — API 호출 예외의 "왜"(예외 메시지)가 서버 로그에서만 끊기던 갭을
   `task_attempt.failure_detail`로 영속하고 P5로 노출.
2. **terraform result 조회** — attempt별 job result 메타를 task 상세(P5)에 인라인하고, 본문(최대
   16MB)은 전용 엔드포인트(P11)에서 lazy 조회.

엔진 시맨틱(상태 전이·attempt 회계·write-back 순서)은 **변경 없음** — detail 스레딩은 순수 additive,
read path는 admin 조회 전용이다. 삭제된 클래스는 없다.

---

## 1. 신규 클래스 (main 4 + test 1)

| 클래스 | 역할 |
|---|---|
| `repository/TerraformResultMetadata` | `terraform_result` 메타 전용 Spring Data 투영 인터페이스. 본문(`result`, MEDIUMTEXT)을 의도적으로 제외하고 `hasBody`(case-when)로 존재만 알린다 — task 상세가 로그 I/O를 지불하지 않게 하는 장치. |
| `dto/pipeline/TerraformResultSummary` | P5 인라인용 job result 메타 요약 record (`job_id/succeeded/truncated/has_body/result_path/created_at`). `@Builder`(dto-builder 패턴). |
| `dto/pipeline/TerraformResultDetail` | P11 응답 record — 메타 + `content`(본문). `content = null`은 본문 조회에 실패했던 포인터 행. `@Builder`. |
| `exception/TerraformResultNotFoundException` | (task, attempt, job) result 행 부재 → 404 + `ORCHESTRATION_TERRAFORM_RESULT_NOT_FOUND`. `OrchestrationException` 서브타입이라 GlobalAdvice가 기존 한곳에서 처리. |
| `service/TerraformResultQueryTest` (test) | 노출 경로 전용 테스트 4건 — 메타 인라인 + 본문 lazy, 포인터 행(content null 200), 소유권 체인·404, failure_detail 512자 clamp. |

## 2. 변경 클래스 — feature 1: failure_detail (main 8)

| 클래스 | 변경 내용 |
|---|---|
| `entity/TaskAttempt` | `failureDetail` 컬럼 추가(`failure_detail` VARCHAR(512), nullable) + `FAILURE_DETAIL_LENGTH` 상수. 표시 전용 — 엔진은 읽지 않는다. |
| `model/TaskProgress` | `Failed` record에 `detail` 컴포넌트 추가. 기존 `failedRetryable(reason)`/`failedTerminal(reason)`은 detail=null로 유지, `(reason, detail)` 오버로드 신설. |
| `model/StepOutcome` | `Failed`·`CallFailure` record에 `detail` 컴포넌트 추가. 팩토리 시그니처 변경: `failed(reason, retryable, detail)`, `callTimeout(dispatch, detail)`, `callFailed(dispatch, detail)`. |
| `service/execution/StepRunner` | 외부 호출 경계 catch에서 `exception.getMessage()`를 detail로 전달 (`CallTimeoutException`/`CallFailedException` — catch 대상 불변). `mapProgress`가 `Failed.detail()`을 StepOutcome으로 이월. |
| `service/task/TaskStateMachine` | detail 스레딩: `applyFailure`/`retryOrFail`에 `failureDetail` 파라미터, `failOutright`는 기존 2-인자 + 3-인자 오버로드. 전이 로직 자체는 불변. |
| `service/task/ObservationRecorder` | `endAttempt(task, outcome, errorCode)` → `endAttempt(task, outcome, errorCode, failureDetail)`. 외부 유래 텍스트라 `clampFailureDetail`로 512자 절단 후 저장. |
| `service/task/TaskCanceller` | `endAttempt` 호출부에 detail=null 전달 (취소 종결에는 원인 텍스트 없음). |
| `service/task/terraform/TerraformTask` | 실패 지점별 원인 텍스트 부여 — malformed 응답(파싱 오류 메시지), 사용 불가 job id, 관찰 유실 후 EXECUTION_TIMEOUT, `JOB_FAILED`(실패 job id 목록, `describeFailedJobs`), 집계 EXECUTION_TIMEOUT(미종결 job id 목록, `describeUnfinishedJobs`). 판정 로직 불변. |

같은 `CHECK_ERROR`라도 이제 detail 텍스트로 전송 실패(HTTP status·URL)와 malformed 응답을 데이터만으로
구분할 수 있다.

## 3. 변경 클래스 — feature 2: result 조회 (main 5)

| 클래스 | 변경 내용 |
|---|---|
| `repository/TerraformResultRepository` | 읽기 2종 추가 — `findByTaskIdAndAttemptNumberAndJobId`(유니크 키 전체 지정 본문 조회, P11에서만 MEDIUMTEXT를 지불) + `findMetadataByTaskId`(`@Query` 메타 투영, P5용). 둘 다 `uq_terraform_result_attempt_job` 인덱스 커버. |
| `dto/pipeline/TaskAttemptView` | 필드 2개 추가 — `failure_detail`(feature 1), `terraform_results[]`(feature 2, `TerraformResultSummary` 목록; TERRAFORM_JOB이 아니거나 기록 없으면 빈 배열). `from(attempt, check)` → `from(attempt, check, terraformResults)`. `@Builder` 전환. |
| `service/query/PipelineQueryService` | `TerraformResultRepository` 주입. `attemptViews`가 메타 투영을 attempt_number로 접어 병합(`terraformResultSummaries`). `terraformResult(pipelineId, taskId, attemptNumber, jobId)` 신설 — 소유권 체인 검증(pipeline 존재 → task 소속) 후 행 조회, 부재 시 404. |
| `controller/PipelineController` | 엔드포인트 추가 — `GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/result` → `TerraformResultDetail`. |
| `exception/OrchestrationErrorCode` | `TERRAFORM_RESULT_NOT_FOUND` 값 추가. |

## 4. 변경 테스트 (test 2)

| 클래스 | 변경 내용 |
|---|---|
| `dto/pipeline/DtoSnakeCaseSerializationTest` | attempt 직렬화 테스트에 `failure_detail`·`terraform_results` 와이어 키 검증 확장 + `terraformResultDetailSerializesSnakeCase` 신규. |
| `service/PipelineExecutionTest` | 기존 실패 경로 2건에 failure_detail 단언 추가 — dispatch 예외("503") 영속, malformed 응답 prefix 구분. |

## 5. 계약 변화 요약

- **스키마**: `task_attempt.failure_detail` VARCHAR(512) 추가 (JPA ddl-auto, additive — 기존 행 null, 열화 없음).
- **와이어**: `TaskAttemptView` +2 필드(additive, snake_case 회귀 테스트로 고정); 신규 GET 엔드포인트 1개; 신규 404 코드 1개.
- **상태 계약(P11)**: 행 부재 = 404 `ORCHESTRATION_TERRAFORM_RESULT_NOT_FOUND` ≠ 포인터 행 = `content: null` 200 — 운영자 안내가 다른 두 상태를 구분한다.
- **렌더링 주의**: `content`는 원시 terraform 출력이다 — ANSI escape 포함 가능, `<pre>` + escape 필수. backend는 정규화하지 않는다(owner 결정). 상세는 설계 문서 §4.6.

## 6. 검증·리뷰

- `mvn test` 178건 전부 통과, `recurring-check.sh` PASS, pr-check(compile/test/package) PASS.
- codex(gpt-5.5, xhigh) 2라운드: R1 무 P0/P1(P2: dto-builder), R2 수정 검증 후 무 P0/P1/P2 — MERGE-READY.
- recurring-review 에이전트: charter 전 항목 PASS. FLAG였던 dto-builder(promoted 패턴 3차 재발) 3건은
  `@Builder` + named `from()`으로 반영, `<p>` javadoc 1건 제거. ledger R9 기록.

## 7. 알려진 한계

- **RUNNING 중 로그는 없다** — postCheck는 attempt 종결 turn에만 기록한다(§4.5 커버리지 한계).
  실행 중 스트리밍이 요구되면 InfraManager result API 실시간 프록시라는 별도 결정이 필요하다.
- 본문 없는 행(포인터)·행 없는 job이 존재한다 — UI는 attempt의 `response` job id 목록과 대조해
  "본문 없음(result_path 참조)" / "기록 없음(attempt errorCode 참조)"을 구분 표기한다.
