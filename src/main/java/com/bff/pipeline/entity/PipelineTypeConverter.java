package com.bff.pipeline.entity;

import com.bff.pipeline.enums.PipelineType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * pipeline.type 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. {@link TaskOperationConverter}와
 * 같은 두 가지 붕괴를 막는다: 값 제거/rename에 대한 read 안전({@link PipelineType#find}로 읽어 미해석을
 * null로 열화, admin 조회를 터뜨리지 않는다)과, 값 추가에 대한 write 안전(컬럼을 VARCHAR로 만들어 값 집합을
 * 스키마에 새기지 않는다). type은 create 시점에 recipe를 고르는 데만 쓰이고 이후로는 표시용 값이라 null
 * 열화가 안전하다.
 */
@Converter
public class PipelineTypeConverter implements AttributeConverter<PipelineType, String> {

    @Override
    public String convertToDatabaseColumn(PipelineType type) {
        return type != null ? type.name() : null;
    }

    @Override
    public PipelineType convertToEntityAttribute(String stored) {
        return PipelineType.find(stored).orElse(null);
    }
}
