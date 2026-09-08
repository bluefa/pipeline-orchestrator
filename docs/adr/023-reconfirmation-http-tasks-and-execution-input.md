# ADR-023: CSP 공통 Task와 재확정·연결 테스트 실행

## 상태와 범위

**2026-09-09 — 사용자 교정을 반영한 백엔드 내부 구현·독립 코드 리뷰 완료. API 호출 수와 Task 수를 구분하며, 운영 연동 범위는 하단에 별도 기록한다.**

ADR-016의 도메인과 ADR-021의 실행 결과 계약을 확장한다. 순차 Task 체인, 대상당 활성 파이프라인
하나, claim/lease/token에 의한 쓰기 보호, 종단 상태 부활 금지는 유지한다.

사용자가 확정한 요구사항:

- **추천 정보 기반 확정정보 입력은 Task 하나**다. 내부에서 recommendation GET → 등록 POST를 수행한다.
- 확정정보 삭제·추천 기반 입력·TestConnection은 **CSP 공통 Task**다. CUSTOM에서 각각 단독 선택할 수 있다.
- 기존 CSP 전용 Task는 대상 CSP와 일치해야 한다. 공통 Task의 내부 API 경로는 필요에 따라 CSP별로 다르다.
- RECONFIRM은 CSP별 인프라 삭제 → 공통 확정정보 삭제 → 공통 추천 기반 입력 순서다.
- 업무 type별 진행 현황을 조회한다. HTTP 응답 원문과 비동기 실행의 실제 종결을 구분한다.

이전 초안의 별도 추천 Task, 신규 Task의 CUSTOM 금지, pipeline 단위 입력 의존성은 폐기한다.
별도 `terraform-apply-approval-gate.md`의 승인 게이트 제안은 이번 범위가 아니다. main에 반영된 실패/취소 파이프라인 재시작은 유지하며, 신규 공통 Task와의 결합은 아래 재시작 절을 따른다.
프런트엔드 저장소는 `/Users/study/pii-agent-demo`다. 이번 우선 구현은 백엔드이며 화면 활성화 조건도 명시한다.

### 확인한 현재 동작

| 표면 | 확인한 동작 |
|---|---|
| Admin 확정정보 입력 | 추천 GET → 편집 가능한 JSON 초안 → 등록 POST. 기존 확정정보가 있으면 편집기 잠금 |
| Admin 확정정보 삭제 | CSP별 DELETE 호출 |
| Admin 및 상세 Step5 연결 테스트 | async POST → latest_version GET, 4초 폴링 |
| Step5 업무 완료 확인 | 테스트 성공 후 completion-status GET, 사용자가 acknowledgment PUT |
| 변경 전 백엔드 | INSTALL/DELETE/CUSTOM, Terraform/Condition executor, type 없는 latest/history |

프런트엔드 근거: `app/admin/pipelines/ops/target-sources/[targetSourceId]/_components/tabs/confirm/`
의 `ConfirmEditorModal.tsx`·`ConfirmDeleteModal.tsx`·`panes.tsx`, 상위 `tabs/TcTab.tsx`,
`app/target-sources/[targetSourceId]/_components/layout/ConnectionTestCard.tsx`,
`app/hooks/useTestConnectionPolling.ts`·`useTcCompletionStatus.ts`, `lib/bff/http.ts`,
`docs/swagger/install-v1.yaml`, `docs/adr/006-integration-confirmation-approval-redesign.md` D-007.

## 결정 1. 업무 유형과 공통 Task 정의

| 축 | 예시 | 의미 |
|---|---|---|
| PipelineType | INSTALL / DELETE / RECONFIRM / TEST_CONNECTION / CUSTOM | 업무 분류 |
| RecipeDefinition | AWS_RECONFIRM_V1 | 버전이 고정된 Task 구성 |
| TaskDefinition | CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1 | 운영자가 선택하는 작업 단위 |
| TaskType mechanism | HTTP_REQUEST / TEST_CONNECTION_JOB | 실행·완료 판정 방식 |
| Task provider scope | CSP_SPECIFIC / ALL_CSP | 대상 CSP에서 선택 가능한가 |

별도 RecipeType이나 mechanism enum은 만들지 않는다. PipelineType만 두 값 추가한다.
신규 공통 정의는 아래 **3개**이며 CSP별로 복제하지 않는다.

| TaskDefinition | 내부 동작 | mechanism | 범위 |
|---|---|---|---|
| DELETE_CONFIRMED_RESOURCES_V1 | 확정정보 DELETE | HTTP_REQUEST | ALL_CSP |
| CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1 | 추천 GET → 저장 → 등록 POST | HTTP_REQUEST | ALL_CSP |
| TEST_CONNECTION_V1 | 시작/실행 회수 → 해당 실행 폴링 | TEST_CONNECTION_JOB | ALL_CSP |

공통 Task는 Terraform slot을 소비하지 않지만 worker 수와 runningPipelineCap을 적용받는다.
기존 Terraform Task는 CSP_SPECIFIC이다. CloudProvider에 COMMON 같은 가짜 CSP를 추가하지 않는다.
TaskDefinition은 명시적 scope와 nullable provider를 갖고, ALL_CSP의 provider는 null이다.
`supportsProvider(provider)`가 catalog 필터·Recipe 검증·CUSTOM 생성 검증의 공통 규칙이다.
CSP_SPECIFIC에는 provider가 필수이고 ALL_CSP에는 provider를 지정하지 않도록 정의 생성 시 검증한다.

