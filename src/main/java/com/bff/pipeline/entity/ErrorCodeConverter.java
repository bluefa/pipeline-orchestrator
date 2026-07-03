package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ErrorCode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * task.error_code/task_attempt.error_code 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다.
 * {@link TaskOperationConverter}와 같은 두 가지 붕괴를 막는다: 값 제거/rename에 대한 read 안전
 * ({@link ErrorCode#find}로 읽어 미해석을 null로 열화, admin 조회를 터뜨리지 않는다)과, 값 추가에 대한
 * write 안전(컬럼을 VARCHAR로 만들어 값 집합을 스키마에 새기지 않는다). error_code는 엔진 분기가 아니라
 * 실패 사유 표시용 값이라 null 열화가 안전하다.
 */
@Converter
public class ErrorCodeConverter implements AttributeConverter<ErrorCode, String> {

    @Override
    public String convertToDatabaseColumn(ErrorCode errorCode) {
        return errorCode != null ? errorCode.name() : null;
    }

    @Override
    public ErrorCode convertToEntityAttribute(String stored) {
        return ErrorCode.find(stored).orElse(null);
    }
}
