# InfraManagerClient 프로덕션 구현 스펙 (Feign 어댑터)

- Status: DECISIONS RESOLVED + REVIEW ROUND 1 REFLECTED (codex/opus P1·P2 반영, 구현 대기)
- Review round 1: codex(gpt-5.5) + opus 병렬. 두 리뷰 모두 P1 3건 수렴 → (a) §6 dispatch 응답 형태(bare `["job-1",...]` 배열로 재직렬화), (b) §2 Feign 예외를 cause-chain으로 분류(raw `RetryableException` 누출 방지), (c) D-3 `@ConditionalOnMissingBean` → test double `@Bean @Primary`로 교체. P2: §7 백엔드 명시 `@Bean feign.Client`(HC5 프로퍼티 아님), 패키지 레이아웃 AGENTS.md §6 준수, pin-free 과장 완화. 모두 반영됨.
- Scope: `InfraManagerClient` 인터페이스의 **프로덕션 구현체**를 Feign 기반으로 추가한다.
- Branch: `feat/infra-manager-feign` (base `chore/claude-pr-skills` — provider축/RecipeCatalog 작업 없는 상태. 인터페이스는 `runTerraform` / `terraformJobStatus` / `checkCondition` 3메서드)

---

## 0. 왜 필요한가 (현황)

- `src/main` 전체에 `InfraManagerClient`의 구현체가 **하나도 없다.** 테스트 fake 2개(`FakeInfraManagerClient`, `GatedInfraManagerClient`)뿐.
- `TerraformTask`·`ConditionCheckTask`가 생성자에서 이 빈을 **필수 주입**받으므로, 실제 서버로 띄우면 빈이 없어 **부팅 실패**한다. 지금은 테스트가 fake를 꽂아 통과 중.
- 결론: 프로덕션에서 애플리케이션이 뜨려면 실제 어댑터가 반드시 있어야 한다.

---

## 1. 레이어링 결정 (확정)

Feign은 **단순 전송 계층(raw transport)으로만** 사용하고, 예외 변환·계약 방어는 Feign 계층(`ErrorDecoder` + 어댑터)에 가둔다.

```
InfraManagerFeignClient   @FeignClient  ── raw HTTP: 경로/쿼리 → HTTP 상태 + 응답 DTO
        ▲ delegate
InfraManagerFeignAdapter  @Component implements InfraManagerClient
        └─ Feign 호출 + 전송 예외를 closed vocabulary로 번역
           + null/빈 응답 방어 → CallFailedException
        ▲ inject
TerraformTask / ConditionCheckTask  (변경 없음 — 인터페이스에만 의존)
```

이유: `InfraManagerClient` Javadoc이 이미 **"전송 예외를 세 예외로만 변환하는 단일 경계"** 로 계약을 확정해 뒀다. 변환을 Feign 계층 안(status는 `ErrorDecoder`, timeout 계열은 어댑터 catch — §2.1)에 가둬 두면, 도메인(`TaskStateMachine.runExternalCall`)은 항상 `CallTimeout`/`CallFailed`/`CallInterrupted` 세 어휘만 보게 되어 1:1로 맞는다. 도메인 쪽에는 Feign 예외가 절대 새지 않는다.

---

## 2. 예외 번역 규약 (계약)

`InfraManagerClient`가 밖으로 내보낼 수 있는 예외는 `CallTimeout` / `CallFailed` / `CallInterrupted` **딱 셋뿐**이다. 어댑터가 하는 일은 "Feign이 던진 온갖 예외 → 이 셋 중 하나"로 바꾸는 것이다.

**주의: Feign은 진짜 원인을 한 겹 포장해서 던진다.** 예를 들어 읽기 타임아웃이 나면 어댑터가 받는 예외는 `SocketTimeoutException`이 아니라 그것을 감싼 `RetryableException`이다.

```
RetryableException                        ← 겉포장 (실제로 던져지는 것)
  └ getCause() == SocketTimeoutException   ← 진짜 원인 (안에 들어 있음)
```

