# InfraManagerClient 프로덕션 구현 스펙 (Feign delegate)

- Status: REWRITTEN against latest `origin/main` (ADR-021 claim-pull 실행 모델 반영). 구현 대기.
- Branch: `feat/infra-manager-feign` (base = 최신 `origin/main`)
- ⚠️ 이전 초안은 스테일 브랜치 기준이라 실행 모델을 잘못 서술했음 — 리베이스 후 전면 교정.

---

## 0. 왜 필요한가 (현황)

- `InfraManagerClient` 인터페이스는 4메서드: `runTerraform` / `terraformJobStatus` / `checkCondition` / **`cloudProvider`**.
- 프로덕션 HTTP 구현체가 **없다.** 대신 데코레이터 `TimeBoundedInfraManagerClient`가 이미 존재하며, 그 delegate 슬롯(`infraManagerDelegate` 빈)이 **비어 있다.**
- 데코레이터는 `@ConditionalOnBean(name = "infraManagerDelegate")`라, delegate 빈이 없으면 자기도 안 뜬다. 그래서 지금은 **테스트만** `FakeInfraManagerClient`를 직접 주입해 통과하고, **실서버 기동엔 실제 HTTP delegate가 반드시 필요**하다.
- 결론: 우리가 만들 것은 **`infraManagerDelegate` 라는 이름의 Feign 기반 HTTP delegate 빈** 하나다.

---

## 1. 레이어링 (기존 데코레이터 밑에 delegate로 꽂힌다)

```
StepRunner  (외부 호출 지점, @Transactional 없음 — 트랜잭션 밖에서 돎)
  └ InfraManagerClient  (도메인이 주입받는 것 = @Primary 데코레이터)
       = TimeBoundedInfraManagerClient   @Primary, @ConditionalOnBean("infraManagerDelegate")
            · 별도 풀(infraManagerCallPool)에서 future.get(apiCallTimeout)로 호출별 타임아웃 강제
            · timeout → CallTimeout,  interrupt → CallInterrupted(+플래그 복원)
            · 그 밖의 RuntimeException은 언랩해 그대로 전파(fail-fast)
         └ delegate = @Qualifier("infraManagerDelegate")   ← ★ 우리가 만들 것
              = InfraManagerFeignAdapter   (@Bean("infraManagerDelegate"))
                   · Feign 호출 + 전송 예외 → CallFailedException 번역 + jobIds 재직렬화
                   └ InfraManagerFeignClient  @FeignClient  (raw HTTP)
```

핵심: 도메인·데코레이터·per-call 타임아웃·스레드 풀은 **이미 다 있다.** 우리는 맨 밑 두 칸(raw @FeignClient + 번역 어댑터)만 채워 `infraManagerDelegate`로 등록한다.

---

## 2. 예외 번역 — delegate의 책임은 `CallFailed` 하나로 좁아진다

`InfraManagerClient`가 밖으로 내보낼 수 있는 예외는 `CallTimeout` / `CallFailed` / `CallInterrupted` 셋뿐이고, 도메인(`StepRunner.runExternalCall`)은 그중 **`CallTimeout`·`CallFailed`만 catch**하고 `CallInterrupted`와 순수 버그는 전파한다.

**타임아웃·인터럽트는 데코레이터가 이미 처리한다** (§1). 그래서 delegate가 할 일은 남는 것 하나:

> **Feign이 던지는 모든 전송 실패(HTTP 4xx/5xx, 연결 거부, 응답 파싱 실패, null·빈 바디) → `CallFailedException`.**

왜 delegate가 반드시 번역해야 하나: 데코레이터의 `rethrow()`는 delegate가 던진 `RuntimeException`을 **그대로 언랩해 전파**한다(닫힌 어휘든 진짜 버그든). 따라서 delegate가 raw `FeignException`/`RetryableException`을 내보내면, 그게 데코레이터를 지나 `StepRunner`까지 올라가는데 거기선 `CallTimeout`/`CallFailed`만 잡으므로 **못 잡고 fail-fast로 새어버린다**(실제로는 재시도 가능한 호출 실패인데 버그로 오인). 그래서 delegate가 경계에서 잡아 `CallFailed`로 바꾼다.

