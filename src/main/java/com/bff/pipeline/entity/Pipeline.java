package com.bff.pipeline.entity;

import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 하나의 target에 대한 하나의 실행(run)을 나타내는 행(row)이다(ADR-016 §2, §4). 이 행 자체가 상태(state)이다.
 *
 * <p><b>target당 하나의 활성 pipeline</b> 불변식은 {@code active_target} 컬럼의 단일 유일 제약(unique constraint)으로
 * 강제된다. MySQL은 부분(filtered) 유일 인덱스를 지원하지 않으므로, {@code WHERE status non-terminal} 형식의 인덱스 대신
 * 이 컬럼이 불변식을 직접 담는다: pipeline이 비종료(non-terminal) 상태인 동안에는 {@code active_target = target}이며,
 * 종료(terminates) 시에는 {@code NULL}로 설정된다. MySQL은 유일 인덱스에서 다수의 NULL을 허용하므로 종료된 행들은
 * 서로 충돌하지 않으며, 주어진 target에 대해 비종료 행은 최대 하나만 존재할 수 있다. 이 컬럼은 상태 변경과 동일한
 * 트랜잭션 내에서 애플리케이션이 유지 관리한다(생성 시 설정, cancel 및 converge 시 초기화).
 */
@Entity
@Table(
        name = "pipeline",
        uniqueConstraints = @UniqueConstraint(name = Pipeline.ACTIVE_TARGET_CONSTRAINT, columnNames = "active_target"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Pipeline {

    public static final String ACTIVE_TARGET_CONSTRAINT = "uq_pipeline_active_target";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineType type;

    @Column(nullable = false)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActivityAt;

    @Column(name = "active_target")
    private String activeTarget;
}
