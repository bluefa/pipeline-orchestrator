package com.bff.pipeline.enums;

import com.bff.pipeline.exception.InvalidStatisticsPeriodException;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;

/**
 * 대시보드 기간 통계(P2)의 조회 창을 나타낸다. 와이어 토큰("1h"/"1d"/"7d")과 실제 기간을 함께 들고,
 * now 기준 하한(since = now - window)을 계산하는 축을 한곳에 둔다. 알 수 없는 토큰은
 * {@link InvalidStatisticsPeriodException}(400)으로 거절한다.
 */
public enum StatisticsPeriod {

    ONE_HOUR("1h", Duration.ofHours(1)),
    ONE_DAY("1d", Duration.ofDays(1)),
    SEVEN_DAYS("7d", Duration.ofDays(7));

    private final String token;
    private final Duration window;

    StatisticsPeriod(String token, Duration window) {
        this.token = token;
        this.window = window;
    }

    @JsonValue
    public String token() {
        return token;
    }

    public Duration window() {
        return window;
    }

    public static StatisticsPeriod fromToken(String token) {
        for (StatisticsPeriod period : values()) {
            if (period.token.equals(token)) {
                return period;
            }
        }
        throw new InvalidStatisticsPeriodException(token);
    }
}