```java
try {
    ... feignClient 호출 ...
} catch (FeignException e) {   // RetryableException(연결/타임아웃)은 FeignException 하위 타입 → 함께 잡힘
    throw new CallFailedException("infra-manager: " + e.getMessage());
}
// 그 밖의 RuntimeException(매핑 버그 등)은 잡지 않는다 — fail-fast로 전파
```

- `catch (RuntimeException)` 통짜 금지: 매핑 로직 자체 버그(NPE 등)까지 `CallFailed`로 삼켜 fail-fast를 깬다.
- null/빈 바디 방어: `terraformJobStatus`가 `TerraformPoll null` → `CallFailed`(기존 `JobIdTerraformJobTest` 계약), `runTerraform` 빈 문자열 → `CallFailed`(기존 `TerraformTask.execute` blank 가드와 이중 방어).
- `Retryer.NEVER_RETRY`: 재시도 책임은 도메인(`retryOrFail`/`nextCheckAt`)에 있다. 전송 계층이 몰래 재시도하면 멱등성·attempt 회계가 어긋난다.

### 2.1 타임아웃 경계에 대한 미세 주의

per-call 타임아웃은 데코레이터의 `apiCallTimeout`이 authoritative다. Feign 자체의 `connectTimeout`/`readTimeout`은 **`apiCallTimeout`보다 넉넉하게(느슨하게)** 잡아 데코레이터가 먼저 터지게 둔다(→ `CallTimeout`). 만에 하나 Feign socket timeout이 먼저 나면 `RetryableException`으로 와서 delegate가 `CallFailed`(→`CHECK_ERROR`)로 번역한다 — `CALL_TIMEOUT`이 아니라 `CHECK_ERROR`로 분류되지만 둘 다 재시도 대상이라 실무상 동등하다. (원하면 delegate가 원인이 `SocketTimeoutException`인지 보고 `CallTimeout`으로 올릴 수도 있으나, 데코레이터가 상위 경계라 필수는 아님.)

---

## 3. per-call 타임아웃 — 이미 데코레이터 소유 (신규 작업 아님)

`TimeBoundedInfraManagerClient`가 `infraManagerCallPool`에서 `future.get(apiCallTimeout)`로 강제한다(ADR-021 Decision 5, 하드 제약 `leaseDuration > apiCallTimeout`). delegate는 이걸 **다시 구현하지 않는다.** Feign Options는 §2.1대로 느슨한 backstop만.

---

## 4. VT pinning — 현재 무관 (풀이 platform thread)

`infraManagerCallPool` = `Executors.newFixedThreadPool(workerPerPod)` → **플랫폼 스레드**다. 워커 풀도 마찬가지. 즉 현재 설계엔 VT가 없어 **pinning은 해당 사항 없음.**

훗날 `infraManagerCallPool`을 가상 스레드로 바꾸면, 블로킹 Feign 호출이 그 위에서 돌므로 그때 백엔드 pinning을 재검토한다(HttpURLConnection/HC4/OkHttp는 `synchronized`-blocking으로 carrier를 pin할 수 있음 → `java.net.http.HttpClient` 기반 `feign-java11`로 교체). **지금은 백엔드 선택 자유** — 기본 백엔드로 시작하고 위 전환 시점에 바꾼다(불필요한 의존성 선반영 안 함).

---

## 5. HTTP 계약 (가정 — 실제 InfraManager 스펙 확정 시 조정)

`@FeignClient(name = "infra-manager", url = "${infra-manager.base-url}")`

