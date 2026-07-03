package com.bff.pipeline.entity;

import com.bff.pipeline.enums.CloudProvider;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * pipeline.cloud_provider 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다.
 * {@link TaskOperationConverter}와 같은 두 가지 붕괴를 막는다: 값 제거/rename에 대한 read 안전
 * ({@link CloudProvider#find}로 읽어 미해석을 null로 열화, admin 조회를 터뜨리지 않는다)과, 값 추가에 대한
 * write 안전(컬럼을 VARCHAR로 만들어 값 집합을 스키마에 새기지 않는다). cloud_provider는 recipe 선택·표시용
 * 메타데이터일 뿐 격리 축이 아니라 null 열화가 안전하다.
 */
@Converter
public class CloudProviderConverter implements AttributeConverter<CloudProvider, String> {

    @Override
    public String convertToDatabaseColumn(CloudProvider cloudProvider) {
        return cloudProvider != null ? cloudProvider.name() : null;
    }

    @Override
    public CloudProvider convertToEntityAttribute(String stored) {
        return CloudProvider.find(stored).orElse(null);
    }
}
