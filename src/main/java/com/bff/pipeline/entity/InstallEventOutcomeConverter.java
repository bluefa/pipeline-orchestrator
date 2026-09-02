package com.bff.pipeline.entity;

import com.bff.pipeline.enums.InstallEventOutcome;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * install_event.outcome 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. {@link CloudProviderConverter}와
 * 같은 이유로, 컬럼을 VARCHAR로 만들어 값 집합을 스키마에 새기지 않고 미해석 값은 null로 열화해 읽기를 터뜨리지 않는다.
 */
@Converter
public class InstallEventOutcomeConverter implements AttributeConverter<InstallEventOutcome, String> {

    @Override
    public String convertToDatabaseColumn(InstallEventOutcome outcome) {
        return outcome != null ? outcome.name() : null;
    }

    @Override
    public InstallEventOutcome convertToEntityAttribute(String stored) {
        return InstallEventOutcome.find(stored).orElse(null);
    }
}
