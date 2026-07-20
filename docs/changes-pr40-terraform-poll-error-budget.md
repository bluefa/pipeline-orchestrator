# PR #40 변경 정리 — terraform 폴 전송 실패의 job별 연속 임계 흡수

> 대상 PR: [#40 `feat/per-job-poll-error-budget`](https://github.com/bluefa/pipeline-orchestrator/pull/40)
> base: `main`(merge-base `cb92386`) … head `9efe671`
> 목적: 배포/마이그레이션 시 "무엇이·어떻게" 바뀌었는지 정확히 파악하기 위한 문서.

## 0. 한 줄 요약

terraform job 폴 단계의 **전송 실패(5xx·타임아웃·null 상태)**를 task를 즉시 실패시키지 않고 **job별로 흡수**한다. 한 job이 **중간에 성공 관측 없이 연속 N회**(기본 3) 폴 호출에 실패하면 그 job만 관측 불능으로 확정해 `JOB_FAILED`(재시도 가능)로 종결한다. 누적 횟수는 관찰 테이블 `terraform_job_state`의 새 컬럼 `call_error_count`에 저장한다.

---

## 1. DB 스키마 변경 (⚠️ 마이그레이션 필요 항목)

**단 하나의 스키마 변경**: `terraform_job_state` 테이블에 컬럼 1개 추가.

| 항목 | 값 |
|---|---|
| 테이블 | `terraform_job_state` |
| 추가 컬럼 | `call_error_count` |
| 타입 | `INT` (Java `int`) |
| 제약 | `NOT NULL` |
| 인덱스 | 없음 (유니크 제약·인덱스 변경 없음) |
| 신규 행 기본값 | 앱이 insert 시 항상 0으로 세팅(원시 int) |

### MySQL 8 (프로덕션) 마이그레이션 DDL

기존 행이 있으므로 `NOT NULL` 컬럼은 **`DEFAULT 0`으로 backfill**해야 한다:

```sql
ALTER TABLE terraform_job_state
  ADD COLUMN call_error_count INT NOT NULL DEFAULT 0;
```

- 기존 행은 전부 `0`으로 채워진다 — 진행 중이던 job의 누적 실패 이력이 없다는 의미로 안전하다(판정에 영향 없음).
- DB 레벨 `DEFAULT 0`은 그대로 둬도 무해하다(앱 insert가 항상 값을 명시하므로 실사용에는 안 쓰인다).

### ddl-auto 동작에 따른 분기

현재 `application.yml`은 `spring.jpa.hibernate.ddl-auto: update`다.

- **`update` 유지 배포**: Hibernate가 기동 시 컬럼을 자동 추가하고 기존 행을 `0`으로 채운다 → **수동 DDL 불필요**.
- **`validate`로 전환한 배포**(스키마 동결 후): Hibernate가 컬럼 부재를 검증 실패로 본다 → **위 DDL을 배포 전에 수동 적용**해야 기동한다.
- 테스트(H2)는 `create-drop`이라 마이그레이션 무관.

### 롤백

컬럼은 이전 코드가 읽지 않으므로 앱 롤백 시 **컬럼을 남겨둬도 무해**하다. 굳이 되돌리려면:

```sql
ALTER TABLE terraform_job_state DROP COLUMN call_error_count;
```

---

## 2. 설정(Config) 변경

### 2-1. 신규 프로퍼티

| 프로퍼티 | env | 기본값 | 검증 |
|---|---|---|---|
| `pipeline.max-terraform-poll-call-errors` | `PIPELINE_MAX_TERRAFORM_POLL_CALL_ERRORS` | **3** | `< 1`이면 기동 실패(fail-fast) |

의미: job 하나가 **연속** 폴 호출 실패 몇 회에 도달하면 관측 불능으로 확정할지. 중간에 정상 관측이 한 번이라도 들어오면 카운트는 0으로 리셋된다(연속 시맨틱).

### 2-2. 기본값이 "어디에" 설정되나 — 코드(`PipelineSettings`)로 이동

`pipeline.*` 도메인 데드라인 값의 **기본값은 이제 `PipelineSettings.java`의 `@DefaultValue`가 단일 출처**다:

```java
public record PipelineSettings(
        @DefaultValue("PT50M") Duration executionTimeout,
        @DefaultValue("PT10M") Duration pollingInterval,
        @DefaultValue("2")     int maxFailCount,
        @DefaultValue("3")     int maxTerraformPollCallErrors,
        @DefaultValue("PT15S") Duration startDelay) { ... }
```

바인딩 규칙:
- yml/env에 키가 **없으면** → `@DefaultValue` 값 사용
- 키가 **있으면** → yml/env 값이 오버라이드

그래서 `application.yml`의 `pipeline.execution-timeout` 등 도메인 데드라인 키는 **yml에서 제거**했다(기본값이 코드에 있으므로 중복). yml에는 "기본값은 PipelineSettings에 있고 필요하면 여기서 오버라이드하라"는 안내 주석만 남겼다. **동작 변화 없음** — `@DefaultValue` 값이 기존 yml 값과 동일하다:

| 키 | 기존 yml 값 | 현재 코드 기본값 | 동일? |
|---|---|---|---|
| `execution-timeout` | PT50M | PT50M | ✅ |
| `polling-interval` | PT10M | PT10M | ✅ |
| `max-fail-count` | 2 | 2 | ✅ |
| `start-delay` | PT15S | PT15S | ✅ |
| `max-terraform-poll-call-errors` | (없음) | **3** | 신규 |

> 배포에서 특정 값을 명시하려면 `application.yml`의 `pipeline:` 아래에 해당 키를 추가하거나 env로 주입하면 된다.

### 2-3. fail-fast 검증

`PipelineSettings` compact constructor가 기동 시 검증한다. 잘못된 값(양수 아닌 duration, 음수 start-delay, `max-fail-count`·`max-terraform-poll-call-errors` < 1)이면 **문제 키 이름과 함께 기동 실패**한다. 미설정은 `@DefaultValue`로 채워지므로 실패하지 않는다.

---

## 3. 동작(Behavior) 변경

### 3-1. 폴 전송 실패 처리

- **이전**: 폴 호출이 실패하면 예외가 위로 전파돼 task가 실패 처리되고 `failCount`가 소진됐다.
- **변경 후**: 폴 전송 실패는 예외로 던지지 않고 job별로 흡수한다.
  - 연속 실패 < 임계 → 이번 turn 미관측(미종결)로 두고 다음 폴에서 재시도. `task.failCount`는 이 경로로 오르지 않음.
  - 정상 관측 1회 → `call_error_count` **0으로 리셋**.
  - 연속 실패 ≥ 임계 → 그 job만 관측 불능(`UNREACHABLE`)으로 확정 → 전원 종결 판정에서 `JOB_FAILED`(재시도 가능).
- 형제 job은 한 job의 실패와 독립적으로 계속 폴된다.

### 3-2. 판정 원인 텍스트(`failure_detail`) 구분

`JOB_FAILED` 시 원인 텍스트가 두 경우를 구분한다:
- `jobs reported FAILED: [...]` — 정상 응답이 FAILED를 보고한 job
- `jobs unreachable after poll budget: [...]` — 폴 임계 초과로 관측 불능이 된 job

### 3-3. 기본 설정에서의 상호작용 (주의)

기본값 기준 `3(연속) × PT10M(폴 간격) = 30분 < PT50M(execution-timeout)`. 즉 **기본 배포에서도 연속 3회 실패면 timeout 전에 버짓이 발화**해 job을 관측 불능으로 확정한다. (임계가 10이던 이전 설계에선 timeout이 먼저 끊어 버짓이 사실상 비활성이었다.) task 단위 "전원 종결 대기 + `maxFailCount` 재시도" 정책은 그대로다.

---

## 4. API / 컨트랙트 변경 — **없음**

- `call_error_count`는 **어떤 응답 DTO에도 노출되지 않는다**(엔진 내부 판정용). `TerraformJobStateSummary`/`TerraformJobStateDetail`/메타 투영 모두 미포함.
- per-job 상태 조회 API, task 상세 응답의 필드 구성은 **변경 없음**.
- → 프론트엔드/BFF 등 소비자 측 마이그레이션 **불필요**.

---

## 5. 변경 파일 목록

### 프로덕션 코드
| 파일 | 변경 |
|---|---|
| `entity/TerraformJobState.java` | `call_error_count` 컬럼 필드 추가, javadoc 갱신 |
| `config/PipelineSettings.java` | `maxTerraformPollCallErrors` 필드 추가, 전 필드 `@DefaultValue`, `<1` 검증 |
| `service/task/terraform/TerraformJobStateRecorder.java` | `recordCallError`(연속 카운트 반환)·`currentCallErrorCount`(가드 읽기) 추가, `recordObserved`가 정상 관측 시 0 리셋 |
| `service/task/terraform/TerraformTask.java` | 폴 전송 실패 흡수·연속 임계 판정·sticky·`failure_detail` 구분 |
| `resources/application.yml` | pipeline.* 데드라인 키 제거(기본값은 코드), 안내 주석만 유지 |

### 테스트 / 리소스
| 파일 | 변경 |
|---|---|
| `PipelineSettingsTest.java` (신규) | fail-fast·`@DefaultValue` 바인딩 검증 |
| `PipelineExecutionTest.java` | 연속 버짓 흡수·리셋·sticky·null 폴·조기 로그 시나리오 추가 |
| `TerraformJobStateRecorderTest.java` | 연속 누적·정상 폴 리셋·저장 유실 반환 검증 |
| 그 외 8개 Wiring 테스트 | `.maxTerraformPollCallErrors(...)` 빌더 인자 추가 |
| `test/resources/application.yml` | 기본값 경로 검증 주석 |

전체 **243개 테스트 통과**.

---

## 6. 배포 체크리스트

1. [ ] (`ddl-auto: validate`인 경우) `terraform_job_state`에 `call_error_count` 컬럼 추가 DDL 선적용 — §1
2. [ ] 필요 시 `PIPELINE_MAX_TERRAFORM_POLL_CALL_ERRORS` env로 임계 오버라이드(미설정 시 3)
3. [ ] 기존에 yml로 `pipeline.execution-timeout` 등을 **명시 관리**하던 배포라면, 키가 yml에서 제거됐어도 코드 기본값(§2-2)이 동일함을 확인 — 기본과 **다른 값**을 쓰던 배포는 `pipeline:` 아래에 키를 다시 추가하거나 env로 유지
4. [ ] 컨트랙트 변경 없음 → 소비자 측 조치 불필요(§4)
