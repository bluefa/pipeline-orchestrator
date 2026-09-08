package com.bff.pipeline.utils;

import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TaskConfirmationInput;
import com.bff.pipeline.client.InstallationOperationsClient.VerifiedContext;
import com.bff.pipeline.model.HttpExchange;
import com.bff.pipeline.exception.CallFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 응답 원문을 UTF-8 바이트 상한 안에서 보존하고 진단 metadata만 자른다. 승인/세대 정체성은 자르지 않고
 * 유효성을 검사한다. digest는 전송할 원문 그대로 계산하므로 JSON 재직렬화가 개입하지 않는다.
 */
public final class HttpResponses {
    public static final String RECOMMENDATION_GET = "RECOMMENDATION_GET";
    public static final String CONFIRMATION_POST = "CONFIRMATION_POST";
    public static final String CONFIRMATION_DELETE = "CONFIRMATION_DELETE";
    public static final String TEST_CONNECTION_START = "TEST_CONNECTION_START";
    public static final String TEST_CONNECTION_POLL = "TEST_CONNECTION_POLL";
    private HttpResponses() { }

    /** 원래 인코딩을 검사한 뒤 길이를 제한한 응답만 반환한다. 오류 응답의 상태 분류는 본문 크기와 독립적이다. */
    public static HttpExchange capture(HttpExchange response, String operation, int maximumBytes) {
        HttpExchange exchange = bounded(response, operation, maximumBytes);
        if (exchange == null || exchange.statusCode() == null) {
            throw new CallFailedException("Missing HTTP response status", false, exchange);
        }
        if (exchange.statusCode() >= 200 && exchange.statusCode() <= 299
                && !supportedEncoding(response.contentType())) {
            throw new CallFailedException("Unsupported HTTP response encoding", false, exchange);
        }
        return exchange;
    }

    /** 기대 상태가 아니면 429와 5xx만 재시도 가능한 외부 호출 오류로 분류한다. */
    public static void requireStatus(HttpExchange exchange, int expectedStatus) {
        int status = exchange.statusCode();
        if (status != expectedStatus) {
            boolean retryable = status == 429 || status >= 500 && status <= 599;
            throw new CallFailedException("Unexpected installation HTTP status " + status, retryable, exchange);
        }
    }

    private static boolean supportedEncoding(String contentType) {
        if (contentType == null) return true;
        String normalized = contentType.toLowerCase(Locale.ROOT).replace(" ", "").replace("\"", "");
        int charset = normalized.indexOf("charset=");
        if (charset < 0) return true;
        try {
            return StandardCharsets.UTF_8.equals(Charset.forName(normalized.substring(charset + 8).split(";", 2)[0]));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException unsupported) {
            return false;
        }
    }

    public static HttpExchange bounded(HttpExchange exchange, String operation, int maximumBytes) {
        if (!Set.of(RECOMMENDATION_GET, CONFIRMATION_POST, CONFIRMATION_DELETE,
                TEST_CONNECTION_START, TEST_CONNECTION_POLL).contains(operation)) {
            throw new IllegalArgumentException("HTTP operation must be a fixed installation operation");
        }
        if (exchange == null) return null;
        String body = exchange.body() == null && exchange.statusCode() != null ? "" : exchange.body();
        String bounded = truncateUtf8(body, maximumBytes);
        return HttpExchange.builder().operation(operation).statusCode(exchange.statusCode())
                .contentType(clamp(exchange.contentType(), TaskAttempt.CONTENT_TYPE_LENGTH))
                .body(bounded).receivedAt(exchange.receivedAt())
                .truncated(exchange.truncated() || !Objects.equals(body, bounded)).build();
    }

    public static String truncateUtf8(String value, int maximumBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) return value;
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > maximumBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    public static boolean validContext(VerifiedContext context) {
        return context != null && validIdentity(context.approvalVersion()) && validIdentity(context.targetGeneration());
    }

    private static boolean validIdentity(String value) {
        return value != null && !value.isBlank() && value.length() <= TaskConfirmationInput.CONTEXT_VALUE_LENGTH;
    }

    public static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public static String clamp(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        int end = maximumLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