그래서 `catch (SocketTimeoutException)`은 절대 걸리지 않는다(겉은 `RetryableException`이니까). **겉 예외 타입만 보지 말고, 포장을 벗겨(`getCause()`로 원인을 따라가) 안에 무엇이 있는지로 판단해야 한다.**

분류 규칙 — 위에서부터 먼저 맞는 것을 적용:

1. 원인에 **인터럽트**(`InterruptedException` / `InterruptedIOException`)가 있으면 → 인터럽트 플래그를 복원(`Thread.currentThread().interrupt()`)한 뒤 `CallInterruptedException`
2. 원인에 **타임아웃**(`SocketTimeoutException` / `HttpTimeoutException`)이 있으면 → `CallTimeoutException`
3. 그 밖의 모든 실패(HTTP 4xx/5xx, 연결 거부, 응답 파싱 실패, null/빈 바디) → `CallFailedException`

| 전송에서 일어난 일 | 어댑터가 던지는 것 | 도메인 매핑 (`TaskStateMachine`) |
|---|---|---|
| 응답 정상 | (정상 반환) | 진행 |
| 원인이 타임아웃 (`RetryableException`이 감싼 `SocketTimeout`/`HttpTimeout`) | `CallTimeoutException` | `ErrorCode.CALL_TIMEOUT` (재시도) |
| 원인이 인터럽트 | `CallInterruptedException` | **`TaskStateMachine`이 catch 안 함 → 그대로 전파(fail-fast). 스레드 중단 신호라 업무 실패로 기록하지 않고 즉시 멈춤** |
| 그 밖의 모든 실패: `FeignException`(4xx/5xx), 연결 거부, `DecodeException`, 파싱 실패, **null/빈 바디** | `CallFailedException(msg)` | `ErrorCode.CHECK_ERROR` (재시도) |

**어댑터가 잡는 범위는 `catch (FeignException | RetryableException e)` — 전송 예외만.** 이 둘을 놓치면 `TaskStateMachine.runExternalCall`이 `CallTimeout`/`CallFailed`만 잡으므로, 밖으로 새어 나간 Feign 예외는 실제 버그로 간주되어 fail-fast로 전파된다. 반대로 `RuntimeException`을 통째로 잡으면 매핑 로직 자체의 버그(NPE 등)까지 `CallFailed`로 삼켜 fail-fast가 깨진다 — 그래서 전송 예외 두 종류만 잡고 나머지는 그대로 전파한다.

> 참고(리뷰 근거): 원인 포장은 OpenFeign `SynchronousMethodHandler`가 `IOException`을 `RetryableException`으로 감싸기 때문이고(codex), 인터럽트 플래그 복원은 `java.net.http` blocking send가 인터럽트를 `InterruptedException`/`InterruptedIOException`으로 올려보내기 때문이다(opus). "전송 예외만 catch"는 codex·opus 이견에서 codex 안을 채택한 것.

### 2.1 예외 변환은 두 군데에서 일어난다 (ErrorDecoder + 어댑터)

`ErrorDecoder`는 **non-2xx HTTP 응답에만** 호출된다. 타임아웃·연결 거부·인터럽트는 `Client`가 `IOException`을 던지고 `SynchronousMethodHandler`가 이를 `RetryableException`으로 감싸므로, **`Retryer`로 전달되며 `ErrorDecoder`는 거치지 않는다.** 따라서 예외 변환은 두 곳으로 나뉜다:

| 위치 | 담당하는 실패 | 하는 일 |
|---|---|---|
| **`FeignConfig`의 `@Bean ErrorDecoder`** | HTTP 4xx/5xx (`FeignException`) | status·body를 읽어 `CallFailedException("HTTP " + status + ...)`으로 변환. status를 메시지에 포함해 로그나 `lastExternalStatus`에 남길 수 있게 한다 |
| **어댑터 경계** (`catch FeignException \| RetryableException`) | timeout / connect refused / interrupt / null·빈 바디 | §2의 cause-chain 3분류. `ErrorDecoder`를 거치지 않는 `RetryableException`은 여기서 반드시 처리해야 한다 |