공통 executor는 Task가 속한 Pipeline의 생성 시 고정된 cloud_provider를 짧은 DB read로 읽어
실제 API를 선택한다. TaskDefinition의 nullable provider를 호출 대상으로 사용하지 않는다.

## 결정 2. CUSTOM과 CSP별 Recipe

CUSTOM 선택 목록은 **해당 CSP 전용 Task + 모든 공통 Task**다. 공통 Task는 `custom_allowed=true`이며,
입력 Task만 넣거나 같은 공통 정의를 여러 번 넣어도 된다. Task마다 입력·요청 키를 독립적으로 소유한다.
서버는 존재하는 정의, CSP 적합성, 옵션, 실제 실행 가용성을 검증한다. 선행 추천 Task를 요구하지 않는다.

`execution_available`은 선택 범위와 구분한다. 실제 API 계약이 아직 연결되지 않은 공통 Task는
전체 catalog에 표시하되 실행 불가임을 알리고, catalog/CUSTOM 생성 모두
`OPERATION_UNAVAILABLE` code를 가진 typed 400으로 거절한다.
이는 공통 Task의 CUSTOM 금지가 아니라 동일한 배포 가용성 검증이다. 계약을 갖추면 CUSTOM에서도 실행한다.

```text
AWS_RECONFIRM_V1
  AWS_BDC_SERVICE_LEVEL_DESTROY_V1
  AWS_BDC_COMMON_DESTROY_V1
  AWS_SERVICE_DESTROY_V1
  DELETE_CONFIRMED_RESOURCES_V1
  CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1   ← 내부 GET + POST, Task는 하나

CUSTOM [CONFIRM_RESOURCES_FROM_RECOMMENDATION_V1]  ← 단독 실행 가능
CUSTOM [TEST_CONNECTION_V1]                      ← 단독 실행 가능
```

GCP는 BDC → Service, Azure는 BDC, IDC는 BDP → CX의 기존 DELETE 순서 뒤 공통 Task 두 개를 연결한다.
AWS/IDC 삭제 순서의 upstream 보장은 활성화 전에 확인한다. 별도 DELETE 자식 파이프라인은 만들지 않는다.
각 CSP의 TEST_CONNECTION Recipe는 같은 공통 TEST_CONNECTION_V1 한 개를 사용한다.
기존 Recipe는 변경하지 않고 신규 V1을 추가한다. 현재 catalog의 `(provider,type)` 중복 거부를 유지한다.

추천 기반 입력 Task는 자동 삭제를 하지 않는다. 기존 확정정보가 있어 등록이 거절되면 업무 실패다.
인프라/확정정보 삭제가 필요한 업무 순서는 RECONFIRM Recipe가 소유한다. 최초 확정에도 입력 Task 자체는
사용할 수 있지만 최초 확정용 별도 PipelineType은 이번에 추가하지 않는다.

생성 요청은 기존 필드에 선택적 `apply_nlb_security_group`(기본 false)만 추가한다. catalog 생성에서는
입력 Task에 적용하고 CUSTOM에서는 해당 CustomTaskRequest에 둔다. AWS 입력 Task에서만 true를 허용한다.
다른 Task/CSP의 true는 typed 400이다. 사용자에게 승인 버전 등의 임의 필수 필드를 새로 요구하지 않는다.

## 결정 3. Task 내부 진행과 입력 보존

Task의 API 호출 두 개를 한 worker turn 안에서 연속 수행할 필요는 없다. **Task는 하나이고 실행 turn은 나뉜다.**
외부 호출 사이에 추천값을 내구성 있게 저장해 프로세스 재시작·등록 재시도에도 동일 본문을 사용한다.

```text
READY        execute: 추천 GET
               ↓ guarded write-back
IN_PROGRESS  추천 원문·검증된 맥락 저장, 같은 Task 유지 (후속 Task는 BLOCKED)
             check: 저장된 추천값으로 POST
               ↓ guarded write-back
DONE         등록 완료, 그제야 후속 Task를 READY로 승격
```

등록 오류로 재시도 READY가 되면 execute가 이미 저장된 입력을 발견하고 **POST부터 재개**한다.
추천 GET은 다시 하지 않는다. 추천 GET 실패·저장 전 크래시만 GET 재실행을 허용한다.
GET 저장 후 성공한 recommendation_body는 write-once이고 최초 정상 커밋된 값이 이번 Task의 입력이다.

`task_confirmation_input`은 입력 Task 생성과 같은 트랜잭션에서 task_id·request_key·AWS 옵션을 저장한다.
추천 본문은 처음에는 null이다. 추천 성공 응답을 채택하는 guarded write-back에서 원문·digest·시각,
source_attempt_number·GET HTTP metadata·검증된 승인/대상 세대 맥락을 한 번 채운다.
입력 저장과 Task IN_PROGRESS는 원자적이다. 추천 입력의 DB 쓰기는 executor의 외부 호출 단계에서 하지 않는다.

승인/세대 맥락의 실제 upstream 필드·인증·조건부 쓰기 방식은 미확정이다. client boundary의 typed 계약으로
표현하되 opaque recommendation JSON에 필드를 발명해 넣지 않는다. 실제 adapter가 추천 원문과 함께
검증한 맥락을 반환하고 등록에 그대로 사용해야 한다. 이를 입증하기 전에는 실제 호출을 활성화하지 않는다.

