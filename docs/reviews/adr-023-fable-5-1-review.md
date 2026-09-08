# ADR-023 Fable 5.1 독립 리뷰 기록

> 최신 main 통합 이후 PR #55의 판정과 386건 검증은 [별도 PR 리뷰 기록](adr-023-pr-fable-5-1-review.md)을 참조한다. 아래는 통합 전 코드의 역사 기록이다.

## main 통합 전 코드 리뷰 상태 — 2026-09-09

**최종 Code Round 4 통과: P0 0건 / P1 0건 / P2 0건.**
이전 코드 라운드의 지적을 수정했고 마지막 P2-F와 직접 회귀도 Fable 5.1이 해결 확인했다.
[최종 Code Round 4 판정·해결 근거·모델 증거](#code-round-4)를 참조한다.

- 전체 `mvn test` **354건 PASS**: 실패 0 / 오류 0 / 스킵 0.
  2026-09-09 01:02:21 KST 완료, 12.676초. HTTP 58건 / TC 41건 포함.
- 검증 원본: `/private/tmp/adr023-final-tests-round4.log`.
- 최신 검토는 01:03:17 KST 고정한 코드 사본의 P2-F 수정과 직접 회귀를 대상으로 했다.
  앞선 Code Round 2의 전체 통합 검토, Round 3의 P2-A~E 확인을 합쳐 최종 판정을 이해한다.
- 실제 `modelUsage.claude-fable-5-1` 증거를 라운드별로 기록했다. 초기 실패·중단 실행은
  완료 리뷰로 집계하지 않았으며 각 고정 사본의 검증 범위를 구분했다.
- 운영 adapter·실제 upstream 계약·실제 MySQL 검증·프런트 연동은 남아 있다.
  H2/fake 기반 검증을 이 운영 연동의 완료로 표시하지 않는다.
- 아래 초기 설계 라운드는 역사 기록이며 최신 코드 판정과 구분한다.

## 초기 설계 리뷰의 역사적 메타데이터

> **Round 1·2는 구설계 / Superseded — Round 3부터 사용자 교정 반영 설계를 검토한다.**
> 사용자는 추천 GET과 등록 POST를 단일 확정정보 입력 Task 안에서 수행하고,
> 삭제·추천 기반 입력·TestConnection을 CSP 공통 TaskDefinition으로 제공하며 CUSTOM 단독 조합도
> 허용하도록 교정했다. 아래 Round 1은 이전의 두 Task 분리·CSP별 정의·CUSTOM 제한 설계를
> 대상으로 한 역사 기록이다. 수정 설계의 승인으로 사용하지 않는다.

- 검토일: 2026-09-08
- 범위: ADR-023 신규 설계 및 ADR-016/021 확장. Java 구현 부재 자체는 결함으로 분류하지 않음.
- 실행: 인증된 Claude CLI, `--model claude-fable-5-1 --effort high`, 읽기 전용 `Read,Glob,Grep`.
- 사용자 지정 모델은 저장소 codex-review 스킬의 기본 모델보다 우선한다.
- 초기 인증 실패 실행은 리뷰로 집계하지 않는다. 아래 Round 1은 인증 후 성공한 실행이다.
- 원본 실행 결과: `/private/tmp/pipeline-adr023-fable-round1-retry.json` (인증 정보 없음).
- 검토 소스는 Round 1 시작 시점 설계이며 후속 수정 전이다. 지적을 그대로 자동 적용하지 않고 사실·불변식과 대조한다.

## Round 1 — 수정 필요

실제 실행 메타데이터: `subtype=success`, `is_error=false`, `duration_ms=592403`, `num_turns=92`.
`modelUsage.claude-fable-5-1`이 기록되었다: input 226, output 44,705, thinking 30,007,
cache-read 1,072,961, cache-creation 340,732 tokens. `canonicalModel=claude-fable-5-1`.
CLI가 보조 모델 `claude-haiku-4-5-20251001` 사용(input 2,089/output 32 tokens)도 메타데이터에
기록했다. 아키텍처 리뷰 본문은 요청한 Fable 5.1 실행 결과이며 모델을 다른 것으로 대체하지 않았다.

판정: **설계 문서 리뷰 통과 아니오 — P0 0건 / P1 5건 / P2 12건.**

### 독립 검토자가 추가 확인한 항목

아래는 Fable 결과가 아니라 구현 영향 검토 과정의 별도 확인이다.

- TestConnection은 재시도에도 동일 외부 실행을 가리키므로 deadline 기준도 고정해야 한다.
  기존 `TaskSettingsResolver.isPastDeadline`은 `Task.startedAt` 기준이고 `markInProgress`가
  재dispatch 때 이를 덮어쓴다. 기존 유틸을 그대로 사용하면 폴 오류에 의해 같은 외부 실행의
  timeout이 연장된다. 고정 최초 실행 시각 또는 deadline과 READY 재dispatch 전 만료 검사가 필요하다.

### Round 1 원문

리뷰를 마쳤습니다. 코드 사실 검증과 설계 검토 결과를 정리합니다.

## 검증 결과 요약

ADR-023이 주장하는 코드 사실은 대부분 정확합니다. `DispatchResult`가 `WithResponse`/`None`뿐이고 `markInProgress`가 instanceof 하나로 분기하는 점, `StepRunner`가 execute 결과를 `Dispatched`로 싸고 `dispatchPhase=true`인 점, 호출 실패가 `getMessage()`만 저장되는 점, latest/history에 type 필터가 없는 점, `RecipeCatalog`의 중복 거부, VARCHAR(16), 프런트의 4초 폴링·completion-status·acknowledgment 분리, Swagger의 `{success}` 접수 응답과 version 결과 스키마까지 모두 소스와 일치합니다. 근거는 `DispatchResult.java:14-20`, `TaskStateMachine.java:99`, `StepRunner.java:79,106,110`, `TargetSourcePipelineController.java:41-52`, `RecipeCatalog.java:36-40`, `Pipeline.java:66`, `TcTab.tsx:63`, `useTcCompletionStatus.ts:52-55`, `install-v1.yaml:9796-9800,10745-10760`입니다.

P0는 없습니다. P1 5건, P2 12건입니다.

## P1

- **HTTP 원문 1 MiB 상한이 실제 컬럼 용량을 넘고, JPA update로는 확장되지 않는다.** `task_attempt.response`는 `columnDefinition="text"`로 약 64 KiB이며, `ddl-auto=update`는 기존 컬럼 타입을 절대 바꾸지 않는다는 사실이 이미 원장에 규칙으로 등록돼 있습니다.
  - 시나리오: 라이브 DB에서 200 KiB 추천 응답을 받은 write-back이 flush 실패, 롤백, 태스크 READY 유지, lease 만료 후 재GET, 같은 실패의 무한 루프. ADR이 스스로 경고한 패턴이 ADR의 기본값으로 발생합니다.
  - 근거: `TaskAttempt.java:64`, `ConditionOperationBinding.java:15-20`의 16000자 산정 근거, `.claude/review-ledger.md:38` enum-column-widening-safe, ADR-023 169-174행과 318-320행.
  - 최소 수정: `response`는 TEXT 그대로 두고 HTTP 원문은 JPA가 새로 생성할 수 있는 별도 LONGTEXT 컬럼 또는 별도 행에 저장하도록 Decision 5와 7을 고치거나, 상한을 TEXT 용량 이하로 내리십시오. "실제 DB에서 검증한다"는 문장은 "update로는 불가"로 바꿔야 합니다.

- **취소 우선 처리가 완료된 upstream 변경의 기록을 통째로 버린다.** write-back은 `cancel_requested`를 보면 outcome을 적용하지 않고 취소로 가며, attempt는 `applyOutcome` 안에서만 열립니다. 따라서 DELETE 200을 받은 뒤 취소가 관찰되면 attempt 행도 응답 원문도 남지 않습니다.
  - 시나리오: 확정정보 DELETE 성공 직후 운영자가 취소. 파이프라인은 CANCELLED, DELETE 태스크는 attempt 없이 CANCELLED. upstream 확정정보는 이미 삭제됐지만 어디에도 기록이 없어 "삭제 진행 정도 표시"와 "기존 삭제 결과를 남긴다"를 지킬 수 없습니다.
  - 근거: `StepReporter.java:58`, `TaskStateMachine.java:56`, `TaskCanceller.java:30`은 없는 attempt에 no-op, ADR-023 87행과 264행, ADR-021 269-271행.
  - 최소 수정: 취소 분기에서 outcome이 dispatch 단계 HTTP 결과이면 상태 전이와 입력 확정은 하지 않되 attempt를 CANCELLED로 열고 닫으면서 응답 원문과 metadata를 기록하도록 규정하십시오.

- **TestConnection 폴 실패의 엔진 동작이 Decision 6과 반대다.** check 단계의 `CallFailure`는 `retryOrFail`로 가서 attempt를 FAILED로 닫고 `failCount`를 올린 뒤 READY로 되돌려 다음 claim에서 execute를 다시 부릅니다. ADR-016 §6 "failed poll은 fresh run" 문장은 그대로 남아 있습니다.
  - 시나리오: 기본 `maxFailCount=2`에서 일시적 폴 오류 두 번이면 외부 테스트가 아직 RUNNING인데 태스크 FAILED. request key 계약 없이 활성화하면 재execute가 새 테스트 실행을 만듭니다.
  - 근거: `TaskStateMachine.java:63-66,123-135`, `PipelineSettings.java:29`, ADR-016 196-199행, ADR-023 200-201행, PR #40이 Terraform에 적용한 흡수 패턴 `TerraformTask.java:187-216`.
  - 최소 수정: TestConnection check가 전송 실패를 예외로 던지지 않고 Pending으로 흡수하며 연속 실패 임계에서만 종결 실패를 반환한다고 명시하고, ADR-016 §6에 TEST_CONNECTION 예외 문장을 추가하십시오.

- **"비활성 유형"의 표현 수단이 정의돼 있지 않다.** `RecipeCatalog`는 `RecipeDefinition.values()`를 무조건 등록하고 `resolveRecipe`에는 활성 여부 검사가 없습니다. 상수를 추가하는 순간 `POST type=RECONFIRM`이 성공합니다.
  - 시나리오: 구현 단계 1에서 신규 Recipe 상수를 넣고 배포하면 upstream 계약 확인 전인데 운영자가 재확정을 실행할 수 있습니다.
  - 근거: `RecipeCatalog.java:29-41`, `PipelineCreator.java:122-132`, ADR-023 288-290행과 298행.
  - 최소 수정: 활성 유형 집합을 env var 설정으로 두고 preview/create에서 `UnsupportedRecipeException`으로 거절하며 카탈로그 응답에 활성 여부를 노출한다고 한 문장으로 확정하십시오. 원장 R14의 settings 기반 선택 전례와 같습니다.

- **HTTP 바인딩이 호출할 서비스와 인증이 미결정이다.** 근거 Swagger는 BFF `/install/v1`이고 프런트는 SSO 쿠키와 authorization 헤더를 그대로 전달합니다. 오케스트레이터에는 `InfraManagerClient` 경계 하나뿐이며 per-call timeout 데코레이터도 그 인터페이스에만 붙어 있습니다.
  - 시나리오: 사용자 세션이 없는 서버 간 호출이라 BFF 경로는 그대로 쓸 수 없고, 새 클라이언트를 만들면 57행의 "per-call timeout 적용"이 자동으로 성립하지 않습니다.
  - 근거: `auth-headers.ts:16-31`, `http.ts:45-50`, `TimeBoundedInfraManagerClient.java:37-73`, `InfraManagerClient.java:29-64`, ADR-023 57행.
  - 최소 수정: 호출 대상 시스템, 서비스 계정 인증, 새 클라이언트 경계와 닫힌 예외 어휘, 타임아웃 데코레이터 적용을 Decision 4에 결정 항목으로 추가하십시오.

## P2

- **HTTP 없이 끝나는 실패의 결과 변형이 어색하다.** `EXECUTION_INPUT_MISSING`은 execute 안에서 외부 호출 없이 판정되는데 `HttpCompleted`에 실려 attempt가 열립니다. 비HTTP 종결 변형을 따로 두거나 HttpCompleted의 exchange를 optional로 정의하고 attempt 회계를 명시하십시오.
- **오류 응답 본문에도 RESPONSE_TOO_LARGE 종결이 적용되는지 불명확하다.** 2 MiB HTML을 실은 5xx가 재시도 가능한 CHECK_ERROR가 아니라 종결 실패가 되면 일시 장애가 영구 실패로 바뀝니다. 크기 규칙을 입력이 되는 성공 본문에 한정하십시오.
- **`response_truncated` 플래그가 Condition 경로에서 거짓이 된다.** Condition 본문은 지금도 16000자에서 조용히 잘리는데 ADR은 기존 경로 불변을 선언합니다. 플래그를 HTTP 전용으로 문서화하거나 clamp에도 세우십시오. 근거 `ConditionOperationBinding.java:41-46`.
- **HTTP 재시도 대기가 `pipeline.polling-interval` 기본 10분이다.** `retryOrFail`이 `nextCheckAt`을 그 값으로 잡고 정의별 override를 넣는 곳이 없습니다. 근거 `TaskStateMachine.java:133`, `PipelineInserter.java:70-81`.
- **request_key 단위가 불명확하다.** `pipeline_confirmation_input`에 키 하나뿐인데 DELETE와 POST 두 변경에 같은 키를 쓰면 upstream dedup이 POST를 DELETE의 중복으로 볼 수 있습니다. 변경 태스크별 키를 명시하십시오.
- **latest 응답의 version 일치 검증은 version 조회와 동등하지 않다.** 성공 후 다음 폴 전에 사용자가 새 실행을 시작하면 성공한 실행이 `EXECUTION_CORRELATION_FAILED`가 됩니다. 결정 7의 "처음 확인될 때 고정"이 latest_version에서 오면 안 된다는 점도 명시하십시오.
- **task 상세가 응답 원문을 인라인한다.** `TaskAttemptView.response`가 전문을 싣고 있어 1 MiB 본문이 attempt 수만큼 실립니다. R9/R10 전례대로 metadata 투영과 attempt별 본문 엔드포인트를 규정하십시오. 근거 `TaskAttemptView.java:27,41`.
- **프런트 무변경 선언과 노출 계획이 충돌한다.** `latest/route.ts:6`은 쿼리를 전달하지 않아 `latest?type=`이 UI에서 닿지 않고, `types.ts:21`과 `format.ts:585-593`의 PipelineType이 닫힌 집합이며 `custom_allowed` 필터도 프런트 변경입니다. 활성화 전제조건으로 프런트 변경 목록을 적으십시오.
- **구현 순서 1단계가 부팅 검증과 충돌한다.** `TaskTypeRegistry`는 모든 operation의 mechanism이 등록된 TaskType을 가리켜야 기동되므로 executor 없이 HTTP TaskDefinition을 먼저 넣을 수 없습니다. 1단계 범위를 PipelineType과 필터로 한정하십시오. 근거 `TaskTypeRegistry.java:57-64`.
- **RECONFIRM 생성 요청 계약이 없다.** AWS NLB 옵션과 승인 참조를 생성 트랜잭션에 넣으려면 `CreatePipelineRequest`가 type만 받는 현재 계약이 바뀌어야 합니다. 요청 필드와 기본값을 적으십시오.
- **사실 오류 하나.** "현재 UI는 이 순서를 강제하지 않는다"는 부정확합니다. 등록이 있는 동안 편집기가 잠겨 실제 순서는 삭제 후 추천 조회입니다. 근거 `panes.tsx:207`, `ConfirmEditorModal.tsx:5-8`. 이 사실은 ADR의 순서 가정을 오히려 뒷받침합니다.
- **ADR-016 잔여 드리프트와 끊어진 링크.** 259행 "three observation tables"는 넷이고, 303행 `task_attempt` 스키마에 `failure_detail`이 없으며, ADR-023 343-344행의 두 링크 파일은 저장소에 없습니다.

## 판정

설계 문서 리뷰 통과: **아니오**. P0 0건, P1 5건, P2 12건.

구현 전 확인할 계약:

1. HTTP 바인딩의 호출 대상 시스템과 서버 간 인증 방식, 그리고 그 클라이언트의 타임아웃 경계.
2. 라이브 DB에서 HTTP 원문을 담을 컬럼의 실제 타입과 용량, 그리고 1 MiB 상한의 근거.
3. 활성 유형 스위치의 형태와 위치, 카탈로그 노출 필드.
4. TestConnection request key 발급·회수 API와 version 조회 API의 존재 여부.
5. DELETE와 POST 각각의 멱등 키 의미론과 "이미 삭제됨", "등록 충돌" 응답의 판별 정보.
6. 승인 소유 서비스의 terminal 소비 계약, 특히 취소와 부분 실패에서 삭제 진행 정도를 어떻게 전달받는지.
7. RECONFIRM 생성 요청의 입력 필드와 Step3 연결 주체.

### 적용 판단 및 후속 검토 상태

후속 수정과 Round 2 검토를 진행한다. P1 취소 지적은 원문 보존 범위가 이미
“guarded write-back에서 채택된 결과”로 제한돼 있음을 고려하여 판단한다.
응답 유실을 전부 없애는 새로운 감사 로그를 도입하는 대신 취소/크래시 시 외부 효과를
“미확정”으로 표시하고 삭제 진척의 보장 범위를 좁히는 방안도 검토한다.
이 기록의 Round 1만으로 설계나 구현이 승인됐다고 간주하지 않는다.


## Round 2 — 구설계 검토 중단, 판정 없음

- 사용자 교정 이전의 Round 1 수정본을 대상으로 실행했다.
- 사용자 요청에 따라 실제 Claude CLI PID 55323에 SIGTERM을 전달했다. unified exec
  session 72027의 종료 코드 143과 해당 PID 부재를 확인했다.
- 중단 시점 최종 JSON·stderr는 모두 0바이트였다. 완성된 리뷰 결과나 modelUsage 증빙이
  없으므로 Round 2 통과/실패 또는 지적 건수를 만들지 않는다.
- 중단된 실행 경로는 `/private/tmp/pipeline-adr023-fable-round2.json`과 동일 basename의
  `.stderr`이며, 삭제하지 않았다. 새 설계가 준비되면 Fable 5.1로 다시 검토한다.


## Round 3 — 사용자 교정 반영 설계, 수정 필요

- 기준: 추천 GET+POST 단일 입력 Task, 신규 3개 ALL_CSP 정의, CUSTOM 단독·반복 조합 허용.
- 실행 모델: `--model claude-fable-5-1 --effort high`, 읽기 전용 `Read,Glob,Grep`.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=449208`, `num_turns=51`.
- `modelUsage.claude-fable-5-1`: input 194, output 32,453, thinking 23,823,
  cache-read 488,425, cache-creation 190,124 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 2,225/output 30 tokens로 기록되었다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-round3.json`. stderr는 비어 있다.
- 판정: **설계 문서 리뷰 통과 아니오 — P0 0건 / P1 1건 / P2 6건.**
- 이전 Round 1의 17개 지적은 현재 설계에 해당하는 범위에서 모두 반영되었다.
- 검토 중 Decision 7의 전역 type 조회 인덱스가 문서에 추가되었다. 입력/TC 핵심 설계는
  리뷰 시작 시점과 동일하다.

### Round 3 원문

리뷰를 마쳤습니다. 코드 사실과 대조한 결과, 새 설계는 Round 1의 P1 다섯 건을 모두 해소했고 P0는 없습니다. 남은 것은 P1 한 건과 P2 여섯 건입니다.

## P1 (1건)

**TC 고정 deadline의 기준점이 "파이프라인 예약 시작"이라 다중 Task CUSTOM에서 TC가 실행도 전에 실패한다.**
- 근거: `docs/adr/023-reconfirmation-http-tasks-and-execution-input.md:214-215`, `docs/adr/016-install-delete-pipeline-domain-model.md:203-204`, `docs/adr/021-pipeline-execution-model.md:284-286`. 셋 다 `deadline_at = pipeline.next_due_at + executionTimeout`을 생성 시 고정한다.
- 시나리오 1: 사용자 요구대로 CUSTOM `[AWS_BDC_SERVICE_LEVEL_DESTROY_V1, TEST_CONNECTION_V1]`을 만든다. destroy가 40분 걸리고 TC의 effective timeout이 10분이면 TC가 READY로 승격되는 순간 이미 deadline을 넘겼다. "READY 재dispatch 전 검사" 규칙에 따라 외부 실행 없이 종결 실패한다.
- 시나리오 2: CUSTOM `[TEST_CONNECTION_V1, TEST_CONNECTION_V1]`은 두 Task가 같은 deadline을 가지므로 두 번째 TC의 실제 허용 시간은 첫 TC가 쓰고 남은 시간뿐이다. "같은 공통 정의 반복 가능" 요구와 충돌한다.
- 기존 코드와의 관계: `TaskSettingsResolver.java:35-41`이 `startedAt` 기준이라 재dispatch마다 연장되는 문제를 피하려다 반대편으로 넘어갔다.
- 최소 수정: `deadline_at`을 생성 시 null로 두고 Task가 **처음 READY가 되는 시점**에 write-once로 고정한다. sequence 0은 생성 트랜잭션에서, 후속 Task는 승격하는 write-back(`StepReporter.java:153-162` 또는 `TaskStateMachine.java:92-96`)에서 `now + effectiveExecutionTimeout`을 쓴다. 큐 대기 포함과 retry 비연장은 그대로 유지된다. 세 문서의 해당 문장을 함께 고친다.

## P2 (6건)

- **HTTP 응답 분류표와 종결 코드 표가 닫혀 있지 않다.** `023:149-157`은 200/201/404/충돌만 다루고 202는 "동기 완료 아님"까지만 말한다. 202·401/403·409·412·429가 retryable인지 terminal인지, TC deadline 초과와 폴 오류 소진의 `ErrorCode`가 무엇인지가 없어 구현이 발명하게 된다. 최소 수정: status 범주별 retry/terminal과 ErrorCode를 표 하나로 확정한다. 컬럼 길이는 확인했다(`TaskAttempt.java:74` 32자, 새 코드 최대 28자).
- **HTTP 재시도 창이 사실상 5초다.** `023:157-158`은 간격만 5초로 override하고 `maxFailCount`는 기본 2를 유지한다(`PipelineSettings.java:29`, `TaskStateMachine.java:126-133`). RECONFIRM에서 확정정보 삭제 뒤 등록 POST가 5xx를 두 번 받으면 5초 만에 파이프라인이 FAILED가 되고 대상에는 확정정보가 없는 상태로 남는다. 최소 수정: HTTP Task용 `max_fail_count` override 기본값을 별도로 두거나 간격을 backoff로 명시한다.
- **TC 연속 폴 오류 임계 3회가 4초 폴과 결합하면 약 12초 장애로 종결된다.** `023:216-218`, `021:287-289`. deadline이 이미 상한이므로 짧은 카운터는 안전을 더하지 않고 취약성만 더한다. 최소 수정: TC 전용 기본값을 크게 두거나 deadline까지 폴을 계속하고 카운터는 진단으로만 쓴다.
- **실행 시점의 capability 부재와 provider null이 예외가 되면 lease 만료 루프에 빠진다.** `023:63-64`는 executor가 pipeline의 `cloud_provider`를 읽어 라우팅한다고 하지만 그 컬럼은 nullable이다(`Pipeline.java:78-79`). `023:162, 272-275`는 capability가 없으면 "제공하지 않는다"고만 한다. 생성 뒤 재배포로 `enabled-operations`가 줄면 Call 계열이 아닌 예외가 전파되어 `docs/exception-strategy.md:93-97` 경로로 lease 유지·재claim이 반복된다. 최소 수정: 두 경우를 외부 호출 전 typed 종결 실패값으로 명시한다.
- **DELETE 멱등 키가 영속되지 않는다.** `023:241`은 키를 task_id에서 "생성"한다. 파생식이 배포 사이에 바뀌면 진행 중인 DELETE의 재시도 키가 달라져 D9의 중복 보호가 깨진다. 최소 수정: 키를 생성 시 저장하거나 파생식을 버전 고정 문자열로 선언한다.
- **폴 오류 전용 결과의 분기 근거를 명시해야 한다.** `023:216`과 `021:286-287`은 StepRunner 경계에서 TC만 다른 결과로 바꾼다고 하는데, `StepRunner.java:101-112`의 catch는 task 이름밖에 모른다. 원장 규칙(extensibility-not-by-name)상 TaskType 속성으로 분기하거나, PR #40의 `TerraformTask.java:187-216`처럼 TaskType이 값으로 흡수하는 방식 중 하나를 문서에 고정한다.

## Round 1 findings 해결 여부와 판정

| Round 1 지적 | 상태 |
|---|---|
| P1 HTTP 원문 TEXT 용량 | 해결. 신규 LONGTEXT 컬럼(`023:174-176, 184`) |
| P1 취소가 완료된 upstream 변경 기록을 버림 | 범위 결정으로 종결. adopted-only 한계와 "확인 불가 효과" 표시(`023:198-200`, `021:273-276`) |
| P1 TC 폴 실패 엔진 동작 | 해결. 전용 결과·같은 attempt·도메인 카운터, ADR-016 §6 예외 문장(`016:200-204`) |
| P1 비활성 유형 표현 수단 | 해결. `enabled-operations` ∩ capability, typed 400(`023:272-275, 288`) |
| P1 HTTP 바인딩 대상·인증 | 해결. `InstallationOperationsClient` 경계와 bounded 실행기(`023:160-167`). 실제 adapter 부재는 합의된 비활성 |
| 독립 확인: deadline 고정 | 부분 해결. 고정은 됐으나 기준점이 틀림(위 P1) |
| P2 12건 | 모두 반영 확인. 비HTTP 실패 변형, 오류 본문 크기, `response_truncated` 범위, 5초 간격, task별 키, latest 바인딩 금지, metadata projection, 프런트 후속 목록, 구현 1단계 범위, 생성 요청 필드, 사실 오류, ADR-016 드리프트와 링크(`docs/adr-023-explained.html`, `docs/reviews/` 존재 확인) |

검토했으나 결함이 아닌 것: GET→check POST와 retry의 attempt 회계는 `ObservationRecorder.java:98-109`의 `(task_id, fail_count+1)` 키와 `retryOrFail`의 순서상 유니크 충돌 없이 성립한다. cancel-first, stale token, 관찰 유실 복구, 입력 유실과 관찰 유실 구분, CUSTOM 반복의 task별 키, `supportsProvider`가 `RecipeCatalog.java:31`의 부팅 검사와 `PipelineCreator.java:106-110`을 대체하는 점은 모두 일관된다. main 코드에 `PipelineType` switch가 없어 신규 type의 숨은 ripple도 없다.

**설계 문서 리뷰 통과: 아니오**. P0 0건 / P1 1건 / P2 6건.

### 다음 조치

TC deadline 기준점을 Task의 최초 실행 가능 시점으로 수정하고 P2 항목을 평가·구체화한 뒤
같은 모델로 검증한다. 이 Round의 판정은 수정 전 설계에 대한 것이며 구현 승인을 뜻하지 않는다.


## Round 4 — 설계 통과, 비차단 문장 보완 1건

- 범위: Round 3의 7개 지적 수정과 ADR-023/016/021 관련 불변식 정합성.
- 병행 구현 중인 코드의 미완성 상태는 이번 설계 리뷰 대상에서 제외했다.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=199892`, `num_turns=21`.
- `modelUsage.claude-fable-5-1`: input 162, output 14,108, thinking 8,937,
  cache-read 228,375, cache-creation 66,765 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 1,791/output 26 tokens로 기록되었다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-round4.json`. stderr는 비어 있다.
- 판정: **설계 문서 리뷰 통과 예 — P0 0건 / P1 0건 / P2 1건(비차단).**
- TC의 접수/poll 전용 typed 결과를 Decision 4에 명시하는 문장 보완이 남았다.

### Round 4 원문

검토를 마쳤습니다. Round3의 7건은 ADR-023·016·021 세 문서에서 모두 반영됐고 서로 모순이 없습니다. 남은 것은 P2 1건뿐이며, 수정 7번(전용 poll 결과)과 직접 맞닿은 값 어휘 공백입니다.

## 남은 지적

**P2 1건. TC 결과 값 변형이 Decision 4에 없다.** 결정 4는 `DispatchResult.HttpPrepared`/`HttpCompleted`와 `TaskProgress.HttpCompleted`만 열거합니다 (`docs/adr/023-reconfirmation-http-tasks-and-execution-input.md:137-142`). 그런데 결정 6은 TC에 대해 세 가지 durable 쓰기를 guarded report에서 요구합니다: 접수 응답에서 검증된 version을 `task_external_execution`에 한 번 고정 (`023:229-231`), 매 poll의 원문·마지막 오류를 `test_connection_result`에 기록 (`023:249-251`), 전용 poll 오류 결과로 같은 attempt 재예약 (`023:241-245`). 현재 값 타입은 이 데이터를 운반하지 못합니다. `DispatchResult.WithResponse`는 원문 문자열뿐이고 엔진은 그 스키마를 해석하지 않는다는 규약이며 (`src/main/java/com/bff/pipeline/model/DispatchResult.java:8-10`), `TaskProgress.Pending`은 `CheckSignal` 하나만 가집니다 (`src/main/java/com/bff/pipeline/model/TaskProgress.java:22`). ADR-021도 "exhaustively handled"와 "snapshots are written in report"만 말하고 변형을 지정하지 않습니다 (`docs/adr/021-pipeline-execution-model.md:260, 290-296`).

- **Failure scenario:** 구현자가 version 바인딩과 진단 원문을 run phase에서 직접 DB에 쓰거나(모든 쓰기는 guarded report 불변식 위반, stale token에도 기록됨), 엔진이 `WithResponse` 원문을 파싱해 version을 꺼내는(응답 스키마는 TaskType 사적 계약 위반) 둘 중 하나로 흐릅니다. 또한 READY에서 deadline 초과·외부 실행 행 유실을 execute가 외부 호출 없이 종결해야 하는데 (`023:238-239`), TC의 dispatch-phase 종결 실패를 실을 변형도 지정돼 있지 않습니다.
- **최소 수정:** 결정 4 목록에 TC 전용 변형 두 줄을 추가합니다. 접수용은 상관관계 검증된 version과 bounded 원문을 담는 dispatch 변형 하나(외부 호출 전 종결 실패는 nullable exchange 실패 값으로 통일), poll용은 관찰 status·bounded 원문·마지막 오류 정보를 담는 progress 변형 하나입니다. 전용 poll 오류 결과가 이 progress 변형의 한 값임을 결정 6에 한 문장으로 연결합니다. 범용 추상화가 아니라 TC mechanism의 typed 값이므로 사용자 요구와 충돌하지 않습니다.

## Round3 7건 해결표

| # | 항목 | 상태 | 근거 |
|---|---|---|---|
| 1 | P1 TC deadline 최초 READY write-once | 해결 | `023:228, 233-239`, `016:203-205`, `021:283-288`. 첫 Task는 next_due_at 기준, 후속은 승격 시각, BLOCKED null, unblock 두 경로(`TaskStateMachine.java:92-96`, `StepReporter.java:153-162`) 동일 initializer, retry(`TaskStateMachine.java:130-133`) 비연장. HTML 설명(`adr-023-explained.html:130`)도 일치 |
| 2 | P2 닫힌 status/ErrorCode/retry 표 | 해결 | `023:162-177`. 401/403 terminal, 429/5xx/연결 retry, 202·예상 밖 status·schema terminal, TC FAIL/deadline/correlation 코드 명시. 새 코드 최장 28자로 기존 32자 컬럼 내 |
| 3 | P2 HTTP 24/5초, TC 접수 24/폴 4초 | 해결 | `023:157-160`. 대기 합계 115초 계산이 `retryOrFail`의 `failCount >= max` 규칙(`TaskStateMachine.java:126`)과 일치. Task 생성 시 override 고정 |
| 4 | P2 TC 짧은 연속 임계 제거 | 해결 | `023:159-160, 176-177, 245`, `016:201-202`, `021:293-295`. transient만 같은 attempt, counters 진단 only, non-retryable 즉시 종결. `CallFailure`에 retryable 운반 추가(`023:175-176`)도 명시 |
| 5 | P2 enabled-operations 생성 gate만 | 해결 | `023:303-305`, `021:296-297`. capability 상실 OPERATION_UNAVAILABLE, provider null EXECUTION_INPUT_INVALID, raw 예외 금지 |
| 6 | P2 DELETE 키 버전 고정 파생식 | 해결 | `023:268-269`. `confirmation-delete:v1:task:{task_id}`를 정의 계약으로 고정 |
| 7 | P2 handleCallFailure 훅 | 해결 | `023:241-244`, `021:291-292`. `StepRunner.java:101-112` catch에서 값 번역 후 다형성 위임, `CallFailure.dispatch` 플래그로 접수/폴 구분 가능. Task 이름 분기 없음 |

불변식 정합성에서 추가로 확인한 것: 첫 Task의 `readyAt`은 현재 생성 시각(`PipelineInserter.java:79`)이지만 ADR이 deadline 기준을 next_due_at으로 별도 지정하므로 PENDING 지연 시작과 충돌하지 않습니다. 기존 `isPastDeadline`이 `startedAt` 기준인 점(`TaskSettingsResolver.java:35-41`)은 TC가 저장된 `deadline_at`을 쓰므로 영향이 없습니다. HTTP 재시도 READY 복귀와 POST 재개, attempt 회계는 Round3 판단 그대로 유지됩니다.

**설계 문서 리뷰 통과: 예** — P0 0건 / P1 0건 / P2 1건(비차단, 결정 4에 TC 변형 문장 추가로 해소).


## Code Round 1 — HTTP/CSP/query 고정 사본 리뷰 통과

- 검토일: 2026-09-09.
- 고정 사본: `/private/tmp/pipeline-adr023-http-review-20260908T150922Z`.
- 캡처: 2026-09-09 00:09:22 KST, 257개 파일. 사본에 diff, tracked/untracked 목록, SHA-256 manifest를 포함했다.
- 검증 로그: `/private/tmp/adr023-http-regression.log`를 사본에 복사. 00:03:58 KST 완료한
  HTTP/기존 회귀 288건 PASS이며 TC 공유 변경 전 결과다. 사본 전체와 정확히 같은 코드가
  이 테스트를 통과했다고 주장하지 않았다.
- TC 전용 실행·wiring 완성도는 제외했다. TC 공유 변경이 완성된 HTTP/CSP/query 경로에
  실제 회귀를 만드는지만 검토했다. live repository를 읽지 않고 사본만 읽도록 제한했다.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=656436`, `num_turns=80`.
- `modelUsage.claude-fable-5-1`: input 322, output 51,465, thinking 38,002,
  cache-read 1,136,937, cache-creation 276,851 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 4,632/output 30 tokens로 기록됐다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-code-http-round1.json`. stderr는 비어 있다.
- 판정: **코드 리뷰 통과 예 — P0 0건 / P1 0건 / P2 10건.**
- P2는 구체적 코드 정리와 실패 경로 테스트를 포함하므로 각각 평가·반영한다.
  TC와 전체 공유 엔진 통합은 별도 고정 사본 리뷰로 이어간다.

### Code Round 1 원문

리뷰를 마쳤습니다. 고정 사본의 HTTP executor, 상태 기계, 보고자, 관찰 기록자, 가용성 gate, 조회 필터, 테스트를 모두 읽었고 TC 전용 동작은 지시대로 판정에서 제외했습니다.

## 결함

P0, P1 해당 없음. 아래는 모두 P2입니다.

**P2-1. 입력 Task 요청 키 파생식이 버전 고정도 문서화도 없이 identity 검증에 재사용된다.**
`src/main/java/com/bff/pipeline/service/task/TaskConfirmationInputs.java:66`, `src/main/java/com/bff/pipeline/service/task/HttpRequestTask.java:99-102`.
- Trigger: 배포에서 `"installation-task-" + id` 문자열이 바뀌면, IN_PROGRESS/READY 상태의 모든 입력 Task가 다음 turn에 EXECUTION_INPUT_INVALID로 종결된다. 저장된 키가 아닌 코드 재파생값과 비교하기 때문이다.
- 영향: 진행 중 재확정이 외부 호출 없이 일괄 실패한다. DELETE 키는 ADR 결정 7에서 `confirmation-delete:v1:task:{id}`로 고정 계약이지만 입력 키는 계약이 없다.
- 수정안: `confirmation-input:v1:task:{id}` 형태로 버전 고정하고 ADR 결정 7에 계약으로 명시한다. 또는 identity 검증을 "저장 키가 존재하고 blank가 아님"으로 두고 파생식 재비교를 제거한다.
- 테스트 누락: 파생식 변경 시 재개 동작을 검증하는 테스트 없음.

**P2-2. 설치 operation 판정이 mechanism 문자열 분기다.**
`src/main/java/com/bff/pipeline/service/lifecycle/InstallationOperationAvailability.java:39-42`, 호출처 `src/main/java/com/bff/pipeline/service/lifecycle/PipelineInserter.java:83,89-90`.
- Trigger: 새 mechanism을 추가하면 이 헬퍼와 inserter의 retry 오버라이드 분기를 함께 고쳐야 한다. recurring-review 패턴 8(extensibility-not-by-name)에 해당한다.
- 수정안: `TaskOperation` 또는 `TaskExecutionSpec`에 "설치 client 사용 여부·retry 정책" 속성을 두고 gate와 inserter가 그 속성만 읽게 한다.

**P2-3. Optional 관용구 위반과 null 센티널.**
`src/main/java/com/bff/pipeline/service/task/TaskConfirmationInputs.java:38-41`은 `orElse(null)` 두 번으로 `ExecutionContext`를 만들고, `HttpRequestTask.java:90-97`의 `validateExecution`은 "유효"를 `null` 반환으로 표현한다.
- 영향: 런타임 결함은 아니지만 recurring-review 패턴 7과 §5.7의 명시적 금지 형태다.
- 수정안: `ExecutionContext`를 sealed 결과(provider 유실 / 입력 없음 / 준비됨)로 바꾸거나 `validateExecution`이 `Optional<HttpTaskResult>`를 반환하게 한다.

**P2-4. 기존 Terraform/Condition attempt 상세에 빈 `http` 객체가 항상 실린다.**
`src/main/java/com/bff/pipeline/dto/pipeline/TaskAttemptView.java:48`, `src/main/java/com/bff/pipeline/dto/pipeline/HttpResponseDetail.java:18-22`.
- Trigger: 기존 INSTALL 파이프라인의 task detail 조회.
- 영향: 모든 필드가 null인 `http` 객체가 wire에 추가된다. 화면이 `http != null`을 HTTP attempt 판별로 쓰면 오판한다.
- 수정안: `httpOperation == null`이면 `http`를 null로 둔다. `DtoSnakeCaseSerializationTest`는 null을 직접 넣어 이 경로를 검증하지 않는다.

**P2-5. 프로덕션 미사용 오버로드가 잘못된 가용성 기본값을 가진다.**
`src/main/java/com/bff/pipeline/dto/pipeline/TaskCatalogResponse.java:18-20`. `of(provider)`는 테스트에서만 쓰이며 `CSP_SPECIFIC → available=true`라는 실제 gate와 무관한 술어를 심는다. §5.10 dead/speculative에 해당하므로 삭제하고 테스트를 predicate 버전으로 옮긴다.

**P2-6. `capture`/`supportedEncoding`/status 판정이 두 TaskType에 복제됐다.**
`src/main/java/com/bff/pipeline/service/task/HttpRequestTask.java:169-194`와 `TestConnectionTask`가 같은 인코딩 파서와 status 분류를 각자 가진다. §5.6 DRY. `HttpResponses`에 `supportedEncoding`과 공통 status 검사를 두면 분류 규칙이 갈라지지 않는다. TC 자체 동작은 이번 범위 밖이며 위치만 지적한다.

**P2-7. 정적 팩토리 결과 다운캐스트.**
`src/main/java/com/bff/pipeline/service/execution/StepRunner.java:110`의 `(StepOutcome.CallFailure)` 캐스트는 `callTimeout`의 반환 타입을 `CallFailure`로 좁히면 사라진다.

**P2-8. 스타일 불일치.**
- `src/main/java/com/bff/pipeline/enums/ErrorCode.java:31-37`, `enums/TaskDefinition.java:199-204`, `enums/TaskOperation.java` 신규 상수에 Javadoc이 없다. 주변 상수는 모두 한국어 Javadoc을 가진다(AGENTS #7, #8).
- `src/test/java/com/bff/pipeline/service/HttpInstallationTaskTest.java`는 와일드카드 import와 한 줄 다중 문장을 쓴다. 주변 테스트 스타일과 다르다.

**P2-9. catalog와 CUSTOM의 불가 응답 코드가 다르다.**
catalog는 `UNSUPPORTED_RECIPE`("no recipe"), CUSTOM은 `OPERATION_UNAVAILABLE`이다. ADR 결정 9가 이를 허용하지만 결정 2의 "같은 typed 400" 문구와 충돌하고, 화면이 "이 CSP의 레시피 없음"과 "operation 비활성"을 구분할 수 없다. 낮은 우선순위이며 ADR 문구 통일 또는 catalog 경로에도 `OPERATION_UNAVAILABLE` 사용 중 하나를 고르면 된다.

**P2-10. 중요한 실패 경로 테스트 누락 (HttpInstallationTaskTest).**
- `CallTimeoutException` 경로: CALL_TIMEOUT retryable, `http_operation`만 기록되고 status/body null인지, StepRunner의 캐스트 경로가 실제로 도는지 검증 없음.
- catalog RECONFIRM 생성 성공 경로: Terraform destroy + 공통 2개 체인, CONFIRM step에만 입력행 생성, catalog `apply_nlb_security_group=true` 전달과 non-AWS catalog 거절. 현재는 불가 경로만 있다.
- cross-type active_target: INSTALL 활성 중 RECONFIRM 또는 CUSTOM 입력 생성이 409인지. ADR 필수 목록 항목이나 `PipelineUniquenessTest`에는 없다.
- POST 완료 outcome에 대한 stale token과 cancel-first. 현재는 GET outcome만 다룬다. 취소 우선 시 Task가 DONE으로 뒤집히지 않고 POST 원문이 attempt에 남지 않는지 확인이 필요하다.
- DELETE 동기 retryable(503) 후 READY 재시도에서 동일 키 재사용.
- `pipeline.cloud_provider` null에서 외부 호출 없이 EXECUTION_INPUT_INVALID.

## 축별 요약

- **A 단일 입력 Task**: PASS. GET → `HttpPrepared` → capture + IN_PROGRESS 원자, 후속 BLOCKED 유지, check의 POST, READY 재시도에서 GET 미호출, Task별 키 분리 모두 코드와 테스트로 확인.
- **B 소유권·원자성**: PASS. run 단계는 read-only 조회만 하고 모든 쓰기는 `writeBack` 안이다. `beginAttempt` 1회, endAttempt 후 failCount 증가, 두 sealed 타입 모두 exhaustive switch.
- **C 유실 구분**: PASS. 행 없음/본문 null/digest 손상/attempt 유실이 각각 구분되며 attempt 복구 시 번호와 입력 참조가 맞다. 복구 행의 `startedAt`이 write-back 시각인 점은 원본 유실상 불가피하다.
- **D HTTP 원문**: PASS. 재직렬화 없음, 빈 응답과 무응답 구분, UTF-8 경계 절단, 성공 초과 terminal과 오류 초과 원분류 보존, metadata 길이 guard, LONGTEXT 신설, 상세는 projection. FLAG는 P2-4.
- **E 외부 예외**: PASS. 단일 boundary, 401/403 terminal, 429/5xx/timeout retry, 202 terminal, `handleCallFailure` 다형성, 인터럽트 미포획, API에는 typed 예외만.
- **F TC**: 범위 제외. 공유 변경(`markReady`, `initialize`, 생성자 의존)은 non-TC Task에서 즉시 반환하므로 HTTP 경로 회귀 없음.
- **G 가용성·CSP**: PASS. `supportsProvider`가 catalog/Recipe/CUSTOM 공통 규칙이고 설정 ∩ capability gate를 우회하는 생성 경로가 없다. FLAG는 P2-1, P2-2, P2-5, P2-9.
- **H 외부 IO 경계**: PASS. fake가 활성 트랜잭션을 거부하며 `BoundedCallExecutor`는 기존 `TimeBoundedInfraManagerClientTest`로 timeout/interrupt가 검증된다. HTTP 전용 decorator는 얇은 위임이라 별도 테스트 부재를 결함으로 보지 않는다.
- **I 조회**: PASS. DB filter 후 정렬/페이지, `created_at desc, id desc`, 204, 잘못된 enum 400, exact 문자열 AND, 인덱스 3종 커버.
- **J 스키마·테스트**: PASS. JPA 선언만 있고 Flyway 없음, 테스트에 `@Transactional` 없음, 주입 Clock, 실제 롤백 검증. FLAG는 P2-10.
- **K 구조**: 대체로 PASS. 인터페이스는 실제 경계 하나뿐이고 생성자 주입, record, sealed switch, 한국어 Javadoc 준수. FLAG는 P2-3, P2-6, P2-7, P2-8.

참고 사항이며 결함이 아닙니다. 회귀 로그의 288건은 `HttpInstallationTaskTest` 34건을 포함하지만 TC 공유 변경 직전 실행이다. 사본의 slice 테스트들은 `TaskStateMachine`, `StepReporter`, `PipelineInserter`가 요구하는 TC bean을 import하지 않으므로 TC wiring이 완료되기 전에는 이 사본 그대로 재실행할 수 없다. H2/fake 결과를 MySQL/upstream 검증으로 주장하는 문구는 발견하지 못했다.

## 판정

코드 통합 리뷰 통과: 예
P0 0건 / P1 0건 / P2 10건


## Code Round 2 — 전체 통합 리뷰 통과, P2 보완 5건

- 검토일: 2026-09-09.
- 고정 사본: `/private/tmp/pipeline-adr023-integrated-review-20260908T153238Z`.
- 캡처: 2026-09-09 00:32:38 KST, 259개 파일. diff, tracked/untracked 목록, SHA-256 manifest 포함.
- 검증 로그: `/private/tmp/adr023-integrated-tests-round2.log`, 00:31:35 KST에 전체
  `mvn test` 345건 PASS(실패/오류/스킵 모두 0). root의 source freeze 후 사본을 만들었다.
- 범위: HTTP Code Round 1의 P2-1~10 해결 확인, TC와 공유 엔진 전체 통합 최초 검토.
  이미 통과한 HTTP 동작은 변경 영향 위주로 확인했다. live repository 대신 사본만 읽었다.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=636888`, `num_turns=111`.
- `modelUsage.claude-fable-5-1`: input 226, output 52,463, thinking 34,577,
  cache-read 932,320, cache-creation 344,822 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 4,755/output 31 tokens로 기록됐다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-code-integrated-round2.json`. stderr는 비어 있다.
- 판정: **코드 통합 리뷰 통과 예 — P0 0건 / P1 0건 / P2 5건.**
- P2-A의 호출 없는 종결 시 접수 원문 유실을 우선 보완하고, 표현·DTO·문서·실패 경로 테스트
  지적도 평가·반영한 뒤 범위를 좁힌 수정 검증을 진행한다.

### Code Round 2 원문

검토를 마쳤습니다. 고정 사본만 읽었고 파일 수정이나 명령 실행은 하지 않았습니다.

## 검증 로그 확인

`validation.log`는 345건, 실패 0, 오류 0, 스킵 0, `Finished at: 2026-09-09T00:31:35+09:00`로 프롬프트의 기술과 일치합니다. `TestConnectionTaskTest` 34건, `HttpInstallationTaskTest` 56건이 포함됩니다. 이 결과는 H2와 fake 경계에 대한 것이며 ADR-023 구현 현황 절도 그렇게 표기하고 있습니다. 운영 adapter, 실제 MySQL, 프런트 미연동은 명시된 잔여 범위이므로 결함으로 세지 않았습니다.

## Code Round 1 P2-1~10 해결표

| # | 항목 | 상태 | 근거 |
|---|---|---|---|
| P2-1 | 입력 키 파생식 버전 고정 | 해결 | `TaskConfirmationInputs.java:78-79` `confirmation-input:v1:task:{id}`, ADR-023:293-294, 테스트 `inputRequestKeyUsesTheVersionedContractAcrossBothCalls` |
| P2-2 | mechanism 문자열 분기 | 해결 | `TaskOperation.java:78-131` `InstallationPolicy` 속성과 `usesInstallationClient`/`usesHttpRetryPolicy`. `InstallationOperationAvailability.java:28`, `PipelineInserter.java:87`이 속성만 읽음 |
| P2-3 | Optional 관용구·null 센티널 | HTTP는 해결, TC에서 재발 | `ConfirmationInput.ExecutionContext` sealed, `HttpRequestTask.validateExecution` Optional 반환. 신규 `TestConnectionExecutionContext`는 `executionId == null` 센티널을 다시 씀(아래 신규 P2-B) |
| P2-4 | 레거시 attempt의 빈 `http` 객체 | 해결 | `TaskAttemptView.java:48`, 테스트 `legacyAttemptsHaveNoHttpMetadataObject` |
| P2-5 | 미사용 `of(provider)` 오버로드 | 해결 | `TaskCatalogResponse.java:17` predicate 버전만 존재, 테스트가 predicate 사용 |
| P2-6 | capture/encoding/status 중복 | 해결 | `HttpResponses.java:29-48` 공통, `TestConnectionTask.java:131-135`가 위임 |
| P2-7 | 정적 팩토리 다운캐스트 | 해결 | `StepOutcome.java:77` `CallFailure` 반환, `StepRunner.java:110` 캐스트 없음 |
| P2-8 | Javadoc·테스트 스타일 | 대부분 해결 | `ErrorCode.java:31-44`, `TaskDefinition.java:199-207`, `TaskOperation.java:59-64` 한국어 Javadoc. `HttpInstallationTaskTest` import 정리. fake 두 파일에는 한 줄 다중 문장이 남음(`FakeTestConnectionClient.java:23,38,43`, `FakeInstallationOperationsClient.java:30,39,42,45`) |
| P2-9 | catalog/CUSTOM 불가 코드 불일치 | 해결 | `RecipeCatalog.java:54-60`이 존재 recipe 비활성을 `OPERATION_UNAVAILABLE`로 통일, ADR-023:347-349 문구 일치 |
| P2-10 | HTTP 실패 경로 테스트 누락 | 해결 | timeout 경로, catalog RECONFIRM 전체 체인·AWS 옵션·타 CSP 거절, cross-type active_target, POST stale/cancel, DELETE 503 동일 키, provider null 테스트 모두 `HttpInstallationTaskTest`에 존재 |

## 신규 결함

P0, P1 없음. 아래는 모두 P2입니다.

**P2-A. 호출 없는 종결이 같은 attempt의 직전 HTTP 원문과 metadata를 null로 덮어쓴다.**
`ObservationRecorder.java:48-61`, 호출처 `TaskStateMachine.java:132-136`, 트리거 `TestConnectionTask.java:92-99`와 `:68-70`.
- Trigger: TC 접수 202가 attempt 1에 `TEST_CONNECTION_START` 원문·수신시각으로 기록된다. 이후 deadline에 도달하면 poll 전 `validate`가 `timedOut(null)`을 반환하고 `completeHttp`가 `recordHttpResponse(task, null, null)`을 호출해 attempt 1의 http_* 컬럼이 전부 null이 된다. `TestConnectionTaskTest.java:185-188`이 정확히 이 경로를 밟지만 attempt의 HTTP 필드는 검증하지 않는다. `runtimeCapabilityLossStopsBeforeAnotherExternalCall`, `malformedStoredVersionDoesNotInvokeTheClient`, `missingExecutionDuringPollingFailsWithoutRedispatch`도 같은 경로다. HTTP 입력 Task도 GET 후 check에서 `invalidInput()`이나 OPERATION_UNAVAILABLE이 나오면 attempt의 GET 원문이 지워지지만 입력행에 GET 증적이 남아 영향이 작다.
- 영향: 접수 증적이 task_attempt에서 사라지고 상세의 `http`가 null이 되어 "호출한 적 없음"과 구분할 수 없다. 이 경로는 recorder를 거치지 않으므로 `test_connection_result.last_error_code`는 직전 transient 오류(CHECK_ERROR)로 남고 task의 EXECUTION_TIMEOUT과 어긋난다. 상태 전이 자체는 정확하다.
- 최소 수정: `recordHttpResponse`에서 `exchange == null`이면 기존 http_* 필드를 유지하고 attempt 확보와 inputId만 처리한다. 선택적으로 deadline pre-check 종결을 `TestConnectionObservation.Failed(timedOut(null))`로 recorder에 통과시켜 last_error_code를 맞춘다.
- 테스트 누락: deadline pre-check timeout 후 attempt 1의 `http_operation`/`response_received_at` 보존 assert 없음.

**P2-B. TC 실행 맥락이 null 센티널로 유실을 표현한다.**
`TestConnectionExecutionService.java:55-59`는 행 유실을 빈 builder 결과로 만들고 `TestConnectionTask.java:93`이 `executionId() == null`로 재검사한다. `:85-92`의 `execution == null` 분기는 도달 불가한 dead code다. Round 1 P2-3와 같은 recurring 패턴 7이며 HTTP 쪽은 sealed로 고쳤으나 TC에서 재발했다.
- 최소 수정: `read`가 `Optional<TestConnectionExecutionContext>`를 반환하거나 sealed `Missing`/`Ready` 변형을 두고 dead 분기를 제거한다. 런타임 결함은 아니다.

**P2-C. TC 상세가 관측이 없어도 빈 metadata 객체를 항상 싣는다.**
`TestConnectionResultDetail.java:25,28,37-47`.
- Trigger: 첫 poll이 503이면 결과 행에 오류 필드만 있는데 `response`는 status null·`truncated=false`인 객체로 내려간다. 반대로 정상만 있으면 `errorResponse`가 빈 객체다. 해결된 P2-4와 같은 유형이다.
- 최소 수정: `lastObservedAt`/`lastErrorAt`이 null이면 해당 metadata를 null로 둔다.
- 테스트 누락: 오류만 있는 행과 정상만 있는 행의 응답 형태 검증 없음.

**P2-D. ADR-016/021의 "아직 구현되지 않았다" 문장이 ADR-023 구현 현황과 충돌한다.**
`docs/adr/016-install-delete-pipeline-domain-model.md:19,409`, `docs/adr/021-pipeline-execution-model.md:17`은 이번 diff가 추가하거나 바꾼 줄이며 "proposed"·"아직 구현되지 않았다"로 남아 있다. `docs/adr/023-...md:381-394`는 이 checkout에 반영됐다고 기록한다.
- 최소 수정: 세 줄을 "ADR-023 구현 현황 참조, 운영 adapter·MySQL 검증·프런트 미연동"으로 한 문장씩 갱신한다. 같은 표의 ErrorCode/TaskOperation 행도 낡았지만 이번 diff가 손대지 않은 레거시라 필수로 요구하지 않는다.

**P2-E. TC 실패 경로 테스트 누락.**
- 접수 또는 poll의 통신 실패가 deadline 이후에 도착하는 분기(`TestConnectionTask.java:84-87`)를 도는 테스트가 없다. 성공 응답 지연만 검증한다.
- 접수 재시도 예산 소진(24회 × 4초 후 CHECK_ERROR FAILED, 실행 행의 키 유지·version 미바인딩)을 검증하는 테스트가 없다. HTTP는 `httpRetryBudgetTerminatesAtTwentyFourAttempts`가 있다.
- IN_PROGRESS에서 deadline null 유실이 새 마감 없이 INVALID로 끝나는지는 READY 케이스만 있다(`missingDeadlineOrExecutionNeverStartsAnotherExternalRun`).

## 축별 요약

- **A 단일 입력 Task**: PASS. Round 1 확인 유지. 바뀐 helper 영향은 `handleCallFailure` 훅과 `callTimeout` 타입 축소뿐이며 56건 테스트 경로가 동일하다.
- **B 소유권·원자성**: PASS. TC의 version 바인딩·진단 쓰기·deadline 초기화가 모두 `MANDATORY` 전파로 write-back 또는 생성 트랜잭션에만 참여한다. stale token, cancel-first, 강제 롤백 테스트가 start와 poll 양쪽에 있다. `DispatchResult` 5개, `StepOutcome` 11개, `TaskProgress` 7개, `TestConnectionObservation` 3개 모두 exhaustive switch다. beginAttempt는 Dispatched에서 1회, poll은 `ensureAttempt`로 같은 attempt를 유지한다.
- **C 유실 구분**: PASS. 실행 행 유실은 MISSING으로 새 테스트를 만들지 않고, deadline·provider·target·키 불일치는 INVALID로 호출 전에 끝난다. attempt·진단 행 유실은 도메인 실행 행에서 복구하며 attempt 번호가 맞다. FLAG는 P2-B의 표현 방식.
- **D HTTP 원문**: 대체로 PASS. TC start/poll 원문은 bounded 후 전달되고 오류 초과는 재시도성을 유지한다. FLAG는 P2-A, P2-C.
- **E 외부 예외**: PASS. `StepRunner.java:105-117` 단일 경계, `TaskType.handleCallFailure` 다형성, `failure.dispatch()`로 접수/poll 구분. Task 이름 분기 없음. 인터럽트와 비 Call 예외는 통과한다.
- **F TC**: PASS. verified request key와 version 고정(`TestConnectionExecutionService.java:68-78` write-once), latest 미바인딩, 접수≠성공, version 불일치 시 타 실행 성공 미채택. deadline은 첫 Task 생성 시 next_due_at 기준, 후속은 `unblock`과 `promoteBlockedSuccessor` 두 경로가 같은 `markReady`를 호출하고 `retryOrFail`은 건드리지 않는다. transient poll은 Pending으로 같은 attempt와 deadline까지 유지, 401/403은 즉시 종결, 카운터는 task_check 진단 전용. terminal 진단 sticky, 오류가 정상 본문을 지우지 않는다.
- **G 가용성·CSP**: PASS. `supportsProvider`가 catalog/Recipe/CUSTOM 공통. 런타임은 `client.supports`만 검사해 enabled-operations 변경으로 실행 중 Task를 중단하지 않는다. provider null은 INVALID.
- **H 외부 IO**: PASS. `read`는 짧은 readOnly 트랜잭션으로 호출 전에 닫히고 fake가 활성 트랜잭션을 거부한다. `TimeBoundedInstallationOperationsClient`는 `BoundedCallExecutor` 얇은 위임이며 한 turn에 외부 호출 한 번이다. TC 전용 bounded 테스트는 없으나 Round 1 판단과 같이 결함으로 보지 않는다.
- **I 조회**: 변경 없음. 신규 두 테이블의 finder는 unique 제약이 커버한다.
- **J 스키마·테스트**: PASS. JPA 선언만, Flyway 없음, `@Transactional(NOT_SUPPORTED)`, 주입 Clock, 실제 롤백 검증. FLAG는 P2-E.
- **K 구조**: 대체로 PASS. 생성자 주입, record/sealed, 한국어 클래스 Javadoc, `@Builder` DTO. FLAG는 P2-B의 dead 분기와 fake의 한 줄 다중 문장.

## 판정

코드 통합 리뷰 통과: 예
P0 0건 / P1 0건 / P2 5건 (P2-A~E, 모두 비차단이며 P2-A는 증적 보존 관점에서 반영을 권장)


<a id="code-round-3"></a>

## Code Round 3 — P2-A~E 해결, 직접 영향 보완 1건

- 검토일: 2026-09-09. 이전 통합 PASS를 재확장하지 않고 수정 5건과 직접 영향을 검증했다.
- 고정 사본: `/private/tmp/pipeline-adr023-final-review-20260908T155413Z`, 00:54:13 KST 캡처.
- 이전 통합 사본 대비 변경 파일 16개와 SHA-256 manifest를 포함했다.
- 검증 로그: `/private/tmp/adr023-final-tests.log`. full `mvn test` 354건 PASS,
  00:53:07 KST 완료, 15.591초. HTTP 58건 / TC 41건 포함.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=263063`, `num_turns=43`.
- `modelUsage.claude-fable-5-1`: input 226, output 20,930, thinking 12,351,
  cache-read 482,956, cache-creation 118,421 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 1,968/output 29 tokens로 기록됐다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-code-final-round3.json`. stderr는 비어 있다.
- 판정: **코드 통합 리뷰 통과 예 — P0 0건 / P1 0건 / P2 1건.**

### Code Round 3 원문

검증을 마쳤습니다. 고정 사본만 읽었고 파일 수정이나 명령 실행은 하지 않았습니다.

## 검증 로그

validation.log는 354건, 실패 0, 오류 0, 스킵 0, 종료 2026-09-09T00:53:07+09:00, 15.591초로 프롬프트와 일치합니다. TestConnectionTaskTest 41건, HttpInstallationTaskTest 58건입니다. Round 2 대비 증가분 9건은 TC 신규 7건과 HTTP 신규 2건의 합과 정확히 맞습니다.

## P2-A~E 해결표

| # | 상태 | 근거 |
|---|---|---|
| P2-A | 해결 | `ObservationRecorder.java:53-62`가 exchange null이면 http_* 필드를 건드리지 않고 attempt 확보와 inputId만 처리한다. 실제 무응답 호출은 `TestConnectionTask.java:80-81`, `HttpRequestTask.java:92-93`이 operation만 있는 exchange를 만들어 `TaskStateMachine.java:60,133`에서 최신 호출로 갱신한다. 두 경우가 섞이지 않는다. TC 사전검사 실패는 `TestConnectionTask.java:63-65,92-98`이 `Failed`로 감싸 `TaskStateMachine.java:51-54`에서 recorder를 거치므로 last_error_code가 Task 오류와 같아진다. 실행 행 유실 시 `TestConnectionResultRecorder.java:37-39`는 행을 만들지 않고 MISSING으로 종결하며, exchange가 null이라 원래 attempt의 접수 원문이 남는다. 테스트 `deadlineBeforeFirstPollPreservesTheAcceptedStartResponse`가 HttpResponseDetail 전체 동등성으로 보존을, `postTimeoutReplacesGetEvidenceWithAnUnansweredPost`와 `recommendationTimeoutKeepsOnlyTheOperationMetadataAndRetries`가 실제 전송 실패의 operation-only 기록을 assert한다. |
| P2-B | 해결 | `TestConnectionExecutionService.java:54-58`이 Optional 반환, `:83-91` builder와 ifPresent. `TestConnectionTask.java:45-49,63-66,84`가 map/orElseGet/filter로 소비하며 `executionId` 센티널과 dead 분기는 사라졌다. 분류는 유지된다. 행 유실은 MISSING, deadline·provider·target·키 불일치는 `:101-105`에서 INVALID로 호출 전 종결. |
| P2-C | 해결 | `TestConnectionResultDetail.java:37-49`가 lastObservedAt/lastErrorAt null이면 해당 metadata를 null로 둔다. 테스트 `normalOnlyResultDetailOmitsErrorMetadata`, `errorOnlyResultDetailOmitsNormalMetadata`가 양방향을 assert한다. |
| P2-D | 해결 | ADR-016 `:17-20`, `:409`와 ADR-021 `:15-17`, `:654-655`가 구현 반영과 잔여 범위를 한 문장씩 구분한다. ADR-023 `:204-206`이 호출 없는 종결과 무응답 전송의 구분, `:261-262`가 사전검사 종결의 진단 동기화, `:271`이 null metadata를 서술한다. ErrorCode 13개는 `ADR-016:410`과, TaskOperation 28개는 `:411`과 일치한다. `:404-407`은 adapter·MySQL·프런트 미연동을 명시하고 완료를 주장하지 않는다. |
| P2-E | 해결 | 접수/poll 지연 통신실패 2건은 `handleCallFailure`의 `:85`와 `:86` 분기를 각각 밟고 EXECUTION_TIMEOUT, failCount 0, version·deadline 불변을 assert한다. 접수 24회 소진 테스트는 CHECK_ERROR, attempt 24개, 동일 키, version 미바인딩, poll 0회를 확인한다. IN_PROGRESS deadline 유실은 INVALID와 deadline 미재초기화를 확인한다. fake 두 파일의 한 줄 다중 문장은 정리됐다. |

## 수정에서 남은 결함

**P2-F. 호출 없는 사전검사 실패의 errorResponse metadata가 실행되지 않은 POLL을 주장한다.** `TestConnectionResultDetail.java:44-48`, `TestConnectionResultRecorder.java:100-110`.
- Failure scenario: 접수 후 첫 poll 전에 deadline에 도달하면 recorder가 lastErrorAt을 채우고 exchange는 null이다. 상세의 `error_response`는 operation이 TEST_CONNECTION_POLL이고 status·received_at이 null인 객체가 된다. 실제 poll이 전송됐지만 무응답인 경우와 형태가 완전히 같아서 DTO만으로는 "poll 없음"과 "poll 무응답"을 구분할 수 없다. attempt 쪽에서 A가 만든 구분이 진단 쪽에서는 유지되지 않는다. `deadlineBeforeFirstPollPreservesTheAcceptedStartResponse`는 이 필드를 assert하지 않는다.
- 최소 수정: TestConnectionResult에 nullable `last_error_operation` 컬럼을 추가해 errorResponse에서 exchange의 operation을 저장한다. errorMetadata는 그 값이 null이면 null을 반환하고 상수 대신 저장값을 operation으로 쓴다. 위 테스트에 `errorResponse()`가 null이고 lastErrorCode가 EXECUTION_TIMEOUT인 assert를 추가한다.
- 비차단이다. 오류 코드와 detail은 정확하고 Task 전이에는 영향이 없다.

결함으로 세지 않은 참고 사항이 하나 있습니다. poll 단계에서 실행 행이 유실되면 detail 문구가 Task 쪽 "is missing" 대신 recorder의 "disappeared before write-back"으로 기록됩니다. 코드는 동일하게 EXECUTION_INPUT_MISSING입니다.

## 판정

코드 통합 리뷰 통과: 예
P0 0건 / P1 0건 / P2 1건


<a id="code-round-4"></a>

## Code Round 4 — 최종 P2-F 해결, 남은 지적 없음

- 검토일: 2026-09-09. P2-F 수정과 직접 회귀만 검증했다. 이미 통과한 전체 통합 범위를
  불필요하게 다시 확대하지 않았다.
- 고정 사본: `/private/tmp/pipeline-adr023-final-one-review-20260908T160317Z`,
  2026-09-09 01:03:17 KST 캡처. Java 4개와 관련 문서 변경, SHA-256 manifest 포함.
- 검증 원본: `/private/tmp/adr023-final-tests-round4.log`, 사본의 `validation.log`에 복사.
  전체 354건 PASS(실패/오류/스킵 0), 01:02:21 KST 완료, 12.676초.
- 실제 결과: `subtype=success`, `is_error=false`, `duration_ms=108487`, `num_turns=30`.
- `modelUsage.claude-fable-5-1`: input 194, output 8,647, thinking 2,837,
  cache-read 174,817, cache-creation 53,073 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 1,668/output 30 tokens로 기록됐다.
- 원본 JSON: `/private/tmp/pipeline-adr023-fable-code-final-round4.json`. stderr는 비어 있다.
- 판정: **코드 통합 리뷰 통과 예 — P0 0건 / P1 0건 / P2 0건.**
- 해결 확인: 호출 없는 실패는 오류 코드·설명·시각만 저장하고 errorResponse는 null이다.
  실제 poll 무응답은 operation-only metadata를 남겨 두 경우를 구분한다. 정상/오류 본문 보존,
  JPA nullable 컬럼, 두 경로의 테스트, ADR/HTML 설명에 직접 회귀가 없음을 확인했다.

### Code Round 4 원문

P2-F 관련 파일과 호출 경로 확인을 마쳤습니다. 결과를 정리합니다.

## P2-F 판정: 해결

**1. no-call 사전검사 실패는 code/detail/at만 남고 errorResponse는 null**
- `src/main/java/com/bff/pipeline/service/task/TestConnectionResultRecorder.java:102-105`가 code·detail·at을 항상 채우고, operation은 exchange가 null이면 null로 저장합니다.
- 호출 없는 경로는 모두 exchange가 null입니다. 마감 사전검사는 `TestConnectionTask.java:94`의 `timedOut(null)`, 나머지는 `HttpTaskResult.failed` 사용처인 `TestConnectionTask.java:70,93,96,139`와 `TestConnectionResultRecorder.java:38-39`입니다.
- `src/main/java/com/bff/pipeline/dto/pipeline/TestConnectionResultDetail.java:45`가 operation null이면 errorMetadata를 null로 반환하고, 46행은 상수 대신 저장값을 씁니다.

**2. 실제 전송 poll 무응답은 operation-only metadata로 구분됨**
- `TestConnectionTask.java:79-81`이 exchange 없는 통신 실패에 operation만 있는 exchange를 만듭니다. 마감 초과면 86행 `Failed(timedOut(exchange))`, 아니면 88행 `CallFailed`로 recorder에 도달하며 양쪽 모두 `recordFailure`로 operation이 저장됩니다.
- 결과 DTO는 operation이 POLL이고 status/receivedAt/body가 null인 객체가 되어 1번의 null과 형태가 다릅니다.

**3. 정상 응답/오류 본문 보존 유지**
- 본문이 있는 모든 오류 exchange는 `HttpResponses.bounded`(`HttpResponses.java:65`)를 거쳐 operation이 항상 채워지므로 errorResponse가 null로 바뀌는 회귀가 없습니다. 통신 예외의 exchange도 `StepRunner.java:110-115`를 거쳐 `handleCallFailure`에서 다시 bounded됩니다.
- `normalMetadata`(`TestConnectionResultDetail.java:37-42`)는 변경이 없고 정상 관측은 poll에서만 생성되므로 상수 사용이 맞습니다.

**4. nullable 새 컬럼 JPA/길이**
- `src/main/java/com/bff/pipeline/entity/TestConnectionResult.java:50-51`에 nullable 기본, `length = TaskAttempt.HTTP_OPERATION_LENGTH`(48)로 선언됐고, 실제 DDL은 `validation.log:95`에 `last_error_operation varchar(48)`로 생성됐습니다. operation 값은 고정 집합이라 최대 19자입니다.

**5. 두 테스트가 실제 두 경로를 보증함** (`src/test/java/com/bff/pipeline/service/TestConnectionTaskTest.java`)
- `deadlineBeforeFirstPollPreservesTheAcceptedStartResponse` 545-551행: 엔티티와 DTO의 lastErrorCode가 EXECUTION_TIMEOUT, `errorResponse()` null, `client.polls` 비어 있음을 assert합니다.
- `pollCommunicationFailureAfterDeadlineTerminatesTheSameAttempt` 621-626행: polls 1건, operation이 TEST_CONNECTION_POLL, statusCode/receivedAt/body가 null임을 assert합니다.
- 기존 본문 보존 assert(182-184, 205, 217, 353, 489, 573-574행)는 그대로 통과합니다.

**문서 동기화**: ADR `023-reconfirmation-http-tasks-and-execution-input.md:271-274, 298`, HTML `adr-023-explained.html:132`에 null 의미와 구분 규칙이 코드와 일치하게 기술됐습니다.

**검증 로그**: `validation.log:13196-13202`에 354건, 실패 0/오류 0/스킵 0, 2026-09-09T01:02:21+09:00, 12.676초, `TestConnectionTaskTest` 41건이 기록돼 있어 프롬프트와 일치합니다.

**수정 직접 회귀**: 없음.

결함으로 세지 않은 참고 사항이 둘 있습니다. 프롬프트의 테스트 경로는 `service/task/`였으나 실제 파일은 `service/TestConnectionTaskTest.java`이며 changed-files.txt는 후자로 정확합니다. 또한 ddl-auto update 환경에서 이 컬럼 추가 이전에 기록된 오류 행이 있다면 본문은 남고 errorResponse만 null이 되는데, 신규 테이블이고 운영 MySQL 미검증은 이미 확정된 잔여 범위입니다.

코드 통합 리뷰 통과: 예
P0 0건 / P1 0건 / P2 0건
