package com.bff.pipeline.exception;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineType;
import org.springframework.http.HttpStatus;

/**
 * (cloud provider, pipeline type) 조합에 대응하는 recipe가 카탈로그에 없다. 400 Bad Request + code
 * {@code ORCHESTRATION_UNSUPPORTED_RECIPE}로 매핑된다.
 */
public class UnsupportedRecipeException extends OrchestrationException {

    public UnsupportedRecipeException(CloudProvider provider, PipelineType type) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.UNSUPPORTED_RECIPE,
                "no recipe for provider " + provider + " and type " + type);
    }

    /** provider와 무관하게 카탈로그 recipe가 없는 type(예: CUSTOM). custom 실행은 {@code /custom} 엔드포인트를 쓴다. */
    public UnsupportedRecipeException(PipelineType type) {
        super(HttpStatus.BAD_REQUEST, OrchestrationErrorCode.UNSUPPORTED_RECIPE,
                "type " + type + " has no catalog recipe; use the custom endpoint for custom execution");
    }
}
