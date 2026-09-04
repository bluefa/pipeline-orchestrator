package com.bff.pipeline.entity;

import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.PipelineStatus;
import com.bff.pipeline.enums.PipelineType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * 하나의 target에 대한 한 번의 실행(run)을 나타내는 행이다(ADR-016 §2, §4). 이 행 자체가 곧 상태(state)다.
 *
 * <p><b>target당 활성 pipeline은 하나</b>라는 불변식은 {@code active_target} 컬럼의 유일 제약(unique constraint)
 * 하나로 강제한다. MySQL은 부분(filtered) 유일 인덱스를 지원하지 않으므로 {@code WHERE status non-terminal} 같은
 * 인덱스 대신 이 컬럼이 불변식을 직접 짊어진다. pipeline이 비종료(non-terminal) 상태인 동안에는
 * {@code active_target = target}이고, 종료되면 {@code NULL}로 비운다. MySQL은 유일 인덱스에 여러 NULL을 허용하니
 * 종료된 행끼리는 서로 부딪히지 않고, 특정 target의 비종료 행은 언제나 최대 하나뿐이다. 이 컬럼은 상태 변경과 같은
 * 트랜잭션 안에서 애플리케이션이 직접 관리한다 — 생성 시 채우고 cancel·converge 시 비운다.
 */
