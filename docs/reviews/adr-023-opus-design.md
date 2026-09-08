# ADR-023 Opus 디자인 제작·검증 기록

- 제작일: 2026-09-09.
- 산출물: [한국어 인터랙티브 설명 HTML](../adr-023-explained.html).
- 요청에 따라 실제 Claude CLI `--model opus`가 HTML/CSS/JavaScript를 직접 작성하고 후속 교정했다. 보조 에이전트는 요구사항 전달, 소스 대조, 브라우저 QA와 이 증빙 문서를 담당했다.
- Claude CLI 버전: `2.1.265`. 모든 실행의 실제 `modelUsage` 키와 `canonicalModel`은 **`claude-opus-5`**다. 다른 모델로 대체하지 않았다.
- 여섯 실행 모두 `subtype=success`, `is_error=false`, `permission_denials=[]`, stderr 0바이트, `webSearchRequests=0`이다.

## 실제 실행

각 호출은 `claude -p --model opus --effort <아래 값> --output-format json`으로 실행했다.
`--permission-mode dontAsk --strict-mcp-config --no-session-persistence`를 사용했고,
최초 세 실행에는 `Read,Glob,Grep,Write,Edit`, 마지막 세 소규모 교정에는 `Read,Edit`만 제공했다.
프롬프트는 HTML 한 파일만 수정하도록 제한했다. Java·ADR·원장은 이 디자인 작업에서 수정하지 않았다.
세 번째 실행은 실제 PR 작업트리의 `PipelineRestarter.java`를 읽도록 해당 작업트리를 추가했다.

| 작업 | effort | duration_ms | turns | inputTokens | outputTokens | thinkingTokens | cacheReadInputTokens | cacheCreationInputTokens |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 최초 재디자인 | high | 742,649 | 33 | 50 | 63,193 | 10,290 | 2,303,122 | 143,474 |
| 재시작·모바일·상태 보완 | high | 513,508 | 46 | 90 | 38,601 | 13,224 | 3,682,879 | 112,143 |
| 실제 PR 재시작 계약·조회 fixture 교정 | medium | 203,370 | 24 | 46 | 15,549 | 3,894 | 921,584 | 54,668 |
| PR 증빙 링크 | low | 6,623 | 3 | 6 | 420 | 0 | 20,364 | 10,837 |
| 어두운 배경 링크 대비 | low | 7,165 | 3 | 6 | 407 | 0 | 24,866 | 6,773 |
| TC 동일 attempt 정적 설명 | low | 4,975 | 2 | 4 | 255 | 0 | 14,066 | 5,767 |

위 수치는 원본 결과 JSON의 `modelUsage.claude-opus-5`에서 추출했다. 출력 토큰에는 디자인 작성·도구 호출 등이 포함되며 HTML 파일의 토큰 수를 뜻하지 않는다.

원본 결과와 무결성 해시:

- `/private/tmp/adr023-opus-design-result.json` — SHA-256 `2f7db5dcaf58cab56afad02d8c4e86ef9cb34276552b078cf11801495a5b681b`
- `/private/tmp/adr023-opus-design-followup-result.json` — SHA-256 `4be3955064084ca33aa0001ba18a5e9e769ef3bf80d33f07f9e20fc19fe19de1`
- `/private/tmp/adr023-opus-design-finalfix-result.json` — SHA-256 `09fa1548a8c84ae7c144213da92eb406b3abaf926bb601a333b4dc9c852477ef`
- `/private/tmp/adr023-opus-design-link-result.json` — SHA-256 `e660bb18bc55644075d00237d817437c3bf313820c6df1e0d88193cac0857f02`
- `/private/tmp/adr023-opus-design-contrast-result.json` — SHA-256 `511c7e00ad639fd7af87617cdf7b8d092f8470738ba031973f38c742aab18e20`
- `/private/tmp/adr023-opus-design-attempt-result.json` — SHA-256 `fd8fc37ce9c1ba59e0e42af956a0f6971bfbf8e663b70a86d8e0eb1b2b0a54af`

프롬프트 원본은 동일한 `/private/tmp/adr023-opus-design` 접두어의 `*-prompt.txt` 파일에 보존했다. 원본 JSON은 인증정보를 포함하지 않는다.

## 디자인과 내용

