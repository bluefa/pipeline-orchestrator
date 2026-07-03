package com.bff.pipeline.controller;

import com.bff.pipeline.dto.pipeline.CreatePipelineRequest;
import com.bff.pipeline.dto.pipeline.CustomPipelineRequest;
import com.bff.pipeline.dto.pipeline.PipelineDetail;
import com.bff.pipeline.dto.pipeline.PipelineSummary;
import com.bff.pipeline.dto.pipeline.RecipePreview;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.MissingPipelineTypeException;
import com.bff.pipeline.service.lifecycle.PipelineCreator;
import com.bff.pipeline.service.query.PipelineQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 특정 target 하위의 파이프라인 이력/실행 REST 컨트롤러다(P7~P10). 이력·최근카드는 {@link PipelineQueryService},
 * 실행 전 미리보기와 실행은 {@link PipelineCreator}에 위임한다. 실행은 두 갈래다 — 카탈로그 recipe는 {@code POST}로
 * INSTALL/DELETE를 골라 실행하고(P10), custom recipe는 {@code POST /custom}으로 요청이 구성한 task 순서를 실행한다
 * (LIN-18, type은 CUSTOM 고정). 둘 다 ADR-016 §4 유일성에 따라 이미 활성 실행이 있으면 409로 거절되고(create 계약),
 * 그 외에는 만들어진 실행의 상세를 반환한다. 최근카드(P8)는 실행이 없으면 204 No Content로 답한다.
 */
@RestController
@RequestMapping("/api/v1/target-sources/{targetSourceId}/pipelines")
public class TargetSourcePipelineController {

    private final PipelineQueryService queryService;
    private final PipelineCreator pipelineCreator;

    public TargetSourcePipelineController(PipelineQueryService queryService, PipelineCreator pipelineCreator) {
        this.queryService = queryService;
        this.pipelineCreator = pipelineCreator;
    }

    @GetMapping
    public Page<PipelineSummary> history(@PathVariable String targetSourceId,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return queryService.historyByTarget(targetSourceId, pageable);
    }

    @GetMapping("/latest")
    public ResponseEntity<PipelineSummary> latest(@PathVariable String targetSourceId) {
        return queryService.latestByTarget(targetSourceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/preview")
    public RecipePreview preview(@PathVariable String targetSourceId, @RequestParam PipelineType type) {
        return RecipePreview.from(pipelineCreator.preview(targetSourceId, type));
    }

    @PostMapping
    public PipelineDetail create(@PathVariable String targetSourceId, @RequestBody CreatePipelineRequest request) {
        if (request == null || request.type() == null) {
            throw new MissingPipelineTypeException();
        }
        return queryService.toDetail(pipelineCreator.create(targetSourceId, request.type()));
    }

    /**
     * custom recipe 실행(LIN-18). 요청이 준 task 순서·이름대로 실행하며 type은 CUSTOM으로 고정된다. tasks가 비어 있거나
     * 각 task 검증(이름 존재·provider 일치·설명 길이)을 어기면 400, 이미 활성 실행이 있으면 409(create 계약과 동일).
     */
    @PostMapping("/custom")
    public PipelineDetail createCustom(@PathVariable String targetSourceId,
            @RequestBody CustomPipelineRequest request) {
        return queryService.toDetail(pipelineCreator.createCustom(targetSourceId,
                request == null ? null : request.tasks()));
    }
}
