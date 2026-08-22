package com.bff.pipeline.controller;

import com.bff.pipeline.dto.pipeline.ApprovalDecisionRequest;
import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.PipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineSummary;
import com.bff.pipeline.dto.pipeline.TaskApprovalView;
import com.bff.pipeline.dto.pipeline.TaskDetail;
import com.bff.pipeline.dto.pipeline.TerraformJobStateDetail;
import com.bff.pipeline.dto.pipeline.TerraformResultDetail;
import com.bff.pipeline.enums.ApprovalChannel;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.service.approval.ApprovalService;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.query.PipelineQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 파이프라인 대시보드/상세 REST 컨트롤러다(P1~P6). 이 오케스트레이터의 인바운드 API는 {@code /api/v1} 접두어를
 * 쓴다(BFF의 공개 경로 {@code /install/v1}과 구분되며 BFF가 이를 프록시한다). 컨트롤러는 얇은 어댑터로,
 * 파라미터 파싱/검증만 하고 조회는 {@link PipelineQueryService}, 취소는 {@link PipelineControl}에 위임한다.
 * 잘못된/누락 파라미터와 typed 예외의 HTTP 매핑은 GlobalAdvice가 한곳에서 처리한다.
 *
 * <p>목록은 Spring Data {@code Page}를 그대로 돌려준다 — BFF swagger의 {@code PageServiceItem}이 곧 이 Page 형태
 * (content/number/size/totalElements/totalPages/pageable/sort/...)이므로, 계약을 그 컨벤션에 맞춘다. 기본 정렬은
 * created_at desc, id desc로 고정해 페이지 경계가 결정적이게 한다.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineQueryService queryService;
    private final PipelineControl pipelineControl;
    private final ApprovalService approvalService;

    @GetMapping("/statistics/live")
    public LivePipelineStatistics liveStatistics() {
        return queryService.liveStatistics();
    }

    @GetMapping("/statistics")
    public PipelineStatistics statistics(@RequestParam String period) {
        return queryService.statistics(StatisticsPeriod.fromToken(period));
    }

    @GetMapping
    public Page<PipelineSummary> list(
            @RequestParam(required = false) PipelineStatus status,
            @RequestParam(required = false) CloudProvider provider,
            @RequestParam(required = false) String period,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        StatisticsPeriod parsedPeriod = period == null ? null : StatisticsPeriod.fromToken(period);
        return queryService.list(status, provider, parsedPeriod, pageable);
    }

    @GetMapping("/{pipelineId}")
    public PipelineDetail detail(@PathVariable Long pipelineId) {
        return queryService.detail(pipelineId);
    }

    @GetMapping("/{pipelineId}/tasks/{taskId}")
    public TaskDetail taskDetail(@PathVariable Long pipelineId, @PathVariable Long taskId) {
        return queryService.taskDetail(pipelineId, taskId);
    }

    /** terraform job result 본문 전용 조회(P11) — task 상세의 result 메타에서 "로그 보기"가 lazy 호출한다. */
    @GetMapping("/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/result")
    public TerraformResultDetail terraformResult(@PathVariable Long pipelineId, @PathVariable Long taskId,
            @PathVariable int attemptNumber, @PathVariable String jobId) {
        return queryService.terraformResult(pipelineId, taskId, attemptNumber, jobId);
    }

    /** terraform job 진행-시점 상태 개별 조회 — task 상세의 job 상태에서 특정 job을 조회한다(result 본문 엔드포인트와 대칭). */
    @GetMapping("/{pipelineId}/tasks/{taskId}/attempts/{attemptNumber}/jobs/{jobId}/state")
    public TerraformJobStateDetail terraformJobState(@PathVariable Long pipelineId, @PathVariable Long taskId,
            @PathVariable int attemptNumber, @PathVariable String jobId) {
        return queryService.terraformJobState(pipelineId, taskId, attemptNumber, jobId);
    }

    @PostMapping("/{pipelineId}/cancel")
    public PipelineDetail cancel(@PathVariable Long pipelineId) {
        return queryService.toDetail(pipelineControl.cancel(pipelineId));
    }

    /**
     * 승인 게이트 승인(승인 게이트 ADR §결정 4). 승인자 신원은 BFF가 검증된 세션에서 채워 보내고, 관리자
     * 권한 강제도 BFF의 몫이다 — 취소 API와 같은 신뢰 모델이다. 이미 결정된 요청은 오류가 아니라 그 결정을
     * 그대로 돌려주므로(같은 클릭이 두 번 도착하는 것은 정상이다), 콘솔은 응답의 승인자와 상태를 보고
     * "이미 ○○ 님이 처리했습니다"로 안내한다. 기한이 지난 요청만 409로 거절된다 — 돌려줄 결정이 없기 때문이다.
     */
    @PostMapping("/{pipelineId}/tasks/{taskId}/approve")
    public TaskApprovalView approve(@PathVariable Long pipelineId, @PathVariable Long taskId,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        return TaskApprovalView.from(approvalService.approve(pipelineId, taskId,
                approverId(request), approverName(request), ApprovalChannel.CONSOLE));
    }

    /**
     * 승인 게이트 반려. 결정을 남기면서 파이프라인에 취소 요청을 함께 세우므로, 워커가 실행 전체를 취소로
     * 닫는다 — 반려는 오류가 아니라 의도된 중단이라 실패가 아닌 취소로 기록된다.
     */
    @PostMapping("/{pipelineId}/tasks/{taskId}/reject")
    public TaskApprovalView reject(@PathVariable Long pipelineId, @PathVariable Long taskId,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        return TaskApprovalView.from(approvalService.reject(pipelineId, taskId,
                approverId(request), approverName(request), ApprovalChannel.CONSOLE));
    }

    /** 본문이 통째로 없으면 승인자도 없는 것이다 — 서비스가 필수 검증에서 400으로 거절한다. */
    private static String approverId(ApprovalDecisionRequest request) {
        return request == null ? null : request.approverId();
    }

    private static String approverName(ApprovalDecisionRequest request) {
        return request == null ? null : request.approverName();
    }
}
