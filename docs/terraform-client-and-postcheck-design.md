# Terraform 클라이언트 인터페이스 재검토 + postCheck ADR 확장 (설계 노트)

- Status: ACCEPTED-IN-PART (2026-07-02 owner 답변 반영 — §5. 설계 2 채택, postCheck는 확장 A 확정. 코드 무변경)
- 입력: 확정된 InfraManager Terraform 실 API 명세 (AWS 3패밀리 / GCP 2 / Azure 1 / IDC 1,
  dispatch·job 조회·result 조회)
- 질문: ① 현행 "operation별 Feign 메서드 + 바인딩 `@Component`" 구조가 실 스펙에서 과한가 —
  더 나은 인터페이스 2개 이상 검토. ② result(= terraform log) 조회를 postCheck로서 lifecycle에
  어떻게 편입하고 ADR을 어떻게 확장할까.

---

## 0. TL;DR

- 실 스펙이 기존 가정 하나를 무너뜨렸다: impl-plan §5의 "operation마다 응답 형태(shape)도 다르다"는
  전제가 **거짓**으로 판명됐다. shape은 3종으로 수렴한다(dispatch 2형 + status 1형 + result String).
  operation마다 다른 것은 **HTTP 메서드·경로·쿼리 파라미터뿐** — 즉 코드가 아니라 데이터다.
- 그래서 권장은: 도메인 경계(`InfraManagerClient`)·`InfraManagerOperationRegistry`·
  `TerraformOperationBinding` 인터페이스는 **그대로** 두고, operation당 바인딩 클래스를
  **한 파일의 바인딩 카탈로그(@Bean 표)** 로 접는 설계 2. Feign은 실 API 1:1 메서드를 유지한다.
- postCheck: 클라이언트 표면에는 `terraformJobResult(jobId, operation)` 하나만 추가하면 되고
  이는 어느 설계안에서도 동일하다. lifecycle 편입은 **확장 A(관찰)로 확정**됐다(owner) — B(완료판정
  편입)·C(별도 태스크)는 구현 계획 없이 미래 경로 기록만 남긴다(§4.3). result 본문은 클 수 있어
  **별도 관찰 테이블 `terraform_result`(MEDIUMTEXT)** 로 저장한다 — §4.4.
- 카탈로그 규칙 하나 추가(owner 피드백): 행에는 **리터럴 인자 금지, 메서드 참조만** — `jobType` 같은
  operation-결정 상수는 Feign 인터페이스의 default 메서드 안에 닫는다(§2 설계 2).

---

## 1. 실 스펙에서 얻은 결정적 관찰

### 1.1 축 분해 — 무엇이 실제로 변하는가

| 패밀리 | dispatch | dispatch 응답 | job/result 조회 | 조회 파라미터 |
|---|---|---|---|---|
| AWS 서비스 | POST plan/apply, DELETE destroy (경로 분리) | `List<TerraformJobResponse>` | 타입별 경로 | 없음 |
| AWS BDC common | POST ×3 (경로 분리) | `BdcTerraformJobResponse` 단건 | 단일 경로 | `jobType` |
| AWS BDC service-level | POST plan/apply, DELETE remove | `BdcTerraformJobResponse` 단건 | plan 전용 / action 공용 경로 | action만 `jobType` |
| GCP 서비스 / BDC | POST 단일 엔드포인트 | `List<TerraformJobResponse>` | 단일 경로 | `jobType` |
| Azure BDC | POST apply, DELETE destroy (경로 분리) | `List<TerraformJobResponse>` | 타입별 경로 | 없음 |
| IDC (CX/BDP) | POST 단일 `action` 엔드포인트 | `BdcTerraformJobResponse` 단건 | 단일 경로 | `jobType`(+dispatch 시 `idcTerraformType`) |

**관찰 1 — 응답 shape은 3종으로 수렴한다.**
- dispatch: 목록형(`List<TerraformJobResponse>`) 아니면 단건형(`BdcTerraformJobResponse`).
  도메인 정규화 결과는 어차피 `List<String> jobIds`이므로 단건형은 원소 1개짜리 목록일 뿐이다.
  두 wire 형태에서 우리가 읽는 필드는 `id` **하나**다.
- job 조회: 전 패밀리 공통으로 `TerraformJob` 엔티티 직렬화(camelCase). 우리가 쓰는 건
  `terraformState`(폴 정규화) + postCheck용 `resultPath`·`failReason` 정도.
- result: 전부 `String`.

**관찰 2 — 변동은 전부 라우팅 데이터다.** HTTP 메서드(POST/DELETE), 경로, 쿼리(`jobType`,
`idcTerraformType`)만 다르다. "operation = shape이 다른 코드"였던 가정이 "operation = 경로가 다른
데이터 행"으로 바뀌었다. 이것이 이 문서의 모든 결론을 결정한다.