| 도메인 메서드 | HTTP (가정) | 응답 wire DTO | 어댑터가 도메인에 넘기는 것 |
|---|---|---|---|
| `runTerraform(target, operation)` | `POST /infra/terraform/{operation}` body `{ "target": ... }` | `{ "jobIds": ["job-1", ...] }` | **`["job-1", ...]` bare JSON 배열 문자열** (⚠️ 아래) |
| `terraformJobStatus(jobId)` | `GET /infra/terraform/jobs/{jobId}` | `{ "finished":bool, "succeeded":bool }` | `TerraformPoll` |
| `checkCondition(target, operation)` | `GET /infra/conditions/{operation}?target=` | `{ "met": bool }` | `boolean` |
| `cloudProvider(target)` | `GET /infra/targets/{target}/cloud-provider` | `{ "provider": "AWS" }` | `CloudProvider` (미매칭 값 → `CallFailed`) |

> ⚠️ **파서 일치:** `TerraformTask.check`는 `attempt.response`를 `List<String>` JSON, 즉 정확히 `["job-1", ...]`로 역직렬화한다(`.../task/terraform/TerraformTask.java`, `TypeReference<List<String>>`). 따라서 어댑터는 wire DTO `{jobIds:[...]}`를 받되 **`jobIds` 배열만 JSON 문자열로 재직렬화**해 넘긴다. 객체 JSON을 그대로 저장하면 모든 poll이 malformed → terminal 실패.

실제 경로/DTO 확정 시 `@FeignClient` 인터페이스 + wire DTO만 고친다. 단 도메인에 넘기는 형태(`["job-1",...]`)는 파서 계약이라 고정.

---

## 6. DB 저장 — 실패 결과는 이미 기록됨 (신규 컬럼 없음)

- `TaskAttempt.errorCode`: 호출 실패는 `CHECK_ERROR`, 타임아웃은 `CALL_TIMEOUT`으로 기록.
- `Task.errorCode`: 태스크 종료 사유.
- 관찰 카운터: poll 단계 신호(`API_ERROR`/`CALL_TIMEOUT`) 등.

raw HTTP status(503/404)·Feign 메시지는 저장하지 않고 `CHECK_ERROR`로 정규화된다 — `exception-strategy.md`가 의도한 설계(전송 세부는 도메인 상태가 아님). 나중에 status 조회가 필요하면 `CallFailedException` 메시지에 실어 관찰 라벨로 흘리는 선택지만 열어둔다. **기본은 현행 유지, 신규 컬럼·테이블 없음.**

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
            readTimeout: ${INFRA_MANAGER_READ_TIMEOUT:20000}   # apiCallTimeout보다 넉넉하게(§2.1)
            loggerLevel: basic

infra-manager:
  base-url: ${INFRA_MANAGER_URL:}     # 있을 때만 delegate 활성화(§8)
  auth-token: ${INFRA_MANAGER_TOKEN:} # bearer token (§7.1)
