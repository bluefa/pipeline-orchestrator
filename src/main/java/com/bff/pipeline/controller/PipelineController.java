package com.bff.pipeline.controller;

import com.bff.pipeline.dto.pipeline.LivePipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.PipelineStatistics;
import com.bff.pipeline.dto.pipeline.PipelineSummary;
import com.bff.pipeline.dto.pipeline.TaskDetail;
import com.bff.pipeline.dto.pipeline.TerraformResultDetail;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.StatisticsPeriod;
import com.bff.pipeline.service.lifecycle.PipelineControl;
import com.bff.pipeline.service.query.PipelineQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
public class PipelineController {

    private final PipelineQueryService queryService;
    private final PipelineControl pipelineControl;

    public PipelineController(PipelineQueryService queryService, PipelineControl pipelineControl) {
        this.queryService = queryService;
        this.pipelineControl = pipelineControl;
    }

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

    @PostMapping("/{pipelineId}/cancel")
    public PipelineDetail cancel(@PathVariable Long pipelineId) {
        return queryService.toDetail(pipelineControl.cancel(pipelineId));
    }
}