어두운 도입부와 밝은 본문, 구획선과 번호, 작업 흐름과 저장 위치의 대비를 사용한 기술 설명서다. 외부 이미지·폰트·CDN·프레임워크 없이 한 HTML에 포함했다.

- 업무 PipelineType과 실행 mechanism을 구분하고 CSP 공통 3개 정의, CUSTOM 단독·반복 실행과 Task별 독립 키를 설명한다.
- CSP 재확정 체인 안에서 추천 GET과 등록 POST를 **한 입력 Task의 내부 단계**로 표시한다. GET 후 미완료, POST retry READY, 고정 입력 재사용, 무응답·입력 유실·취소를 직접 확인할 수 있다.
- 기존 6개·확장 9개 DB 지도, 7가지 HTTP 원문/무호출/무응답/본문 상한 사례, 고정 deadline, type·exact recipe AND 조회와 latest 204, operation 활성화 게이트를 제공한다.
- 재시작은 최신 FAILED/CANCELLED의 선택된 suffix로 만든 새 실행이다. 새 Task/key/추천 GET/TC 실행과 내부 재시도를 구분한다. 진행 중 재시작 불가, 입력행 유실의 typed404, TC poll의 같은 attempt를 반영했다.
- 검증 숫자나 최종 PR 리뷰 판정을 고정하지 않고 [PR #55](https://github.com/bluefa/pipeline-orchestrator/pull/55)의 증빙으로 연결한다. H2/fake 내부 검증과 미연결 운영 adapter·MySQL·프런트 범위를 명시한다.

## 브라우저 QA

실제 설치된 Google Chrome을 Playwright로 실행했다. 다음 검증은 브라우저에서 수행했으며 Opus가 스스로 브라우저를 실행했다고 주장하지 않는다.

- 1440·768·390·320px 화면에서 문서 가로 넘침 없음.
- JavaScript 문법 및 실행 오류 없음. 자동 HTTP(S) 네트워크 요청 없음.
- 내부 앵커·ARIA 참조·고유 id 확인, 모든 select/input의 레이블 확인.
- 반복 Task 1~3개와 CSP 4개 체인, GET 후 완료 수 유지, POST 재시도·무응답 후 READY 및 같은 입력, 입력 유실·취소 확인.
- DB 6/9개 전환과 상세, HTTP 7개 사례의 상태·원문, TC 마감 재시도 비연장 확인.
- type/exact recipe AND, 최신 정렬, CUSTOM 이력 없음의 204, 비활성/미지원 생성 코드 확인.
- 재시작의 latest terminal·active_target 전제, suffix/new identity, 입력행 404, TC same-attempt를 별도로 확인.
- 데스크톱 도입부·실행 흐름·재시작 비교와 모바일 화면을 이미지로 직접 확인했다. 어두운 배경 링크의 낮은 대비도 Opus에게 수정시켰다.

초기 QA의 모바일 넘침, retry 상태 배지, 빈 이력 예시 불일치 및 재시작 설명 오류는 실제 Opus 후속 편집으로 고쳤다. HTML을 보조 에이전트가 직접 패치하지 않았다.

검증 스크립트·로그:

- `/private/tmp/adr023-html-qa/opus-smoke.cjs`
- `/private/tmp/adr023-html-qa/opus-interactions.cjs`
- `/private/tmp/adr023-opus-smoke.json`
- `/private/tmp/adr023-opus-interactions.json`

최종 화면:

- `/private/tmp/adr023-opus-desktop-final.png`
- `/private/tmp/adr023-opus-flow-final.png`
- `/private/tmp/adr023-opus-restart-final.png`
- `/private/tmp/adr023-opus-mobile-final.png`

## 배포 파일과 해시

HTML 하나만 배포하면 된다. 상대 href로 요구하는 부속 파일은 없고, `src` 자산도 없다.
유일한 외부 링크는 사용자가 직접 여는 PR #55이며, 설명과 시뮬레이션은 오프라인으로 동작한다.
웹 게시 자체는 별도 사이트 배포 작업에서 수행한다.

- 파일: `docs/adr-023-explained.html` (134,051 bytes)
- SHA-256: `ba477ce45ec5064b6327a134068a372f05f5b3851d64d5aee21a8861fe58550f`
