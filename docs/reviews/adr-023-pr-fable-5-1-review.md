# ADR-023 PR #55 Fable 5.1 리뷰

## 최신 판정 — 2026-09-09

[PR #55](https://github.com/bluefa/pipeline-orchestrator/pull/55)의 **Round 1 통과: P0 0 / P1 0 / P2 6**.
P2는 소스·명시된 계약과 대조하여 평가·수정한다. P2-5의 `custom_allowed=true`는 현재 모든 정의를
CUSTOM에서 허용한다는 계약과 응답 호환성을 고려해 root가 적용 여부를 판단한다.

- 검토 HEAD: `8bd49e27d6fec3323d1a0272e5775f3241be0718`.
- PR base: `fb7dc777785b603b91741f7bf738501517dbe6da`.
- 고정 사본: `/private/tmp/pipeline-adr023-pr55-review-20260908T232842Z`,
  2026-09-09 08:28:42 KST에 정확한 HEAD의 `git archive`로 생성했다.
- 실제 PR diff 변경 파일은 **98개**다(`pr-changed-files.txt`). 아래 reviewer 원문의 92개 표기는
  집계 차이가 있어 실제 manifest 수로 바로잡는다. 신규·수정 파일은 사본에 모두 포함했다.
- 최신 main 통합 `mvn test`: **372건 PASS**(실패/오류/스킵 0), 08:26:40 KST 완료, 15.687초.
  원본 `/private/tmp/adr023-main-integration-full.log`를 사본 `validation.log`로 복사했다.
  로그 자체에 HEAD는 없으며 root가 마지막 Java freeze 이후 문서만 변경했음을 확인했다.
- 이전 checkout의 354건/0 finding 증거는 이번 PR 판정과 구분한다.
- 운영 adapter·실제 upstream 계약·실제 MySQL 검증·프런트 연동은 남아 있다.
  UI 설명 HTML 후속 작업은 이 HEAD 사본에 없고 이번 Java 리뷰의 결함으로 집계하지 않았다.

## Round 1 모델 증거

- 실행: 인증된 Claude CLI `--model claude-fable-5-1 --effort high`, Read/Glob/Grep만 허용한
  읽기 전용 리뷰. 원본 저장소 대신 고정 사본만 읽도록 제한했다. 파일 수정/커밋/푸시/댓글 없음.
- 결과: `subtype=success`, `is_error=false`, `duration_ms=737559`, `num_turns=56`.
- 실제 `modelUsage.claude-fable-5-1`: input 354, output 54,308, thinking 43,421,
  cache-read 2,122,366, cache-creation 372,712 tokens. canonicalModel은 `claude-fable-5-1`.
- CLI 보조 `claude-haiku-4-5-20251001`도 input 2,286/output 28 tokens로 기록됐다.
- 원본 JSON: `/private/tmp/pipeline-adr023-pr55-fable-round1.json`. stderr는 비어 있다.

## Round 1 원문

## 검증 로그와 리뷰 대상

- **대상**: PR #55, head `8bd49e27d6fec3323d1a0272e5775f3241be0718`, base `fb7dc777785b603b91741f7bf738501517dbe6da`. 고정 사본의 `pr.diff` 전체(문서 6개, 신규 Java 39개 포함 92개 파일)와 관련 소스를 읽었습니다. 신규 파일 누락 없음.
- **새 검증 로그**: `validation.log`는 종료 시각 2026-09-09T08:26:40+09:00, 15.687초, BUILD SUCCESS입니다.

| 항목 | 값 |
|---|---|
| 전체 | 372건 / 실패 0 / 오류 0 / 스킵 0 |
| HttpInstallationTaskTest | 58 |
| TestConnectionTaskTest | 46 (restart 결합 5건 포함) |
| RestartPipelineTest | 11 |

- 로그에는 커밋 해시가 없습니다. HEAD 사본의 세 테스트 파일에서 `@Test`와 parameterized 케이스 수를 직접 세어 58/46/11이 로그와 정확히 일치함을 확인했고, 37개 클래스 합계도 372입니다. 이전 기록(`docs/reviews/adr-023-fable-5-1-review.md`)의 354건·01:02 KST 증거는 다른 사본이며 이번 판정에 쓰지 않았습니다.

## 결함

**P0**: 없음. **P1**: 없음.

**P2 (6건)**

1. **restart 경고 대체로 기존 Terraform in-flight 안내 소실** — `PipelineRestarter.java:202-208`. suffix에 공통 Task가 하나라도 있으면 새 키 안내만 반환하고 Terraform 경고를 계산하지 않습니다. 시나리오: AWS RECONFIRM이 `AWS_SERVICE_DESTROY_V1` dispatch 직후 실패해 재시작하면, destroy job이 아직 실행 중일 수 있다는 기존 안내가 preview에서 사라집니다. 최소 수정: 두 경고를 리스트에 누적. 누락 테스트: 최근 종료된 RECONFIRM 원본의 preview가 두 경고를 모두 담는지.
2. **preview가 승계될 NLB 옵션을 보여주지 않음** — `RestartPreview.java:44-54`, 승계 로직은 `PipelineRestarter.java:157-160`. TaskToRun에 `apply_nlb_security_group`이 없어 운영자는 restart가 원본 옵션 true로 POST할지 알 수 없고, restart 요청에 override도 없습니다. 최소 수정: TaskToRun에 필드 추가 후 `computation.steps()`(suffix와 인덱스 정렬)에서 채움. 누락 테스트: 옵션 true 원본의 preview 필드 검증.
3. **NLB 옵션 규칙 중복** — `PipelineCreator.java:66-69`와 `PipelineCreator.java:126-133`. catalog 경로는 recipe 단위 `steps().contains(...)`, CUSTOM/restart는 step 단위 operation 비교로 같은 규칙을 다른 식으로 씁니다. 발산 위험(§5.6 DRY). 최소 수정: catalog plan의 step에도 `validateStep`을 돌리거나 `requireNlbOptionAllowed(provider, definition, flag)` 하나로 추출.
4. **`charset=utf8` 별칭을 계약 위반으로 종결** — `HttpResponses.java:50-55`. 정상 2xx 본문이 `application/json;charset=utf8`로 오면 CHECK_ERROR 비재시도 FAILED가 됩니다. 실제 adapter 미연결이라 지금은 잠재적이지만 닫힌 분류의 오탐입니다. 최소 수정: `Charset.forName(...)`으로 정규화해 UTF-8 동치 비교. 누락 테스트: `charset=utf8` 200이 채택되는지.
5. **`custom_allowed`가 상수 true** — `TaskCatalogEntry.java:38`. 진실원 없는 고정 필드로 §5.10 speculative generality에 해당합니다. 최소 수정: TaskDefinition 속성으로 두거나 필드 제거(ADR-023 결정 2 문구 동기화).
6. **신규 wire DTO의 snake_case·엔드포인트 테스트 부재** — `DtoSnakeCaseSerializationTest.java:139-160`은 컴파일 보정만 있습니다. `HttpResponseDetail`, `ConfirmationInputDetail`, `TestConnectionResultDetail`, `TaskAttemptView.http`, `TaskCatalogEntry` 신규 3필드의 직렬화와 새 GET 3개의 MockMvc 계약(200/404 code)이 검증되지 않습니다. 누락 테스트: 각 DTO 1건씩 snake_case 키 assert, 404 code assert.

참고(결함 미집계): ADR-023 891행이 `../adr-023-explained.html`을 링크하고 879행이 그 HTML의 QA를 기술하지만 HEAD에 파일이 없습니다. 후속 docs 작업이라는 전제는 인정하되, 병합 시 링크가 깨집니다.

## 축별 판정

- **한 Task 내부 GET→durable input→POST**: PASS. `HttpRequestTask.execute`가 body null이면 GET(HttpPrepared), 있으면 POST 재개. POST 재시도 시 GET 미호출, write-once 캡처, digest/키 검증 테스트 있음.
- **신규 3정의 ALL_CSP, CUSTOM 단독·반복, CSP 전용 혼합, 업무 type 필터**: PASS. `supportsProvider`가 catalog·recipe·CUSTOM 공통 규칙. 반복 정의는 task_id 기반 키로 분리. 필터는 JPQL에서 적용 후 페이지/latest, tie-break id, 204, 잘못된 enum 400, 인덱스 3개 추가.
- **원문 보존과 실제 TC 종결 구분, 외부 필드 발명 없음**: PASS. 접수 202≠DONE, version 상관관계, 정상/오류 본문 별도, terminal sticky. VerifiedContext는 typed 값이며 URL/헤더/승인 필드 발명 없음.
- **restart 상호작용**: PASS(P2 1·2 제외). 새 키·새 입력행(옵션만 승계)·새 TC 실행행·첫 Task deadline은 새 nextDueAt 기준, 원본 행 불변, 계보 유지, one-active-target 유니크 공유, preview/restart 동일 검증에서 capability·RECONFIRM 계약 재확인, 입력행 유실은 404. 비활성 operation 우회 경로 없음(catalog는 RecipeCatalog, CUSTOM/restart는 validateStep).
- **guarded report**: PASS. GET/POST/TC start/poll 각각 stale·cancel·rollback 테스트가 실제 트랜잭션으로 있음. 외부 호출 tx 밖(fake가 assert).
- **attempt 회계, 누락 attempt vs 입력 유실, no-call vs 실제 무응답**: PASS. exchange null은 기존 증적 유지, operation-only exchange로 무응답 구분, ensureAttempt 복구.
- **TC deadline/retry/late/transient/diagnostic**: PASS. 최초 READY 두 경로가 같은 initializer, retry 비연장, 응답 수신 후 재검사, transient poll은 같은 attempt(failCount 0), non-retryable 즉시 종결.
- **CSP scope/capability/runtime typed fail/Optional·context**: PASS. provider null은 EXECUTION_INPUT_INVALID, capability 상실은 OPERATION_UNAVAILABLE, 예외 없이 값으로 종결.
- **HTTP 오류 닫힌 분류/timeout/interrupt**: PASS(P2 4 제외). StepRunner 한 경계, retryable 운반, `BoundedCallExecutor`가 인터럽트 복원·전파.
- **schema JPA only, 길이 상한**: PASS. 신규 LONGTEXT 컬럼 추가, 기존 TEXT 불변, contentType/failureDetail clamp, 정체성 값은 잘라내지 않고 거절, 엔티티 상수 공유.
- **기존 회귀**: PASS. `CallFailure.retryable` 기본 true로 Terraform/Condition 경로 동일. Terraform poll 오류 예산은 `terraform_job_state` 경로로 독립.
- **§6/recurring**: PASS(P2 3·5 제외). 추가 줄에 HTML 태그·인라인 FQN·`.get(0)`·이름 분기 없음. sealed/exhaustive switch, Clock 주입, 테스트 `@Transactional(NOT_SUPPORTED)`.

**PR 리뷰 통과: 예** — P0 0건 / P1 0건 / P2 6건. P2는 평가 반영 대상이며 모두 비차단입니다.
