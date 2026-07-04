# 전체 코드 리뷰 (2026-07-04)

main 소스 전체(~6,000줄)를 대상으로 한 리뷰 결과다. 관점은 세 가지 — (1) 유지보수가 까다로운 코드,
(2) 오용되기 쉬운 interface/method, (3) 버그 가능성. 심각도·조치 현황은 맨 아래 표에 모았다.

## 총평

이 규모에서는 드물게 높은 완성도다. claim(claim 트랜잭션) → 외부 호출(무트랜잭션) → guarded
write-back(write-back 트랜잭션) 실행 모델이 코드 구조와 1:1로 대응하고
(`PipelineClaimer`/`StepRunner`/`StepReporter`), 경합 시나리오가 클래스 헤더 Javadoc에 근거까지
문서화되어 있다.

- **부팅 시점 완전성 검증** — `TaskTypeRegistry` / `InfraManagerOperationRegistry` / `RecipeCatalog`가
  바인딩·레시피 누락을 런타임이 아니라 부팅/CI에서 터뜨린다.
- **닫힌 예외 어휘** — 외부 호출 실패는 `CallTimeout/CallFailed/CallInterrupted` 셋으로만 흐르고,
  비즈니스 실패는 sealed 값(`TaskProgress`/`StepOutcome`)이다. catch-all이 없다.
- **read 열화 정책의 일관성** — 표시용 enum(operation/errorCode)은 null 열화, 엔진 분기용(status)은
  fail-fast. 컨버터마다 이유가 주석에 있다.
- **락 순서 일관성** — 취소 경로와 write-back 경로 모두 pipeline 행 락 → task 행 락 순서라 데드락
  여지를 찾지 못했다.

## 1. 유지보수가 까다로운 코드

### 1-1. `TaskStateMachine` ↔ `ObservationRecorder`의 암묵적 시간 순서 계약 (최상위)

attempt 식별이 `attemptNumber = task.failCount + 1`이라는 **파생값**에 걸려 있어, 세 클래스에 흩어진
순서 규칙을 전부 알아야 안전하게 수정할 수 있다.

- `TaskStateMachine.retryOrFail()` — `endAttempt()`를 **반드시 `failCount++` 전에** 호출해야 한다.
  순서를 바꾸면 존재하지 않는 다음 시도 번호로 기록을 시도하고 `currentAttempt()`가 empty라
  **조용히 no-op**된다(예외 없음).
- `StepOutcome.dispatchPhase() == true`인 variant는 `applyOutcome()` 직전에 `beginAttempt()`가
  선행돼야 한다는 프로토콜이 `StepOutcome` Javadoc에만 있다. 새 variant 작성자가 놓치면 attempt 없이
  관찰 기록이 전부 no-op된다.

**개선안**: (a) `endAttempt`가 대상 attempt를 못 찾을 때 no-op 대신 `log.warn` 한 줄 — 순서 실수가
즉시 보이게. (b) 규칙을 `TaskStateMachine` 클래스 헤더 불변식 항목에 완결적으로 명문화.

### 1-2. operation × API 격자의 3중 표기

같은 24개 operation 격자가 세 파일에 반복된다: `InfraManagerFeignClient`(raw + default 메서드),
`TerraformBindingCatalog`(@Bean 24행 + 테스트용 `rows()` 중복 목록), `TaskDefinition`.

구조 자체는 방어돼 있다 — 행 누락은 registry가 부팅/CI에서, `rows()` 누락은 단위 테스트가 잡는다.
실제 위험은 하나: **`TaskDefinition`의 `TaskExecutionSpec`에 박힌 API 경로 문자열**은 Feign
애노테이션 경로의 수작업 사본인데 **일치를 검증하는 것이 없다**. Feign 경로가 바뀌면 Admin API가
옛 경로를 보여주는 채로 조용히 어긋난다.

**개선안**: Feign 애노테이션을 리플렉션으로 읽어 spec 문자열과 대조하는 검증 테스트 1개 추가
(repo 철학인 부팅/CI 검증과 부합). 대안은 spec에서 경로 문자열 제거 후 "FeignClient가 진실원" 문서화.