입력 행 자체가 없으면 EXECUTION_INPUT_MISSING, 본문/digest/맥락이 손상되면 EXECUTION_INPUT_INVALID
종결 실패이며 쓰기 API를 호출하지 않는다. 아직 조회하지 않은 정상 행(null body)과 행 유실은 다르다.
이미 IN_PROGRESS인데 본문이 없다면 새 GET으로 복구하지 않고 입력 오류로 종결한다.
관찰용 task_attempt가 유실된 경우에는 입력이 정상일 때 POST를 재개할 수 있다. guarded write-back에서
현재 `(task_id,fail_count+1)` attempt가 없으면 복구 생성해 응답을 남긴다. 입력 유실과 관찰 유실을 구분한다.

## 결정 4. 실행 결과·실패·외부 경계

확장 전 DispatchResult는 WithResponse/None뿐이고 TaskStateMachine은 instanceof로 응답 유무만 구분했다.
다음 변형과 exhaustive switch를 추가한다.

- `DispatchResult.HttpPrepared`: 추천 GET 성공. 원문과 입력 후보를 저장하고 Task를 IN_PROGRESS로 유지한다.
- `DispatchResult.HttpCompleted`: 삭제 또는 저장된 추천 기반 POST의 동기 완료/실패.
- `TaskProgress.HttpCompleted`: IN_PROGRESS에서 POST를 수행한 완료/실패. StepOutcome에도 대응 값을 추가한다.
- 외부 호출 전 입력 오류는 nullable HTTP exchange를 가진 HttpCompleted 실패 값이다. 실제 응답 없음으로 표시한다.

execute 결과는 기존 StepOutcome.Dispatched를 통해 dispatchPhase=true로 전달하며 attempt를 한 번 연다.
check의 HttpCompleted는 기존 attempt를 사용한다(유실 시만 복구 생성). 추천 GET과 POST는 같은 attempt에
속할 수 있다. 동기 삭제는 READY → IN_PROGRESS → DONE/FAILED/재시도 READY를 한 write-back에서 적용한다.
추천 입력은 GET 완료만으로 DONE이 되지 않는다. 새 상태 enum은 추가하지 않는다.

| 호출 | 성공 판정 | 실패 정책 |
|---|---|---|
| 추천 GET | 200 + 유효한 JSON object + client가 검증한 맥락 | 404 추천 없음은 종결 업무 실패 |
| 확정 DELETE | 현재 UI 계약 200, 빈 본문 가능 | 단순 404를 동일 요청의 성공으로 추정하지 않음 |
| 확정 POST | 현재 UI 계약 201, 빈 본문 가능 | 충돌을 무조건 성공으로 처리하지 않음 |

알려진 업무 거절은 값이다. 연결/timeout/시스템 실패는 controlled call 예외이며 StepRunner 한 경계가
ErrorCode와 HTTP 응답 정보를 값으로 변환한다. 2xx/4xx 전체를 일괄 성공/업무 실패로 해석하지 않는다.
202는 접수이므로 동기 완료로 취급하지 않는다. HTTP 전용 기본값은 retry 간격 5초·maxFailCount 24다
(최대 24번 시도, 대기 합계 115초 + 호출 시간). Task 생성 시 override로 고정한다. TC 폴 기본 4초,
접수 maxFailCount 기본 24를 사용한다. TC poll 전송 오류는 별도 짧은 실패 횟수로 종결하지 않고 해당
Task의 고정 execution deadline까지 재관찰한다. TC 실행 제한은
`pipeline.test-connection.execution-timeout`으로 설정하며 기본 50분이다. TC 폴 간격과 접수 예산도
같은 `pipeline.test-connection` 설정에서 정하고 생성 시 Task에 고정한다.
기존 Terraform/Condition 기본값은 변경하지 않는다.

| 응답/상황 | ErrorCode | 처리 |
|---|---|---|
| 추천 GET 404 | RECOMMENDATION_NOT_FOUND | 종결 업무 실패 |
| mutation 409/412 | CONFIRMATION_CONFLICT | 종결 업무 실패, 임의 성공 간주 금지 |
| 401/403 | CHECK_ERROR | 재시도하지 않는 controlled 외부 호출 실패 |
| 429/5xx, 연결 장애 | CHECK_ERROR | controlled 외부 호출 실패, HTTP/TC 접수는 예산 내 재시도 |
| per-call timeout | CALL_TIMEOUT | 동일 retry 규칙; TC poll은 동일 attempt로 deadline까지 관찰 |
| HTTP mutation 202, 그 외 예상 밖 status, 성공 schema/encoding 오류 | CHECK_ERROR | 계약 위반 종결 실패 |
| 성공 본문 크기 초과 | RESPONSE_TOO_LARGE | 종결 실패 |
| TC connection_status=FAIL / deadline 초과 | JOB_FAILED / EXECUTION_TIMEOUT | 종결 실패 |
| provider/필수 실행 맥락 유실 또는 손상 | EXECUTION_INPUT_INVALID 또는 EXECUTION_INPUT_MISSING | 외부 호출 없이 종결 실패 |
| 실행 시 실제 client capability 부재 | OPERATION_UNAVAILABLE | 외부 호출 없이 종결 실패 |

