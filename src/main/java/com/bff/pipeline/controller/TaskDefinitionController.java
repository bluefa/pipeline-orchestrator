package com.bff.pipeline.controller;

import com.bff.pipeline.dto.pipeline.TaskCatalogResponse;
import com.bff.pipeline.enums.CloudProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TaskDefinition 카탈로그 조회 REST 컨트롤러다(LIN-27). Custom Recipe 빌더(B2)가 provider별 Task 목록을 채우는 데 쓴다.
 * {@code provider} 쿼리 파라미터는 선택이며(미지정 시 전체), 잘못된 값은 Spring 바인딩이 400으로 거절한다
 * (GlobalAdvice의 MethodArgumentTypeMismatch 매핑). 카탈로그는 정적 enum이라 서비스 계층 없이 DTO 팩토리로 만든다.
 */
@RestController
@RequestMapping("/api/v1/task-definitions")
public class TaskDefinitionController {

    @GetMapping
    public TaskCatalogResponse list(@RequestParam(required = false) CloudProvider provider) {
        return TaskCatalogResponse.of(provider);
    }
}
