package com.bff.pipeline.entity;

import com.bff.pipeline.enums.PipelineStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * pipeline.status 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. {@link TaskOperationConverter}와
 * 달리 read는 열화하지 않는다: status는 엔진이 claim/전이 분기에 직접 쓰는 상태 머신 어휘라, 미해석 값을 null로
 * 열화하면 {@code isTerminal()} 같은 후속 호출이 예측 불가능하게 깨진다 — 차라리 {@code @Enumerated}와 같은
 * fail-fast(예외)로 그 행 하나만 즉시 드러내는 편이 안전하다. 이 변환기가 고치는 건 write 안전뿐이다:
 * 컬럼을 VARCHAR로 만들어, 상태 값이 추가될 때 네이티브 enum(...) 컬럼 정의에 insert가 막히지 않게 한다.
 */
@Converter
public class PipelineStatusConverter implements AttributeConverter<PipelineStatus, String> {

    @Override
    public String convertToDatabaseColumn(PipelineStatus status) {
        return status != null ? status.name() : null;
    }

    @Override
    public PipelineStatus convertToEntityAttribute(String stored) {
        return stored != null ? PipelineStatus.valueOf(stored) : null;
    }
}
