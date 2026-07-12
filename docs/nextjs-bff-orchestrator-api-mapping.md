# Next.js BFF ↔ pipeline-orchestrator API 매핑

`pii-agent-demo`(Next.js BFF)가 노출하는 API 중, 본 repo(`pipeline-orchestrator`)로 프록시되는 항목의 경로 대응표.

## 경로 규칙

- **basePath `/integration`**: `next.config.ts`의 `basePath: '/integration'`(LIN-56 / ADR-024)로 인해 route·API 핸들러·`/_next/*` 자산이 모두 `/integration` prefix로 서빙된다. 브라우저가 보는 실제 경로에는 `/integration`이 붙는다.
- **`orchestrator` 네임스페이스**: BFF는 `app/api/v1/orchestrator/**` 아래 route만 본 repo로 프록시한다 (`app/api/_lib/orchestrator.ts`의 `withOrchestratorProxy`).
- **verbatim 프록시**: upstream 상태코드와 snake_case 본문을 그대로 전달(204 → body null). upstream 도달 불가(`OrchestratorUnreachableError`)일 때만 `502 { code: 'ORCHESTRATOR_UNREACHABLE' }`로 변환.
- **param 표기**: Next는 `[param]`, 본 repo는 `{param}`.
- **내부 호출**: BFF가 본 repo를 호출할 때는 `/integration` prefix 없이 `/api/v1/...`로 요청한다 (prefix는 브라우저 노출 경로에만 적용).

## 매핑표

| Next.js 노출 경로 (basePath 포함) | pipeline-orchestrator 컨트롤러 경로 |
|---|---|
| `/integration/api/v1/orchestrator/pipelines` | `/api/v1/pipelines` |
| `/integration/api/v1/orchestrator/pipelines/statistics` | `/api/v1/pipelines/statistics` |
| `/integration/api/v1/orchestrator/pipelines/statistics/live` | `/api/v1/pipelines/statistics/live` |
| `/integration/api/v1/orchestrator/pipelines/[pipelineId]` | `/api/v1/pipelines/{pipelineId}` |
| `/integration/api/v1/orchestrator/pipelines/[pipelineId]/cancel` | `/api/v1/pipelines/{pipelineId}/cancel` |
| `/integration/api/v1/orchestrator/pipelines/[pipelineId]/tasks/[taskId]` | `/api/v1/pipelines/{pipelineId}/tasks/{taskId}` |
| `/integration/api/v1/orchestrator/pipelines/[pipelineId]/tasks/[taskId]/attempts/[attemptNumber]/jobs/[jobId]/result` | `/api/v1/pipelines/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/result` |
| `/integration/api/v1/orchestrator/pipelines/[pipelineId]/tasks/[taskId]/attempts/[attemptNumber]/jobs/[jobId]/state` | `/api/v1/pipelines/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/state` |
| `/integration/api/v1/orchestrator/target-sources/[targetSourceId]/pipelines` | `/api/v1/target-sources/{targetSourceId}/pipelines` |
| `/integration/api/v1/orchestrator/target-sources/[targetSourceId]/pipelines/latest` | `/api/v1/target-sources/{targetSourceId}/pipelines/latest` |
| `/integration/api/v1/orchestrator/target-sources/[targetSourceId]/pipelines/preview` | `/api/v1/target-sources/{targetSourceId}/pipelines/preview` |
| `/integration/api/v1/orchestrator/target-sources/[targetSourceId]/pipelines/custom` | `/api/v1/target-sources/{targetSourceId}/pipelines/custom` |
| `/integration/api/v1/orchestrator/task-definitions` | `/api/v1/task-definitions` |

본 repo 컨트롤러 엔드포인트 13개 전부가 대응되며 누락 없음.

## 범위 밖 (본 repo 아님)

`app/api/v1/orchestrator/**` 밖의 Next route(`aws/`, `azure/`, `gcp/`, `idc/`, `services/`, `target-sources/.../approval-requests`, `user/`, `health` 등)는 다른 백엔드용이며 본 repo와 무관.

## 출처

- Next repo: `bluefa/pii-agent-demo` @ `main`
  - `next.config.ts` (basePath)
  - `app/api/v1/orchestrator/**/route.ts` (노출 경로)
  - `app/api/_lib/orchestrator.ts` (프록시 래퍼)
- 본 repo: `controller/PipelineController.java`, `controller/TargetSourcePipelineController.java`, `controller/TaskDefinitionController.java`
