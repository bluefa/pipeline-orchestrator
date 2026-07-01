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
}