정리하면, status 기반 실패는 `FeignConfig`에서 변환하되 `ErrorDecoder`만으로는 timeout 계열을 잡지 못하므로 어댑터의 catch가 여전히 필요하다. 두 지점은 양자택일이 아니라 상호보완 관계다.

### 2.2 DB 저장 — 이미 기록되지만 정해진 도메인 용어로만 남긴다

외부 호출 실패는 **이미 DB에 기록된다** (신규 컬럼은 필요 없다):
- `TaskAttempt.errorCode` (persisted enum): 호출 실패는 `CHECK_ERROR`, 타임아웃은 `CALL_TIMEOUT`으로 기록된다 (`observationRecorder.endAttempt(task, FAILED, reason)`).
- `Task.errorCode`: 태스크 종료 사유.
- `TaskCheck.apiErrorCount` / `callTimeoutCount` / `lastExternalStatus`: poll 단계의 카운터와 자유 형식 디버그 라벨.

**저장하지 않는 정보**: raw HTTP status(503/404), Feign 예외 메시지·바디. 이 값들은 모두 `CHECK_ERROR`로 정규화된다 — `exception-strategy.md`에서 의도한 설계다(전송 세부는 도메인 상태가 아니고, 비즈니스 실패는 `ErrorCode` 값으로만 표현한다).

**결정 D-5:** raw HTTP status를 나중에 조회해야 한다면, `ErrorDecoder`가 만든 메시지(`"HTTP 503 ..."`)를 `CallFailedException` 메시지로 전달해 **기존 `TaskCheck.lastExternalStatus` 라벨**에 기록한다(poll 경로). **새 컬럼이나 새 테이블은 추가하지 않는다** — 필요성이 확인되면 그때 추가한다. 기본 방침은 현행 유지다(로그 + `CHECK_ERROR`).

방어 포인트:
- `terraformJobStatus`가 `TerraformPoll null`을 반환하면 → `CallFailedException` (기존 `JobIdTerraformJobTest` 계약과 일치).
- `runTerraform`이 빈/blank 응답 문자열을 반환하면 → `CallFailedException`.
- Feign은 `Retryer.NEVER_RETRY`로 둔다. 재시도 책임은 도메인(`retryOrFail` + `nextCheckAt`)에 있으며, 전송 계층에서 별도로 재시도하면 멱등성과 attempt 회계가 어긋난다.

---

## 3. per-call 타임아웃 (어댑터가 소유)

인터페이스 Javadoc: *"프로덕션 어댑터가 호출별 타임아웃을 소유"*. 구현:
- Feign `Request.Options(connectTimeout, readTimeout, followRedirects)` 로 호출당 상한 강제.
- 초과 시 Feign이 `SocketTimeoutException`(기본 백엔드) 또는 `HttpTimeoutException`(java.net.http 백엔드) 계열을 던짐 → §2 표대로 `CallTimeoutException`.
- 값은 `application.yml`에 노출: `infra-manager.connect-timeout`, `infra-manager.read-timeout`.

주의: 도메인의 태스크 데드라인(`execution-timeout`, `polling-interval`)과는 **다른 층**이다. 전자는 단일 HTTP 왕복 상한, 후자는 태스크 전체 수명/재폴 간격.

---

## 4. VT pinning (지금은 문제 아님, 그러나 백엔드로 예방)

현 브랜치엔 스케줄러·`@Async`·Executor·VT 설정이 **전혀 없다.** 외부 호출은 `PipelineEngine.advance()`의 `@Transactional` 안에서 **호출 스레드에 동기**로 돈다. 즉 pinning은 *지금 코드엔 존재하지 않는* 문제.

그래도 나중에 ADR-021 러너가 `spring.threads.virtual.enabled=true` 한 줄로 VT를 켜는 순간을 대비해, **처음부터 pinning 위험이 낮은 백엔드**로 고정한다:
- Feign 기본 백엔드(`HttpURLConnection`)와 Apache HC4/OkHttp는 blocking I/O를 `synchronized` 블록 안에서 수행해 carrier 스레드를 **pin**할 수 있다.
- **`java.net.http.HttpClient` 기반 백엔드**(JDK 내장, VT 친화적)는 이런 pinning이 없다.