원문의 status/body는 분류와 별도로 보존한다. controlled call 예외는 retryable 여부와 응답을 함께
운반하며 StepRunner가 이를 값으로 바꾼다. TC poll도 401/403 등 non-retryable 계약 오류는 즉시
종결한다. transient poll 오류만 deadline까지 같은 attempt를 유지한다.

외부 boundary는 `InstallationOperationsClient`로 분리한다. 호출 대상은 설치 업무를 소유한 서버 서비스이며
프런트 Next.js API나 Terraform InfraManager API를 대용으로 호출하지 않는다. production delegate는 실제
계약이 없는 동안 capability를 제공하지 않는다. 실제 adapter의 주소·서비스 계정 인증·버전/세대 검증은
contract test로 검증한다. 사용자 SSO 쿠키를 저장하거나 재사용하지 않는다.

새 경계도 apiCallTimeout을 강제하는 공통 bounded 실행기를 사용한다. StepRunner는 timeout 실행기가 아니다.
한 turn에 외부 boundary 호출은 한 번이다. 시작/회수 내부에 여러 요청이 필요하다면 그 전체가 같은
호출 상한 안에 들어가야 한다. 외부 호출은 DB 트랜잭션 밖에서 실행하고 인터럽트는 그대로 전파한다.

## 결정 5. HTTP 원문과 상세 조회

원문은 JSON parse/재직렬화 전에 캡처한 UTF-8 본문 텍스트다. JSON wrapper·pretty print·필드 변환을
하지 않는다. TLS·압축·전체 header·wire bytes는 범위 밖이다. 상한 이내 공백/순서/미지 필드를 보존한다.

- 기존 task_attempt.response TEXT와 Terraform/Condition 계약은 유지한다.
- 신규 nullable `http_response` LONGTEXT와 `http_operation`, `http_status_code`, `response_content_type`,
  `response_received_at`, `response_truncated`, `confirmation_input_id`를 task_attempt에 추가한다.
- 한 attempt의 현재/최종 HTTP 호출을 기록한다. GET 후 POST가 같은 attempt 필드를 덮더라도 성공 GET의
  원문·metadata는 task_confirmation_input에 따로 고정되어 보존된다. GET 실패는 실패 attempt에 남는다.
- POST attempt의 confirmation_input_id로 실제 전송 본문·옵션·성공 GET 원문을 추적한다.
- 실제 빈 응답은 status 존재 + 본문 "", 전송 실패는 status/body null이다. failure_detail은 짧은 설명이다.
- 외부 호출 자체가 없었던 종결 결과(exchange=null)는 같은 attempt의 직전 HTTP 원문·metadata를
  지우지 않는다. 실제로 호출했지만 응답을 받지 못했다면 operation이 있는 exchange에 status/body null을
  기록해 두 경우를 구분한다. 입력 참조와 현재 attempt 확보는 호출 없는 실패에서도 유지한다.
- 크기 기본값은 응답/입력 각각 1 MiB다. UTF-8 경계에서 자르고 truncated=true로 표시한다.
  성공 응답 초과는 RESPONSE_TOO_LARGE 종결 실패로 처리하며 잘린 추천값을 등록하지 않는다.
  오류 응답 초과는 bounded 원문만 보존하되 원래 오류의 retry/terminal 분류를 유지한다.
- metadata 길이도 report 전에 제한한다. 기존 TEXT 컬럼 확장에 의존하지 않고 새 LONGTEXT 컬럼을 추가한다.
  response_truncated는 신규 HTTP capture 전용이며 기존 Condition 절단 여부를 뜻하지 않는다.

Task 상세의 attempts는 새로운 HTTP 본문을 제외한 metadata projection으로 조회한다(큰 본문을 DB에서
읽고 응답에서만 버리지 않는다). 원문은 소유 관계 검증을 거친 별도 endpoint로 제공한다.

```http
GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/http-response
GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}/confirmation-input
```

두 번째 endpoint는 고정된 추천 GET 원문/metadata와 등록 입력을 제공한다. 아직 미조회면 미완료 metadata를
표시하고, 행 없음은 typed 404다. 목록·최근카드·알림에는 본문을 싣지 않는다. 화면 렌더링은 escape한다.

보존 단위는 정상 guarded write-back에서 채택된 결과다. 크래시 전·stale token·취소 우선으로 버린
결과까지 모두 감사 기록하는 것은 범위 밖이다. CANCELLED나 attempt 부재는 외부 효과 없음의 증거가 아니다.
화면은 커밋된 삭제 단계와 **확인 불가인 외부 효과**를 구분한다. 저장 실패는 report 전체를 롤백한다.

## 결정 6. TestConnection은 실행 정체성과 업무 확인을 구분한다

공통 Task 하나가 async POST 접수 후 자신의 실행만 폴링한다. 접수 success=true는 Task DONE이 아니다.
PENDING/RUNNING은 대기, 같은 test_connection_version의 SUCCESS/FAIL이 실제 실행 종결이다.
completion-status는 논리 DB 정책을 반영한 별도 업무 상태이며 acknowledgment는 사용자가 수행한다.
Task가 이를 자동 호출하거나 성공 후 정책 변경 때문에 종단 상태를 뒤집지 않는다.

