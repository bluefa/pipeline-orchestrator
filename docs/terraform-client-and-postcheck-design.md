# Terraform 클라이언트 인터페이스 재검토 + postCheck ADR 확장 (설계 노트)

- Status: PROPOSED (검토용 — 코드 무변경)
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
  이는 어느 설계안에서도 동일하다. lifecycle 편입은 3단계 확장안(관찰 → 완료판정 편입 → 별도 태스크)으로
  정리했고, **지금은 확장 A(관찰) + 승급 seam 확보**를 권장한다. 승급용 seam은 이미 존재한다
  (`extensibility.md`의 `TerraformJob` per-job completion-check ownership).

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

**관찰 3 — 오케스트레이터가 부를 것은 부분집합이다 (YAGNI).** install/delete 파이프라인의 실행
단위는 apply/destroy다. 상한은 8개 실행 단위 × 2 = **16 operation**, Feign 메서드로는
dispatch 11 + status 9 + result 9 ≈ **29개**다. plan 계열, `error_guide_url` 노출 등은 recipe나
운영 화면이 요구하기 전까지 바인딩하지 않는다. registry 부팅 검증은 `TaskOperation` enum 기준이므로
"스펙에 있는데 안 만든 것"이 아니라 "recipe가 쓰는데 빠뜨린 것"만 잡는다 — 지금 철학 그대로.

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

현행 `ApplyNetworkBinding` 패턴을 16 operation으로 확장하되, DTO만 3종 공용(§3)으로 통합한다.

- 규모: Feign 메서드 ~29 + 바인딩 클래스 16 (+카탈로그 파일 없음).
- 장점: 지금 코드와 동형이라 이동 비용 0. operation별 quirk(주석·방어)를 둘 곳이 클래스라 명확.
- 단점: 클래스 16개가 전부 "경로만 다른 4줄짜리"가 된다 — **클래스가 데이터 노릇**을 한다.
  스펙 표와 코드의 대조가 16개 파일에 흩어지고, 새 op 추가가 "파일 생성" 의식이 된다.
- 이 안이 다시 옳아지는 조건: 패밀리별 응답 shape이 재분화하거나(관찰 1 붕괴), operation별
  전처리·방어 로직이 실제로 자라날 때.

### 설계 2 (권장) — 바인딩 카탈로그: 한 파일 @Bean 표 + Feign 1:1 유지

`TerraformOperationBinding`·registry·도메인은 그대로. **구현체 16클래스를 @Configuration 한 파일의
@Bean 16개(각 3~5줄 람다)로 접는다.** 공통 정규화(단건→목록, `requireJobIds`, `toPoll`)는 제네릭
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
        return row(GCP_BDC_TF_DESTROY,
                t -> f.gcpBdcDispatch(t, "DESTROY"),    // 단일 엔드포인트 + jobType
                j -> f.gcpBdcJobStatus(j, "DESTROY"),
                j -> f.gcpBdcJobResult(j, "DESTROY"));
    }
    // ... 16행
}
```

- Feign은 **실 API 1:1 concrete 메서드를 유지**한다(메서드당 선언 2줄). 경로가 애너테이션 상수라
  grep 가능하고 오타가 부팅 시 검증되며, 기존 인터셉터(bearer)·WireMock 테스트·어댑터 층이 그대로 산다.
  `jobType`/`idcTerraformType`은 Feign 메서드 파라미터다.
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
| 바인딩 파일 수 | 16 | **1** (+제네릭 1) | 1 | 7 |
| 새 op 추가 비용 | 클래스 1 + Feign 2~3 | **@Bean 4줄 + Feign 2~3** | enum 1줄 | 기존 클래스 수정 |
| 누락/오타 검출 | 컴파일+부팅 | **컴파일(람다)+부팅** | 호출 시점 | 컴파일+부팅 |
| 스펙 표와 대조 | 16파일 산개 | **한 화면** | 한 화면 | 7파일 |
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
- status: `terraformState` → `TerraformPoll` 매핑. **`TerraformState`의 값 목록과 종결/성공 판별
  기준이 미확정**(열린 질문 1) — 미지의 상태 문자열은 `CallFailedException`으로 닫는다(현행
  `toPoll` 철학과 동일: 해석 불능 응답은 성공도 실패도 아닌 호출 실패).

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

**확장 A — 관찰(Observation)로만 기록. (지금 권장)**
`TerraformTask`가 종결 폴을 낸 그 turn에(모든 job finished) job별 result를 조회해 관찰 행에 남긴다.
result 조회 실패는 상태에 관여하지 않는다 — DONE은 DONE, 관찰만 비었다고 기록.
- ADR-016 문안: Decision 3(Observation is separate from state)에 한 문장 추가 —
  *"Post-completion result retrieval (postCheck) is observation: the terraform log tail and
  `resultPath` are recorded per job on the concluding poll, and a failure to fetch them never
  changes task state."*
- 스키마: 신규 컬럼 없음. 단 terraform log는 클 수 있으므로 **tail N KB로 절단해 저장하고, 원본
  포인터는 status 응답의 `resultPath`를 함께 기록**한다(전문은 InfraManager/스토리지가 소유).

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

### 4.3 권장 경로

**지금: 4.1(클라이언트 표면) + 확장 A.** result 검증 규칙이 확정되고 "state는 SUCCESS인데 로그는
실패"라는 실사례가 확인되면 **확장 B로 승급** — seam(`TerraformJob`)이 이미 있어 승급이 추가
파일 하나다. 확장 C는 task 간 출력 채널이라는 별도 요구가 생길 때까지 열지 않는다.

---

## 5. 열린 질문

1. **`TerraformState` 값 목록**과 종결/성공 매핑(무엇이 finished, 무엇이 succeeded인가). 폴 정규화의
   유일한 미지수다.
2. **plan 계열을 recipe가 쓸 계획이 있는가?** 쓴다면 plan의 "산출물"이 result 그 자체이므로 확장 A의
   관찰 기록이 필수가 되고, plan operation 6~8개가 카탈로그에 행으로 추가된다.
3. **result 본문 규격** — 성공/실패 판별 규칙을 정의할 수 있는가(확장 B 승급 조건). 크기 상한은?
4. 이 API들의 인증도 동일 bearer 인터셉터인가.
5. `BdcTerraformJobResponse`의 `fail_reason`/`error_guide_url`을 관찰 행이나 운영 API에 실을 것인가
   (지금은 미바인딩 — YAGNI).
6. 목록형 dispatch 응답의 부분 실패(일부 job만 반환되는 케이스)가 존재하는가 — 존재하면
   `requireJobIds` 방어를 넘어서는 정합 규칙이 필요하다.