**관찰 3 — 오케스트레이터가 부를 것은 부분집합이다 (YAGNI).** 실행 단위는 8개(패밀리×CX/BDP 분해),
recipe가 쓸 job 타입은 **plan/apply/destroy 셋 다**(owner 확정 — plan도 recipe에 편입 예정)로 상한은
8 × 3 = **24 operation**, Feign raw 선언은 스펙 엔드포인트 1:1로 **~40개**다(default 메서드 제외).
단 카탈로그에 행을 만드는 시점은 recipe가 그 operation을 실제로 참조할 때다. `fail_reason`/
`error_guide_url`은 노출하지 않는 것으로 확정(owner) — 바인딩하지 않는다. registry 부팅 검증은
`TaskOperation` enum 기준이므로 "스펙에 있는데 안 만든 것"이 아니라 "recipe가 쓰는데 빠뜨린 것"만
잡는다 — 지금 철학 그대로. plan 태스크의 산출물은 result 그 자체이므로 §4.4의 저장 설계가 전제다.

**관찰 4 — 클라이언트 관심사가 아닌 것.** 순서 제약(GCP 서비스→BDC, IDC CX→BDP)은 서버가 예외로
강제하고 우리 recipe의 task 순서(BLOCKED→READY 체인)가 이미 보장한다 — 바인딩에 로직을 두지 않는다.
Azure destroy 조회의 target 필터 부재(`findByIdAndType`만 사용)는 상류 비일관성으로, 바인딩 주석으로만
기록한다.

---

## 2. 설계안 (2안 + 변형 2)

공통 전제: `InfraManagerClient` 4(+1)메서드, `TimeBoundedInfraManagerClient` 데코레이터, 닫힌 예외
어휘, registry 부팅 검증은 **어느 안에서도 불변**이다. "과하다"는 감각은 wire 계층(클래스·DTO 증식)에
대한 것이고, 바뀌어야 하는 것도 wire 계층뿐이다.

### 설계 1 — 현행 연장: operation별 바인딩 클래스 + wire DTO 공용화

현행 `ApplyNetworkBinding` 패턴을 ~24 operation(plan 포함, §1 관찰 3)으로 확장하되, DTO만 3종 공용(§3)으로 통합한다.

- 규모: Feign 메서드 ~40 + 바인딩 클래스 ~24 (+카탈로그 파일 없음).
- 장점: 지금 코드와 동형이라 이동 비용 0. operation별 quirk(주석·방어)를 둘 곳이 클래스라 명확.
- 단점: 클래스 ~24개가 전부 "경로만 다른 4줄짜리"가 된다 — **클래스가 데이터 노릇**을 한다.
  스펙 표와 코드의 대조가 24개 파일에 흩어지고, 새 op 추가가 "파일 생성" 의식이 된다.
- 이 안이 다시 옳아지는 조건: 패밀리별 응답 shape이 재분화하거나(관찰 1 붕괴), operation별
  전처리·방어 로직이 실제로 자라날 때.

### 설계 2 (권장) — 바인딩 카탈로그: 한 파일 @Bean 표 + Feign 1:1 유지

`TerraformOperationBinding`·registry·도메인은 그대로. **구현체 ~24클래스를 @Configuration 한 파일의
@Bean ~24개(각 2~4줄, 메서드 참조만)로 접는다.** 공통 정규화(단건→목록, `requireJobIds`, `toPoll`)는 제네릭
바인딩 하나가 소유한다.

```java
/** 실 API 스펙 표와 1:1로 대응하는 terraform 바인딩 카탈로그 — 한 화면이 스펙 diff 단위다. */
@Configuration
class TerraformBindingCatalog {

    // 제네릭 바인딩: operation + (dispatch, status, result) 람다 3개가 한 행(row)
    private static TerraformOperationBinding row(TaskOperation op,
            Function<String, List<DispatchedJob>> dispatch,
            Function<String, TerraformJobStatusResponse> status,
            Function<String, String> result) {
        return new CatalogTerraformBinding(op, dispatch, status, result);
    }

    @Bean TerraformOperationBinding awsServiceApply(InfraManagerFeignClient f) {
        return row(AWS_SERVICE_TF_APPLY,
                f::awsServiceApply,                     // POST .../terraform-jobs/apply → List
                f::awsServiceApplyJobStatus,            // GET  /infra/terraform-jobs/apply/{id}
                f::awsServiceApplyJobResult);           // GET  ... + /result
    }

    @Bean TerraformOperationBinding gcpBdcDestroy(InfraManagerFeignClient f) {
        return row(GCP_BDC_TF_DESTROY, f::gcpBdcDestroy, f::gcpBdcDestroyJobStatus, f::gcpBdcDestroyJobResult);
    }
    // ... 행 수 = recipe가 쓰는 operation 수
}
```