`task_external_execution`은 Task 생성 시 request_key를 저장한다. deadline_at 초기화는 아래의 최초 READY 규칙을 따른다. 키는 task별로 다르고
모든 자동 retry에서 유지된다. 외부 version은 요청 키와의 상관관계가 증명된 시작/회수 응답에서 한 번
고정한다. latest에서 처음 본 version을 바인딩하지 않는다. version별 조회를 우선하며 latest 검증은
관측 종결까지 다른 진입점도 실행을 덮어쓰지 못하는 보장이 있을 때만 허용한다.

deadline_at은 각 Task가 **최초 READY가 될 때** 한 번 고정한다. 첫 Task는 생성 트랜잭션에서
예정 시작 시각(pipeline.next_due_at) + effectiveExecutionTimeout, 후속 Task는 최초 승격 report에서
ready 시각 + effectiveExecutionTimeout을 저장한다. BLOCKED인 TC의 deadline은 아직 null이다.
Task 준비와 deadline 초기화는 같은 트랜잭션이며, 기존 unblock 경로도 같은 초기화 함수를 사용한다.
CUSTOM의 앞 Task 실행 시간을 후속 TC의 허용 시간에서 차감하지 않는다. READY 이후 큐 대기는 포함한다.
READY 재dispatch와 IN_PROGRESS poll 전에 모두 검사하고, retry가 readyAt을 바꿔도 deadline은 연장하지 않는다.
READY/IN_PROGRESS에서 deadline이 유실되면 EXECUTION_INPUT_INVALID이며 새 마감으로 복구하지 않는다.
시작/폴 응답이 늦게 도착한 경우도 응답 수신 직후 deadline을 검사하여 EXECUTION_TIMEOUT으로 종결하고
bounded 응답은 보존한다. 마감 전에 수신한 결과는 이후 짧은 write-back 지연만으로 timeout으로 바꾸지 않는다.

폴 전송 오류는 단일 StepRunner 예외 경계에서 값으로 번역한 뒤
`TaskType.handleCallFailure(task, callFailure)`에 전달한다. 기본 구현은 기존 CallFailure를 그대로
사용하고 TestConnection 구현만 retryable poll 오류를 전용 poll 결과로 바꾼다. Task 이름 비교를 하지 않는다.
전용 결과는 generic CallFailure→READY로 보내지 않고 같은 attempt에서 다음 poll을 예약한다.
오류 횟수는 관찰(task_check/진단)에만 기록하며 종결 판정에 읽지 않는다. 고정 deadline이 대기를 제한한다.
접수 오류만 기존 attempt 재시도 예산을 사용하되 같은 요청을 회수한다. FAIL/timeout은 종결 실패다.
상관관계가 맞지 않으면 EXECUTION_CORRELATION_FAILED다. 외부 실행 행 유실도 새 테스트 생성으로 복구하지 않는다.

진단용 test_connection_result는 해당 실행의 최신/최종 원문·metadata를 저장한다. 정상 본문을 통신
실패로 지우지 않고 별도 마지막 오류 정보를 둔다. terminal 진단은 pending/오류로 덮어쓰지 않는다.
판정은 typed 현재 poll과 도메인 deadline을 사용하고 진단 테이블을 읽지 않는다. 모든 쓰기는 guarded report다.
IN_PROGRESS에서 호출 전 deadline·맥락·capability 검사로 종결하는 경우도 실행 행이 존재하면 진단의
마지막 오류를 함께 갱신한다. 실행 행이 유실됐다면 재생성하지 않고 Task/attempt에 유실 실패를 남긴다.

```http
GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}/test-connection-result
```

이 endpoint도 pipeline/Task 소유 관계를 검증한다. 실행 행이 없으면 typed 404이며, 실행 행이 있으나
아직 폴링 전이면 요청 키·바인딩된 version·deadline 등 실행 metadata만 200으로 반환한다.
관측 이후에는 정상 응답과 마지막 오류 응답을 각각 제공한다. 대용량 진단 본문은 일반 Task 상세에 포함하지 않는다.
정상 관측이 없으면 정상 metadata는 null이다. 오류의 `last_error_operation`은 실제 exchange의 operation을
저장하며, 호출 없는 사전검사 실패이면 null이다. 오류 metadata는 이 operation이 있을 때만 제공한다.
따라서 사전검사 오류 코드·시각은 존재해도 errorResponse는 null일 수 있다. 실제 poll 무응답은
operation이 존재하고 status/body가 null이므로 이 경우와 구별된다.

실행 결과 어휘도 명시한다. 접수/회수는 `DispatchResult.TestConnectionStarted`로 반환하며
`TestConnectionStartResponse(requestKey, executionVersion, HttpExchange)`를 운반한다.
폴은 `TaskProgress.TestConnectionPolled` → `StepOutcome.TestConnectionPolled`이고 내부
`TestConnectionObservation`은 `Observed(TestConnectionPollResponse)` 또는
`CallFailed(StepOutcome.CallFailure)`, `Failed(HttpTaskResult)`다. 마지막 변형은 응답 상관관계나 크기 등의
업무 실패를 진단 기록에 전달한다. PollResponse는 executionVersion/status/HttpExchange를 담는다.
외부 호출 전 종결 오류는 execute에서는 `DispatchResult.HttpCompleted`, check에서는 진단 동기화를 위해
`TestConnectionObservation.Failed`로 전달하며 HTTP exchange는 null이다. 원시 WithResponse를 엔진이
파싱하거나 run 단계에서 실행 version/진단을 직접 저장하지 않는다.

