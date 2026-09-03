package com.bff.pipeline.entity;

import com.bff.pipeline.enums.InstallEventOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 자동 설치 이벤트를 받은 기록이다. 대상이 4단계로 넘어갈 때 발행되는 이벤트 한 건마다 한 행이 남고, 그 이벤트로
 * 설치 파이프라인을 열었는지, 이미 진행 중이라 열지 않았는지, provider 보류인지, 본문이 잘못됐는지를
 * {@code outcome}으로 적는다. "이 대상에 설치 이벤트가 왔는데 왜 안 열렸지?"를 로그 검색 없이 답하기 위한
 * 추가 전용 원장이며, 엔진(claim·스케줄링·상태 전이)은 이 테이블을 읽지 않는다.
 *
 * {@code messageId}는 Pub/Sub 메시지 id다. 같은 메시지가 다시 전달되면(ack 유실) 행이 하나 더 남을 수 있는데,
 * 두 번째 행은 ALREADY_ACTIVE로 기록될 뿐 파이프라인이 중복 생성되지는 않으므로 유니크 제약을 걸지 않았다.
 * {@code cloudProvider}는 이벤트가 보낸 원문 문자열이다 — 카탈로그와 다르거나 모르는 값이어도 그대로 남겨야
 * 발행 측의 오류를 추적할 수 있다.
 */
@Entity
@Table(name = "install_event", indexes = @Index(name = "idx_install_event_target", columnList = "target"))
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class InstallEvent {

    public static final int MESSAGE_ID_LENGTH = 64;
    public static final int TARGET_LENGTH = 128;
    public static final int CLOUD_PROVIDER_LENGTH = 32;
    /** 사람이 읽을 처리 이유(INVALID·PROVIDER_HELD의 근거). 이 길이를 넘으면 잘라 저장한다 — 진단 문자열이라 절단이 무해하다. */
    public static final int REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, updatable = false, length = MESSAGE_ID_LENGTH)
    private String messageId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** 이벤트가 가리킨 target source id. 본문을 못 읽은 이벤트는 null이다. */
    @Column(updatable = false, length = TARGET_LENGTH)
    private String target;

    @Column(name = "cloud_provider", updatable = false, length = CLOUD_PROVIDER_LENGTH)
    private String cloudProvider;

    @Convert(converter = InstallEventOutcomeConverter.class)
    @Column(nullable = false, updatable = false, length = 32)
    private InstallEventOutcome outcome;

    /** STARTED면 연 파이프라인, ALREADY_ACTIVE면 막고 있던 파이프라인. 그 외는 null. */
    @Column(name = "pipeline_id", updatable = false)
    private Long pipelineId;

    @Column(updatable = false, length = REASON_LENGTH)
    private String reason;
}
