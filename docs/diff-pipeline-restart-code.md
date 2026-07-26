# feat/pipeline-restart — 코드/유닛 테스트 diff (vs origin/main)

- 브랜치: `feat/pipeline-restart` (PR #42)
- 기준: `origin/main` (a66b05d) → HEAD (2fb8ec0)
- 범위: `src/main`, `src/test` 만 (docs/ADR/수용기준 문서 제외)

## 변경 요약

```
 .../controller/TargetSourcePipelineController.java |  25 ++
 .../java/com/bff/pipeline/dto/NotifyPayload.java   |   7 +-
 .../bff/pipeline/dto/pipeline/PipelineDetail.java  |   7 +-
 .../bff/pipeline/dto/pipeline/PipelineSummary.java |   8 +-
 .../pipeline/dto/pipeline/RestartOriginView.java   |  24 ++
 .../dto/pipeline/RestartPipelineRequest.java       |  11 +
 .../bff/pipeline/dto/pipeline/RestartPreview.java  |  55 ++++
 .../com/bff/pipeline/dto/pipeline/TaskDetail.java  |   3 +-
 .../com/bff/pipeline/dto/pipeline/TaskSummary.java |   5 +-
 .../java/com/bff/pipeline/entity/Pipeline.java     |  12 +-
 src/main/java/com/bff/pipeline/entity/Task.java    |   7 +
 .../exception/InvalidResumeSequenceException.java  |  17 ++
 .../pipeline/exception/OrchestrationErrorCode.java |   3 +
 .../exception/PipelineNotLatestException.java      |  16 +
 .../exception/PipelineNotRestartableException.java |  18 ++
 .../java/com/bff/pipeline/model/PipelinePlan.java  |  34 ++-
 .../pipeline/repository/PipelineRepository.java    |   3 +
 .../service/lifecycle/PipelineCreator.java         |   8 +-
 .../service/lifecycle/PipelineInserter.java        |   2 +
 .../service/lifecycle/PipelineRestarter.java       | 187 ++++++++++++
 .../bff/pipeline/service/notify/SlackNotifier.java |   8 +-
 .../pipeline/service/notify/TerminalNotifier.java  |   1 +
 .../service/query/PipelineQueryService.java        |  39 +++
 .../com/bff/pipeline/dto/NotifyPayloadPiiTest.java |   7 +-
 .../pipeline/DtoSnakeCaseSerializationTest.java    |  32 +-
 .../pipeline/service/CustomRecipeCreationTest.java |   5 +-
 .../pipeline/service/PipelineIntegrationTest.java  |   5 +-
 .../bff/pipeline/service/RestartPipelineTest.java  | 339 +++++++++++++++++++++
 .../pipeline/service/notify/SlackNotifierTest.java |  21 ++
 29 files changed, 873 insertions(+), 36 deletions(-)
```

## 전체 diff

```diff
diff --git a/src/main/java/com/bff/pipeline/controller/TargetSourcePipelineController.java b/src/main/java/com/bff/pipeline/controller/TargetSourcePipelineController.java
index df3741b..8eaea36 100644
--- a/src/main/java/com/bff/pipeline/controller/TargetSourcePipelineController.java
+++ b/src/main/java/com/bff/pipeline/controller/TargetSourcePipelineController.java
@@ -5,9 +5,12 @@ import com.bff.pipeline.dto.pipeline.CustomPipelineRequest;
 import com.bff.pipeline.dto.pipeline.PipelineDetail;
 import com.bff.pipeline.dto.pipeline.PipelineSummary;
 import com.bff.pipeline.dto.pipeline.RecipePreview;
+import com.bff.pipeline.dto.pipeline.RestartPipelineRequest;
+import com.bff.pipeline.dto.pipeline.RestartPreview;
 import com.bff.pipeline.enums.PipelineType;
 import com.bff.pipeline.exception.MissingPipelineTypeException;
 import com.bff.pipeline.service.lifecycle.PipelineCreator;
+import com.bff.pipeline.service.lifecycle.PipelineRestarter;
 import com.bff.pipeline.service.query.PipelineQueryService;
 import lombok.RequiredArgsConstructor;
 import org.springframework.data.domain.Page;
@@ -37,6 +40,7 @@ public class TargetSourcePipelineController {
 
     private final PipelineQueryService queryService;
     private final PipelineCreator pipelineCreator;
+    private final PipelineRestarter pipelineRestarter;
 
     @GetMapping
     public Page<PipelineSummary> history(@PathVariable String targetSourceId,
@@ -74,4 +78,25 @@ public class TargetSourcePipelineController {
         return queryService.toDetail(pipelineCreator.createCustom(targetSourceId,
                 request == null ? null : request.tasks()));
     }
+
+    /**
+     * 재시작 미리보기(재시작 설계 §3.1). 실행과 동일한 검증을 먼저 수행하므로 불가 상태(DONE·live·최신 아님)는
+     * 미리보기 단계부터 409다. from_sequence 오버라이드 시의 미리보기도 같은 검증으로 지원한다.
+     */
+    @GetMapping("/{pipelineId}/restart-preview")
+    public RestartPreview restartPreview(@PathVariable String targetSourceId, @PathVariable Long pipelineId,
+            @RequestParam(name = "from_sequence", required = false) Integer fromSequence) {
+        return pipelineRestarter.preview(targetSourceId, pipelineId, fromSequence);
+    }
+
+    /**
+     * 재시작 실행(재시작 설계 §3.2). 원본 체인의 첫 non-DONE task부터(또는 from_sequence 오버라이드 — 더
+     * 앞으로만) suffix로 새 파이프라인을 만든다. 본문은 생략 가능하고, 응답은 create와 동일한 상세다.
+     */
+    @PostMapping("/{pipelineId}/restart")
+    public PipelineDetail restart(@PathVariable String targetSourceId, @PathVariable Long pipelineId,
+            @RequestBody(required = false) RestartPipelineRequest request) {
+        return queryService.toDetail(pipelineRestarter.restart(targetSourceId, pipelineId,
+                request == null ? null : request.fromSequence()));
+    }
 }
diff --git a/src/main/java/com/bff/pipeline/dto/NotifyPayload.java b/src/main/java/com/bff/pipeline/dto/NotifyPayload.java
index fb4a357..70dc998 100644
--- a/src/main/java/com/bff/pipeline/dto/NotifyPayload.java
+++ b/src/main/java/com/bff/pipeline/dto/NotifyPayload.java
@@ -13,6 +13,8 @@ import lombok.Builder;
  * {@code cloudProvider}도 같은 이유(또는 미지정 파이프라인)로 null일 수 있다(AWS | GCP | AZURE | IDC | null).
  * {@code environment}는 설정에서 오는 배포 환경 이름(stg, prd 등)이다 — 여러 환경이 한 채널을
  * 공유할 때 알림을 구분한다.
+ * {@code originPipelineId}는 재시작 계보다 — 이 실행이 재시작한 원본 파이프라인 id(재시작이 아니면 null).
+ * 수신측이 "INSTALL(#123의 재시작)" 문맥을 붙일 수 있게 한다. id 값이라 민감 정보가 아니다.
  * {@code detailUrl}은 이 스키마에서 유일하게 허용되는 링크다. 파이프라인 상세 화면 주소
  * (설정된 base)에 파이프라인 id만 붙여 만들고(오너 결정 2026-07-10), 대상 정보가 담긴 다른
  * 링크는 여전히 금지다.
@@ -28,8 +30,9 @@ public record NotifyPayload(
         String failedTask,
         String errorCode,
         String detailUrl,
-        String schemaVersion) {
+        String schemaVersion,
+        Long originPipelineId) {
 
     /** payload 스키마 버전. 알림을 받는 쪽이 형식이 바뀌었는지 구분하는 기준 값이다. */
-    public static final String SCHEMA_VERSION = "2";
+    public static final String SCHEMA_VERSION = "3";
 }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/PipelineDetail.java b/src/main/java/com/bff/pipeline/dto/pipeline/PipelineDetail.java
index 6bf10a5..df9e9cc 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/PipelineDetail.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/PipelineDetail.java
@@ -13,6 +13,8 @@ import lombok.Builder;
  * + 현재·최종 task 파생값 + task 흐름 목록을 담는다. claimedBy fencing 토큰은 보안상 노출하지 않고 leased(boolean)로만
  * 표시한다. currentTaskSequence는 최저 순번의 READY/IN_PROGRESS task이며, 그런 task가 없으면(모두 종료) null이다.
  * dueLagMillis는 now 기준 지연(now - nextDueAt)을 ms로 준 값으로, 아직 due가 아니거나 종료 상태면 0으로 클램프한다.
+ * 재시작 계보 3필드: originPipelineId(이 실행이 재시작한 원본), origin(원본 요약 블록 — 원본이 있을 때만),
+ * restartedByPipelineId(역링크 — 이 실행을 재시작한 최신 실행). 셋 다 해당 없으면 null이다.
  * 와이어 필드는 BFF swagger 계약에 맞춰 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신
  * {@code @Builder}로 만든다.
  */
@@ -36,5 +38,8 @@ public record PipelineDetail(
         @JsonProperty("current_max_fail_count") Integer currentMaxFailCount,
         @JsonProperty("done_task_count") long doneTaskCount,
         @JsonProperty("total_task_count") long totalTaskCount,
-        @JsonProperty("tasks") List<TaskSummary> tasks) {
+        @JsonProperty("tasks") List<TaskSummary> tasks,
+        @JsonProperty("origin_pipeline_id") Long originPipelineId,
+        @JsonProperty("origin") RestartOriginView origin,
+        @JsonProperty("restarted_by_pipeline_id") Long restartedByPipelineId) {
 }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/PipelineSummary.java b/src/main/java/com/bff/pipeline/dto/pipeline/PipelineSummary.java
index 7b90d34..9195ef0 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/PipelineSummary.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/PipelineSummary.java
@@ -10,6 +10,8 @@ import java.time.Instant;
 /**
  * 목록·이력·최근카드가 쓰는 pipeline 요약 행이다(P3/P7/P8). targetName은 이 저장소에 없어(다른 repo 담당)
  * 싣지 않고 targetSourceId와 cloudProvider만 준다. 진행 N/M은 task 집계값(doneTaskCount/totalTaskCount)이다.
+ * originPipelineId는 재시작 계보다 — 자기 행의 컬럼이라 행별 조인 없이 목록에서 "재시작" 배지와 원본
+ * 링크를 그릴 수 있고, 재시작이 아니면 null이다.
  * 와이어 필드는 BFF swagger 계약에 맞춰 snake_case로 직렬화한다({@code @JsonProperty}).
  */
 public record PipelineSummary(
@@ -22,11 +24,13 @@ public record PipelineSummary(
         @JsonProperty("done_task_count") long doneTaskCount,
         @JsonProperty("total_task_count") long totalTaskCount,
         @JsonProperty("created_at") Instant createdAt,
-        @JsonProperty("last_activity_at") Instant lastActivityAt) {
+        @JsonProperty("last_activity_at") Instant lastActivityAt,
+        @JsonProperty("origin_pipeline_id") Long originPipelineId) {
 
     public static PipelineSummary from(Pipeline pipeline, long doneTaskCount, long totalTaskCount) {
         return new PipelineSummary(pipeline.getId(), pipeline.getType(), pipeline.getTarget(),
                 pipeline.getCloudProvider(), pipeline.getRecipeDefinition(), pipeline.getStatus(),
-                doneTaskCount, totalTaskCount, pipeline.getCreatedAt(), pipeline.getLastActivityAt());
+                doneTaskCount, totalTaskCount, pipeline.getCreatedAt(), pipeline.getLastActivityAt(),
+                pipeline.getOriginPipelineId());
     }
 }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/RestartOriginView.java b/src/main/java/com/bff/pipeline/dto/pipeline/RestartOriginView.java
new file mode 100644
index 0000000..49794cc
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/RestartOriginView.java
@@ -0,0 +1,24 @@
+package com.bff.pipeline.dto.pipeline;
+
+import com.bff.pipeline.enums.PipelineStatus;
+import com.bff.pipeline.enums.PipelineType;
+import com.fasterxml.jackson.annotation.JsonProperty;
+import lombok.Builder;
+
+/**
+ * 재시작 파이프라인 상세({@link PipelineDetail})에 얹는 원본 실행 요약 블록이다(재시작 설계 §3.3).
+ * 재시작 실행의 done/total은 자기 suffix 기준을 유지하고, "원본 8단계 중 6단계부터"는 이 블록으로 그린다.
+ * {@code resumedFromSequence}는 저장값이 아니라 파생값이다 — 재시작 체인의 첫 task가 가리키는
+ * (origin_task_id) 원본 task의 sequence이며, 원본 행이 사라졌으면 null이다(FK 없음 — null-safe 표시가 계약).
+ * 인접 동형 인자가 있어 위치 기반 생성 대신 {@code @Builder}로 만든다.
+ */
+@Builder
+public record RestartOriginView(
+        @JsonProperty("pipeline_id") long pipelineId,
+        @JsonProperty("type") PipelineType type,
+        @JsonProperty("recipe_definition") String recipeDefinition,
+        @JsonProperty("status") PipelineStatus status,
+        @JsonProperty("total_task_count") long totalTaskCount,
+        @JsonProperty("done_task_count") long doneTaskCount,
+        @JsonProperty("resumed_from_sequence") Integer resumedFromSequence) {
+}
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/RestartPipelineRequest.java b/src/main/java/com/bff/pipeline/dto/pipeline/RestartPipelineRequest.java
new file mode 100644
index 0000000..68bd200
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/RestartPipelineRequest.java
@@ -0,0 +1,11 @@
+package com.bff.pipeline.dto.pipeline;
+
+import com.fasterxml.jackson.annotation.JsonProperty;
+
+/**
+ * 재시작 실행 요청 본문이다(본문 자체가 생략 가능). {@code from_sequence}는 재시작 지점 오버라이드로,
+ * null이면 서버가 계산한 기본 지점(원본 체인의 첫 non-DONE task)부터다. 0 이상 기본 지점 이하만
+ * 허용된다 — 더 뒤(실패 task 건너뛰기)는 400이다(재시작 설계 결정 3).
+ */
+public record RestartPipelineRequest(@JsonProperty("from_sequence") Integer fromSequence) {
+}
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/RestartPreview.java b/src/main/java/com/bff/pipeline/dto/pipeline/RestartPreview.java
new file mode 100644
index 0000000..a41c907
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/RestartPreview.java
@@ -0,0 +1,55 @@
+package com.bff.pipeline.dto.pipeline;
+
+import com.bff.pipeline.enums.ErrorCode;
+import com.bff.pipeline.enums.PipelineStatus;
+import com.bff.pipeline.enums.PipelineType;
+import com.bff.pipeline.enums.TaskStatus;
+import com.fasterxml.jackson.annotation.JsonProperty;
+import java.util.List;
+import lombok.Builder;
+
+/**
+ * 재시작 미리보기 응답이다(재시작 설계 §3.1). 실행 버튼을 누르기 전에 무엇이 어떻게 돌아갈지 보여준다 —
+ * 어떤 task가 왜(origin_status·origin_error_code) 다시 돌아가고 무엇이 생략되는지. 실행과 동일한 검증을
+ * 거친 뒤에만 만들어지므로, 미리보기가 성공하면 실행도 (경합이 없는 한) 성공한다. {@code warnings}는
+ * 차단이 아닌 안내다(취소/실패 직후의 in-flight Terraform job 안내). 와이어 필드는 snake_case로 직렬화한다.
+ */
+@Builder
+public record RestartPreview(
+        @JsonProperty("origin") OriginSummary origin,
+        @JsonProperty("resume_from_sequence") int resumeFromSequence,
+        @JsonProperty("skipped_tasks") List<SkippedTask> skippedTasks,
+        @JsonProperty("tasks_to_run") List<TaskToRun> tasksToRun,
+        @JsonProperty("warnings") List<String> warnings) {
+
+    /** 원본 실행 요약 — "원본 N단계 중 어디부터"를 화면이 그릴 재료다. 인접 동형 인자가 있어 {@code @Builder}로 만든다. */
+    @Builder
+    public record OriginSummary(
+            @JsonProperty("pipeline_id") long pipelineId,
+            @JsonProperty("type") PipelineType type,
+            @JsonProperty("recipe_definition") String recipeDefinition,
+            @JsonProperty("status") PipelineStatus status,
+            @JsonProperty("total_task_count") long totalTaskCount,
+            @JsonProperty("done_task_count") long doneTaskCount) {
+    }
+
+    /** 재시작에서 건너뛰는(완료된) 원본 task. */
+    public record SkippedTask(
+            @JsonProperty("sequence") int sequence,
+            @JsonProperty("task_definition") String taskDefinition,
+            @JsonProperty("status") TaskStatus status) {
+    }
+
+    /** 다시 실행될 task — sequence는 원본 체인 기준이고, origin_*이 "왜 여기부터인지"를 설명한다. 인접 동형 인자가 많아 {@code @Builder}로 만든다. */
+    @Builder
+    public record TaskToRun(
+            @JsonProperty("sequence") int sequence,
+            @JsonProperty("task_definition") String taskDefinition,
+            @JsonProperty("kind") String kind,
+            @JsonProperty("terraform_action") String terraformAction,
+            @JsonProperty("origin_task_id") long originTaskId,
+            @JsonProperty("origin_status") TaskStatus originStatus,
+            @JsonProperty("origin_error_code") ErrorCode originErrorCode,
+            @JsonProperty("origin_fail_count") int originFailCount) {
+    }
+}
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java b/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
index 7ee40dc..39d3f15 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
@@ -42,5 +42,6 @@ public record TaskDetail(
         @JsonProperty("effective_execution_timeout") Duration effectiveExecutionTimeout,
         @JsonProperty("effective_max_fail_count") int effectiveMaxFailCount,
         @JsonProperty("attempts") List<TaskAttemptView> attempts,
-        @JsonProperty("description") String description) {
+        @JsonProperty("description") String description,
+        @JsonProperty("origin_task_id") Long originTaskId) {
 }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java b/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
index 6ac1fc0..0a21825 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
@@ -13,6 +13,7 @@ import lombok.Builder;
  * "TERRAFORM_JOB"/"CONDITION_CHECK" 같은 실행 메커니즘 식별자다. errorCode는 FAILED일 때만 채워진다.
  * description은 custom recipe 실행에서 운영자가 붙인 설명이고 카탈로그 task면 null이다(LIN-18).
  * terraformAction은 operation에서 파생한 표시용 액션 라벨(PLAN/APPLY/DESTROY)이고 terraform이 아닌 task면 null이다.
+ * originTaskId는 재시작 계보다 — 이 task가 다시 실행하는 원본 task 행 id이며, 재시작이 아니면 null이다.
  * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
  */
 @Builder
@@ -29,7 +30,8 @@ public record TaskSummary(
         @JsonProperty("consumes_terraform_slot") Boolean consumesTerraformSlot,
         @JsonProperty("started_at") Instant startedAt,
         @JsonProperty("finished_at") Instant finishedAt,
-        @JsonProperty("description") String description) {
+        @JsonProperty("description") String description,
+        @JsonProperty("origin_task_id") Long originTaskId) {
 
     public static TaskSummary from(Task task) {
         TaskOperation operation = task.getOperation();
@@ -47,6 +49,7 @@ public record TaskSummary(
                 .startedAt(task.getStartedAt())
                 .finishedAt(task.getFinishedAt())
                 .description(task.getDescription())
+                .originTaskId(task.getOriginTaskId())
                 .build();
     }
 }
diff --git a/src/main/java/com/bff/pipeline/entity/Pipeline.java b/src/main/java/com/bff/pipeline/entity/Pipeline.java
index 3461794..a75e359 100644
--- a/src/main/java/com/bff/pipeline/entity/Pipeline.java
+++ b/src/main/java/com/bff/pipeline/entity/Pipeline.java
@@ -42,7 +42,9 @@ import lombok.Setter;
                 @Index(name = "idx_pipeline_target_created", columnList = "target, created_at"),
                 // ponytail: ~2,000행 규모엔 (notified_at, notify_next_at) 복합이면 충분. MySQL8은 부분(filtered)
                 // 인덱스가 없으므로 status 필터는 옵티마이저에 맡긴다. 대규모로 커지면 재검토.
-                @Index(name = "idx_pipeline_notify", columnList = "notified_at, notify_next_at")
+                @Index(name = "idx_pipeline_notify", columnList = "notified_at, notify_next_at"),
+                // 재시작 역링크 조회(origin_pipeline_id = :id인 최신 행) 지원.
+                @Index(name = "idx_pipeline_origin", columnList = "origin_pipeline_id")
         })
 @Getter
 @Setter
@@ -96,6 +98,14 @@ public class Pipeline {
     @Column(name = "active_target")
     private String activeTarget;
 
+    /**
+     * 이 실행이 재시작한 원본 파이프라인 id. 재시작이 아니면 null. 표시용 계보 메타데이터라 엔진(claim·전이·
+     * reconciler)은 읽지 않고, FK 제약도 걸지 않는다 — 원본 행이 사라져도 조회가 null-safe로 계보 표시만
+     * 생략한다(카탈로그 이름 열화와 같은 태도).
+     */
+    @Column(name = "origin_pipeline_id", updatable = false)
+    private Long originPipelineId;
+
     // ── ADR-021 실행 좌표(execution-coordination) 컬럼: claim/lease/cooperative-cancel. 도메인 상태와는 분리된다. ──
 
     /**
diff --git a/src/main/java/com/bff/pipeline/entity/Task.java b/src/main/java/com/bff/pipeline/entity/Task.java
index e452215..5cb1fd1 100644
--- a/src/main/java/com/bff/pipeline/entity/Task.java
+++ b/src/main/java/com/bff/pipeline/entity/Task.java
@@ -102,6 +102,13 @@ public class Task {
     @Column(length = 100)
     private String description;
 
+    /**
+     * 이 task가 다시 실행하는 원본 task 행 id. 재시작이 아니면 null. 표시용 계보 — 엔진은 읽지 않으며,
+     * 재시작 실행 화면에서 원본 task의 attempt·terraform 로그(실패 진단)로 바로 이동하는 링크다.
+     */
+    @Column(name = "origin_task_id", updatable = false)
+    private Long originTaskId;
+
     /**
      * task 상태 머신의 현재 상태(varchar 저장, {@link TaskStatusConverter}). {@code @Enumerated}가 아닌 변환기를
      * 쓰는 이유는 write 안전뿐이다 — status는 엔진 분기에 직접 쓰이므로 read는 fail-fast를 유지한다(미해석
diff --git a/src/main/java/com/bff/pipeline/exception/InvalidResumeSequenceException.java b/src/main/java/com/bff/pipeline/exception/InvalidResumeSequenceException.java
new file mode 100644
index 0000000..22eee12
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/exception/InvalidResumeSequenceException.java
@@ -0,0 +1,17 @@
+package com.bff.pipeline.exception;
+
+import org.springframework.http.HttpStatus;
+
+/**
+ * 재시작 오버라이드 {@code from_sequence}가 범위 밖이다 — 허용 범위는 0(전체 재실행)부터 기본 재시작
+ * 지점(첫 non-DONE task)까지다. 더 앞은 DONE task의 멱등 재실행이라 안전하지만, 더 뒤는 실패 task를
+ * 건너뛰어 설치 완전성을 깨므로 거절한다(재시작 설계 결정 3). 400 Bad Request + code
+ * {@code ORCHESTRATION_INVALID_RESUME_SEQUENCE}로 매핑된다.
+ */
+public class InvalidResumeSequenceException extends OrchestrationException {
+
+    public InvalidResumeSequenceException(int fromSequence, int defaultResumeSequence) {
+        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.INVALID_RESUME_SEQUENCE,
+                "from_sequence " + fromSequence + " must be between 0 and " + defaultResumeSequence);
+    }
+}
diff --git a/src/main/java/com/bff/pipeline/exception/OrchestrationErrorCode.java b/src/main/java/com/bff/pipeline/exception/OrchestrationErrorCode.java
index b599d3d..efebb52 100644
--- a/src/main/java/com/bff/pipeline/exception/OrchestrationErrorCode.java
+++ b/src/main/java/com/bff/pipeline/exception/OrchestrationErrorCode.java
@@ -15,6 +15,9 @@ public enum OrchestrationErrorCode {
     TERRAFORM_RESULT_NOT_FOUND,
     TERRAFORM_JOB_STATE_NOT_FOUND,
     PIPELINE_ALREADY_ACTIVE,
+    PIPELINE_NOT_RESTARTABLE,
+    PIPELINE_NOT_LATEST,
+    INVALID_RESUME_SEQUENCE,
     PIPELINE_PERSISTENCE_ERROR,
     UNSUPPORTED_RECIPE,
     CUSTOM_TASKS_REQUIRED,
diff --git a/src/main/java/com/bff/pipeline/exception/PipelineNotLatestException.java b/src/main/java/com/bff/pipeline/exception/PipelineNotLatestException.java
new file mode 100644
index 0000000..08c0011
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/exception/PipelineNotLatestException.java
@@ -0,0 +1,16 @@
+package com.bff.pipeline.exception;
+
+import org.springframework.http.HttpStatus;
+
+/**
+ * 재시작 대상이 target의 최신 실행이 아니다 — stale한 과거 이력의 재시작은 현재 인프라 상태와 무관한 실행을
+ * 만들므로 거절한다(재시작 설계 결정 5). 검사는 best-effort이고 최종 동시성 심판은 active_target 유니크
+ * 제약이다. 409 Conflict + code {@code ORCHESTRATION_PIPELINE_NOT_LATEST}로 매핑된다.
+ */
+public class PipelineNotLatestException extends OrchestrationException {
+
+    public PipelineNotLatestException(long pipelineId, long latestPipelineId) {
+        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_LATEST,
+                "pipeline " + pipelineId + " is not the latest run (latest: " + latestPipelineId + ")");
+    }
+}
diff --git a/src/main/java/com/bff/pipeline/exception/PipelineNotRestartableException.java b/src/main/java/com/bff/pipeline/exception/PipelineNotRestartableException.java
new file mode 100644
index 0000000..26022b5
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/exception/PipelineNotRestartableException.java
@@ -0,0 +1,18 @@
+package com.bff.pipeline.exception;
+
+import com.bff.pipeline.enums.PipelineStatus;
+import org.springframework.http.HttpStatus;
+
+/**
+ * 재시작 허용표(재시작 설계 결정 5) 위반이다 — 재시작은 최신 FAILED/CANCELLED 실행에서만 가능하다.
+ * DONE은 재시작할 실패 지점이 없고(active_target 백스톱도 없어 이 검사가 유일 방어선), RUNNING/PENDING은
+ * 재시작이 아니라 취소 대상이다. 409 Conflict + code {@code ORCHESTRATION_PIPELINE_NOT_RESTARTABLE}로 매핑된다.
+ */
+public class PipelineNotRestartableException extends OrchestrationException {
+
+    public PipelineNotRestartableException(long pipelineId, PipelineStatus status) {
+        super(HttpStatus.CONFLICT, OrchestrationErrorCode.PIPELINE_NOT_RESTARTABLE,
+                "pipeline " + pipelineId + " is " + status
+                        + " — only the latest FAILED/CANCELLED run can be restarted");
+    }
+}
diff --git a/src/main/java/com/bff/pipeline/model/PipelinePlan.java b/src/main/java/com/bff/pipeline/model/PipelinePlan.java
index aa5ba92..ca68f47 100644
--- a/src/main/java/com/bff/pipeline/model/PipelinePlan.java
+++ b/src/main/java/com/bff/pipeline/model/PipelinePlan.java
@@ -1,5 +1,6 @@
 package com.bff.pipeline.model;
 
+import com.bff.pipeline.entity.Pipeline;
 import com.bff.pipeline.enums.CloudProvider;
 import com.bff.pipeline.enums.PipelineType;
 import com.bff.pipeline.enums.RecipeDefinition;
@@ -10,17 +11,21 @@ import java.util.Objects;
 /**
  * create 요청이 해석된 결과 — 무엇에(target) 어떤 순서의 어떤 task 체인을 실행할지다. PipelineCreator가 target
  * provider를 조회해 카탈로그 recipe를 고르거나(catalog 경로) 요청의 custom task 리스트를 검증해(custom 경로) 만든 뒤
- * 이 값으로 PipelineInserter에 넘긴다. 두 경로 모두 같은 꼴이라 inserter는 분기 없이 한 가지로 삽입한다.
+ * 이 값으로 PipelineInserter에 넘긴다. 재시작(restart 경로)은 실패한 실행의 suffix로 PipelineRestarter가 만든다.
+ * 세 경로 모두 같은 꼴이라 inserter는 분기 없이 한 가지로 삽입한다.
  *
  * {@code type}이 이 실행의 분류다 — INSTALL/DELETE는 카탈로그 recipe, {@link PipelineType#CUSTOM}은 요청이 직접
  * 구성한 custom 실행이다(LIN-18). {@code recipeDefinition}은 catalog 경로에서만 RecipeDefinition 상수 이름
  * (예: {@code AWS_INSTALL_V1})을 담고, custom 경로는 백킹 recipe가 없으므로 null이다 — 분류 신호는 type이 진다.
  *
+ * {@code originPipelineId}는 재시작 계보다 — restart 경로만 원본 파이프라인 id를 싣고, 나머지 경로는 null이다.
+ * 표시용 write-once 메타데이터로 inserter가 행에 스탬핑만 하고 엔진은 읽지 않는다.
+ *
  * 각 {@link PlannedStep}은 실행할 TaskDefinition과 선택적 운영자 설명(custom 경로에서만 채워지고, catalog 경로는
- * null)이다.
+ * null), 그리고 재시작 계보(originTaskId — restart 경로에서만)다.
  */
 public record PipelinePlan(String target, PipelineType type, CloudProvider provider, String recipeDefinition,
-        List<PlannedStep> steps) {
+        Long originPipelineId, List<PlannedStep> steps) {
 
     public PipelinePlan {
         if (target == null || target.isBlank()) {
@@ -40,18 +45,33 @@ public record PipelinePlan(String target, PipelineType type, CloudProvider provi
         List<PlannedStep> steps = recipe.steps().stream()
                 .map(definition -> new PlannedStep(definition, null))
                 .toList();
-        return new PipelinePlan(target, recipe.pipelineType(), recipe.provider(), recipe.name(), steps);
+        return new PipelinePlan(target, recipe.pipelineType(), recipe.provider(), recipe.name(), null, steps);
     }
 
     /** 검증을 통과한 custom step 리스트로 plan을 만든다 — type은 {@link PipelineType#CUSTOM}, recipeDefinition은 없다(null). */
     public static PipelinePlan custom(String target, CloudProvider provider, List<PlannedStep> steps) {
-        return new PipelinePlan(target, PipelineType.CUSTOM, provider, null, steps);
+        return new PipelinePlan(target, PipelineType.CUSTOM, provider, null, null, steps);
+    }
+
+    /**
+     * 재시작 plan — type/recipeDefinition을 원본에서 승계하고 계보(originPipelineId)를 실어 만든다(재시작 설계
+     * 결정 1·2). provider는 호출자(PipelineRestarter)가 원본 저장값 또는 폴백 조회로 확정해 넘긴다.
+     */
+    public static PipelinePlan restartOf(Pipeline origin, CloudProvider provider, List<PlannedStep> steps) {
+        Objects.requireNonNull(origin, "origin must not be null");
+        return new PipelinePlan(origin.getTarget(), origin.getType(), provider,
+                origin.getRecipeDefinition(), origin.getId(), steps);
     }
 
-    /** 체인의 한 단계 — 실행할 TaskDefinition과, custom 경로에서 운영자가 붙인 선택적 설명. */
-    public record PlannedStep(TaskDefinition definition, String description) {
+    /** 체인의 한 단계 — 실행할 TaskDefinition, 운영자가 붙인 선택적 설명, 재시작 계보(원본 task 행 id). */
+    public record PlannedStep(TaskDefinition definition, String description, Long originTaskId) {
         public PlannedStep {
             Objects.requireNonNull(definition, "definition must not be null");
         }
+
+        /** 기존 catalog/custom 경로용 — 계보 없음. */
+        public PlannedStep(TaskDefinition definition, String description) {
+            this(definition, description, null);
+        }
     }
 }
diff --git a/src/main/java/com/bff/pipeline/repository/PipelineRepository.java b/src/main/java/com/bff/pipeline/repository/PipelineRepository.java
index cd15ab3..cc0affe 100644
--- a/src/main/java/com/bff/pipeline/repository/PipelineRepository.java
+++ b/src/main/java/com/bff/pipeline/repository/PipelineRepository.java
@@ -109,6 +109,9 @@ public interface PipelineRepository extends JpaRepository<Pipeline, Long> {
     /** 최근 파이프라인 카드(P8): 특정 target의 가장 최근 실행 1건(상태 무관). id를 tiebreaker로 결정적 선택. */
     Optional<Pipeline> findFirstByTargetOrderByCreatedAtDescIdDesc(String target);
 
+    /** 재시작 역링크: 이 파이프라인을 재시작한 최신 실행. idx_pipeline_origin이 지원한다. */
+    Optional<Pipeline> findFirstByOriginPipelineIdOrderByIdDesc(Long originPipelineId);
+
     // ── 종단 알림(ADR-022) 질의. TerminalNotifier가 쓴다. ──
 
     /**
diff --git a/src/main/java/com/bff/pipeline/service/lifecycle/PipelineCreator.java b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineCreator.java
index bd0c9e0..639a51d 100644
--- a/src/main/java/com/bff/pipeline/service/lifecycle/PipelineCreator.java
+++ b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineCreator.java
@@ -77,8 +77,8 @@ public class PipelineCreator {
         return insert(PipelinePlan.custom(target, provider, steps), target);
     }
 
-    /** plan 삽입 + active-target 유니크 위반의 도메인 번역. catalog/custom 경로가 공유한다. */
-    private Pipeline insert(PipelinePlan plan, String target) {
+    /** plan 삽입 + active-target 유니크 위반의 도메인 번역. catalog/custom/restart(PipelineRestarter) 경로가 공유한다. */
+    Pipeline insert(PipelinePlan plan, String target) {
         try {
             return pipelineInserter.insert(plan);
         } catch (DataIntegrityViolationException violation) {
@@ -134,9 +134,9 @@ public class PipelineCreator {
     /**
      * cloud provider를 조회한다(외부 호출). 인프라 실패(타임아웃/호출 오류)는 비즈니스 실패가 아니므로 503으로
      * 번역한다 — raw CallTimeout/CallFailed가 catch-all로 새어 500이 되지 않게 한다(§3). CallInterrupted는 잡지 않고
-     * 그대로 전파한다(fail-fast).
+     * 그대로 전파한다(fail-fast). create/preview와 restart(provider 열화 폴백, PipelineRestarter)가 공유한다.
      */
-    private CloudProvider resolveProvider(String target) {
+    CloudProvider resolveProvider(String target) {
         CloudProvider provider;
         try {
             provider = infraManagerClient.cloudProvider(target);
diff --git a/src/main/java/com/bff/pipeline/service/lifecycle/PipelineInserter.java b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineInserter.java
index 306af05..fb78117 100644
--- a/src/main/java/com/bff/pipeline/service/lifecycle/PipelineInserter.java
+++ b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineInserter.java
@@ -51,6 +51,7 @@ public class PipelineInserter {
                 .target(target)
                 .cloudProvider(plan.provider())
                 .recipeDefinition(plan.recipeDefinition())
+                .originPipelineId(plan.originPipelineId())
                 .status(delayed ? PipelineStatus.PENDING : PipelineStatus.RUNNING)
                 .activeTarget(target)
                 .createdAt(now)
@@ -75,6 +76,7 @@ public class PipelineInserter {
                             .taskDefinition(step.definition().name())
                             .consumesTerraformSlot(step.definition().consumesTerraformSlot())
                             .description(step.description())
+                            .originTaskId(step.originTaskId())
                             .status(first ? TaskStatus.READY : TaskStatus.BLOCKED)
                             .readyAt(first ? now : null)
                             .failCount(0)
diff --git a/src/main/java/com/bff/pipeline/service/lifecycle/PipelineRestarter.java b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineRestarter.java
new file mode 100644
index 0000000..dd46670
--- /dev/null
+++ b/src/main/java/com/bff/pipeline/service/lifecycle/PipelineRestarter.java
@@ -0,0 +1,187 @@
+package com.bff.pipeline.service.lifecycle;
+
+import com.bff.pipeline.config.PipelineSettings;
+import com.bff.pipeline.dto.pipeline.RestartPreview;
+import com.bff.pipeline.dto.pipeline.RestartPreview.OriginSummary;
+import com.bff.pipeline.dto.pipeline.RestartPreview.SkippedTask;
+import com.bff.pipeline.dto.pipeline.RestartPreview.TaskToRun;
+import com.bff.pipeline.entity.Pipeline;
+import com.bff.pipeline.entity.Task;
+import com.bff.pipeline.enums.CloudProvider;
+import com.bff.pipeline.enums.PipelineStatus;
+import com.bff.pipeline.enums.TaskDefinition;
+import com.bff.pipeline.enums.TaskStatus;
+import com.bff.pipeline.exception.InvalidResumeSequenceException;
+import com.bff.pipeline.exception.PipelineNotFoundException;
+import com.bff.pipeline.exception.PipelineNotLatestException;
+import com.bff.pipeline.exception.PipelineNotRestartableException;
+import com.bff.pipeline.exception.UnknownTaskException;
+import com.bff.pipeline.model.PipelinePlan;
+import com.bff.pipeline.model.PipelinePlan.PlannedStep;
+import com.bff.pipeline.repository.PipelineRepository;
+import com.bff.pipeline.repository.TaskRepository;
+import java.time.Clock;
+import java.time.Instant;
+import java.util.List;
+import lombok.RequiredArgsConstructor;
+import org.springframework.stereotype.Service;
+
+/**
+ * 실패한 파이프라인의 재시작을 구현한다(재시작 설계 결정 1–5). 재시작은 원본을 되살리는 것이 아니라
+ * (ADR-016 §7 — terminal은 부활하지 않는다) 원본 체인에서 첫 non-DONE task부터의 suffix로 만드는
+ * 새 파이프라인이다. type/recipe_definition은 원본에서 승계하고(CUSTOM으로 위장하지 않는다),
+ * 계보는 write-once 컬럼(origin_pipeline_id/origin_task_id)으로만 남긴다 — 엔진(ADR-021)은 무변경.
+ *
+ * 재시작 가능 대상은 "target의 최신 실행 + FAILED/CANCELLED"뿐이다. DONE은 재시작할 실패 지점이
+ * 없고 active_target 백스톱도 없어 여기의 명시 검사가 유일 방어선이며, RUNNING/PENDING은 취소가 먼저다.
+ * 검사는 best-effort 읽기이고 동시 재시작·create 경합의 최종 심판은 insert의 active_target 유니크 제약이다.
+ *
+ * {@code @Transactional}을 일부러 붙이지 않는다(PipelineCreator와 동일) — 읽기·검증은 트랜잭션 밖에서
+ * 끝내고 삽입만 inserter의 트랜잭션이며, 유니크 위반의 도메인 번역은 {@link PipelineCreator#insert}를 공유한다.
+ * preview와 restart는 검증·suffix 계산({@link #compute})을 공유하므로, 미리보기가 성공하면 실행도
+ * (경합이 없는 한) 성공한다.
+ */
+@Service
+@RequiredArgsConstructor
+public class PipelineRestarter {
+
+    /** 재시작을 허용하는 원본 상태 — 미완으로 끝난 실행만(재시작 설계 결정 5). */
+    private static final List<PipelineStatus> RESTARTABLE_STATUSES =
+            List.of(PipelineStatus.FAILED, PipelineStatus.CANCELLED);
+
+    private static final String IN_FLIGHT_JOB_WARNING =
+            "원본 실행이 최근에 종료되었습니다. 이전에 dispatch된 Terraform job이 아직 실행 중일 수 있습니다(멱등이므로 무해).";
+
+    private final PipelineRepository pipelines;
+    private final TaskRepository tasks;
+    private final PipelineCreator pipelineCreator;
+    private final PipelineSettings pipelineSettings;
+    private final Clock clock;
+
+    /** 재시작 실행 — 검증·suffix 계산 후 새 파이프라인을 삽입한다. fromSequence는 선택적 오버라이드(더 앞으로만). */
+    public Pipeline restart(String target, Long pipelineId, Integer fromSequence) {
+        RestartComputation computation = compute(target, pipelineId, fromSequence);
+        return pipelineCreator.insert(
+                PipelinePlan.restartOf(computation.origin(), computation.provider(), computation.steps()), target);
+    }
+
+    /** 재시작 미리보기 — 실행과 동일한 검증을 수행하고(불가 상태는 여기서부터 409/400) 아무것도 저장하지 않는다. */
+    public RestartPreview preview(String target, Long pipelineId, Integer fromSequence) {
+        return toPreview(compute(target, pipelineId, fromSequence));
+    }
+
+    /** preview/restart가 공유하는 검증·suffix 계산. 분기 금지 — 두 경로의 결과가 항상 같은 근거다. */
+    private RestartComputation compute(String target, Long pipelineId, Integer fromSequence) {
+        Pipeline origin = loadRestartableOrigin(target, pipelineId);
+        List<Task> originChain = tasks.findByPipelineIdOrderBySequenceAsc(origin.getId());
+        int resumeFromSequence = resolveResumeSequence(origin, originChain, fromSequence);
+        // suffix는 여기서 한 번만 계산하고 preview 렌더와 restart step이 같은 리스트를 쓴다(필터 중복 금지).
+        List<Task> suffix = originChain.stream()
+                .filter(task -> task.getSequence() >= resumeFromSequence)
+                .toList();
+        List<PlannedStep> steps = suffix.stream().map(PipelineRestarter::toStep).toList();
+        // provider는 원본 저장값 재사용 — task들이 그 provider로 이미 검증된 상태다. 열화(null)면 create와 동일 폴백.
+        CloudProvider provider = origin.getCloudProvider() != null
+                ? origin.getCloudProvider()
+                : pipelineCreator.resolveProvider(target);
+        return new RestartComputation(origin, originChain, resumeFromSequence, provider, suffix, steps);
+    }
+
+    /** 원본 로드(404) + 결정 5 허용표(409) + 최신 실행 검증(409). */
+    private Pipeline loadRestartableOrigin(String target, Long pipelineId) {
+        Pipeline origin = pipelines.findById(pipelineId)
+                .filter(pipeline -> pipeline.getTarget().equals(target))
+                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
+        // type이 열화(미해석 옛 값 → null)된 행은 승계할 분류가 없다 — plan 구성에서 500으로 새기 전에 여기서 거절.
+        if (!RESTARTABLE_STATUSES.contains(origin.getStatus()) || origin.getType() == null) {
+            throw new PipelineNotRestartableException(pipelineId, origin.getStatus());
+        }
+        Pipeline latest = pipelines.findFirstByTargetOrderByCreatedAtDescIdDesc(target)
+                .orElseThrow(() -> new PipelineNotFoundException(pipelineId));
+        if (!latest.getId().equals(origin.getId())) {
+            throw new PipelineNotLatestException(pipelineId, latest.getId());
+        }
+        return origin;
+    }
+
+    /**
+     * 기본 재시작 지점 = 원본 체인의 첫 non-DONE task. FAILED엔 FAILED task가, CANCELLED엔 취소된 task가
+     * 반드시 있어 빈 결과는 상태 기계상 도달 불가지만, 그래도 비면 재시작 불가로 거절한다(방어).
+     * 오버라이드는 "더 앞으로"만 — 뒤로 가면 실패 task를 건너뛰어 설치 완전성이 깨진다(결정 3).
+     */
+    private static int resolveResumeSequence(Pipeline origin, List<Task> originChain, Integer fromSequence) {
+        int defaultResume = originChain.stream()
+                .filter(task -> task.getStatus() != TaskStatus.DONE)
+                .findFirst().map(Task::getSequence)
+                .orElseThrow(() -> new PipelineNotRestartableException(origin.getId(), origin.getStatus()));
+        if (fromSequence == null) {
+            return defaultResume;
+        }
+        if (fromSequence < 0 || fromSequence > defaultResume) {
+            throw new InvalidResumeSequenceException(fromSequence, defaultResume);
+        }
+        return fromSequence;
+    }
+
+    /** 원본 task 행의 task_definition(진실원)을 재해석해 step으로 만든다 — 사라진 이름은 조용한 열화 대신 400. */
+    private static PlannedStep toStep(Task task) {
+        TaskDefinition definition = TaskDefinition.find(task.getTaskDefinition())
+                .orElseThrow(() -> new UnknownTaskException(task.getTaskDefinition()));
+        return new PlannedStep(definition, task.getDescription(), task.getId());
+    }
+
+    private RestartPreview toPreview(RestartComputation computation) {
+        Pipeline origin = computation.origin();
+        List<Task> originChain = computation.originChain();
+        return RestartPreview.builder()
+                .origin(OriginSummary.builder()
+                        .pipelineId(origin.getId()).type(origin.getType())
+                        .recipeDefinition(origin.getRecipeDefinition()).status(origin.getStatus())
+                        .totalTaskCount(originChain.size()).doneTaskCount(countDone(originChain))
+                        .build())
+                .resumeFromSequence(computation.resumeFromSequence())
+                .skippedTasks(computation.skipped().stream()
+                        .map(task -> new SkippedTask(task.getSequence(), task.getTaskDefinition(), task.getStatus()))
+                        .toList())
+                .tasksToRun(computation.suffix().stream().map(PipelineRestarter::toTaskToRun).toList())
+                .warnings(warnings(origin))
+                .build();
+    }
+
+    private static TaskToRun toTaskToRun(Task task) {
+        return TaskToRun.builder()
+                .sequence(task.getSequence())
+                .taskDefinition(task.getTaskDefinition())
+                .kind(task.getTaskName())
+                .terraformAction(task.getOperation() == null
+                        ? null : task.getOperation().terraformAction().orElse(null))
+                .originTaskId(task.getId())
+                .originStatus(task.getStatus())
+                .originErrorCode(task.getErrorCode())
+                .originFailCount(task.getFailCount())
+                .build();
+    }
+
+    /**
+     * 차단이 아닌 안내(설계 §3.1). 원본이 executionTimeout 창 안에서 끝났으면 이전에 dispatch된 Terraform job이
+     * 아직 InfraManager에서 돌고 있을 수 있다 — 멱등이라 무해하지만 운영자에게 알린다. 끝난 행은 갱신되지
+     * 않으므로 lastActivityAt이 곧 끝난 시각이다.
+     */
+    private List<String> warnings(Pipeline origin) {
+        Instant inFlightHorizon = clock.instant().minus(pipelineSettings.executionTimeout());
+        return origin.getLastActivityAt().isAfter(inFlightHorizon) ? List.of(IN_FLIGHT_JOB_WARNING) : List.of();
+    }
+
+    private static long countDone(List<Task> chain) {
+        return chain.stream().filter(task -> task.getStatus() == TaskStatus.DONE).count();
+    }
+
+    /** 검증·suffix 계산 결과 — preview 렌더와 restart 삽입이 같은 값을 쓴다(suffix/steps는 compute가 한 번만 만든다). */
+    private record RestartComputation(Pipeline origin, List<Task> originChain, int resumeFromSequence,
+            CloudProvider provider, List<Task> suffix, List<PlannedStep> steps) {
+
+        List<Task> skipped() {
+            return originChain.stream().filter(task -> task.getSequence() < resumeFromSequence).toList();
+        }
+    }
+}
diff --git a/src/main/java/com/bff/pipeline/service/notify/SlackNotifier.java b/src/main/java/com/bff/pipeline/service/notify/SlackNotifier.java
index 34cb159..f232ec5 100644
--- a/src/main/java/com/bff/pipeline/service/notify/SlackNotifier.java
+++ b/src/main/java/com/bff/pipeline/service/notify/SlackNotifier.java
@@ -114,16 +114,20 @@ public class SlackNotifier {
     }
 
     /**
-     * 예: ":white_check_mark: *[prd] Pipeline DONE* — INSTALL (id 1234) · 상세 보기 링크".
+     * 예: ":white_check_mark: *[prd] Pipeline DONE* — INSTALL(#123의 재시작) (id 1234) · 상세 보기 링크".
      * 상세 링크는 Slack 링크 문법(꺾쇠 괄호로 주소와 라벨을 묶는 형식)으로 붙인다.
      * type을 알 수 없는 옛 데이터면(null) 그 구간을 빼고, environment/detailUrl이 없으면 그 구간도 뺀다.
+     * 재시작 실행이면 재시작 구간이 붙는다 — 수신자가 "CUSTOM 종료"가 아니라 "INSTALL의 재시작"으로
+     * 읽게 하는 것이 재시작 설계 §3.4의 요구다.
      */
     private static String headline(MessageStyle style, NotifyPayload payload) {
         String environmentSegment = payload.environment() == null ? "" : "[" + payload.environment() + "] ";
         String typeSegment = payload.type() == null ? "" : " — " + payload.type();
+        String restartSegment = payload.originPipelineId() == null
+                ? "" : "(#" + payload.originPipelineId() + "의 재시작)";
         String detailSegment = payload.detailUrl() == null ? "" : " · <" + payload.detailUrl() + "|상세 보기 →>";
         return style.emoji() + " *" + environmentSegment + "Pipeline " + payload.terminalStatus() + "*"
-                + typeSegment + " (id " + payload.pipelineId() + ")" + detailSegment;
+                + typeSegment + restartSegment + " (id " + payload.pipelineId() + ")" + detailSegment;
     }
 
     private static void addFieldUnlessNull(List<Map<String, Object>> fields, String title, String value,
diff --git a/src/main/java/com/bff/pipeline/service/notify/TerminalNotifier.java b/src/main/java/com/bff/pipeline/service/notify/TerminalNotifier.java
index 586f1a0..85d1164 100644
--- a/src/main/java/com/bff/pipeline/service/notify/TerminalNotifier.java
+++ b/src/main/java/com/bff/pipeline/service/notify/TerminalNotifier.java
@@ -215,6 +215,7 @@ public class TerminalNotifier {
                 .errorCode(failedTask.map(Task::getErrorCode).map(ErrorCode::name).orElse(null))
                 .detailUrl(toDetailUrl(pipeline.getId()))
                 .schemaVersion(NotifyPayload.SCHEMA_VERSION)
+                .originPipelineId(pipeline.getOriginPipelineId())
                 .build();
     }
 
diff --git a/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java b/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
index 0cd4828..894930e 100644
--- a/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
+++ b/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
@@ -6,6 +6,7 @@ import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
 import com.bff.pipeline.dto.pipeline.PipelineDetail;
 import com.bff.pipeline.dto.pipeline.PipelineStatistics;
 import com.bff.pipeline.dto.pipeline.PipelineSummary;
+import com.bff.pipeline.dto.pipeline.RestartOriginView;
 import com.bff.pipeline.dto.pipeline.TaskAttemptView;
 import com.bff.pipeline.dto.pipeline.TaskDefinitionView;
 import com.bff.pipeline.dto.pipeline.TaskDetail;
@@ -171,9 +172,46 @@ public class PipelineQueryService {
                 .doneTaskCount(countDone(chain))
                 .totalTaskCount(chain.size())
                 .tasks(chain.stream().map(TaskSummary::from).toList())
+                .originPipelineId(pipeline.getOriginPipelineId())
+                .origin(originView(pipeline, chain))
+                .restartedByPipelineId(pipelines.findFirstByOriginPipelineIdOrderByIdDesc(pipeline.getId())
+                        .map(Pipeline::getId).orElse(null))
                 .build();
     }
 
+    /**
+     * 재시작 파이프라인의 원본 요약 블록. 원본 행이 사라졌으면(FK 없음) 계보 표시만 생략하고 null을 준다.
+     * resumed_from_sequence는 저장값이 아닌 파생값이다 — 이 체인의 첫 task가 가리키는(originTaskId) 원본
+     * task의 sequence. 상세 단건 조회에만 붙는 추가 read 2회라 목록 경로 비용은 없다.
+     */
+    private RestartOriginView originView(Pipeline pipeline, List<Task> chain) {
+        if (pipeline.getOriginPipelineId() == null) {
+            return null;
+        }
+        return pipelines.findById(pipeline.getOriginPipelineId()).map(origin -> {
+            List<Task> originChain = tasks.findByPipelineIdOrderBySequenceAsc(origin.getId());
+            return RestartOriginView.builder()
+                    .pipelineId(origin.getId()).type(origin.getType())
+                    .recipeDefinition(origin.getRecipeDefinition()).status(origin.getStatus())
+                    .totalTaskCount(originChain.size()).doneTaskCount(countDone(originChain))
+                    .resumedFromSequence(resumedFromSequence(chain, originChain))
+                    .build();
+        }).orElse(null);
+    }
+
+    private static Integer resumedFromSequence(List<Task> chain, List<Task> originChain) {
+        if (chain.isEmpty()) {
+            return null;
+        }
+        Long firstOriginTaskId = chain.getFirst().getOriginTaskId();
+        if (firstOriginTaskId == null) {
+            return null;
+        }
+        return originChain.stream()
+                .filter(task -> firstOriginTaskId.equals(task.getId()))
+                .findFirst().map(Task::getSequence).orElse(null);
+    }
+
     private static boolean isLeased(Pipeline pipeline, Instant now) {
         return pipeline.getClaimedUntil() != null && pipeline.getClaimedUntil().isAfter(now);
     }
@@ -212,6 +250,7 @@ public class PipelineQueryService {
                 .effectiveMaxFailCount(TaskSettingsResolver.resolveMaxFailCount(task, pipelineSettings))
                 .attempts(attemptViews(taskId))
                 .description(task.getDescription())
+                .originTaskId(task.getOriginTaskId())
                 .build();
     }
 
diff --git a/src/test/java/com/bff/pipeline/dto/NotifyPayloadPiiTest.java b/src/test/java/com/bff/pipeline/dto/NotifyPayloadPiiTest.java
index 206d59e..f1f927b 100644
--- a/src/test/java/com/bff/pipeline/dto/NotifyPayloadPiiTest.java
+++ b/src/test/java/com/bff/pipeline/dto/NotifyPayloadPiiTest.java
@@ -20,7 +20,7 @@ import org.junit.jupiter.api.Test;
  * {@link NotifyPayload}가 직렬화될 때 민감 정보가 새지 않는다는 규칙을 회귀 테스트로 고정한다.
  *
  * 고정하는 규칙:
- * (a) 스키마에는 허용된 10개 필드만 있다. 링크는 detailUrl 하나만 허용된다(오너 결정 2026-07-10) —
+ * (a) 스키마에는 허용된 11개 필드만 있다. 링크는 detailUrl 하나만 허용된다(오너 결정 2026-07-10) —
  *     파이프라인 상세 화면 주소에 id만 붙인 값이고, 대상 정보가 담긴 다른 링크 필드는 여전히 없다.
  * (b) failed_task 값은 정해진 단계 이름 목록 안에서만 나온다. 1순위는 taskDefinition(TaskDefinition
  *     상수 이름)이고, 정의가 없는 옛 행은 taskName(TaskOperation의 mechanism 값 — 부팅 시
@@ -37,14 +37,14 @@ class NotifyPayloadPiiTest {
     private final ObjectMapper mapper = new ObjectMapper();
 
     @Test
-    void theSchemaCarriesExactlyTheTenAllowedFieldsAndOnlyTheDetailLink() {
+    void theSchemaCarriesExactlyTheElevenAllowedFieldsAndOnlyTheDetailLink() {
         JsonNode json = mapper.valueToTree(failedPayload());
 
         List<String> fields = new ArrayList<>();
         json.fieldNames().forEachRemaining(fields::add);
         assertThat(fields).containsExactlyInAnyOrder(
                 "pipelineId", "type", "terminalStatus", "targetRef", "cloudProvider", "environment",
-                "failedTask", "errorCode", "detailUrl", "schemaVersion");
+                "failedTask", "errorCode", "detailUrl", "schemaVersion", "originPipelineId");
         // 링크가 들어갈 수 있는 자리는 detailUrl 하나뿐이라는 사실을 회귀로 고정한다 —
         // url 같은 범용 링크 필드가 생기면 대상 상세가 채널로 새는 문이 열린다.
         assertThat(json.has("url")).isFalse();
@@ -113,6 +113,7 @@ class NotifyPayloadPiiTest {
                 .errorCode(ErrorCode.JOB_FAILED.name())
                 .detailUrl("http://localhost:3001/integration/admin/pipelines/1234")
                 .schemaVersion(NotifyPayload.SCHEMA_VERSION)
+                .originPipelineId(1200L)   // 재시작 계보 — id 값이라 raw 연결 식별자가 아니다
                 .build();
     }
 }
diff --git a/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java b/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
index 42810b5..2852d6b 100644
--- a/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
+++ b/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
@@ -30,14 +30,16 @@ class DtoSnakeCaseSerializationTest {
     void pipelineSummarySerializesSnakeCase() throws Exception {
         PipelineSummary summary = new PipelineSummary(101L, PipelineType.INSTALL, "ts-1", CloudProvider.AWS,
                 "AWS_INSTALL_V1", PipelineStatus.RUNNING, 1, 2,
-                Instant.parse("2026-07-02T00:00:00Z"), Instant.parse("2026-07-02T00:05:00Z"));
+                Instant.parse("2026-07-02T00:00:00Z"), Instant.parse("2026-07-02T00:05:00Z"), 90L);
 
         String json = mapper.writeValueAsString(summary);
 
         assertThat(json).contains("\"pipeline_id\":101", "\"target_source_id\":\"ts-1\"",
                 "\"cloud_provider\":\"AWS\"", "\"recipe_definition\":", "\"done_task_count\":1",
-                "\"total_task_count\":2", "\"created_at\":", "\"last_activity_at\":");
-        assertThat(json).doesNotContain("pipelineId", "targetSourceId", "cloudProvider", "doneTaskCount");
+                "\"total_task_count\":2", "\"created_at\":", "\"last_activity_at\":",
+                "\"origin_pipeline_id\":90");
+        assertThat(json).doesNotContain("pipelineId", "targetSourceId", "cloudProvider", "doneTaskCount",
+                "originPipelineId");
     }
 
     @Test
@@ -48,10 +50,21 @@ class DtoSnakeCaseSerializationTest {
                 .status(TaskStatus.IN_PROGRESS).failCount(0).errorCode(null).consumesTerraformSlot(true)
                 .startedAt(Instant.parse("2026-07-02T00:00:00Z")).finishedAt(null).description("manual apply")
                 .build();
-        PipelineDetail detail = new PipelineDetail(101L, PipelineType.INSTALL, "ts-1", CloudProvider.AWS,
-                "AWS_INSTALL_V1", PipelineStatus.RUNNING, Instant.parse("2026-07-02T00:00:00Z"),
-                Instant.parse("2026-07-02T00:05:00Z"), Instant.parse("2026-07-02T00:06:00Z"), true, false,
-                0L, 0, 1, 0, 3, 1, 2, List.of(task));
+        PipelineDetail detail = PipelineDetail.builder()
+                .pipelineId(101L).type(PipelineType.INSTALL).targetSourceId("ts-1")
+                .cloudProvider(CloudProvider.AWS).recipeDefinition("AWS_INSTALL_V1")
+                .status(PipelineStatus.RUNNING).createdAt(Instant.parse("2026-07-02T00:00:00Z"))
+                .lastActivityAt(Instant.parse("2026-07-02T00:05:00Z"))
+                .nextDueAt(Instant.parse("2026-07-02T00:06:00Z")).leased(true).cancelRequested(false)
+                .dueLagMillis(0L).currentTaskSequence(0).finalTaskSequence(1)
+                .currentFailCount(0).currentMaxFailCount(3).doneTaskCount(1).totalTaskCount(2)
+                .tasks(List.of(task)).originPipelineId(90L)
+                .origin(RestartOriginView.builder()
+                        .pipelineId(90L).type(PipelineType.INSTALL).recipeDefinition("AWS_INSTALL_V1")
+                        .status(PipelineStatus.FAILED).totalTaskCount(8).doneTaskCount(5)
+                        .resumedFromSequence(5).build())
+                .restartedByPipelineId(null)
+                .build();
 
         String json = mapper.writeValueAsString(detail);
 
@@ -59,7 +72,10 @@ class DtoSnakeCaseSerializationTest {
                 "\"current_task_sequence\":0", "\"final_task_sequence\":1", "\"current_max_fail_count\":3");
         assertThat(json).contains("\"task_id\":5", "\"consumes_terraform_slot\":true", "\"fail_count\":0",
                 "\"terraform_action\":\"APPLY\"", "\"description\":\"manual apply\"");
-        assertThat(json).doesNotContain("nextDueAt", "cancelRequested", "taskId", "consumesTerraformSlot");
+        assertThat(json).contains("\"origin_pipeline_id\":90", "\"origin\":", "\"resumed_from_sequence\":5",
+                "\"restarted_by_pipeline_id\":null");
+        assertThat(json).doesNotContain("nextDueAt", "cancelRequested", "taskId", "consumesTerraformSlot",
+                "originPipelineId", "resumedFromSequence", "restartedByPipelineId");
     }
 
     @Test
diff --git a/src/test/java/com/bff/pipeline/service/CustomRecipeCreationTest.java b/src/test/java/com/bff/pipeline/service/CustomRecipeCreationTest.java
index 5198a9d..22a3f53 100644
--- a/src/test/java/com/bff/pipeline/service/CustomRecipeCreationTest.java
+++ b/src/test/java/com/bff/pipeline/service/CustomRecipeCreationTest.java
@@ -27,6 +27,7 @@ import com.bff.pipeline.repository.PipelineRepository;
 import com.bff.pipeline.repository.TaskRepository;
 import com.bff.pipeline.service.lifecycle.PipelineCreator;
 import com.bff.pipeline.service.lifecycle.PipelineInserter;
+import com.bff.pipeline.service.lifecycle.PipelineRestarter;
 import com.bff.pipeline.service.lifecycle.RecipeCatalog;
 import com.bff.pipeline.service.query.PipelineQueryService;
 import java.time.Duration;
@@ -50,8 +51,8 @@ import org.springframework.transaction.annotation.Transactional;
  */
 @DataJpaTest
 @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
-@Import({PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class, PipelineQueryService.class,
-        TargetSourcePipelineController.class, CustomRecipeCreationTest.Wiring.class})
+@Import({PipelineCreator.class, PipelineInserter.class, PipelineRestarter.class, RecipeCatalog.class,
+        PipelineQueryService.class, TargetSourcePipelineController.class, CustomRecipeCreationTest.Wiring.class})
 @Transactional(propagation = Propagation.NOT_SUPPORTED)
 class CustomRecipeCreationTest {
 
diff --git a/src/test/java/com/bff/pipeline/service/PipelineIntegrationTest.java b/src/test/java/com/bff/pipeline/service/PipelineIntegrationTest.java
index bf62cef..7ffeabd 100644
--- a/src/test/java/com/bff/pipeline/service/PipelineIntegrationTest.java
+++ b/src/test/java/com/bff/pipeline/service/PipelineIntegrationTest.java
@@ -33,6 +33,7 @@ import com.bff.pipeline.service.execution.StepRunner;
 import com.bff.pipeline.service.lifecycle.PipelineControl;
 import com.bff.pipeline.service.lifecycle.PipelineCreator;
 import com.bff.pipeline.service.lifecycle.PipelineInserter;
+import com.bff.pipeline.service.lifecycle.PipelineRestarter;
 import com.bff.pipeline.service.lifecycle.RecipeCatalog;
 import com.bff.pipeline.service.query.PipelineQueryService;
 import com.bff.pipeline.service.task.ConditionCheckTask;
@@ -74,8 +75,8 @@ import org.springframework.transaction.annotation.Transactional;
 @Import({PipelineClaimer.class, PipelineWorker.class, StepRunner.class, StepReporter.class,
         TaskStateMachine.class, TaskTypeRegistry.class, TerraformTask.class, TerraformResultRecorder.class, TerraformJobStateRecorder.class,
         ConditionCheckTask.class, ObservationRecorder.class, TaskCanceller.class, PipelineCreator.class,
-        PipelineInserter.class, PipelineControl.class, RecipeCatalog.class, PipelineQueryService.class,
-        TargetSourcePipelineController.class, PipelineIntegrationTest.Wiring.class})
+        PipelineInserter.class, PipelineRestarter.class, PipelineControl.class, RecipeCatalog.class,
+        PipelineQueryService.class, TargetSourcePipelineController.class, PipelineIntegrationTest.Wiring.class})
 @Transactional(propagation = Propagation.NOT_SUPPORTED)
 class PipelineIntegrationTest {
 
diff --git a/src/test/java/com/bff/pipeline/service/RestartPipelineTest.java b/src/test/java/com/bff/pipeline/service/RestartPipelineTest.java
new file mode 100644
index 0000000..eccbf9a
--- /dev/null
+++ b/src/test/java/com/bff/pipeline/service/RestartPipelineTest.java
@@ -0,0 +1,339 @@
+package com.bff.pipeline.service;
+
+import static org.assertj.core.api.Assertions.assertThat;
+import static org.assertj.core.api.Assertions.assertThatThrownBy;
+import static org.assertj.core.api.Assertions.tuple;
+
+import com.bff.pipeline.client.FakeInfraManagerClient;
+import com.bff.pipeline.config.ExecutionSettings;
+import com.bff.pipeline.config.PipelineSettings;
+import com.bff.pipeline.controller.TargetSourcePipelineController;
+import com.bff.pipeline.dto.pipeline.PipelineDetail;
+import com.bff.pipeline.dto.pipeline.RestartPipelineRequest;
+import com.bff.pipeline.dto.pipeline.RestartPreview;
+import com.bff.pipeline.dto.pipeline.TaskSummary;
+import com.bff.pipeline.entity.Pipeline;
+import com.bff.pipeline.entity.Task;
+import com.bff.pipeline.enums.CloudProvider;
+import com.bff.pipeline.enums.ErrorCode;
+import com.bff.pipeline.enums.PipelineStatus;
+import com.bff.pipeline.enums.PipelineType;
+import com.bff.pipeline.enums.TaskDefinition;
+import com.bff.pipeline.enums.TaskStatus;
+import com.bff.pipeline.exception.InvalidResumeSequenceException;
+import com.bff.pipeline.exception.PipelineAlreadyActiveException;
+import com.bff.pipeline.exception.PipelineNotFoundException;
+import com.bff.pipeline.exception.PipelineNotLatestException;
+import com.bff.pipeline.exception.PipelineNotRestartableException;
+import com.bff.pipeline.exception.UnknownTaskException;
+import com.bff.pipeline.model.PipelinePlan;
+import com.bff.pipeline.model.PipelinePlan.PlannedStep;
+import com.bff.pipeline.repository.PipelineRepository;
+import com.bff.pipeline.repository.TaskRepository;
+import com.bff.pipeline.service.lifecycle.PipelineCreator;
+import com.bff.pipeline.service.lifecycle.PipelineInserter;
+import com.bff.pipeline.service.lifecycle.PipelineRestarter;
+import com.bff.pipeline.service.lifecycle.RecipeCatalog;
+import com.bff.pipeline.service.query.PipelineQueryService;
+import java.time.Duration;
+import java.time.Instant;
+import java.util.List;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
+import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
+import org.springframework.boot.test.context.TestConfiguration;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Import;
+import org.springframework.transaction.annotation.Propagation;
+import org.springframework.transaction.annotation.Transactional;
+
+/**
+ * 파이프라인 재시작(재시작 설계 결정 1–5). 재시작 = 원본 체인의 첫 non-DONE task부터의 suffix로 만드는 새
+ * 파이프라인이며(terminal 불부활), type/recipe/provider는 원본에서 승계하고 계보는 origin_pipeline_id/
+ * origin_task_id로만 남는다. 허용 대상은 "최신 + FAILED/CANCELLED"뿐이다 — 특히 DONE 거절은 active_target
+ * 백스톱이 없어 명시 검사가 유일 방어선이라는 점을 회귀로 고정한다. preview는 실행과 같은 검증을 공유해
+ * 불가 상태에서는 미리보기부터 409다.
+ */
+@DataJpaTest
+@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
+@Import({PipelineRestarter.class, PipelineCreator.class, PipelineInserter.class, RecipeCatalog.class,
+        PipelineQueryService.class, TargetSourcePipelineController.class, RestartPipelineTest.Wiring.class})
+@Transactional(propagation = Propagation.NOT_SUPPORTED)
+class RestartPipelineTest {
+
+    private static final Instant START = Instant.parse("2026-07-25T00:00:00Z");
+    private static final List<TaskDefinition> CHAIN_DEFINITIONS = List.of(
+            TaskDefinition.AWS_SERVICE_PLAN_V1, TaskDefinition.AWS_SERVICE_APPLY_V1,
+            TaskDefinition.AWS_BDC_COMMON_PLAN_V1, TaskDefinition.AWS_BDC_COMMON_APPLY_V1);
+
+    @Autowired private PipelineRestarter restarter;
+    @Autowired private TargetSourcePipelineController controller;
+    @Autowired private PipelineQueryService queryService;
+    @Autowired private PipelineInserter inserter;
+    @Autowired private PipelineRepository pipelineRepository;
+    @Autowired private TaskRepository taskRepository;
+    @Autowired private MutableClock clock;
+
+    @AfterEach
+    void clean() {
+        taskRepository.deleteAll();
+        pipelineRepository.deleteAll();
+        clock.set(START);
+    }
+
+    @Test
+    void failedPipelineRestartsFromFailedTask() {
+        Pipeline origin = seedTerminal("rst-failed", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+        List<Task> originChain = chainOf(origin);
+
+        Pipeline restarted = restarter.restart("rst-failed", origin.getId(), null);
+
+        assertThat(restarted.getId()).isNotEqualTo(origin.getId());   // terminal 불부활 — 항상 새 행
+        assertThat(restarted.getType()).isEqualTo(PipelineType.INSTALL);   // CUSTOM으로 위장하지 않는다(결정 1)
+        assertThat(restarted.getRecipeDefinition()).isEqualTo("AWS_INSTALL_V1");
+        assertThat(restarted.getCloudProvider()).isEqualTo(CloudProvider.AWS);
+        assertThat(restarted.getOriginPipelineId()).isEqualTo(origin.getId());
+        List<Task> chain = chainOf(restarted);
+        assertThat(chain).extracting(Task::getTaskDefinition, Task::getSequence, Task::getOriginTaskId)
+                .containsExactly(
+                        tuple(TaskDefinition.AWS_BDC_COMMON_PLAN_V1.name(), 0, originChain.get(2).getId()),
+                        tuple(TaskDefinition.AWS_BDC_COMMON_APPLY_V1.name(), 1, originChain.get(3).getId()));
+        assertThat(chain.getFirst().getStatus()).isEqualTo(TaskStatus.READY);   // fresh run — 첫 task READY
+        assertThat(chain).allSatisfy(task -> assertThat(task.getFailCount()).isZero());
+        assertThat(chain.getLast().getStatus()).isEqualTo(TaskStatus.BLOCKED);
+    }
+
+    @Test
+    void cancelledPipelineRestartsFromCancelPoint() {
+        Pipeline origin = seedTerminal("rst-cancelled", PipelineStatus.CANCELLED,
+                TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
+
+        Pipeline restarted = restarter.restart("rst-cancelled", origin.getId(), null);
+
+        assertThat(chainOf(restarted)).extracting(Task::getTaskDefinition).containsExactly(
+                TaskDefinition.AWS_SERVICE_APPLY_V1.name(),   // 취소 당시 진행 task부터
+                TaskDefinition.AWS_BDC_COMMON_PLAN_V1.name(),
+                TaskDefinition.AWS_BDC_COMMON_APPLY_V1.name());
+    }
+
+    @Test
+    void doneOriginIsRejected() {
+        // DONE은 active_target 슬롯을 이미 해제해 유니크 제약 백스톱이 없다 — 이 명시 검사가 유일 방어선이다.
+        Pipeline origin = seedTerminal("rst-done", PipelineStatus.DONE,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE);
+
+        assertThatThrownBy(() -> restarter.restart("rst-done", origin.getId(), null))
+                .isInstanceOf(PipelineNotRestartableException.class);
+        assertThat(pipelineRepository.count()).isEqualTo(1);   // 아무것도 만들지 않는다
+    }
+
+    @Test
+    void liveOriginIsRejectedWithExplicitCode() {
+        // 비종료 원본은 유니크 제약 백스톱(ALREADY_ACTIVE 409)도 있지만, 그보다 먼저 정확한 코드로 거절한다.
+        Pipeline running = seedActive("rst-live");
+
+        assertThatThrownBy(() -> restarter.restart("rst-live", running.getId(), null))
+                .isInstanceOf(PipelineNotRestartableException.class);
+
+        running.setStatus(PipelineStatus.PENDING);
+        pipelineRepository.save(running);
+        assertThatThrownBy(() -> restarter.restart("rst-live", running.getId(), null))
+                .isInstanceOf(PipelineNotRestartableException.class);
+    }
+
+    @Test
+    void staleTerminalOriginIsRejected() {
+        Pipeline older = seedTerminal("rst-stale", PipelineStatus.FAILED,
+                TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
+        clock.advance(Duration.ofMinutes(5));
+        seedTerminal("rst-stale", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
+
+        assertThatThrownBy(() -> restarter.restart("rst-stale", older.getId(), null))
+                .isInstanceOf(PipelineNotLatestException.class);
+    }
+
+    @Test
+    void activeRunBlocksRestartAtTheUniqueConstraint() {
+        // 경합 재현: 최신 검증(createdAt desc)은 통과하지만 삽입 시점에 활성 실행이 존재하는 상태.
+        // 활성 rival을 원본보다 이른 createdAt으로 만들어 "검사 후 삽입 전 create가 끼어든" 순간을 고정한다.
+        clock.advance(Duration.ofMinutes(10));
+        Pipeline origin = seedTerminal("rst-race", PipelineStatus.FAILED,
+                TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.CANCELLED, TaskStatus.CANCELLED);
+        clock.set(START);
+        seedActive("rst-race");
+        clock.advance(Duration.ofMinutes(10));
+
+        assertThatThrownBy(() -> restarter.restart("rst-race", origin.getId(), null))
+                .isInstanceOf(PipelineAlreadyActiveException.class);   // 최종 심판은 active_target 유니크 제약
+    }
+
+    @Test
+    void unknownTaskDefinitionIsRejected() {
+        Pipeline origin = seedTerminal("rst-unknown", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+        Task failed = chainOf(origin).get(2);
+        failed.setTaskDefinition("GONE_TASK_V0");   // 카탈로그에서 사라진 이름 — 조용한 열화 금지
+        taskRepository.save(failed);
+
+        assertThatThrownBy(() -> restarter.restart("rst-unknown", origin.getId(), null))
+                .isInstanceOf(UnknownTaskException.class);
+    }
+
+    @Test
+    void fromSequenceOverridesOnlyTowardTheFront() {
+        Pipeline origin = seedTerminal("rst-seq", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+
+        assertThatThrownBy(() -> restarter.restart("rst-seq", origin.getId(), 3))   // 실패 task 건너뛰기
+                .isInstanceOf(InvalidResumeSequenceException.class);
+        assertThatThrownBy(() -> restarter.restart("rst-seq", origin.getId(), -1))
+                .isInstanceOf(InvalidResumeSequenceException.class);
+
+        Pipeline fullRerun = restarter.restart("rst-seq", origin.getId(), 0);   // DONE 재실행은 멱등-안전(결정 3)
+
+        assertThat(chainOf(fullRerun)).hasSize(4);
+    }
+
+    @Test
+    void previewMatchesRestartAndSavesNothing() {
+        Pipeline origin = seedTerminal("rst-preview", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+        List<Task> originChain = chainOf(origin);
+        long pipelineCount = pipelineRepository.count();
+
+        RestartPreview preview = controller.restartPreview("rst-preview", origin.getId(), null);
+
+        assertThat(preview.resumeFromSequence()).isEqualTo(2);
+        assertThat(preview.origin().status()).isEqualTo(PipelineStatus.FAILED);
+        assertThat(preview.origin().totalTaskCount()).isEqualTo(4);
+        assertThat(preview.origin().doneTaskCount()).isEqualTo(2);
+        assertThat(preview.skippedTasks()).extracting(RestartPreview.SkippedTask::sequence).containsExactly(0, 1);
+        assertThat(preview.tasksToRun())
+                .extracting(RestartPreview.TaskToRun::sequence, RestartPreview.TaskToRun::originTaskId,
+                        RestartPreview.TaskToRun::originStatus, RestartPreview.TaskToRun::originErrorCode)
+                .containsExactly(
+                        tuple(2, originChain.get(2).getId(), TaskStatus.FAILED, ErrorCode.JOB_FAILED),
+                        tuple(3, originChain.get(3).getId(), TaskStatus.CANCELLED, null));
+        assertThat(preview.warnings()).isNotEmpty();   // 방금 끝난 원본 — in-flight job 안내
+        assertThat(pipelineRepository.count()).isEqualTo(pipelineCount);   // 읽기 전용
+
+        // 실행과 검증을 공유한다 — 불가 상태는 preview부터 409.
+        Pipeline done = seedTerminal("rst-preview-done", PipelineStatus.DONE,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE, TaskStatus.DONE);
+        assertThatThrownBy(() -> controller.restartPreview("rst-preview-done", done.getId(), null))
+                .isInstanceOf(PipelineNotRestartableException.class);
+        assertThatThrownBy(() -> controller.restartPreview("rst-preview", done.getId(), null))
+                .isInstanceOf(PipelineNotFoundException.class);   // target 불일치는 404
+    }
+
+    @Test
+    void detailCarriesProvenanceInBothDirections() {
+        Pipeline origin = seedTerminal("rst-links", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+
+        PipelineDetail restarted = controller.restart("rst-links", origin.getId(),
+                new RestartPipelineRequest(null));
+
+        assertThat(restarted.originPipelineId()).isEqualTo(origin.getId());
+        assertThat(restarted.origin().pipelineId()).isEqualTo(origin.getId());
+        assertThat(restarted.origin().totalTaskCount()).isEqualTo(4);
+        assertThat(restarted.origin().doneTaskCount()).isEqualTo(2);
+        assertThat(restarted.origin().resumedFromSequence()).isEqualTo(2);   // 원본 4단계 중 2번부터
+        assertThat(restarted.tasks()).extracting(TaskSummary::originTaskId).doesNotContainNull();
+        assertThat(restarted.doneTaskCount()).isZero();   // 진행률은 자기 suffix 기준(숫자 조작 없음)
+
+        PipelineDetail originDetail = queryService.detail(origin.getId());
+        assertThat(originDetail.restartedByPipelineId()).isEqualTo(restarted.pipelineId());   // 역링크
+        assertThat(originDetail.origin()).isNull();
+    }
+
+    @Test
+    void restartOfRestartChainsToImmediateOrigin() {
+        Pipeline first = seedTerminal("rst-chain", PipelineStatus.FAILED,
+                TaskStatus.DONE, TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.CANCELLED);
+        clock.advance(Duration.ofMinutes(5));
+        Pipeline second = restarter.restart("rst-chain", first.getId(), null);
+        terminalize(second, PipelineStatus.FAILED, TaskStatus.FAILED, TaskStatus.CANCELLED);
+        clock.advance(Duration.ofMinutes(5));
+
+        Pipeline third = restarter.restart("rst-chain", second.getId(), null);
+
+        assertThat(third.getOriginPipelineId()).isEqualTo(second.getId());   // 체인은 직전 실행을 가리킨다
+        assertThatThrownBy(() -> restarter.restart("rst-chain", first.getId(), null))
+                .isInstanceOf(PipelineNotLatestException.class);   // 최신 가드가 체인 끝만 허용
+    }
+
+    /** 원본 시딩: INSTALL 분류의 4-task 실행을 삽입한 뒤 주어진 상태로 종료시킨다. */
+    private Pipeline seedTerminal(String target, PipelineStatus status, TaskStatus... taskStatuses) {
+        Pipeline pipeline = seedActive(target);
+        terminalize(pipeline, status, taskStatuses);
+        return pipeline;
+    }
+
+    /** 활성(RUNNING, active_target 점유) 실행 삽입 — startDelay 0이라 fast path RUNNING이다. */
+    private Pipeline seedActive(String target) {
+        List<PlannedStep> steps = CHAIN_DEFINITIONS.stream()
+                .map(definition -> new PlannedStep(definition, null))
+                .toList();
+        return inserter.insert(new PipelinePlan(target, PipelineType.INSTALL, CloudProvider.AWS,
+                "AWS_INSTALL_V1", null, steps));
+    }
+
+    /** 행 직접 갱신으로 종료 상태를 만든다 — 실행 엔진 없이 write-back 결과와 같은 꼴(activeTarget 해제 포함). */
+    private void terminalize(Pipeline pipeline, PipelineStatus status, TaskStatus... taskStatuses) {
+        List<Task> chain = chainOf(pipeline);
+        for (int i = 0; i < taskStatuses.length; i++) {
+            Task task = chain.get(i);
+            task.setStatus(taskStatuses[i]);
+            if (taskStatuses[i] == TaskStatus.FAILED) {
+                task.setFailCount(3);
+                task.setErrorCode(ErrorCode.JOB_FAILED);
+            }
+            taskRepository.save(task);
+        }
+        pipeline.setStatus(status);
+        pipeline.setActiveTarget(null);
+        pipeline.setLastActivityAt(clock.instant());
+        pipelineRepository.save(pipeline);
+    }
+
+    private List<Task> chainOf(Pipeline pipeline) {
+        return taskRepository.findByPipelineIdOrderBySequenceAsc(pipeline.getId());
+    }
+
+    @TestConfiguration
+    static class Wiring {
+        @Bean
+        MutableClock clock() {
+            return new MutableClock(START);
+        }
+
+        @Bean
+        FakeInfraManagerClient infraManager() {
+            return new FakeInfraManagerClient();
+        }
+
+        @Bean
+        PipelineSettings pipelineSettings() {
+            return PipelineSettings.builder()
+                    .executionTimeout(Duration.ofMinutes(50))
+                    .pollingInterval(Duration.ofMinutes(10)).maxFailCount(2).maxTerraformPollCallErrors(10)
+                    .startDelay(Duration.ZERO).build();
+        }
+
+        @Bean
+        ExecutionSettings executionSettings() {
+            return ExecutionSettings.builder()
+                    .workerPerPod(2).leaseDuration(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(15))
+                    .runningPipelineCap(100).terraformSlotCap(100).terraformSlotRetry(Duration.ofSeconds(1))
+                    .pollInterval(Duration.ofSeconds(1)).maxIdleSleep(Duration.ofSeconds(1))
+                    .backoffBase(Duration.ofMillis(100)).backoffMax(Duration.ofSeconds(1)).jitterRatio(0.2)
+                    .schedulerInitialDelay(Duration.ofSeconds(5))
+                    .build();
+        }
+    }
+}
diff --git a/src/test/java/com/bff/pipeline/service/notify/SlackNotifierTest.java b/src/test/java/com/bff/pipeline/service/notify/SlackNotifierTest.java
index 5255d9a..e88f97a 100644
--- a/src/test/java/com/bff/pipeline/service/notify/SlackNotifierTest.java
+++ b/src/test/java/com/bff/pipeline/service/notify/SlackNotifierTest.java
@@ -77,6 +77,27 @@ class SlackNotifierTest {
         assertThat(fieldTitles(message)).doesNotContain("failed_task", "error_code");
     }
 
+    @Test
+    void aRestartedRunHeadlineCarriesTheOriginContext() {
+        // 재시작 실행의 알림은 "INSTALL(#123의 재시작)"로 읽혀야 한다(재시작 설계 §3.4) —
+        // payload에만 실리고 웹훅 메시지에서 사라지면 일반 INSTALL 알림과 구분되지 않는다.
+        NotifyPayload payload = NotifyPayload.builder()
+                .pipelineId(124L).type("INSTALL").terminalStatus("DONE").targetRef("483")
+                .environment("prd").detailUrl("http://localhost:3001/integration/admin/pipelines/124")
+                .schemaVersion(NotifyPayload.SCHEMA_VERSION)
+                .originPipelineId(123L)
+                .build();
+
+        Map<String, Object> message = SlackNotifier.toSlackMessage(payload);
+
+        assertThat((String) message.get("text")).contains("INSTALL(#123의 재시작)", "(id 124)");
+    }
+
+    @Test
+    void aNonRestartHeadlineCarriesNoRestartSegment() {
+        assertThat((String) SlackNotifier.toSlackMessage(donePayload()).get("text")).doesNotContain("재시작");
+    }
+
     @Test
     void degradedOrMissingOptionalValuesAreOmittedInsteadOfPrintedAsNull() {
         // type을 해석 못 하는 옛 행 + 환경/링크/CSP가 없는 payload — 빠진 값은 그 구간째 사라져야 한다.
```