### 1-3. 동시성 코어

lease·fencing 추론은 본질 복잡성이고 문서화로 잘 관리되고 있다. 단, ADR을 읽지 않은 수정이
위험한 영역이므로 `recurring-review` 게이트 유지가 맞다.

## 2. 오용되기 쉬운 interface / method

### 2-1. `StepReporter.writeBack(pipelineId, token, null)` — null이 프로토콜 값 ★수정됨

`outcome == null`이 "적용할 결과 없음, 수렴·claim 해제만 해라"라는 의미를 가졌다(호출부:
`PipelineWorker.processStep`의 nothingToDispatch 경로). 시그니처만 봐서는 알 수 없고, 실수로 null을
넘겨도 조용히 지나간다.

**조치**: `convergeAndRelease(pipelineId, token)` 메서드로 분리하고 `writeBack`은 non-null
`outcome`을 요구하도록 변경 (이 리뷰와 같은 변경 세트에서 수정).

### 2-2. `TaskType.check(target, task, attempt)`의 attempt nullable 계약 부재 ★수정됨

`StepRunner`는 현재 attempt 행이 없으면 null을 넘기고, `TerraformTask`만 이를 "관찰 유실" 신호로
처리한다. 그런데 `TaskType` 인터페이스 Javadoc에는 attempt가 null일 수 있다는 언급이 없다 —
세 번째 TaskType 구현자가 NPE를 내기 좋은 지점.

**조치**: `checkWithoutAttempt(target, task)` 메서드를 인터페이스에 분리해 null을 계약에서 제거
(`check`의 attempt는 항상 non-null 보장). "attempt 유실 시 어떻게 할 것인가"를 모든 TaskType
구현자가 명시적으로 결정하게 된다. 분기는 엔진(`StepRunner`)이 소유한다 (이 리뷰와 같은 변경
세트에서 수정).

### 2-3. `ObservationRecorder`의 silent no-op

`recordResponse`/`endAttempt`는 attempt 행이 없으면 조용히 넘어간다. 유실 복원력으로는 옳지만
"beginAttempt 전에 호출" 같은 **호출자 버그까지 같은 침묵으로** 삼킨다. 최소 warn 로그 권장
(1-1 개선안과 동일 건).

### 2-4. `TimeBoundedInfraManagerClient` — 타임아웃에 큐 대기가 포함됨

`infraManagerCallPool`(크기 = workerPerPod = 4)을 워커 폴링과 admin 경로(`PipelineCreator.
resolveProvider` → `cloudProvider`)가 공유한다. 풀 포화 시 `future.get(30s)`의 30초에 큐 대기가
포함돼 호출을 시작도 못 하고 `CallTimeoutException`이 난다. 또 타임아웃 시 `future.cancel(true)`는
소켓 read를 중단시키지 못하는 경우가 많아 그 스레드는 Feign readTimeout(60s)까지 슬롯을 점유한다 —
느린 InfraManager에서 admin create가 연쇄 503이 되는 시나리오. **admin 경로용 별도 소형 풀 분리**
또는 큐 대기/실행 구분 계측 권장.

### 2-5. `PipelineWorker.pollOnce()` — 프로덕션 빈의 public 테스트 심

실제 claim+처리를 수행한다. 문서화가 계약을 지고 있어 현 규모에서는 허용 범위.

### 2-6. `TaskSettingsResolver.isPastDeadline` 문서-구현 불일치 (경미)

Javadoc은 "현재 attempt의 startedAt 기준"이라 하지만 실제로는 `task.getStartedAt()`을 쓴다.
`markInProgress`가 dispatch마다 startedAt을 갱신하므로 결과는 동치지만, 이 동치성 자체가 암묵
규칙이다. 주석 한 줄로 명시 권장.

## 3. 버그 가능성

### B1. `GlobalAdvice` catch-all이 프레임워크 404/405를 500으로 변환 (가장 확실)

`@ExceptionHandler(Exception.class)`는 `DefaultHandlerExceptionResolver`보다 우선한다. Spring Boot
3.2+에서:

- 존재하지 않는 경로 GET → `NoResourceFoundException` → **404가 아니라 500** + error 스택트레이스 로그
- 잘못된 HTTP 메서드 → `HttpRequestMethodNotSupportedException` → **405가 아니라 500**

아무나 `GET /favicon.ico`로 error 로그(알람 노이즈)와 잘못된 500을 만들 수 있고 BFF 쪽 HTTP
의미론이 깨진다. **수정**: `ErrorResponseException` 계열(`NoResourceFoundException` 포함)을 자기
status 그대로 매핑하는 핸들러를 catch-all 앞에 추가.

### B2. lease(2분)가 "호출 1회 타임아웃"만 보장, "turn 전체"는 미보장 (저확률·고혼란 엣지)

`ExecutionSettings`는 `leaseDuration > apiCallTimeout`을 강제하지만, TERRAFORM_JOB check turn은
job id 개수만큼 **순차** status 호출(+종결 turn엔 job별 result 조회)을 한다. dispatch 하나가 N개
job을 만들 수 있으므로 InfraManager가 느려지면 turn 소요 ≈ N×30s(+큐 대기)가 되어 N≥4부터 2분
lease를 넘을 수 있다. 그러면 재claim → 토큰 회전 → 원래 워커의 write-back no-op → 중복 폴링이고,
장애가 지속되면 매 turn이 lease를 넘겨 write-back이 계속 버려지는 준-livelock도 이론상 가능하다.
application.yml 주석은 이를 운영 튜닝 여지로 인지하고 있으나, **검증식이 주는 확신과 실제 보장
범위가 다르다**. 후보: job 폴 병렬화 + turn 데드라인 / job 폴 사이 lease 갱신 / 최소한
"lease > 예상 최대 job 수 × timeout" 문서·검증 반영.

### B3. `runSweep` finally에서 delay 계산이 던지면 루프가 조용히 죽음 (방어 관점)

`nextDelay()`/`cappedIdleDelay()` 자체가 예외를 던지면(현재 코드상 가능성 희박) `schedule()`이
호출되지 않고 스케줄러 루프가 로그 없이 영구 정지한다. finally 내부의 delay 계산을
`catch (RuntimeException) { delay = backoffBase }`로 감싸면 "재예약을 멈추는 건 인터럽트뿐"이라는
클래스 계약이 코드로 완성된다.

### 버그 아님으로 확인한 항목 (검토 흔적)

- attempt 번호 중복/유실 경합 — 단일 claim-holder + write-back 트랜잭션 내 `beginAttempt` 구조라
  발생 경로 없음. `uq_task_attempt` 유니크가 이중 방어.
- cancel(Case A/B) vs claim vs write-back 경합 — 락 순서·토큰 가드가 전 조합에서 수렴.
- `TerraformResultRecorder`의 run 단계(트랜잭션 밖) 저장 — 유니크 키 + 존재 선검사로 멱등, 설계
  의도대로 안전.

## 우선순위 / 조치 현황

| 순위 | 항목 | 심각도 | 조치 |
|---|---|---|---|
| 1 | B1 — GlobalAdvice 404/405→500 | 버그 (확실) | 미조치 — 핸들러 1개 추가 필요 |
| 2 | 2-1 — `writeBack(..., null)` sentinel | 오용 위험 | **수정 완료** — `convergeAndRelease` 분리 |
| 3 | 2-2 — `TaskType.check` attempt nullable | 오용 위험 | **수정 완료** — `checkWithoutAttempt` 분리 |
| 4 | B2 — lease vs multi-call turn 마진 | 설계 엣지 | 미조치 — 설계 판단 필요 |
| 5 | 1-2 — TaskDefinition 경로 문자열 ↔ Feign 대조 | 드리프트 위험 | 미조치 — 검증 테스트 1개 |
| 6 | 1-1/2-3 — 관찰 프로토콜 명문화 + no-op warn | 유지보수 | 미조치 |
| 7 | 2-4 — call pool 공유/큐 대기 타임아웃 | 운영 리스크 | 미조치 |
| 8 | B3 — runSweep finally 방어 | 하드닝 | 미조치 |
| 9 | 2-6 — isPastDeadline 주석 | 문서 | 미조치 |
