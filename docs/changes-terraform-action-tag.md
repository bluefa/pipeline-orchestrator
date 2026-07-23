# 변경 기록 — Task 응답에 `terraform_action` 표시 필드 추가

## 요약

운영 어드민 UI가 Terraform job 노드에 **PLAN / APPLY / DESTROY 태그**를 표시할 수 있도록, task 관련 API 응답에 읽기 전용 필드 `terraform_action`을 추가한다. 순수 가산(additive) 변경이며 **상태 전이·DB 스키마·트랜잭션·예외 분류·실행 로직에는 변경이 없다.** DB 마이그레이션·설정(yml)·의존성 추가도 없다.

## 새 필드: `terraform_action`

- 값: `"PLAN"` | `"APPLY"` | `"DESTROY"` | `null`
- 의미: 해당 task가 수행하는 Terraform 액션. terraform이 아닌 task(`CONDITION_CHECK`)는 `null`.
- 파생: `TaskOperation`의 이름 규약 suffix(`…_TF_PLAN` / `…_TF_APPLY` / `…_TF_DESTROY`)에서 서버가 계산하는 표시용 값이다. 저장 컬럼이 아니다.
- 폴 정규화·API 바인딩의 authority는 여전히 `TerraformBindingCatalog`의 행(operation → `TerraformJobType`)이며, 이 필드는 표시용 분류 라벨일 뿐이다.
- 직렬화: snake_case (`terraform_action`).

## 영향받는 엔드포인트 (응답에 필드 추가, 하위호환)

| DTO | 엔드포인트 | 위치 |
|---|---|---|
| `TaskSummary` | `GET /api/v1/pipelines/{pipelineId}`, `POST /api/v1/pipelines/{pipelineId}/cancel` | `PipelineDetail.tasks[]` 각 노드 |
| `TaskDetail` | `GET /api/v1/pipelines/{pipelineId}/tasks/{taskId}` | task 상세 |
| `TaskCatalogEntry` | `GET /api/v1/task-definitions` | 카탈로그 항목 |
| `RecipePreviewStep` | `GET /api/v1/target-sources/{targetSourceId}/pipelines/preview` | `RecipePreview.steps[]` |

기존 필드·타입·순서는 그대로이고 필드 하나만 추가되므로 기존 클라이언트에 영향이 없다.

## 코드 변경

- **`enums/TaskOperation.java`** — 파생 메서드 `terraformAction(): Optional<String>` 신설. TERRAFORM_JOB이 아니거나 규약을 벗어난 이름이면 empty.
- **`dto/pipeline/TaskSummary.java`, `dto/pipeline/TaskDetail.java`** — `terraform_action` 필드 추가. 소스가 `Task` 엔티티라 operation이 null일 수 있어(제거·rename된 operation을 `TaskOperationConverter`가 의도적으로 null 열화) **null 가드** 적용. 기존 열화-계약(`taskDetailWithALegacyOperationValueDegradesInsteadOfThrowing`) 준수.
- **`dto/pipeline/TaskCatalogEntry.java`, `dto/pipeline/RecipePreviewStep.java`** — `terraform_action` 필드 추가. 소스가 `TaskDefinition`(카탈로그 enum)이라 operation이 항상 non-null → 가드 불필요. `RecipePreviewStep`은 위치기반 record 생성을 `@Builder`로 전환(인자 스왑 방지 규칙 준수).
- **`service/query/PipelineQueryService.java`** — `taskDetail()` 빌더에 `.terraformAction(...)` 추가(null 가드 포함).

## 테스트

- **신규** `enums/TaskOperationTest.java` — 모든 TERRAFORM_JOB operation이 PLAN/APPLY/DESTROY 중 하나를 내고, CONDITION_CHECK(`NETWORK_READY`)는 empty임을 회귀로 고정.
- **수정** `dto/pipeline/DtoSnakeCaseSerializationTest.java` — `terraform_action` snake_case 직렬화 단언 추가(camelCase 거부 포함), 위치기반 생성자 2곳을 `@Builder`로 전환.