**D-1 확정: `java.net.http.HttpClient` 백엔드로 고정.** 의존성 `io.github.openfeign:feign-java11`을 추가하고, `@Bean feign.Client`로 `feign.http2client.Http2Client`를 명시 등록한다(§7 참조).

> ⚠️ P2(codex): "pin-free"는 검증된 불변식이 아니라 **백엔드 선택**으로 이해할 것. `Http2Client`도 결국 동기 `HttpClient.send()`를 부르지만, `java.net.http`는 VT에서 carrier를 pin하지 않도록 설계돼 있어 `synchronized`-blocking 계열(HttpURLConnection/HC4/OkHttp)보다 안전하다는 근거로 택함. 공식 보증 문구가 아니므로 코드에 "pin-free 불변식" 주석으로 단정하지 않는다.

---

## 5. 별개로 기록만 해두는 리스크 (이번 PR 범위 밖)

외부 HTTP 호출이 `PipelineEngine.advance()`의 `@Transactional` **안**에 있어, HTTP 왕복 내내 DB 커넥션을 점유한다(커넥션 풀 고갈 위험). 이는 ADR-021 실행 모델(러너가 트랜잭션 밖에서 외부 호출)에서 해결할 영역이며, Feign 어댑터가 해결할 문제는 아니다. **이번 작업에서는 건드리지 않고** 문서에만 남긴다.

---

## 6. HTTP 계약 (엔드포인트 — 가정, 실제 InfraManager 스펙에 맞춰 조정 필요)

`@FeignClient(name="infra-manager", url="${infra-manager.base-url}")`

**D-2 확정: 실제 계약이 없으므로 `/infra/...` 프리픽스로 가정한다(추후 실제 스펙에 맞춰 조정).**

| 도메인 메서드 | HTTP (가정) | 요청 | 응답 → 매핑 |
|---|---|---|---|
| 도메인 메서드 | HTTP (가정) | 응답 wire DTO | 어댑터가 도메인에 넘기는 것 |
|---|---|---|---|
| `runTerraform(target, operation)` | `POST /infra/terraform/{operation}` body `{ "target": ... }` | `200` `{ "jobIds": ["job-1", ...] }` | **`["job-1", ...]` JSON 배열 문자열** (⚠️ 아래) |
| `terraformJobStatus(jobId)` | `GET /infra/terraform/jobs/{jobId}` | `200` `{ "finished":bool, "succeeded":bool }` | `TerraformPoll` |
| `checkCondition(target, operation)` | `GET /infra/conditions/{operation}?target=` | `200` `{ "met": bool }` | `boolean` |

> ⚠️ **파서 일치(P1, codex):** `TerraformTask.check`는 `attempt.response`를 `List<String>` JSON, 즉 정확히 `["job-1", ...]` 형태로 역직렬화한다(`src/main/.../terraform/TerraformTask.java:84`). 따라서 어댑터는 wire DTO `{jobIds:[...]}`를 받되 **도메인에는 `jobIds` 배열만 JSON 문자열로 직렬화해 넘겨야 한다.** 객체 JSON(`{"jobIds":...}`)을 그대로 저장하면 모든 poll이 malformed로 `CHECK_ERROR` 실패. (어댑터가 `runTerraform` 시 blank/빈 배열이면 `CallFailedException` — `TerraformTask.execute`의 blank 가드와 이중 방어.)

> ⚠️ 위 경로/wire DTO는 가정. 실제 InfraManager API 확정 시 Feign 인터페이스 + wire DTO만 고치면 됨. 단 **도메인에 넘기는 형태(`["job-1",...]`)는 파서 계약이라 고정**.

Feign 인터페이스는 위 wire DTO(예: `record JobDispatchResponse(List<String> jobIds)`, `record ConditionResponse(boolean met)`)를 반환하고, 어댑터가 도메인 타입(String / TerraformPoll / boolean)으로 변환한다.

