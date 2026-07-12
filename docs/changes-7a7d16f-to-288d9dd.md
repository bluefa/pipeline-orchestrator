# 변경 파일 기록: `7a7d16f` → `288d9dd`

`git diff 45829656013529613660bec4b9fc2b3e18b2f42b 288d9dd` 기준.

- 시작점: `4582965` (#31, 2026-07-04) — `7a7d16f`(#32)의 부모 커밋
- 종료점: `288d9dd` (#36, HEAD)
- 이 범위는 "**`7a7d16f` 커밋 포함, 최신까지**"와 동일하다 (`4582965`가 `7a7d16f`의 직전 커밋이므로).

## 포함된 커밋 (오래된 순)

| 커밋 | 제목 |
|------|------|
| `7a7d16f` (#32) | feat: terraform job 실패 원인(failure_detail) 영속·노출 + result 조회 API |
| `448f8d0` (#35) | chore(codex-review): 리뷰 모델을 gpt-5.6-sol로 변경 |
| `c8d1d87` (#33) | feat: terraform job 진행-시점 상태 관찰(terraform_job_state)·per-job 상태 조회 API |
| `8643074` (#34) | feat: ADR-022 종단 상태 알림 — 상태 파생 + 행 잠금 단일 트랜잭션 전달 + Slack webhook(env) |
| `288d9dd` (#36) | refactor: 순수 필드 대입 생성자를 @RequiredArgsConstructor로 대체 (19개 컴포넌트) |

## 요약

- **82개 파일 변경**, +4775 / −372
- 신규(A) 27개, 수정(M) 55개, 삭제 0개

## 변경 상세 (`--name-status`, A=신규 / M=수정)

### 문서 · 설정
| 상태 | 파일 |
|------|------|
| M | `.claude/agents/recurring-review.md` |
| M | `.claude/review-ledger.md` |
| M | `.claude/skills/codex-review/SKILL.md` |
| A | `design/pipeline/022-notifier-implementation.md` |
| M | `docs/adr/016-install-delete-pipeline-domain-model.md` |
| A | `docs/adr/022-terminal-state-notification.md` |
| M | `docs/terraform-client-and-postcheck-design.md` |
| A | `docs/terraform-result-exposure-changes-2026-07-06.md` |
| M | `mock-infra.js` |
| M | `src/main/resources/application.yml` |

### main 소스 — client
| 상태 | 파일 |
|------|------|
| M | `client/condition/ConditionOperationBinding.java` |
| M | `client/condition/NetworkReadyBinding.java` |
| M | `client/terraform/TerraformOperationBinding.java` |

### main 소스 — config / controller
| 상태 | 파일 |
|------|------|
| A | `config/NotifySettings.java` |
| M | `config/PipelineConfig.java` |
| M | `controller/GlobalAdvice.java` |
| M | `controller/PipelineController.java` |
| M | `controller/TargetSourcePipelineController.java` |

### main 소스 — dto
| 상태 | 파일 |
|------|------|
| M | `dto/NetworkReadyResponse.java` |
| A | `dto/NotifyPayload.java` |
| M | `dto/TerraformJobStatusResponse.java` |
| M | `dto/TerraformPoll.java` |
| M | `dto/pipeline/TaskAttemptView.java` |
| A | `dto/pipeline/TerraformJobStateDetail.java` |
| A | `dto/pipeline/TerraformJobStateSummary.java` |
| A | `dto/pipeline/TerraformResultDetail.java` |
| A | `dto/pipeline/TerraformResultSummary.java` |

### main 소스 — entity / enums / exception / model
| 상태 | 파일 |
|------|------|
| M | `entity/Pipeline.java` |
| M | `entity/TaskAttempt.java` |
| A | `entity/TerraformJobState.java` |
| M | `entity/TerraformResult.java` |
| M | `enums/TaskExecutionSpec.java` |
| M | `exception/OrchestrationErrorCode.java` |
| A | `exception/TerraformJobStateNotFoundException.java` |
| A | `exception/TerraformResultNotFoundException.java` |
| M | `model/StepOutcome.java` |
| M | `model/TaskProgress.java` |
| A | `model/TerraformJobRef.java` |

### main 소스 — repository
| 상태 | 파일 |
|------|------|
| M | `repository/PipelineRepository.java` |
| A | `repository/TerraformJobStateMetadata.java` |
| A | `repository/TerraformJobStateRepository.java` |
| A | `repository/TerraformResultMetadata.java` |
| M | `repository/TerraformResultRepository.java` |

### main 소스 — service
| 상태 | 파일 |
|------|------|
| M | `service/execution/PipelineClaimer.java` |
| M | `service/execution/PipelineWorker.java` |
| M | `service/execution/StepReporter.java` |
| M | `service/execution/StepRunner.java` |
| M | `service/lifecycle/PipelineControl.java` |
| M | `service/lifecycle/PipelineCreator.java` |
| M | `service/lifecycle/PipelineInserter.java` |
| A | `service/notify/SlackNotifier.java` |
| A | `service/notify/TerminalNotifier.java` |
| M | `service/query/PipelineQueryService.java` |
| M | `service/task/ConditionCheckTask.java` |
| M | `service/task/ObservationRecorder.java` |
| M | `service/task/TaskCanceller.java` |
| M | `service/task/TaskStateMachine.java` |
| A | `service/task/terraform/TerraformJobStateRecorder.java` |
| M | `service/task/terraform/TerraformResultRecorder.java` |
| M | `service/task/terraform/TerraformTask.java` |

### 테스트
| 상태 | 파일 |
|------|------|
| A | `NotifySettingsTest.java` |
| M | `client/FakeInfraManagerClient.java` |
| M | `client/FeignDelegateWiringTest.java` |
| M | `client/InfraManagerFeignAdapterTest.java` |
| M | `client/InfraManagerFeignIntegrationTest.java` |
| M | `client/InfraManagerOperationRegistryTest.java` |
| M | `client/TimeBoundedInfraManagerClientTest.java` |
| A | `dto/NotifyPayloadPiiTest.java` |
| A | `dto/RawResponseCaptureDecodeTest.java` |
| M | `dto/pipeline/DtoSnakeCaseSerializationTest.java` |
| M | `service/MutableClock.java` |
| M | `service/ObservationTest.java` |
| M | `service/PipelineExecutionTest.java` |
| M | `service/PipelineIntegrationTest.java` |
| M | `service/PipelineSoftCapTest.java` |
| M | `service/PipelineStartDelayTest.java` |
| A | `service/TerraformJobStateQueryTest.java` |
| A | `service/TerraformResultQueryTest.java` |
| A | `service/notify/SlackNotifierTest.java` |
| A | `service/notify/TerminalNotifierTest.java` |
| A | `service/task/terraform/TerraformJobStateRecorderTest.java` |
| M | `service/task/terraform/TerraformResultRecorderTest.java` |

## 재현 명령

```bash
git diff --stat 45829656013529613660bec4b9fc2b3e18b2f42b 288d9dd
git diff --name-status -M 45829656013529613660bec4b9fc2b3e18b2f42b 288d9dd
```