**행 규칙(owner 피드백 반영): 리터럴 인자 금지 — 모든 행은 메서드 참조만.** `f.gcpBdcDispatch(t,
"DESTROY")`처럼 호출부에 문자열 상수를 흘리지 않는다. `jobType`/`idcTerraformType`처럼 operation이
결정하는 상수는 Feign 인터페이스 안에서 닫는다 — raw 선언은 엔드포인트당 1개, operation별 구분은
**default 메서드**로:

```java
// raw 선언은 실 엔드포인트 1:1, 상수는 enum으로 default 메서드에 갇힌다
@PostMapping("/infra/target-sources/{id}/gcp/bdc-terraform-jobs")
List<TerraformJobResponse> gcpBdcDispatch(@PathVariable("id") long id,
        @RequestParam("jobType") TerraformJobType jobType);

default List<TerraformJobResponse> gcpBdcApply(long id)   { return gcpBdcDispatch(id, TerraformJobType.APPLY); }
default List<TerraformJobResponse> gcpBdcDestroy(long id) { return gcpBdcDispatch(id, TerraformJobType.DESTROY); }
```

- 이렇게 하면 카탈로그는 "operation ↔ 메서드 이름"의 순수 대응표가 되고, 오타 가능한 문자열은
  Feign 인터페이스 한 파일의 enum 파라미터로만 존재한다.
- Feign은 **실 API 1:1 concrete 메서드를 유지**한다(메서드당 선언 2줄). 경로가 애너테이션 상수라
  grep 가능하고 오타가 부팅 시 검증되며, 기존 인터셉터(bearer)·WireMock 테스트·어댑터 층이 그대로 산다.
- 장점: **스펙 표 ↔ 코드가 한 화면에서 1:1 대응** — API 스펙이 바뀌면 diff가 그 행 하나다.
  새 op 추가 = Feign 메서드 2~3개 + @Bean 4줄. 람다 시그니처가 dispatch/status/result 구현을
  컴파일 타임에 강제하고, 행 누락은 registry 부팅 검증이 잡는다(현행과 동일한 안전망).
- 단점: 클래스 단위보다 응집 강제가 약간 느슨하다. operation별 방어가 필요해진 행은 그때 클래스로
  꺼내면 된다(카탈로그와 클래스 바인딩은 같은 인터페이스라 공존 가능 — 점진 탈출구 내장).

### 설계 2-b (변형) — 순수 데이터 카탈로그 + 제네릭 전송

enum 행에 `(method, pathTemplate, params, dispatchShape)`를 데이터로 넣고, 전송은 `RestClient`(또는
Feign URI-override) 제네릭 4~5메서드로 실행한다.

- 장점: op당 진짜 1줄. 이론상 가장 작다.
- 단점: 경로 문자열 오류가 컴파일/부팅이 아니라 **호출 시점**에 드러나고, 기존 Feign 레이어
  (인터셉터·설정·테스트)와 이질적이다. 경로 ~30개 수준에서는 과투자 — 설계 2로 충분하다.
  경로가 수백 개로 늘거나 설정 파일에서 읽어야 할 때만 재검토.

### 설계 3 (변형) — 패밀리 단위 바인딩 (7클래스)

컨트롤러 패밀리당 바인딩 1개를 두고 내부에서 op→jobType을 분기한다.

- 장점: 클래스 수 절충(7).
- 단점: 패밀리 내부도 불규칙해서(AWS SL의 plan/action 이원 경로, Azure의 비대칭 destroy) 클래스
  안에 switch가 생긴다 — registry 패턴이 없앤 switch를 되들여오는 셈이고, "1 operation = 1 binding"
  이라는 registry 키 모델과도 어긋난다(패밀리 클래스가 바인딩 빈 2개를 노출하는 우회가 필요).

### 비교와 권장

| | 설계 1 (현행 연장) | **설계 2 (카탈로그)** | 2-b (데이터+제네릭) | 설계 3 (패밀리) |
|---|---|---|---|---|
| 바인딩 파일 수 | ~24 | **1** (+제네릭 1) | 1 | 7 |
| 새 op 추가 비용 | 클래스 1 + Feign 2~3 | **@Bean 4줄 + Feign 2~3** | enum 1줄 | 기존 클래스 수정 |
| 누락/오타 검출 | 컴파일+부팅 | **컴파일(람다)+부팅** | 호출 시점 | 컴파일+부팅 |
| 스펙 표와 대조 | ~24파일 산개 | **한 화면** | 한 화면 | 7파일 |
| 기존 층과의 이질성 | 없음 | **없음** | 있음(전송 교체) | 낮음 |

