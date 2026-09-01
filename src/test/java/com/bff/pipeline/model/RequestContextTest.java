package com.bff.pipeline.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.exception.RequestNoteTooLongException;
import com.bff.pipeline.exception.RequestedByTooLongException;
import org.junit.jupiter.api.Test;

/** 요청 맥락의 경계 규칙 — 무엇을 없는 값으로 볼지, 길면 자를지 거절할지, 재시작이 언제 승계하는지. */
class RequestContextTest {

    /** 사람이 쓴 값은 자르지 않고 되돌려 보낸다 — 잘린 요청자는 다른 사람을 가리킬 수 있다. */
    @Test
    void anOverlongValueIsRejectedNotTruncated() {
        assertThatThrownBy(() -> RequestContext.of("x".repeat(Pipeline.REQUESTED_BY_LENGTH + 1), null))
                .isInstanceOf(RequestedByTooLongException.class);
        assertThatThrownBy(() -> RequestContext.of("admin", "x".repeat(Pipeline.REQUEST_NOTE_LENGTH + 1)))
                .isInstanceOf(RequestNoteTooLongException.class);
    }

    @Test
    void theLengthLimitItselfIsAccepted() {
        RequestContext context = RequestContext.of("x".repeat(Pipeline.REQUESTED_BY_LENGTH),
                "y".repeat(Pipeline.REQUEST_NOTE_LENGTH));

        assertThat(context.requestedBy()).hasSize(Pipeline.REQUESTED_BY_LENGTH);
        assertThat(context.requestNote()).hasSize(Pipeline.REQUEST_NOTE_LENGTH);
    }

    /** 공백만 넣어 값이 있는 척하는 길을 막는다 — 없는 것과 같게 다뤄 저장하지 않는다. */
    @Test
    void aBlankValueIsTheSameAsNoValue() {
        RequestContext context = RequestContext.of("   ", "\t\n");

        assertThat(context.requestedBy()).isNull();
        assertThat(context.requestNote()).isNull();
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(RequestContext.of("  admin@example.com  ", null).requestedBy())
                .isEqualTo("admin@example.com");
    }

    /** 승계는 맥락이 통째로 비었을 때만이다 — 필드별로 섞으면 요청자와 사유가 서로 다른 사람의 것이 된다. */
    @Test
    void inheritanceIsAllOrNothing() {
        Pipeline origin = Pipeline.builder().requestedBy("first").requestNote("먼저 요청한 건").build();

        assertThat(RequestContext.none().orInheritFrom(origin).requestedBy()).isEqualTo("first");
        assertThat(RequestContext.of("second", null).orInheritFrom(origin).requestNote()).isNull();
    }
}
