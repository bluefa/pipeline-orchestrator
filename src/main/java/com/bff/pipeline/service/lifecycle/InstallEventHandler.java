package com.bff.pipeline.service.lifecycle;

import com.bff.pipeline.dto.event.InstallEventPayload;
import com.bff.pipeline.entity.InstallEvent;
import com.bff.pipeline.entity.InstallEvent.InstallEventBuilder;
import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.enums.CloudProvider;
import com.bff.pipeline.enums.InstallEventOutcome;
import com.bff.pipeline.enums.PipelineType;
import com.bff.pipeline.exception.PipelineAlreadyActiveException;
import com.bff.pipeline.model.RequestContext;
import com.bff.pipeline.repository.InstallEventRepository;
import com.bff.pipeline.repository.PipelineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 자동 설치 이벤트 한 건을 처리한다. 본문을 읽어 검증하고, 허용된 provider(Azure, IDC)의 INSTALL이면 카탈로그 설치
 * 파이프라인을 요청자 {@code SYSTEM}으로 연다. 어떤 경우든 결과를 install_event 행 하나로 남긴다 — 열었으면 STARTED,
 * 같은 대상에 진행 중인 파이프라인이 있으면 ALREADY_ACTIVE(막고 있던 파이프라인 id를 함께), AWS/GCP는 PROVIDER_HELD,
 * 본문이 잘못됐거나 이벤트의 provider가 카탈로그가 아는 provider와 다르면 INVALID.
 *
 * <p>이미 진행 중인 파이프라인이 있을 때 뒤에 줄을 세우지 않는다. 같은 이벤트의 재전달이면 무시가 맞고, 진짜 새
 * 이벤트라면 사람이 봐야 할 상황이라 기록으로 드러내는 데서 멈춘다(오너 결정 2026-09-02).
 *
 * <p>정상 판정(위 네 결과)은 값으로 돌려주고 ack 대상이다 — 다시 받아도 결과가 같기 때문이다. 반면 provider 조회
 * 실패나 DB 장애처럼 다시 하면 달라질 수 있는 실패는 예외로 전파해 구독자가 nack하게 둔다(재전달).
 * 요청자 {@code SYSTEM}은 콘솔 계정과 구분되는 예약 와이어 값이다(RequestContext 참조).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstallEventHandler {

    /** 자동 설치를 허용하는 provider. AWS/GCP는 서비스 측 리소스 생성에 대한 담당자 검토 문제가 정리될 때까지 보류한다. */
    static final Set<CloudProvider> AUTO_INSTALL_PROVIDERS = EnumSet.of(CloudProvider.AZURE, CloudProvider.IDC);
    /** 자동 설치가 남기는 요청자. 콘솔은 이 값을 「시스템」으로 그린다. */
    static final String SYSTEM_REQUESTER = "SYSTEM";
    static final String AUTO_INSTALL_NOTE = "4단계 진입 자동 설치";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipelineCreator pipelineCreator;
    private final PipelineRepository pipelineRepository;
    private final InstallEventRepository installEventRepository;
    private final Clock clock;

    /** 이벤트 본문(JSON)을 처리하고 남긴 기록 행을 돌려준다. */
    public InstallEvent handle(String messageId, String body) {
        InstallEvent record = decide(body).messageId(messageId).receivedAt(clock.instant()).build();
        log.info("install event {} message={} target={} provider={} pipeline={} reason={}", record.getOutcome(),
                messageId, record.getTarget(), record.getCloudProvider(), record.getPipelineId(), record.getReason());
        return installEventRepository.save(record);
    }

    private InstallEventBuilder decide(String body) {
        InstallEventPayload payload;
        try {
            payload = JSON.readValue(body, InstallEventPayload.class);
        } catch (JsonProcessingException unreadable) {
            return InstallEvent.builder().outcome(InstallEventOutcome.INVALID)
                    .reason(bounded("unreadable payload: " + unreadable.getOriginalMessage()));
        }
        InstallEventBuilder row = InstallEvent.builder()
                .target(payload.targetSourceId()).cloudProvider(payload.cloudProvider());
        if (payload.targetSourceId() == null || payload.targetSourceId().isBlank()) {
            return row.outcome(InstallEventOutcome.INVALID).reason("target_source_id missing");
        }
        if (!PipelineType.INSTALL.name().equals(payload.type())) {
            return row.outcome(InstallEventOutcome.INVALID).reason(bounded("type must be INSTALL, got " + payload.type()));
        }
        Optional<CloudProvider> provider = CloudProvider.find(payload.cloudProvider());
        if (provider.isEmpty()) {
            return row.outcome(InstallEventOutcome.INVALID)
                    .reason(bounded("unknown cloud_provider " + payload.cloudProvider()));
        }
        if (!AUTO_INSTALL_PROVIDERS.contains(provider.get())) {
            return row.outcome(InstallEventOutcome.PROVIDER_HELD).reason(provider.get() + " auto install is on hold");
        }
        return start(row, payload.targetSourceId(), provider.get());
    }

    private InstallEventBuilder start(InstallEventBuilder row, String target, CloudProvider eventProvider) {
        CloudProvider catalogProvider = pipelineCreator.resolveProvider(target);   // 조회 실패는 전파 → nack → 재전달
        if (catalogProvider != eventProvider) {
            return row.outcome(InstallEventOutcome.INVALID)
                    .reason("cloud_provider " + eventProvider + " does not match catalog " + catalogProvider);
        }
        try {
            Pipeline pipeline = pipelineCreator.createForProvider(target, catalogProvider, PipelineType.INSTALL,
                    RequestContext.of(SYSTEM_REQUESTER, AUTO_INSTALL_NOTE));
            return row.outcome(InstallEventOutcome.STARTED).pipelineId(pipeline.getId());
        } catch (PipelineAlreadyActiveException alreadyActive) {
            // target당 활성은 하나뿐이라 가장 최근 행이 곧 막고 있는 파이프라인이다.
            Long blocking = pipelineRepository.findFirstByTargetOrderByCreatedAtDescIdDesc(target)
                    .map(Pipeline::getId).orElse(null);
            return row.outcome(InstallEventOutcome.ALREADY_ACTIVE).pipelineId(blocking);
        }
    }

    private static String bounded(String reason) {
        return reason.length() <= InstallEvent.REASON_LENGTH ? reason : reason.substring(0, InstallEvent.REASON_LENGTH);
    }
}