**권장: 설계 2.** 근거는 관찰 2 하나로 요약된다 — 변동이 데이터로 판명됐으므로 클래스는 표로 접되,
이 코드베이스가 지켜온 이득(부팅 검증, 컴파일 강제, 닫힌 예외, Feign 층)은 전부 유지하는 최소
이동이기 때문이다. 도메인·데코레이터·어댑터·registry는 한 줄도 바뀌지 않는다.

---

## 3. 공용 wire DTO — 3종이면 끝난다

per-operation DTO(`ApplyNetworkResponse` 등 가정 DTO 전부)는 폐기하고:

| DTO | 커버 범위 | 읽는 필드 |
|---|---|---|
| `record DispatchedJob(long id)` | 목록형·단건형 dispatch 응답 **둘 다** (`@JsonIgnoreProperties(ignoreUnknown)`) | `id`뿐 |
| `record TerraformJobStatusResponse(String terraformState, String failReason, String resultPath)` | 전 패밀리 job 조회 (엔티티 직렬화, camelCase) | 폴 정규화 + postCheck 재료 |
| `String` | 전 패밀리 result | 그대로 |

정규화 규칙(제네릭 바인딩 소유):
- dispatch: 단건형도 `List.of(response)`로 승격 → `id`를 문자열화 → 기존 `requireJobIds` → 기존
  `["…", …]` 재직렬화 계약(TerraformTask 파서)과 그대로 접속.
- status: `terraformState` → `TerraformPoll` 매핑 — **owner 확정**:

  | terraformState | PLAN / APPLY job | DESTROY job |
  |---|---|---|
  | `COMPLETED` | finished + succeeded | not finished (계속 폴링) |
  | `DESTROYED` | not finished (계속 폴링) | finished + succeeded |
  | `FAILED` | finished + failed | finished + failed |
  | 그 외 전부 (미지 포함) | not finished | not finished |

  성공을 뜻하는 terminal 상태가 jobType마다 다르므로(`COMPLETED` vs `DESTROYED`) 매핑 함수는
  "기대 성공 상태" 하나를 파라미터로 받는다 — 카탈로그 행이 operation을 알고 있어 자연스럽다.
  미지 상태를 `CallFailed`로 닫지 않고 **not-finished로 두는** 이유(owner 지침): 미지/전이 중 상태는
  다음 폴이 해소하고, 영영 안 끝나는 경우도 `executionTimeout`이 회수해 fresh run으로 재시도한다 —
  무한 대기가 아니라 기존 예산(§6) 안의 지연이다.

---

## 4. postCheck — result 조회의 lifecycle 편입 (ADR 확장)

result는 terraform log를 보는 창구이고, ADR에서 한 번 언급된 postCheck(완료 후 결과 확인)가 바로
이 지점이다. 참고로 README는 extensibility.md가 "task post-checks" seam을 다룬다고 적고 있으나
현재 그 문서에 해당 절이 없다 — 이 설계가 그 빈 절을 채우는 내용이 된다.

### 4.1 클라이언트 표면 — 어느 안이든 동일, 지금 추가 가능

- `InfraManagerClient`에 `String terraformJobResult(String jobId, TaskOperation operation)` 추가.
  (status와 마찬가지로 result 경로가 operation별이라 operation 인자가 필요하다.)
- `TerraformOperationBinding`에 `String result(String jobId)` 추가 — 설계 2에서는 카탈로그 행의
  세 번째 람다가 이미 그 자리다.
- 예외 규약(닫힌 3예외)·per-call 타임아웃(데코레이터)·bearer 인터셉터 모두 자동 적용. lifecycle
  결정과 독립적으로 **클라이언트 표면은 지금 확정해도 안전**하다.

### 4.2 lifecycle 편입 — 3단계 확장안

**확장 A — 관찰(Observation)로만 기록. (✅ owner 확정)**
`TerraformTask`가 종결 폴을 낸 그 turn에(모든 job finished) job별 result를 조회해 관찰 행에 남긴다.
result 조회 실패는 상태에 관여하지 않는다 — DONE은 DONE, 관찰만 비었다고 기록.
- ADR-016 문안: Decision 3(Observation is separate from state)에 한 문장 추가 —
  *"Post-completion result retrieval (postCheck) is observation: the terraform log tail and
  `resultPath` are recorded per job on the concluding poll, and a failure to fetch them never
  changes task state."*
- 저장: terraform log는 꽤 클 수 있다(owner) — 기존 관찰 행에 끼워 넣지 않고 **전용 관찰 테이블에
  MEDIUMTEXT로 저장**한다. 상세 설계는 §4.4.

