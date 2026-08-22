package com.bff.pipeline.entity;

import com.bff.pipeline.enums.ApprovalChannel;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * task_approval.channel 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. 이 값은 표시·감사
 * 전용이라 전이 분기가 읽지 않지만, 변환기가 고치는 문제는 읽는 쪽이 아니라 쓰는 쪽이다:
 * {@code @Enumerated}는 MySQL에서 컬럼을 네이티브 enum으로 만들어, 나중에 경로가 하나 늘 때 스키마 갱신이
 * 그 정의를 넓혀 주지 못하면 새 값의 insert가 통째로 막힌다({@link TaskOperationConverter}와 같은 이유).
 * 승인 경로는 앞으로 늘어날 자리라 더 그렇다.
 *
 * read는 열화하지 않는다 — 해석할 수 없는 값이 들어 있다면 우리가 모르는 무언가가 그 행을 썼다는 뜻이고,
 * 그것을 "경로 없음"으로 조용히 보여 주면 감사 기록이 거짓말을 한다.
 */
@Converter
public class ApprovalChannelConverter implements AttributeConverter<ApprovalChannel, String> {

    @Override
    public String convertToDatabaseColumn(ApprovalChannel channel) {
        return channel != null ? channel.name() : null;
    }

    @Override
    public ApprovalChannel convertToEntityAttribute(String stored) {
        return stored != null ? ApprovalChannel.valueOf(stored) : null;
    }
}