---

## 7. 설정 (`application.yml`)

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          infra-manager:
            connectTimeout: 3000
            readTimeout: 10000
            loggerLevel: basic

infra-manager:
  base-url: ${INFRA_MANAGER_URL:http://localhost:8081}
```

> ⚠️ **P2(opus): `openfeign.httpclient.*` 프로퍼티는 Apache HC5를 켠다 — D-1의 `java.net.http` 백엔드가 아니다.** spring-cloud-openfeign엔 JDK `Http2Client`용 프로퍼티 오토컨피그가 없으므로, 백엔드는 **명시적 `@Bean feign.Client`로 등록**해야 한다:
> ```java
> @Bean
> feign.Client feignClient() {
>     return new feign.http2client.Http2Client();   // java.net.http.HttpClient 기반, pin-free
> }
> ```
> 이 빈이 없으면 spring-cloud-openfeign 기본 클라이언트로 조용히 떨어져 D-1이 방지하려던 pinning 백엔드를 쓰게 됨. 그래서 §7 YAML엔 `httpclient` 블록을 **넣지 않는다**(넣으면 HC5로 오해).

의존성: `spring-cloud-starter-openfeign` + `io.github.openfeign:feign-java11` + spring-cloud BOM (`pom.xml`). Boot 3.3 ↔ Spring Cloud **2023.0.x(최신 패치로 pin)** 라인.

---

## 8. 빈 활성화 / 테스트 격리

**충돌 지점 (D-3가 필요한 유일한 이유):** `PipelineEngineTransactionTest`가 유일한 `@SpringBootTest`(전체 컨텍스트 + 컴포넌트 스캔)이고, `@TestConfiguration`으로 `GatedInfraManagerClient` 빈을 꽂는다. 여기에 `InfraManagerFeignAdapter`가 `@Component implements InfraManagerClient`로 추가되면 같은 타입 빈이 2개 → `NoUniqueBeanDefinitionException`으로 이 테스트가 깨진다. 나머지 테스트는 `@DataJpaTest` 슬라이스(@Component 스캔 안 함) + 수동 `new Fake...()`라 무관.

**⚠️ P1(codex): `@ConditionalOnMissingBean`은 이 상황에서 신뢰할 수 없다.** Boot 문서는 이 조건이 "지금까지 처리된 빈 정의만" 보며 **오토컨피그 전용으로 권장**된다고 명시한다. 컴포넌트 스캔된 `@Component` 어댑터의 조건이 `@TestConfiguration`의 `@Bean`보다 **먼저** 평가되면 둘 다 등록돼 여전히 `NoUniqueBeanDefinitionException`. 순서 비보장 → flaky.

**D-3 재확정: 어댑터는 조건 없는 평범한 `@Component`로 두고, `@SpringBootTest`의 test double `@Bean`에 `@Primary`를 붙인다** (네가 처음 말한 "test를 바꾸면 되지"가 실제로 더 견고한 답).
- 두 빈이 다 존재해도 주입은 `@Primary`(Gated)로 결정 → 결정적(deterministic), 순서 무관.
- 변경 지점 **딱 1줄**: `PipelineEngineTransactionTest.Wiring`의 `@Bean`에 `@Primary` 추가.
- 프로덕션엔 test 컨텍스트가 없어 어댑터가 유일 빈 → 정상 주입.
- Gated/Fake test double은 **계속 유지** — 트랜잭션 경합(호출 도중 cancel 커밋) 재현엔 게이트 제어형 double이 필수(실제 HTTP로 타이밍 재현 불가).
- 어댑터가 `@SpringBootTest`에서 같이 로드돼 Feign 프록시를 만들지만 아무도 주입받지 않으므로 무해(미사용). base-url이 안 잡혀도 호출이 없으니 상관없음.
- 슬라이스 테스트(`@DataJpaTest` 등)엔 애초에 `@Component`/Feign 스캔 안 됨 → 무관.

---

## 9. 검증 (테스트 계획)

Feign은 실제 HTTP라 순수 단위테스트가 어렵다. 최소 검증:
- **어댑터 번역 단위테스트**: Feign 인터페이스를 stub(수동 구현)으로 주입해 어댑터만 검사.
  - null `TerraformPoll` → `CallFailedException`
  - blank dispatch 응답 → `CallFailedException`
  - `SocketTimeout/HttpTimeout` 계열 → `CallTimeoutException`
  - 정상 응답 → 도메인 타입 변환 정확성
- **D-4 확정: WireMock 통합테스트 추가.** `wiremock-standalone`(test scope)로 실제 HTTP 경로 검증:
  - 정상 200 → 도메인 타입 변환
  - read timeout(응답 지연) → `CallTimeoutException`
  - 4xx/5xx → `CallFailedException`
  - 빈/malformed 바디 → `CallFailedException`
  - 실제 Feign 스택(java.net.http 백엔드 + Options)을 관통하므로 §2·§3 계약을 end-to-end로 증명.
- 기존 테스트 그린 유지.

---

## 10. 파일 델타 (예상)

**⚠️ P2(codex): AGENTS.md §6 레이어 규칙 준수.** wire DTO는 `client/dto`가 아니라 root **`dto/`** 레이어(external transport)에, `@EnableFeignClients`는 신설 `config/`가 아니라 **root 패키지**(`PipelineConfig` — 앱 와이어링 위치)에. 각 신규 major 컴포넌트엔 **한국어 클래스 헤더 Javadoc**(§7).

추가:
- `client/InfraManagerFeignClient.java` (@FeignClient interface — client 경계)
- `client/InfraManagerFeignAdapter.java` (@Component implements InfraManagerClient — 예외 번역 경계, 한국어 Javadoc)
- `dto/` 에 wire DTO record 2~3개 (예: `TerraformDispatchResponse(List<String> jobIds)`, `ConditionResponse(boolean met)`; poll은 기존 `TerraformPoll` 재사용 가능)
- `FeignConfig.java` (**root 패키지** — AGENTS.md §6상 앱 와이어링은 root): `@EnableFeignClients` + `@Bean feign.Client`(Http2Client, D-1) + `@Bean ErrorDecoder`(§2.1, HTTP status→`CallFailedException`)
- 어댑터 단위테스트 + WireMock 통합테스트

변경:
- `pom.xml` (spring-cloud BOM + openfeign starter + `feign-java11`)
- `application.yml` (infra-manager 블록)

**손 안 대는 것**: `InfraManagerClient` 인터페이스, 도메인(`TaskStateMachine`/`PipelineEngine`/Task 계열), 기존 테스트 fake.
**바뀌는 테스트**: `PipelineEngineTransactionTest`의 test double `@Bean`에 `@Primary` 1줄 (D-3).

---

## 결정 (확정)

- **D-1 (백엔드):** ✅ `java.net.http.HttpClient` 백엔드(pin-free). `feign-java11` 의존성.
- **D-2 (HTTP 계약):** ✅ 실제 계약 없음 → `/infra/...` 경로 + 가정 DTO. 확정 시 Feign 인터페이스/DTO만 조정.
- **D-3 (빈 격리):** ✅ 어댑터에 `@ConditionalOnMissingBean(InfraManagerClient.class)`. 테스트 fake 유지.
- **D-4 (통합테스트):** ✅ WireMock 통합테스트 추가(정상/timeout/4xx/5xx/malformed).
- **D-5 (실패 세부 영속):** ✅ 신규 컬럼 없음. 실패 결과는 이미 `TaskAttempt.errorCode`(`CHECK_ERROR`/`CALL_TIMEOUT`) + `TaskCheck` 카운터에 남음. raw HTTP status는 `ErrorDecoder` 메시지→`lastExternalStatus` 라벨로 흘리는 선택지만 열어둠(기본 현행 유지).
- **번역 위치:** ErrorDecoder(FeignConfig, status 기반) + 어댑터 catch(timeout/connect/null) — 상호보완(§2.1).