**확장 B — 완료 판정에 편입: postCheck가 성공의 일부. (승급 목표)**
`terraformState = SUCCESS`만으로 DONE을 선언하지 않고, result를 검증(예: 오류 시그니처 부재)해
통과해야 DONE. 검증 실패는 `JOB_FAILED`와 같은 재시도 경로(fresh run, 동일 failCount 예산),
result 조회 자체의 실패는 `CHECK_ERROR`(재시도)로 흡수 — **새 상태·새 스키마·새 태스크 없음**.
- 편입 자리는 이미 열려 있다: `extensibility.md`의 `TerraformJob` seam(per-job completion-check
  ownership — "각 job이 자기 완료 판정을 스스로 소유"). `JobIdTerraformJob`의 판정을
  status+result 2단으로 확장하거나 `ResultVerifiedTerraformJob` 구현을 추가하면 된다. 이 seam이
  "단일 구현인데 인터페이스"라는 예외를 owner 결정으로 감수한 이유가 정확히 이 시나리오다.
- ADR-016 문안: §5의 *"task completion is a code-level check"* 문장을 *"completion check =
  status poll **+ post-check (result verification)**; a task is DONE only when its post-check
  passes"*로 확장하고, §6에 *"a result-fetch error is a failed poll: it shares the task's
  `fail_count` budget"* 한 줄을 더한다.
- **승급 전제(현재 미충족):** result 본문에서 무엇을 성공/실패로 판별할지 규칙이 정의돼야 한다.
  규칙 없는 게이트는 거짓 실패 제조기다 — 열린 질문 3.

**확장 C — 별도 `POST_CHECK` 태스크 kind. (보류)**
recipe에서 TERRAFORM_JOB 뒤에 POST_CHECK step을 둔다. `TaskTypeRegistry`는 이미 새 kind를
지원한다(decision-7의 `PostCheckProbeTask` 테스트 빈이 증명). 그러나 **선행 task의 job id를
넘겨받을 채널이 없다** — 현재 task 간 데이터 흐름은 0이고, 이웃 task의 `task_attempt`를 읽는 것은
ADR-016 §3의 경계(관찰 행은 자기 task의 reconciler만 읽는다)를 넓히는 일이다. 필요해지면
`task.output`(DONE과 같은 트랜잭션에 쓰는 작은 durable 컬럼)을 신설하는 것이 Decision 1
(database is the only state)에 맞는 통로다.
- 보류 해제 조건: postCheck가 자기만의 재시도 케이던스·운영자 가시성(별도 attempt/check 행)을
  요구하거나, recipe별로 켜고 꺼야 할 때. 그 전까지는 확장 B가 같은 효과를 더 싸게 낸다.

### 4.3 확정 경로 (owner)

**확장 A가 최종 결정이다.** 4.1(클라이언트 표면) + 종결 폴 turn의 result 관찰 기록 + §4.4 저장으로
간다. 확장 B·C는 **구현 계획이 없다** — 위 서술은 미래에 "result가 성공 판정을 뒤집어야 한다"(B)거나
"task 간 출력 전달"(C)이라는 새 요구가 실제로 생겼을 때 다시 설계하지 않도록 경로만 기록해 둔 것이며,
그런 요구가 없는 한 A가 전부다.

### 4.4 완료 집계 정책과 result 저장 설계

**선행 결정 — 완료 집계는 fail-fast가 아니라 "전원 terminal 대기"다.** FAILED job을 관측한 순간
attempt를 접지 않고, **모든 job이 terminal(성공이든 실패든)이 될 때까지 폴링을 계속한 뒤** JOB_FAILED로
종결한다. 근거(중요도순):

1. **재실행 위생 — 주된 근거.** 재시도는 whole-task fresh dispatch다(ADR-016 §6). 형제 job이 아직
   인프라를 변경 중인데 재dispatch하면 같은 대상에 terraform이 동시 실행된다 — state lock 충돌로 새
   attempt가 즉사해 failCount만 태우거나, 최악에는 동시 변경 경합이 난다. 전원 terminal 대기는 "이전
   attempt의 손이 인프라에서 완전히 떨어진 뒤에만 fresh run"을 별도 장치 없이 보장한다.
2. **관찰 완결성 — 부수 이득.** 종결 turn에 모든 job의 진짜 결과(성공/실패)와 result가 확보된다.
   "미종결 job을 어떻게 기록하나"라는 §4.4 저장 쪽 예외가 정상 경로에서 사라진다.
3. **비용은 이미 유계.** 추가 대기의 상한은 기존 executionTimeout이다(ADR-016 §6). 실패 확정이
   늦어지는 지연이 비용의 전부이고, 새 상태·새 스키마·새 설정이 없다.

데드라인 도달 시(미종결 job 잔존): 그 시점까지 FAILED 관측이 하나라도 있으면 `JOB_FAILED`, 없으면
`EXECUTION_TIMEOUT`으로 종결한다 — 원인이 더 정확한 쪽을 terminal errorCode로 남긴다. 둘 다 재시도
가능이라 실행 경로는 동일하다.

**저장 전제**(owner): result 본문은 꽤 클 수 있고, TEXT/BLOB급 저장이 필요하다.