@Entity
@Table(
        name = "pipeline",
        uniqueConstraints = @UniqueConstraint(name = Pipeline.ACTIVE_TARGET_CONSTRAINT, columnNames = "active_target"),
        indexes = {
                @Index(name = "idx_pipeline_claim", columnList = "status, next_due_at"),
                @Index(name = "idx_pipeline_claimed_until", columnList = "claimed_until"),
                // Admin 조회 API(P2/P3/P7) 지원: 상태별 기간 집계·목록, target 이력 최신순.
                @Index(name = "idx_pipeline_status_created", columnList = "status, created_at"),
                @Index(name = "idx_pipeline_target_created", columnList = "target, created_at"),
                // ponytail: ~2,000행 규모엔 (notified_at, notify_next_at) 복합이면 충분. MySQL8은 부분(filtered)
                // 인덱스가 없으므로 status 필터는 옵티마이저에 맡긴다. 대규모로 커지면 재검토.
                @Index(name = "idx_pipeline_notify", columnList = "notified_at, notify_next_at"),
                // 재시작 역링크 조회(origin_pipeline_id = :id인 최신 행) 지원.
                @Index(name = "idx_pipeline_origin", columnList = "origin_pipeline_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Pipeline {

    public static final String ACTIVE_TARGET_CONSTRAINT = "uq_pipeline_active_target";

    /** 요청자 컬럼 길이. 넘는 값은 자르지 않고 요청 자체를 거절한다(사람이 쓴 값을 말없이 훼손하지 않는다). */
    public static final int REQUESTED_BY_LENGTH = 100;

    /** 요청 사유 컬럼 길이. 승인자에게 남기는 한두 문단 분량을 담는다. */
    public static final int REQUEST_NOTE_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 파이프라인 유형의 write-once 캐시(varchar 저장, {@link PipelineTypeConverter}). create 시점에 recipe를
     * 고르는 데만 쓰이고 이후로는 표시용이라, 미해석 옛 값은 null로 열화한다({@code updatable = false}가
     * write-once를 강제해 열화된 행에도 저장된 옛 값이 보존된다).
     */
    @Convert(converter = PipelineTypeConverter.class)
    @Column(nullable = false, updatable = false, length = 16)
    private PipelineType type;

    @Column(nullable = false)
    private String target;

    /**
     * create 시점에 targetSourceId로 조회해 저장하는 cloud provider의 write-once 캐시(varchar 저장,
     * {@link CloudProviderConverter}). recipe 선택·표시용, 격리 축 아님. nullable(데모/drain). 미해석 옛 값은
     * null로 열화한다.
     */
    @Convert(converter = CloudProviderConverter.class)
    @Column(name = "cloud_provider", updatable = false, length = 16)
    private CloudProvider cloudProvider;

    /** 이 실행을 만든 RecipeDefinition 상수 이름. Admin API가 metadata를 조인하는 링크. nullable(데모/drain). */
    @Column(name = "recipe_definition")
    private String recipeDefinition;

    /** pipeline 생명주기 상태(varchar 저장, {@link PipelineStatusConverter}). 엔진 claim/전이 분기에 직접 쓰이므로 read는 fail-fast를 유지한다. */
    @Convert(converter = PipelineStatusConverter.class)
    @Column(nullable = false, length = 16)
    private PipelineStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastActivityAt;

    @Column(name = "active_target")
    private String activeTarget;

    // ── 요청 맥락: 누가 왜 이 실행을 요청했는가. 생성 시 한 번 채우고 바꾸지 않는다. ──

    /**
     * 이 실행을 요청한 사람. 검증된 계정에서 BFF가 채워 보내며 오케스트레이터는 기록만 한다. 실행 기록만으로는
     * 무엇이 돌았는지까지만 알 수 있어, 시킨 사람을 행에 함께 남긴다.
     */
    @Column(name = "requested_by", updatable = false, length = REQUESTED_BY_LENGTH)
    private String requestedBy;

    /**
     * 요청자가 이 실행에 남기는 사유. 자유 텍스트라 길이를 경계에서 검증하고(초과는 거절),
     * 저장 후에는 바꾸지 않는다.
     */
    @Column(name = "request_note", updatable = false, length = REQUEST_NOTE_LENGTH)
    private String requestNote;

    /**
     * 이 실행이 재시작한 원본 파이프라인 id. 재시작이 아니면 null. 표시용 계보 메타데이터라 엔진(claim·전이·
     * reconciler)은 읽지 않고, FK 제약도 걸지 않는다 — 원본 행이 사라져도 조회가 null-safe로 계보 표시만
     * 생략한다(카탈로그 이름 열화와 같은 태도).
     */
    @Column(name = "origin_pipeline_id", updatable = false)
    private Long originPipelineId;

    // ── ADR-021 실행 좌표(execution-coordination) 컬럼: claim/lease/cooperative-cancel. 도메인 상태와는 분리된다. ──

    /**
     * 다음 claim 대상 시각. 과거(&lt;= now)면 due다. 생성 시 {@code now}로 시딩하고 성공 report/reschedule가 앞으로
     * 당기므로 항상 non-null이다(claim predicate {@code next_due_at <= now}는 null을 due로 보지 않아, null이면 영영 unclaimed).
     */
    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** claim할 때마다 새로 발급하는 fencing token(UUID). write-back 트랜잭션의 guarded write-back 소유권 키다. */
    @Column(name = "claimed_by")
    private String claimedBy;

    /** lease 만료 시각. {@code < now}면 claim이 저절로 풀려 다음 스캔이 reclaim한다(ADR-021 Decision 5). */
    @Column(name = "claimed_until")
    private Instant claimedUntil;

    /** cooperative cancel(Case B) 플래그. claim을 쥔 워커가 안전지점에서 읽어 스스로 CANCELLED를 적용한다. */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    // ── ADR-022 종단 알림 메타데이터: 도메인 상태가 아니다. reconciler·실행 점유·상태 전이는 이 필드들을 읽지 않는다. ──

    /** 알림 전달이 완료된(전송처가 성공으로 응답한) 시각. 값이 차면 이 행은 알림 대상에서 영구히 빠진다. */
    @Column(name = "notified_at")
    private Instant notifiedAt;

    /** 전송 실패 후 다음 재시도 시각. 이 시각이 오기 전에는 다시 잡지 않는다. */
    @Column(name = "notify_next_at")
    private Instant notifyNextAt;

    /**
     * 알림 전송을 시도한 횟수. 재시도 간격 계산에 쓰고, 상한에 닿으면 자동 재시도를 멈춘다.
     * 상한에 닿은 행을 다시 보내려면 사람이 이 값을 0으로 되돌리면 된다.
     */
    @Column(name = "notify_attempts", nullable = false)
    @Builder.Default
    private int notifyAttempts = 0;
}