현재 UI 명세에는 request key 회수 계약이 없고 시작 응답은 success만 있다. 실제 실행 활성화에는 이
계약이 필요하다. 사용자 새 실행도 이전 실행 종료 또는 upstream의 대상별 중복 차단이 보장돼야 한다.

## 결정 7. JPA 데이터 모델

| 테이블 | 변경 | 역할 |
|---|---|---|
| pipeline | type/recipe 재사용, target+type+created_at+id, type+created_at+id, recipe+created_at+id 인덱스 | type별 최신/페이지 조회 |
| task | 상태 유지, 공통 정의/operation 사용 | Task 단위 실행 |
| task_attempt | 기존 response 유지, 신규 HTTP LONGTEXT·metadata·입력 참조 | 현재/최종 호출 응답 |
| task_confirmation_input (신규) | id, task_id UNIQUE, request_key UNIQUE, apply_nlb_security_group, recommendation_body LONGTEXT, body_digest, 승인/세대 맥락, source_attempt_number, GET metadata, captured_at | 입력 Task별 write-once 추천값 |
| task_external_execution (신규) | id, task_id UNIQUE, request_key UNIQUE, external_execution_version, created_at, deadline_at | TC 실행 정체성·고정 마감 |
| test_connection_result (신규) | id, external_execution_id UNIQUE, last_connection_status, last_response LONGTEXT/metadata, last_observed_at, terminal_observed_at, 별도 마지막 오류 body/metadata 및 nullable last_error_operation | TC 최신/최종 진단 |

신규 테이블은 총 3개이며 HTTP 단계에서는 task_confirmation_input 하나다. 중복 공통 Task도
각각 다른 task_id를 가져 입력/키가 겹치지 않는다. 삭제 멱등 키는 `confirmation-delete:v1:task:{task_id}`라는 버전 고정 파생식으로 생성한다.
이 파생식은 DELETE_CONFIRMED_RESOURCES_V1의 계약으로 유지하고 배포 때 바꾸지 않는다.
입력 키도 `confirmation-input:v1:task:{task_id}`로 생성해 저장하고, 해당 V1 Task 정의의 불변 계약으로
고정한다. 재개 때 저장 키와 이 파생식의 일치를 검사하므로 새 배포에서 기존 V1 파생식을 바꾸지 않는다.
TC 키는 `test-connection:v1:task:{task_id}`이며 같은 버전 고정 규칙을 따른다.
`pipelineId + TaskDefinition.name()`은 CUSTOM의 같은 정의 반복을 구분하지 못하므로 사용하지 않는다.

상태 결정에 필요한 입력/실행 행은 관찰 보존 정책으로 지우지 않는다. 종단 실행 전체를 정리할 때 함께
제거한다. 관계는 pipeline 1:N task, 입력/TC 각각 task 1:0..1, task 1:N attempt다.
모든 schema는 JPA annotation으로 선언한다. 수동 migration/Flyway를 만들지 않는다. 실제 MySQL의
컬럼·인덱스·길이·packet 제한은 별도 검증하며 H2만으로 검증했다고 주장하지 않는다.

## 결정 8. 유형별 조회와 업무 완료

```http
GET /api/v1/target-sources/{targetSourceId}/pipelines/latest?type=RECONFIRM
GET /api/v1/target-sources/{targetSourceId}/pipelines?type=INSTALL
GET /api/v1/pipelines?type=RECONFIRM&recipeDefinition=AWS_RECONFIRM_V1
```

필터를 DB에서 적용한 후 created_at DESC,id DESC로 최신/페이지를 고른다. latest는 종단 실행도
포함하고 없음은 기존 204다. 잘못된 enum은 400, type+recipe는 AND, recipeDefinition은 과거 이름도
조회 가능한 exact 문자열이다. 필터 미지정 동작과 snake_case는 유지한다. active_target은 type과 무관하게 유일하다.

RECONFIRM DONE은 등록 완료이며 인프라 재설치 완료가 아니다. TC DONE은 실행 성공이며 acknowledgment
완료가 아니다. CUSTOM에서 공통 입력 Task를 수행해도 pipeline type은 CUSTOM이다. type은 Task 종류로
역추론하지 않는다. Step3 업무 완료는 latest type만으로 판단하지 않고 승인/설정 버전과 pipelineId를 연결한다.
전체/완료 Task 수는 호출 수나 시간 기반 진행률이 아니다. 추천 GET까지 끝났어도 입력 Task 완료 수는 0이다.

업무 소유 서비스가 terminal 상태를 멱등·재전달 가능하게 소비한다. ADR-006 D-007의 최종 업무 실패 시
승인 완료 정보 제거 정책을 유지하고 일시 retry/취소와 구분한다. Slack 알림을 업무 콜백으로 사용하지 않는다.
삭제 후 실패/취소 시 자동 복구하지 않으며 커밋된 결과와 확인 불가 효과를 구분한다.

## 결정 9. 실제 외부 연동의 활성화