**S1 — 기존 관찰 행(`task_attempt`/`task_check`)에 컬럼 추가: 기각.** 두 테이블은 reconciler가
매 폴 읽는 hot 행이고, `task_attempt.response`는 job id 파서 계약을 이미 소유한다. 최대 수 MB짜리
로그를 거기 싣으면 폴링 read가 로그 I/O를 같이 지불한다. 게다가 result는 attempt당이 아니라
**job당**이라 카디널리티도 맞지 않는다.

**S2 — 전용 append-only 관찰 테이블 `terraform_result`: 권장.**

```sql
CREATE TABLE terraform_result (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id         BIGINT       NOT NULL,
  attempt_no      INT          NOT NULL,
  job_id          VARCHAR(64)  NOT NULL,
  succeeded       BOOLEAN      NOT NULL,           -- 그 job의 폴 종결 결과 (finished job만 행을 얻는다)
  result_path     VARCHAR(1024) NULL,              -- status 응답의 resultPath (원본 전문 포인터)
  result          MEDIUMTEXT   NULL,               -- terraform log 본문
  truncated       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at      DATETIME(6)  NOT NULL,
  UNIQUE KEY uk_task_attempt_job (task_id, attempt_no, job_id)
);
```

- **타입: MEDIUMTEXT(16MB).** TEXT(64KB)는 terraform log에 부족할 수 있고, 로그는 UTF-8 텍스트라
  BLOB보다 TEXT 계열이 맞다(열람·검색이 자연스럽다). 16MB 초과분은 tail 우선으로 절단하고
  `truncated`를 표시 — 실패 원인은 로그 끝에 몰린다. 전문이 필요하면 `result_path`로 원본을 찾는다.
- **쓰기 경로(write path) 정밀 정의.**
  1. **시점**: attempt가 **판정으로 종결되는 turn** — `check()`가 SUCCEEDED / JOB_FAILED /
     EXECUTION_TIMEOUT을 내리는 자리에서, **상태 전이가 커밋되기 전**, 트랜잭션 밖(외부 호출 규칙
     동일)에서 수행한다. (성공 turn만이 아니다 — 실패·타임아웃으로 접히는 attempt도 기록한다.)
  2. **단위: 그 turn에 finished로 관측된 job당 1행.** 집계 정책(전원 terminal 대기) 덕에
     SUCCEEDED/JOB_FAILED 종결 turn에는 전 job이 finished다 — 즉 **정상 종결에서는 모든 job이 행을
     얻는다.** EXECUTION_TIMEOUT 종결만 미종결 job이 행 없이 남는데, 그 부재는 attempt의
     errorCode + `task_attempt.response`의 job 목록으로 이미 설명된다 — 미종결 job의 빈 행은 유도
     가능한 정보라 싣지 않는다(무리한 보장 대신 효율). 재시도는 새 attempt_number + 새 dispatch(새
     job id들)이므로 attempt별로 행이 따로 쌓인다.
  3. **job별 독립 — 부분 실패 격리.** result 본문 조회는 job마다 독립적으로 수행하고, 한 job의
     조회가 실패(CallFailed/CallTimeout)해도 **나머지 job의 저장에는 영향이 없다**(루프 계속).
     조회에 실패한 job도 행 자체는 남긴다 — `result = NULL`, `result_path`(status 폴이 실어 온
     포인터)와 `succeeded`는 채워서. 포인터가 보존되고 "본문 결손"이 로그가 아니라 DB에서 보인다.
  4. **트랜잭션: 행마다 자체 단문 트랜잭션**(`repository.save`). 엔진의 상태 전이 트랜잭션에
     참여하지 않는다 — 관찰 실패가 전이를 굴리거나(롤백) 전이 실패가 관찰을 잃게 하지 않는다.
  5. **멱등·자기치유**: `UNIQUE(task_id, attempt_number, job_id)` + 존재 선검사 + 중복 insert 무시.
     기록 도중 크래시/리스 회수가 나면 상태 전이가 아직 없으므로 다음 폴 turn이 같은 terminal 판정을
     다시 내리고 recorder가 재실행된다 — 이미 저장된 행은 skip, 빠진 행만 채워진다. 본문이 영구
     결손되는 유일한 경우는 "조회 실패 + 같은 turn의 상태 전이 성공"이며, 그때도 포인터 행은 남는다.
  6. **상태 무관여**: 조회·저장의 어떤 실패도 반환되는 TaskProgress를 바꾸지 않는다(확장 A 정의).
     단 CallInterrupted는 규칙대로 전파한다(fail-fast) — 5의 자기치유가 이를 회수한다.