```

### 7.1 인증 — bearer token

`InfraManagerFeignClient`의 모든 호출은 `Authorization: Bearer <token>` 헤더가 필요하다. Feign `RequestInterceptor` 빈으로 붙인다(엔드포인트마다 반복하지 않게 한 곳에서):

```java
@Bean
RequestInterceptor infraManagerAuth(@Value("${infra-manager.auth-token}") String token) {
    return template -> template.header("Authorization", "Bearer " + token);
}
```

토큰은 우선 설정값(`infra-manager.auth-token`)에서 읽는다. 토큰이 회전(rotate)하는 방식이면 나중에 `Supplier<String>`/토큰 프로바이더로 교체하되, 지금은 정적 설정으로 시작한다.

의존성: `spring-cloud-starter-openfeign` + spring-cloud BOM(2023.0.x 최신 패치로 pin, Boot 3.3 호환). `feign-java11`은 VT 전환 시점까지 보류(§4).

---

## 8. 빈 배선 / 테스트 격리 — 이미 구조가 해결함

- Feign delegate를 **`infraManagerDelegate` 이름 빈**으로 등록한다. 그러면 기존 `TimeBoundedInfraManagerClient`(`@ConditionalOnBean("infraManagerDelegate")` + `@Primary`)가 자동으로 켜져 도메인이 데코레이터를 주입받는다.
  ```java
  @Bean("infraManagerDelegate")
  @ConditionalOnProperty(prefix = "infra-manager", name = "base-url")   // 값 있을 때만(프로덕션)
  InfraManagerClient infraManagerDelegate(InfraManagerFeignClient feign, ObjectMapper mapper) {
      return new InfraManagerFeignAdapter(feign, mapper);
  }
  ```
- **테스트는 변경 없음.** 최신 main엔 `@SpringBootTest`가 없다 — 모든 테스트가 `@DataJpaTest` 슬라이스 + `new FakeInfraManagerClient()`. 슬라이스는 `@EnableFeignClients`/delegate를 컴포넌트 스캔하지 않으므로 delegate가 안 뜨고, 데코레이터도 `@ConditionalOnBean`으로 안 뜬다 → fake만 쓰인다. **빈 충돌 없음.**
- `@EnableFeignClients`는 `PipelineApplication`(또는 `config/`의 설정 클래스)에. `base-url` 미설정이면 delegate가 안 떠 데모/로컬 기동도 안 깨진다.

---

## 9. 검증 (테스트 계획)

- **어댑터 번역 단위테스트** (Feign 인터페이스를 손수 stub 주입):
  - `FeignException`(4xx/5xx) → `CallFailedException`
  - null `TerraformPoll` / 빈 dispatch 응답 → `CallFailedException`
  - 정상 응답 → 도메인 타입 변환 정확성 (특히 `{jobIds:[...]}` → `["...", ...]` 재직렬화)
  - `cloudProvider` 미매칭 문자열 → `CallFailedException`
- **WireMock 통합테스트**: 실제 Feign 스택 관통 — 200 정상 / 4xx / 5xx / 지연(read timeout) / malformed 바디. 각 경로가 §2 계약대로 `CallFailed`(또는 backstop timeout) 로 귀결되는지.
- 기존 테스트 그린 유지.

의존성: `wiremock-standalone`(test scope).

---

## 10. 파일 델타 (예상)

추가:
- `client/InfraManagerFeignClient.java` — `@FeignClient` raw 전송 인터페이스
- `client/InfraManagerFeignAdapter.java` — `implements InfraManagerClient`, 전송 예외 번역 + jobIds 재직렬화 (한국어 클래스 Javadoc)
- `dto/` 에 wire DTO record (`TerraformDispatchResponse(List<String> jobIds)`, `ConditionResponse(boolean met)`, `CloudProviderResponse(String provider)`; poll은 기존 `TerraformPoll` 재사용)
- `config/FeignConfig.java` — `@EnableFeignClients` + `@Bean("infraManagerDelegate")`(§8) + `RequestInterceptor`(bearer token, §7.1) + (필요 시) `ErrorDecoder`
- 어댑터 단위테스트 + WireMock 통합테스트

변경:
- `pom.xml` — spring-cloud BOM + openfeign starter
- `application.yml` — `infra-manager` 블록

**손 안 대는 것**: `InfraManagerClient` 인터페이스, `TimeBoundedInfraManagerClient` 데코레이터, 도메인/실행 계층(`StepRunner`/`PipelineWorker`/…), 기존 테스트·fake. (테스트 0줄 변경.)

---

## 결정 (확정)

- **D-1 백엔드:** 현재 platform thread라 pinning 무관 → **기본 Feign 백엔드로 시작.** VT 전환 시 `feign-java11`로 교체(§4).
- **D-2 HTTP 계약:** 실제 스펙 없음 → `/infra/...` 가정(§5). 확정 시 인터페이스+DTO만 조정.
- **D-3 빈 격리:** 별도 조치 불요 — `infraManagerDelegate` 이름 등록 + 기존 `@ConditionalOnBean`/`@Primary` 데코레이터로 해결. `@ConditionalOnProperty(base-url)`로 프로덕션 게이트. **테스트 무변경.**
- **D-4 통합테스트:** WireMock 추가.
- **D-5 실패 세부 영속:** 신규 컬럼 없음(§6).
- **번역 위치:** delegate 어댑터의 `catch(FeignException)` 한 경계(RetryableException 포함). 타임아웃/인터럽트는 데코레이터 소유.