공통 Task의 사용 범위는 ALL_CSP다. 실제 capability는 검증된 provider/operation별로 제공한다.
`pipeline.installation.enabled-operations`는 신규 operation의 명시적 활성 집합이며 기본 빈 집합이다.
설정과 client capability의 교집합만 catalog/CUSTOM 생성에서 실행할 수 있다. RECONFIRM은 두 공통
operation과 해당 CSP 삭제 계약이 모두 준비돼야 한다. 삭제 순서와 삭제 후 추천 가능 여부의 보장은
`InstallationOperationsClient.supportsReconfirmation(provider)`로 별도 선언하며 기본값은 false다.
공통 Task를 개별 CUSTOM으로 구성하는 데에는 이 RECONFIRM 전용 capability를 요구하지 않는다.
enabled-operations는 생성 검증에만 사용하며 설정 변경으로 실행 중 Recipe를 재조립하거나 중단하지 않는다.
실행 중 실제 adapter capability가 사라지면 OPERATION_UNAVAILABLE 값으로 종결한다. 저장된 provider가
null이면 EXECUTION_INPUT_INVALID로 종결하며, raw 예외를 던져 재claim 루프를 만들지 않는다.

| 확인할 계약 | 없으면 |
|---|---|
| 서비스 주소·서버 계정 인증·응답/맥락 검증·timeout | 해당 공통 operation 실제 실행 비활성 |
| task별 요청 키의 멱등성 및 지연 DELETE/POST가 다음 세대 정보를 훼손하지 않는 보호 | 해당 mutation 비활성 |
| Admin 직접 쓰기 등 모든 진입점의 동일 세대 보호 또는 동등한 직렬화 | 해당 mutation 비활성 |
| 삭제 후 추천 조회 가능, 필요한 참조 보존, CSP 삭제 순서 | 해당 CSP RECONFIRM 비활성 |
| TC 요청 키로 실행 회수, version 검증, 중복 실행 제한 | TC 실제 실행 비활성 |
| 승인 소유 서비스의 성공/최종실패/취소 처리 및 재전달 | Step3 자동 연동 비활성 |

claim token은 DB 쓰기를 막을 뿐 이미 보낸 외부 요청을 취소하지 않는다. timeout은 미실행 증거가 아니다.
실제 client adapter를 구현하지 않고 endpoint/header를 발명해 활성화하지 않는다. 내부 executor·저장·fake
테스트는 먼저 구현할 수 있다. 존재하는 Recipe/Task의 operation 또는 RECONFIRM 전용 capability가
비활성이면 create/preview 모두 OPERATION_UNAVAILABLE typed 400이다. 실제로 지원하는 Recipe가 없는
type/provider 조합은 기존 UnsupportedRecipeException의 UNSUPPORTED_RECIPE typed 400을 유지한다.

프런트엔드 후속: provider_scope 기반 공통 Task 노출, execution_available 표시, 새 type union/label,
latest/history/global 필터 전달, HTTP/입력/TC 상세 표시, CUSTOM 반복 항목의 개별 식별과 입력 옵션,
Step3/Step5 업무 버전 연결. 백엔드 구현만으로
화면까지 적용되었다고 간주하지 않는다.

## 기존 파이프라인 재시작과의 결합

main의 재시작 기능은 최신 FAILED/CANCELLED 실행에서 선택한 suffix로 **새 Pipeline과 새 Task**를 만든다.
이 정책과 원본 계보(`origin_pipeline_id`, `origin_task_id`)를 유지한다. 같은 Task의 자동 재시도와는 다르다.
provider는 원본 저장값을 우선하고, 유실된 경우 main의 기존 정책대로 대상 provider를 다시 조회한 뒤 새 실행의
CSP 범위·옵션·capability를 검증한다. 기존 Task의 실행 문맥 유실을 자동 복구하는 경로는 아니다.

- 추천 기반 입력의 재시작은 원본 입력행의 `apply_nlb_security_group` 옵션만 승계한다. 원문·digest·승인/대상
  세대·출처 attempt는 복사하지 않는다. 새 Task의 새 요청 키로 recommendation GET부터 수행한다.
  원본 입력행이 유실되어 옵션을 알 수 없으면 preview/restart 모두 CONFIRMATION_INPUT_NOT_FOUND typed 404로 거절한다.
- TC 재시작은 새 Task별 요청 키와 새 실행행을 생성한다. 원본 외부 version·deadline·진단을 복사하지 않는다.
  새 첫 Task는 새 Pipeline의 예정 시작을 기준으로, 후속 Task는 실제 최초 READY에서 자기 deadline을 정한다.
- 확정정보 삭제도 새 Task의 요청 키를 사용한다. DONE prefix는 기존 정책대로 건너뛰고, 명시적 앞 단계 선택은
  그 단계부터 새 실행하는 기존 동작을 유지한다.
- preview와 restart의 공통 검증에서 현재 CSP 적용 범위·operation 활성 설정과 실제 capability를 재확인한다.
  RECONFIRM 원본의 재시작은 해당 CSP 삭제 순서 계약도 다시 확인한다. 옵션은 AWS 입력 Task에만 적용한다.
- 원본 실행의 외부 호출은 취소·timeout 뒤에도 계속 실행 중일 수 있다. 새 요청 키는 원본 요청과의 중복을
  제거하지 않는다. 신규 공통 Task가 포함된 미리보기는 이 점과 새 입력/실행 생성 사실을 알린다.
  이미 보낸 mutation이 새 세대를 훼손하지 않는 보호 및 TC 중복 실행 제한은 기존 운영 adapter 활성 조건이며,
  재시작을 추가했다고 안전이 증명된 것으로 간주하지 않는다.

## 구현 순서와 검증