- **"아예 누락" 매트릭스 — 완전 무기록으로 남는 경로와 그 이유(전부 의도된 트레이드오프).**
  정상 종결(전 job terminal) attempt는 모든 job의 행을 보장한다. 행이 없는 경우:
  1. **EXECUTION_TIMEOUT 종결 시점의 미종결 job** — 정책상 의도된 생략. attempt errorCode와
     `task_attempt.response`의 job 목록이 "이 job은 데드라인까지 안 끝났다"를 이미 말해 준다.
  2. **job id 원천 부재** — dispatch 응답이 유실된 채(lost-response 분기) 또는 malformed(CHECK_ERROR)로
     attempt가 접힌 경우. 기록할 job id 자체가 없어 어떤 설계로도 불가능하며, `task_attempt.response`의
     부재/오염 자체가 진단 정보다.
  3. **poll 도중 CallFailed/CallTimeout으로 접힌 attempt** — InfraManager 자체가 안 닿는 상황이라
     result 조회도 같이 실패했을 것이 거의 확실하다(같은 서비스). `task_check`의
     api_error/call_timeout 카운터가 원인을 남기고, 다른 attempt의 행은 살아 있다.
  4. **INSERT 실패 + 같은 turn 상태 전이 성공** — 관찰 테이블 공통의 잔여 리스크(ADR-016 §3
     invariant). 전이 트랜잭션에 넣으면 막을 수 있으나 관찰 실패가 상태를 굴리게 되고 MEDIUMTEXT
     쓰기가 hot 전이 트랜잭션을 늘어뜨리므로 채택하지 않는다(확장 A 원칙 위반).
  요약: "정상 동작으로 성공/실패까지 간 terraform의 결과가 조용히 안 남는" 경로는 없다. 남는 넷은
  타임아웃·InfraManager 불통·응답 유실·DB 장애라는 상위 사건의 그림자이고, 각각 다른 관찰 신호
  (attempt errorCode, task_check 카운터)가 그 사건 자체를 기록한다.
- **ADR-016 문안:** §3의 관찰 테이블 목록에 세 번째로 한 줄 추가 — *"`terraform_result` — 완료
  시점의 job별 terraform log(tail-truncated)와 `result_path`; 상태 전이·claim·스케줄링은 절대 읽지
  않는다."*
- **plan과의 접속:** plan 태스크는 산출물이 result 그 자체이므로 이 테이블이 plan 출력의 열람 창구가
  된다. 운영 노출은 후속 admin API(예: task별 result 조회)로 — 이 설계의 범위 밖.

**S3 — 본문 저장 없이 `result_path`만: 기각.** 저장은 가장 싸지만 owner가 본문 저장 필요를 확인했고,
열람이 InfraManager/스토리지의 보존 주기와 접근 권한에 종속되는 것도 리스크다.

### 4.5 관리자 노출 — 저장만으로는 대시보드에 안 보인다

`docs/admin-pipeline-dashboard-requirements.md`의 Task 상세 패널(P5, `TaskDetail`)은 attempt의 원시
`response`(= job id 목록)와 `task_check` 폴 요약까지만 싣는다 — **terraform_result를 읽는 경로가 없다.**
"관리자에게 terraform 결과를 보여준다"가 성립하려면 저장(§4.4) 위에 읽기 경로 둘이 필요하다:

1. **P5 확장 — 메타데이터만 인라인.** `TaskDetail`의 attempt 항목에 job별 result 메타를 추가한다:
   `job_id, succeeded, truncated, result_path, has_body(boolean)`. **본문은 싣지 않는다** — 최대
   16MB짜리 로그를 상세 패널 JSON에 인라인하면 패널 전체가 그 로그 I/O를 지불한다. InfraManager가
   status와 result를 분리한 것과 같은 이유로 우리도 분리한다.
2. **P11 신설 — 본문 전용 엔드포인트.**
   `GET /install/v1/pipelines/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/result`
   → 저장된 log 본문(text). UI는 노드 클릭 → 메타 확인 → "로그 보기"에서만 이걸 부른다.

관찰 테이블을 admin 쿼리 경로가 읽는 것은 기존 선례 그대로다 — P5가 이미 `task_attempt`/`task_check`를
읽는다. "엔진은 절대 읽지 않는다"는 invariant는 엔진(claim·스케줄링·전이) 얘기지 admin 조회 얘기가
아니다.

**커버리지 한계 2가지(요구사항과 대조 필요):**
- **실행 중 로그는 없다.** 확장 A는 종결 turn에만 기록하므로 RUNNING 중인 job의 로그는 DB에 없다.
  대시보드 요구가 "완료/실패 후 결과 확인"(postCheck 정의 그대로)이면 충분하고, "실행 중 진행 로그
  스트리밍"까지면 확장 A로는 부족하다 — 그건 InfraManager result API를 실시간 프록시하는 별도
  결정(admin→InfraManager 의존 추가)이 필요하다.
