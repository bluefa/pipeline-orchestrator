package com.bff.pipeline.model;

import com.bff.pipeline.entity.Pipeline;
import com.bff.pipeline.exception.RequestNoteTooLongException;
import com.bff.pipeline.exception.RequestedByRequiredException;
import com.bff.pipeline.exception.RequestedByTooLongException;

/**
 * 실행을 누가 왜 요청했는지다. 실행 기록만 보고는 "이 target에 무엇이 돌았다"까지만 알 수 있고 누가 시켰는지는
 * 알 수 없어, 파이프라인을 만드는 시점에 요청자와 요청 사유를 함께 받아 행에 붙인다. 세 경로(카탈로그·custom·
 * 재시작)가 같은 값을 받고, 재시작은 요청이 비어 있으면 원본에서 승계한다.
 *
 * 두 값 모두 사람이 쓴 값이라 길이를 넘으면 자르지 않고 거절한다. 요청자 이름을 말없이 자르면
 * 감사 기록이 다른 사람을 가리킬 수 있고, 요청 사유를 자르면 하려던 말이 훼손된다. 저장이 깨질까
 * 걱정해 자르는 것보다, 경계에서 되돌려 보내 다시 쓰게 하는 편이 정직하다.
 *
 * 요청자는 승인 게이트가 있는 레시피에서만 필수다({@link #requireRequestedBy()}) — 요청자를 모르는 승인
 * 요청은 감사가 성립하지 않는다. 게이트가 없는 실행에서는 있으면 기록하고 없으면 그만인 선택값이다.
 */
public record RequestContext(String requestedBy, String requestNote) {

    private static final RequestContext NONE = new RequestContext(null, null);

    /**
     * 요청에서 온 두 값을 검증해 담는다. 빈 문자열은 값이 없는 것과 같게 다뤄 저장하지 않는다 —
     * 공백만 넣어 필수 검사를 형식적으로 통과하는 길을 막는다.
     */
    public static RequestContext of(String requestedBy, String requestNote) {
        String requester = blankToNull(requestedBy);
        String note = blankToNull(requestNote);
        if (requester != null && requester.length() > Pipeline.REQUESTED_BY_LENGTH) {
            throw new RequestedByTooLongException(requester.length(), Pipeline.REQUESTED_BY_LENGTH);
        }
        if (note != null && note.length() > Pipeline.REQUEST_NOTE_LENGTH) {
            throw new RequestNoteTooLongException(note.length(), Pipeline.REQUEST_NOTE_LENGTH);
        }
        return new RequestContext(requester, note);
    }

    /** 요청 맥락이 없는 경로(재시작 승계 전 기본값 등)를 위한 빈 값. */
    public static RequestContext none() {
        return NONE;
    }

    /** 승인 게이트가 있는 레시피에서 부른다 — 요청자가 없으면 400으로 거절한다. */
    public RequestContext requireRequestedBy() {
        if (requestedBy == null) {
            throw new RequestedByRequiredException();
        }
        return this;
    }

    /**
     * 이 맥락이 비어 있으면 원본의 것을 승계한다 — 재시작이 쓴다. 재시작 요청이 자기 요청자를 실어 보내면
     * 그 사람이 새 요청자이고, 안 실어 보내면 원본 실행의 요청 맥락을 그대로 이어받는다.
     */
    public RequestContext orInheritFrom(Pipeline origin) {
        if (requestedBy != null || requestNote != null) {
            return this;
        }
        return new RequestContext(origin.getRequestedBy(), origin.getRequestNote());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