## 검증

- 백엔드 전체 테스트 **245/245 통과**, 0 에러.
- codex(gpt-5.6-sol, xhigh) 2라운드 독립 리뷰 — **P0/P1 없음.** 카탈로그 24행과 suffix 파생 값 **불일치 0** 확인.

## 알려진 수용 사항 (P2)

`terraformAction()` suffix 파생은 catalog의 authority(operation → jobType)와 자동 cross-check되지 않는다. 현재 24행 전부 일치하나, 진짜 cross-check는 client 레이어 jobType 노출 + Spring 컨텍스트 스윕이 필요해 표시 라벨엔 과하다고 판단하여 수용했다.

---

## 전체 변경 diff (`git diff origin/main`)

```diff
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/RecipePreviewStep.java b/src/main/java/com/bff/pipeline/dto/pipeline/RecipePreviewStep.java
index dee70b5..a87da48 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/RecipePreviewStep.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/RecipePreviewStep.java
@@ -3,25 +3,36 @@ package com.bff.pipeline.dto.pipeline;
 import com.bff.pipeline.enums.TaskDefinition;
 import com.bff.pipeline.enums.TaskOperation;
 import com.fasterxml.jackson.annotation.JsonProperty;
+import lombok.Builder;
 
 /**
  * preview recipe의 한 단계다(P9). kind는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이고,
+ * terraformAction은 operation에서 파생한 표시용 액션(PLAN/APPLY/DESTROY)이며 terraform이 아니면 null이다.
  * taskDefinition은 이 단계를 만든 TaskDefinition 상수 이름이다. definition은 그 항목의 실행 계약 뷰
  * (호출 API·성공 판정 정책·result 저장 방식)로, 운영자가 생성 전에 각 단계가 정확히 무엇을 하는지 볼 수 있게 한다.
  * 와이어 필드는 snake_case로 직렬화한다.
  */
+@Builder
 public record RecipePreviewStep(
         @JsonProperty("sequence") int sequence,
         @JsonProperty("task_definition") String taskDefinition,
         @JsonProperty("kind") String kind,
         @JsonProperty("operation") TaskOperation operation,
+        @JsonProperty("terraform_action") String terraformAction,
         @JsonProperty("display_name") String displayName,
         @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot,
         @JsonProperty("definition") TaskDefinitionView definition) {
 
     public static RecipePreviewStep from(int sequence, TaskDefinition definition) {
-        return new RecipePreviewStep(sequence, definition.name(), definition.mechanism(),
-                definition.operation(), definition.displayName(), definition.consumesTerraformSlot(),
-                TaskDefinitionView.from(definition));
+        return RecipePreviewStep.builder()
+                .sequence(sequence)
+                .taskDefinition(definition.name())
+                .kind(definition.mechanism())
+                .operation(definition.operation())
+                .terraformAction(definition.operation().terraformAction().orElse(null))
+                .displayName(definition.displayName())
+                .consumesTerraformSlot(definition.consumesTerraformSlot())
+                .definition(TaskDefinitionView.from(definition))
+                .build();
     }
 }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/TaskCatalogEntry.java b/src/main/java/com/bff/pipeline/dto/pipeline/TaskCatalogEntry.java
index 2c5c887..0dff34b 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/TaskCatalogEntry.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/TaskCatalogEntry.java
@@ -8,7 +8,8 @@ import lombok.Builder;
 /**
  * TaskDefinition 카탈로그 목록 항목이다(LIN-27, {@code GET /api/v1/task-definitions}). Custom Recipe 빌더가
  * "이 provider가 수행할 수 있는 Task 목록"을 렌더링하는 데 쓰는 얇은 뷰로, 실행 계약 API 상세는 담지 않는다
- * (상세는 {@link TaskDefinitionView}). {@code kind}는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이다.
+ * (상세는 {@link TaskDefinitionView}). {@code kind}는 실행 메커니즘(TERRAFORM_JOB/CONDITION_CHECK)이고,
+ * {@code terraformAction}은 operation에서 파생한 표시용 액션(PLAN/APPLY/DESTROY)이며 terraform이 아니면 null이다.
  * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
  */
 @Builder
@@ -18,6 +19,7 @@ public record TaskCatalogEntry(
         @JsonProperty("description") String description,
         @JsonProperty("provider") CloudProvider provider,
         @JsonProperty("kind") String kind,
+        @JsonProperty("terraform_action") String terraformAction,
         @JsonProperty("consumes_terraform_slot") boolean consumesTerraformSlot) {
 
     public static TaskCatalogEntry from(TaskDefinition definition) {
@@ -27,6 +29,7 @@ public record TaskCatalogEntry(
                 .description(definition.description())
                 .provider(definition.provider())
                 .kind(definition.mechanism())
+                .terraformAction(definition.operation().terraformAction().orElse(null))
                 .consumesTerraformSlot(definition.consumesTerraformSlot())
                 .build();
     }
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java b/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
index 7ba1d77..7ee40dc 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/TaskDetail.java
@@ -17,6 +17,7 @@ import lombok.Builder;
  * 폴 하나가 곧 attempt 하나라 attempts 목록이 폴 수만큼 늘어난다(각 attempt의 check는 call_count=1).
  * definition은 task_definition 이름을 카탈로그에서 해석한 실행 계약 뷰이며, 미해석(삭제/rename된 옛 이름)이면 null이다.
  * description은 custom recipe 실행에서 운영자가 이 task에 붙인 설명이고(LIN-18), 카탈로그 recipe task면 null이다.
+ * terraformAction은 operation에서 파생한 표시용 액션 라벨(PLAN/APPLY/DESTROY)이고 terraform이 아닌 task면 null이다.
  * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
  */
 @Builder
@@ -28,6 +29,7 @@ public record TaskDetail(
         @JsonProperty("task_definition") String taskDefinition,
         @JsonProperty("definition") TaskDefinitionView definition,
         @JsonProperty("operation") TaskOperation operation,
+        @JsonProperty("terraform_action") String terraformAction,
         @JsonProperty("status") TaskStatus status,
         @JsonProperty("fail_count") int failCount,
         @JsonProperty("error_code") ErrorCode errorCode,
diff --git a/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java b/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
index 1d893b8..6ac1fc0 100644
--- a/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
+++ b/src/main/java/com/bff/pipeline/dto/pipeline/TaskSummary.java
@@ -12,6 +12,7 @@ import lombok.Builder;
  * task 흐름 노드 한 개의 읽기 전용 뷰다(P4의 tasks 목록). kind는 task type 이름(taskName)이며
  * "TERRAFORM_JOB"/"CONDITION_CHECK" 같은 실행 메커니즘 식별자다. errorCode는 FAILED일 때만 채워진다.
  * description은 custom recipe 실행에서 운영자가 붙인 설명이고 카탈로그 task면 null이다(LIN-18).
+ * terraformAction은 operation에서 파생한 표시용 액션 라벨(PLAN/APPLY/DESTROY)이고 terraform이 아닌 task면 null이다.
  * 와이어 필드는 snake_case로 직렬화한다. 인접 동형 인자가 많아 위치 기반 생성 대신 {@code @Builder}로 만든다.
  */
 @Builder
@@ -21,6 +22,7 @@ public record TaskSummary(
         @JsonProperty("kind") String kind,
         @JsonProperty("task_definition") String taskDefinition,
         @JsonProperty("operation") TaskOperation operation,
+        @JsonProperty("terraform_action") String terraformAction,
         @JsonProperty("status") TaskStatus status,
         @JsonProperty("fail_count") int failCount,
         @JsonProperty("error_code") ErrorCode errorCode,
@@ -30,12 +32,14 @@ public record TaskSummary(
         @JsonProperty("description") String description) {
 
     public static TaskSummary from(Task task) {
+        TaskOperation operation = task.getOperation();
         return TaskSummary.builder()
                 .taskId(task.getId())
                 .sequence(task.getSequence())
                 .kind(task.getTaskName())
                 .taskDefinition(task.getTaskDefinition())
-                .operation(task.getOperation())
+                .operation(operation)
+                .terraformAction(operation == null ? null : operation.terraformAction().orElse(null))
                 .status(task.getStatus())
                 .failCount(task.getFailCount())
                 .errorCode(task.getErrorCode())
diff --git a/src/main/java/com/bff/pipeline/enums/TaskOperation.java b/src/main/java/com/bff/pipeline/enums/TaskOperation.java
index b07ef5f..ec1e69e 100644
--- a/src/main/java/com/bff/pipeline/enums/TaskOperation.java
+++ b/src/main/java/com/bff/pipeline/enums/TaskOperation.java
@@ -77,6 +77,20 @@ public enum TaskOperation {
         return mechanism;
     }
 
+    /**
+     * terraform 액션 라벨(PLAN/APPLY/DESTROY)이다 — 운영 UI가 job 노드에 태그로 표시한다. TERRAFORM_JOB
+     * operation의 이름 규약(…_TF_PLAN/…_TF_APPLY/…_TF_DESTROY) suffix에서 파생하며, terraform이 아닌
+     * operation(CONDITION_CHECK)이나 규약을 벗어난 이름은 empty다. 이 값은 표시용 분류 라벨일 뿐이고,
+     * 폴 정규화·API 바인딩의 authority는 여전히 TerraformBindingCatalog의 행(operation → TerraformJobType)이다.
+     */
+    public Optional<String> terraformAction() {
+        if (!Mechanism.TERRAFORM_JOB.equals(mechanism)) {
+            return Optional.empty();
+        }
+        int marker = name().lastIndexOf("_TF_");
+        return marker < 0 ? Optional.empty() : Optional.of(name().substring(marker + "_TF_".length()));
+    }
+
     /**
      * terraform slot 소비 여부의 단일 authority다. slot 소비는 operation이 아니라 mechanism의 속성이라, 값을 op마다
      * 두지 않고 mechanism으로 판별한다. insert 때 이 값이 task 행(consumes_terraform_slot)에 캐시돼 slot 게이트가 쓴다.
diff --git a/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java b/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
index 3779925..0cd4828 100644
--- a/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
+++ b/src/main/java/com/bff/pipeline/service/query/PipelineQueryService.java
@@ -197,6 +197,8 @@ public class PipelineQueryService {
                 .definition(TaskDefinition.find(task.getTaskDefinition())
                         .map(TaskDefinitionView::from).orElse(null))
                 .operation(task.getOperation())
+                .terraformAction(task.getOperation() == null
+                        ? null : task.getOperation().terraformAction().orElse(null))
                 .status(task.getStatus())
                 .failCount(task.getFailCount())
                 .errorCode(task.getErrorCode())
diff --git a/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java b/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
index ec4ac6e..42810b5 100644
--- a/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
+++ b/src/test/java/com/bff/pipeline/dto/pipeline/DtoSnakeCaseSerializationTest.java
@@ -42,8 +42,12 @@ class DtoSnakeCaseSerializationTest {
 
     @Test
     void pipelineDetailAndNestedTaskSerializeSnakeCase() throws Exception {
-        TaskSummary task = new TaskSummary(5L, 0, "TERRAFORM_JOB", "AWS_SERVICE_APPLY_V1", TaskOperation.AWS_SERVICE_TF_APPLY,
-                TaskStatus.IN_PROGRESS, 0, null, true, Instant.parse("2026-07-02T00:00:00Z"), null, "manual apply");
+        TaskSummary task = TaskSummary.builder()
+                .taskId(5L).sequence(0).kind("TERRAFORM_JOB").taskDefinition("AWS_SERVICE_APPLY_V1")
+                .operation(TaskOperation.AWS_SERVICE_TF_APPLY).terraformAction("APPLY")
+                .status(TaskStatus.IN_PROGRESS).failCount(0).errorCode(null).consumesTerraformSlot(true)
+                .startedAt(Instant.parse("2026-07-02T00:00:00Z")).finishedAt(null).description("manual apply")
+                .build();
         PipelineDetail detail = new PipelineDetail(101L, PipelineType.INSTALL, "ts-1", CloudProvider.AWS,
                 "AWS_INSTALL_V1", PipelineStatus.RUNNING, Instant.parse("2026-07-02T00:00:00Z"),
                 Instant.parse("2026-07-02T00:05:00Z"), Instant.parse("2026-07-02T00:06:00Z"), true, false,
@@ -54,7 +58,7 @@ class DtoSnakeCaseSerializationTest {
         assertThat(json).contains("\"next_due_at\":", "\"cancel_requested\":false", "\"due_lag_millis\":0",
                 "\"current_task_sequence\":0", "\"final_task_sequence\":1", "\"current_max_fail_count\":3");
         assertThat(json).contains("\"task_id\":5", "\"consumes_terraform_slot\":true", "\"fail_count\":0",
-                "\"description\":\"manual apply\"");
+                "\"terraform_action\":\"APPLY\"", "\"description\":\"manual apply\"");
         assertThat(json).doesNotContain("nextDueAt", "cancelRequested", "taskId", "consumesTerraformSlot");
     }
 
@@ -87,7 +91,7 @@ class DtoSnakeCaseSerializationTest {
         String json = mapper.writeValueAsString(RecipePreview.from(RecipeDefinition.AWS_INSTALL_V1));
 
         assertThat(json).contains("\"recipe_definition\":\"AWS_INSTALL_V1\"", "\"display_name\":",
-                "\"task_definition\":", "\"consumes_terraform_slot\":",
+                "\"task_definition\":", "\"consumes_terraform_slot\":", "\"terraform_action\":",
                 "\"definition\":", "\"dispatch_api\":", "\"success_policy\":", "\"result_storage\":");
         assertThat(json).doesNotContain("recipeDefinition", "displayName", "consumesTerraformSlot",
                 "dispatchApi", "successPolicy", "resultStorage");
@@ -95,20 +99,25 @@ class DtoSnakeCaseSerializationTest {
 
     @Test
     void taskDetailEffectiveSettingsSerializeSnakeCase() throws Exception {
-        TaskDetail detail = new TaskDetail(5L, 101L, 0, "TERRAFORM_JOB", "AWS_SERVICE_APPLY_V1",
-                TaskDefinitionView.from(TaskDefinition.AWS_SERVICE_APPLY_V1),
-                TaskOperation.AWS_SERVICE_TF_APPLY, TaskStatus.IN_PROGRESS, 0, null, true,
-                Instant.parse("2026-07-02T00:00:00Z"), Instant.parse("2026-07-02T00:00:00Z"), null, null,
-                Duration.ofMinutes(10), Duration.ofMinutes(50), 2, List.of(), "custom note");
+        TaskDetail detail = TaskDetail.builder()
+                .taskId(5L).pipelineId(101L).sequence(0).kind("TERRAFORM_JOB").taskDefinition("AWS_SERVICE_APPLY_V1")
+                .definition(TaskDefinitionView.from(TaskDefinition.AWS_SERVICE_APPLY_V1))
+                .operation(TaskOperation.AWS_SERVICE_TF_APPLY).terraformAction("APPLY")
+                .status(TaskStatus.IN_PROGRESS).failCount(0).errorCode(null).consumesTerraformSlot(true)
+                .startedAt(Instant.parse("2026-07-02T00:00:00Z")).readyAt(Instant.parse("2026-07-02T00:00:00Z"))
+                .finishedAt(null).nextCheckAt(null)
+                .effectivePollingInterval(Duration.ofMinutes(10)).effectiveExecutionTimeout(Duration.ofMinutes(50))
+                .effectiveMaxFailCount(2).attempts(List.of()).description("custom note")
+                .build();
 
         String json = mapper.writeValueAsString(detail);
 
         assertThat(json).contains("\"effective_polling_interval\":", "\"effective_execution_timeout\":",
-                "\"effective_max_fail_count\":2", "\"next_check_at\":",
+                "\"effective_max_fail_count\":2", "\"next_check_at\":", "\"terraform_action\":\"APPLY\"",
                 "\"definition\":", "\"dispatch_api\":", "\"status_api\":", "\"result_api\":",
                 "\"success_policy\":", "\"result_storage\":", "\"description\":\"custom note\"");
         assertThat(json).doesNotContain("effectiveMaxFailCount", "nextCheckAt", "dispatchApi", "statusApi",
-                "resultApi", "successPolicy", "resultStorage");
+                "resultApi", "successPolicy", "resultStorage", "terraformAction");
     }
 
     @Test
@@ -119,7 +128,7 @@ class DtoSnakeCaseSerializationTest {
 
         assertThat(json).contains("\"task_definitions\":", "\"name\":\"AWS_SERVICE_APPLY_V1\"", "\"display_name\":",
                 "\"description\":", "\"provider\":\"AWS\"", "\"kind\":\"TERRAFORM_JOB\"",
-                "\"consumes_terraform_slot\":true");
+                "\"terraform_action\":\"APPLY\"", "\"consumes_terraform_slot\":true");
         assertThat(json).doesNotContain("taskDefinitions", "displayName", "consumesTerraformSlot");
     }
 
diff --git a/src/test/java/com/bff/pipeline/enums/TaskOperationTest.java b/src/test/java/com/bff/pipeline/enums/TaskOperationTest.java
new file mode 100644
index 0000000..121848f
--- /dev/null
+++ b/src/test/java/com/bff/pipeline/enums/TaskOperationTest.java
@@ -0,0 +1,37 @@
+package com.bff.pipeline.enums;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import com.bff.pipeline.client.terraform.TerraformJobType;
+import java.util.Arrays;
+import java.util.Set;
+import java.util.stream.Collectors;
+import org.junit.jupiter.api.Test;
+
+/**
+ * terraformAction() 파생을 회귀로 못박는다. 이 값은 운영 UI가 job 노드에 PLAN/APPLY/DESTROY 태그로 표시하는
+ * 라벨이라, operation 이름 규약(…_TF_PLAN/…_TF_APPLY/…_TF_DESTROY)이 깨지거나 새 terraform operation이
+ * 규약을 벗어나면 태그가 조용히 사라진다 — 여기서 잡는다.
+ */
+class TaskOperationTest {
+
+    private static final Set<String> JOB_TYPE_NAMES = Arrays.stream(TerraformJobType.values())
+            .map(Enum::name)
+            .collect(Collectors.toUnmodifiableSet());
+
+    @Test
+    void everyTerraformOperationYieldsAKnownAction() {
+        for (TaskOperation operation : TaskOperation.values()) {
+            if (TaskOperation.Mechanism.TERRAFORM_JOB.equals(operation.mechanism())) {
+                assertThat(operation.terraformAction())
+                        .as("terraform operation %s must derive an action", operation)
+                        .hasValueSatisfying(action -> assertThat(JOB_TYPE_NAMES).contains(action));
+            }
+        }
+    }
+
+    @Test
+    void nonTerraformOperationHasNoAction() {
+        assertThat(TaskOperation.NETWORK_READY.terraformAction()).isEmpty();
+    }
+}
```
