package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ApprovalStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * task_approval.status 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. read는 열화하지 않는다:
 * 이 값은 게이트 태스크의 전이를 정하는 판정 어휘라, 미해석 값을 null로 열화하면 뒤따르는 분기가 조용히
 * 어긋난다({@link TaskStatusConverter}와 같은 이유). 변환기가 고치는 건 write 안전뿐이다 — 컬럼을 VARCHAR로
 * 만들어 값이 추가될 때 네이티브 enum 컬럼 정의에 insert가 막히지 않게 한다.
 */
@Converter
public class ApprovalStatusConverter implements AttributeConverter<ApprovalStatus, String> {

    @Override
    public String convertToDatabaseColumn(ApprovalStatus status) {
        return status != null ? status.name() : null;
    }

    @Override
    public ApprovalStatus convertToEntityAttribute(String stored) {
        return stored != null ? ApprovalStatus.valueOf(stored) : null;
    }
}