- **본문 없는 행/행 없는 job이 존재한다.** 조회 실패 job은 본문 없는 포인터 행이고, §4.4 누락
  매트릭스의 네 경로(타임아웃 시점 미종결 job 등)는 행이 없다. UI는 attempt의 job 목록
  (`task_attempt.response`)과 대조해 "본문 없음(result_path 참조)" / "기록 없음(attempt errorCode
  참조)"을 구분 표기하면 된다.

구현 시 `admin-pipeline-dashboard-requirements.md`의 Task 상세 패널 표에 terraform result 행(✅)과
P11을 추가한다.

### 4.6 렌더링 주의 — result 본문은 원시 terraform 출력이다 (owner 확정, 2026-07-06)

P11이 내려주는 `content`는 InfraManager가 저장한 terraform 출력 **원문 그대로**다. backend는 어떤
정규화(ANSI strip, 색상 제거, 줄 정리)도 하지 않는다 — **owner 결정: 이 가공은 backend 책임이 아니다.**
소비자(FE/BFF)는 다음을 전제해야 한다:

1. **ANSI escape 시퀀스가 포함될 수 있다.** terraform은 non-TTY라고 색을 자동으로 끄지 않으므로,
   InfraManager가 `-no-color` 없이 실행하면 본문에 SGR 코드(`\x1b[32m+\x1b[0m` 류)가 박힌다. 이걸
   plain text로 그대로 뿌리면 `←[0m←[1m` 형태의 깨진 문자가 로그 전체에 나타난다. FE에서 strip하거나
   ansi_up류 렌더러로 처리해야 한다. (상류에서 `-no-color`로 해결되는 것이 최선이며, 이는 InfraManager
   소관이다 — 확인 전까지 FE는 방어적으로 다룬다.)
2. **whitespace가 의미를 갖는다.** plan diff는 들여쓰기·컬럼 정렬이 정보다. `<pre>` + monospace로
   렌더링해야 하며 일반 요소에 흘려 넣으면 접혀서 읽을 수 없다.
3. **외부 유래 텍스트다.** 반드시 HTML escape해서 뿌린다(innerHTML 금지) — 로그 내용은 신뢰 경계
   밖에서 온 문자열이다.
4. **절단 경계가 시퀀스를 자를 수 있다.** 16MB 초과 시 tail 우선 절단(§4.4)은 문자 단위라 ANSI
   시퀀스나 멀티라인 블록의 중간을 자를 수 있다. `truncated: true`면 앞부분이 잘렸다는 배너와 함께
   원본 전문은 `result_path`로 안내한다.

색상까지 살린 로그 뷰가 요구되면 그때는 DB 사본이 아니라 `result_path` 원본을 쓰는 별도 결정이
필요하다(§4.5 커버리지 한계와 같은 성격).

---

## 5. 확정 사항 (owner 답변, 2026-07-02)

1. **`TerraformState` 매핑 확정** — `COMPLETED`(plan/apply 성공 terminal), `DESTROYED`(destroy 성공
   terminal), `FAILED`(실패), 그 외/미지 → not finished로 계속 폴링. §3의 표가 규범.
2. **plan 계열을 recipe가 쓴다** — 카탈로그 범위에 포함(§1 관찰 3). plan의 산출물은 result 그
   자체이므로 §4.4 저장이 전제.
3. **postCheck는 확장 A로 확정.** result 본문은 클 수 있음 → `terraform_result` 테이블에
   MEDIUMTEXT 저장(§4.4).
4. **인증 동일 bearer** — 기존 `FeignConfig` 인터셉터 재사용, 추가 작업 없음.
5. **`fail_reason`/`error_guide_url`은 노출하지 않음** — 미바인딩 확정.
6. **목록형 dispatch의 부분 실패는 존재하며, 실패로 분류되는 형태다** — 클라이언트에 별도 방어가
   필요 없다: 부분 실패한 job은 폴에서 `FAILED`로 읽히고, 기존 whole-task 집계(하나라도 FAILED →
   `JOB_FAILED`, fresh run 재시도)가 그대로 수렴시킨다. dispatch 방어는 현행 `requireJobIds`
   (빈 목록·blank id)로 충분하다.

## 6. TODO (owner 확인, 2026-07-02)

- **TODO: `TerraformState` 전체 값 목록 확정.** 그때까지 wire 표현은 **String**으로 간다 — §3 표의
  세 terminal 값(`COMPLETED`/`DESTROYED`/`FAILED`)만 해석하고 나머지 문자열은 전부 not-finished.
  목록이 확정되면 닫힌 enum으로 교체한다(교체 지점은 status wire DTO와 §3 매핑 함수 두 곳뿐).
- result 16MB 초과 절단은 **tail-only로 시작**하고, 운영 데이터를 본 뒤 필요하면 조정한다.