1. 신규 PipelineType과 type/recipe 조회 필터·인덱스.
2. 공통 scope·CUSTOM 검증·HTTP executor/결과·Task별 입력·원문·가용성 gate를 함께 추가.
   신규 operation/definition만 먼저 넣어 TaskTypeRegistry의 부팅 검증을 깨지 않는다.
3. TC 실행 정체성·최초 READY 기준 고정 deadline·폴 실패·진단 및 공통 정의.
4. 검증된 실제 adapter·업무 연동·프런트 적용 후 활성화.

필수 행동 테스트:

- 모든 CSP에서 공통 입력 Task만 CUSTOM 구성, 기존 전용 Task의 CSP 불일치 거절.
- 같은 공통 정의를 반복한 CUSTOM에서 서로 다른 입력/key, catalog와 CUSTOM 동일 가용성 검증.
- 추천 GET 후 같은 Task IN_PROGRESS/후속 BLOCKED, POST 성공 후 DONE/후속 READY.
- GET→POST 원문 일치, POST retry는 GET 미호출, GET/POST 양쪽의 stale/cancel 결과 폐기와 저장 롤백.
- DELETE 503 재시도에서 같은 키 사용, per-call timeout의 operation 보존과 status/body null.
- 입력 행 유실·digest 손상은 POST 금지, attempt 유실은 정상 입력으로 복구 및 응답 보존.
- 두 호출 원문/metadata 추적, 빈 응답과 전송 실패 구분, 4xx/5xx 및 UTF-8 상한 처리.
- TC 접수≠성공, version 불일치, poll 오류에서 새 테스트 미생성, deadline 비연장, 진단 유실 독립성.
- CUSTOM의 장시간 선행 Task와 반복 TC에서 각 최초 READY부터 독립된 timeout 보장.
- type filter-before-page/latest, tie-break, 빈 latest 204, 잘못된 enum 400, cross-type 활성 하나.
- 모든 terminal 경로 active_target 해제, 기존 Terraform/Condition 회귀, 상세에 대용량 HTTP 본문 미조회.
- RECONFIRM 생성 성공의 전체 체인·입력 행·AWS 옵션, 다른 CSP 옵션 거절, 기존 attempt의 http=null.

완료 전에 `mvn test`를 수행한다. 실제 upstream 계약/MySQL 검증은 테스트 fake/H2로 대체했다고 표시하지 않는다.

## 구현 현황 — 2026-09-09

백엔드 내부 구현은 이 checkout에 반영했다. Fable 5.1 설계 리뷰 후 구현했고, 코드 리뷰의 지적을 수정한 뒤 main 재시작 통합 전 Code Round 4에서 **P0 0 / P1 0 / P2 0**으로 통과했다. 이후 main 재시작 기능과의 결합 및 회귀 검증을 추가했다. 전체 과정과 실제 모델 증빙은 [리뷰 기록](../reviews/adr-023-fable-5-1-review.md)에 보존한다.

| 반영한 범위 | 구현 |
|---|---|
| 공통 Task 구성 | ALL_CSP 3개 정의, CSP별 기존 Terraform 정의 유지, CUSTOM 단독·반복 구성 |
| 업무 Recipe | RECONFIRM / TEST_CONNECTION, 생성 시 operation 가용성 검사 |
| 추천 기반 입력 | 한 Task 내부 GET 캡처 → 고정 본문 POST, POST 재시도 시 추천 재조회 없음 |
| 실행 데이터 | JPA 신규 3개 테이블과 HTTP 원문·metadata 컬럼, Task별 요청 키 |
| TestConnection | 요청과 version 연결, 최초 READY deadline, 동일 attempt 폴 재시도, 정상/오류 진단 분리 |
| 조회 API | type/exact recipe 필터와 인덱스, HTTP/입력/연결 테스트 결과 상세 |
| 기존 재시작 통합 | 원본 계보 유지, NLB 옵션 승계, 새 입력/키/TC deadline, 현재 capability 재검증 |

main 재시작 통합 전 `mvn test`: **354건, 실패 0, 오류 0, 건너뜀 0**.
main 재시작 통합 후 최종 `mvn test`: **372건, 실패 0, 오류 0, 건너뜀 0**
(2026-09-09 08:26 KST). 기존 Terraform/Condition 및 재시작 회귀와 신규 결합 5건을 포함한다.
HTML은 데스크톱 1440px·모바일 390px에서 흐름·필터·본문 예시·링크·넘침·JavaScript 오류를 확인했다.

실제 운영 adapter는 아직 연결하지 않았다. 기본 `UnavailableInstallationOperationsClient`와 빈
enabled-operations 설정은 실제 호출을 열지 않는다. 서버 계약 검증, 실제 MySQL에서의 schema 검증,
업무 완료 연동과 `/Users/study/pii-agent-demo` 프런트엔드 반영은 남아 있다.
이 테스트 결과를 운영 API 또는 실제 MySQL 검증으로 해석하지 않는다.

## 연결 문서

- [ADR-016 도메인 모델](016-install-delete-pipeline-domain-model.md)
- [ADR-021 실행 모델](021-pipeline-execution-model.md)
- [ADR-022 종단 알림](022-terminal-state-notification.md)
- [변경 구조 HTML 설명](../adr-023-explained.html)
- [Fable 5.1 리뷰 기록](../reviews/adr-023-fable-5-1-review.md)
