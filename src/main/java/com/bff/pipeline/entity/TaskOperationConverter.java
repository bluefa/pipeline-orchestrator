package com.bff.pipeline.entity;

import com.bff.pipeline.enums.TaskOperation;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * task.operation 컬럼의 String 변환기다 — {@code @Enumerated(STRING)} 대신 쓴다. 두 가지 붕괴를 막는다.
 *
 * 첫째, 값 제거/rename에 대한 read 안전. {@code @Enumerated}는 저장된 이름이 enum에 없으면 조회 자체가
 * 예외로 터져, terminal history 행 하나가 admin 조회와 worker 클레임 경로를 전부 깨뜨린다. 여기서는
 * {@link TaskOperation#find}로 읽어 미해석을 null로 열화한다 — 실행 경로는 StepRunner의 row 캐시 대조가
 * null을 정의 불일치로 보고 UNKNOWN_TASK로 끊고, 조회 경로는 null을 그대로 노출한다(task_definition
 * 이름이 남아 있어 식별은 유지된다).
 *
 * 둘째, 값 추가에 대한 write 안전. {@code @Enumerated(STRING)}는 MySQL에서 네이티브 enum(...) 컬럼을
 * 만들 수 있고, ddl-auto는 기존 컬럼 정의를 바꾸지 않으므로 enum에 값을 추가할 때마다 insert가 깨질 수
 * 있다. 변환기는 컬럼을 VARCHAR로 만들어 값 집합을 스키마에 새기지 않는다. (이미 enum(...)으로 생성된
 * 기존 DB는 수동 ALTER가 한 번 필요하다: {@code ALTER TABLE task MODIFY COLUMN operation VARCHAR(64) NOT NULL}.)
 */
@Converter
public class TaskOperationConverter implements AttributeConverter<TaskOperation, String> {

    @Override
    public String convertToDatabaseColumn(TaskOperation operation) {
        return operation != null ? operation.name() : null;
    }

    @Override
    public TaskOperation convertToEntityAttribute(String stored) {
        return TaskOperation.find(stored).orElse(null);
    }
}
