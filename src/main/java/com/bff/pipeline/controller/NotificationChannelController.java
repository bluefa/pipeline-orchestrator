package com.bff.pipeline.controller;

import com.bff.pipeline.dto.ChannelUpsert;
import com.bff.pipeline.dto.ChannelView;
import com.bff.pipeline.dto.NotifyHealthView;
import com.bff.pipeline.dto.TestResult;
import com.bff.pipeline.service.notify.NotificationChannelService;
import com.bff.pipeline.service.notify.SlackNotifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * Admin의 Slack 알림 채널 관리 REST 컨트롤러다(ADR-022 §6.1). 단일 sink이므로 단수 리소스이며, 얇은
 * 어댑터로서 설정 읽기/upsert/건강 조회는 {@link NotificationChannelService}에 위임한다. 검증 실패
 * (webhook SSRF, enabled 누락, 컬럼 길이 초과)의 typed 400 매핑은 GlobalAdvice가 한곳에서 처리한다.
 *
 * 건강 조회({@code GET …/health})는 give-up 경보(배포 게이트)의 정규 소스인 DB 파생 술어의 HTTP 표면이다 —
 * 조직 alerting 스택/admin 대시보드가 주기 폴링한다(ADR-022 §4, 구현 명세 §7).
 *
 * 테스트 전송({@code POST …/test})만 예외 규약이 다르다 — 전달 실패는 probe 결과이지 서버 오류가 아니라서
 * {@link SlackNotifier}의 {@code RestClientException}을 여기서 잡아 {@code delivered=false} 본문으로
 * 항상 200을 내린다. gate는 webhook 설정 기준이다 — webhook만 저장돼 있으면 비활성 채널에서도 probe를
 * 실행한다("테스트 후 활성화" 순서 지원). 미설정(행 없음 또는 webhook 없음)이면 전송 시도 없이
 * "channel not configured"를 돌려준다.
 */
@RestController
@RequestMapping("/api/v1/admin/notification-channel")
public class NotificationChannelController {

    static final String CHANNEL_NOT_CONFIGURED_ERROR = "channel not configured";

    private final NotificationChannelService channelService;
    private final SlackNotifier slackNotifier;

    public NotificationChannelController(NotificationChannelService channelService, SlackNotifier slackNotifier) {
        this.channelService = channelService;
        this.slackNotifier = slackNotifier;
    }

    @GetMapping
    public ChannelView view() {
        return channelService.view();
    }

    @PutMapping
    public ChannelView upsert(@RequestBody ChannelUpsert request) {
        return channelService.upsert(request);
    }

    @GetMapping("/health")
    public NotifyHealthView health() {
        return channelService.health();
    }

    @PostMapping("/test")
    public TestResult test() {
        return channelService.configuredWebhookUrl()
                .map(this::probeDelivery)
                .orElseGet(() -> new TestResult(false, CHANNEL_NOT_CONFIGURED_ERROR));
    }

    private TestResult probeDelivery(String webhookUrl) {
        try {
            slackNotifier.deliverTest(webhookUrl);
            return new TestResult(true, null);
        } catch (RestClientException deliveryFailed) {
            return new TestResult(false, sanitizedFailureMessage(deliveryFailed, webhookUrl));
        }
    }

    /**
     * probe 실패 메시지에서 webhook 원문을 지운다 — {@code ResourceAccessException} 등 RestClient 예외
     * 메시지는 요청 URL 전문을 싣는데, webhook은 secret이라 조회 응답과 마찬가지로 원문을 내리지 않는다.
     */
    private static String sanitizedFailureMessage(RestClientException deliveryFailed, String webhookUrl) {
        String message = deliveryFailed.getMessage();
        if (message == null) {
            return deliveryFailed.getClass().getSimpleName();
        }
        return message.replace(webhookUrl, "<webhook>");
    }
}
