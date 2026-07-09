package com.bff.pipeline.model;

import com.bff.pipeline.dto.NotifyPayload;

/**
 * 알림 한 건의 점유 결과를 담아 나르는 내부 값이다.
 * 점유 트랜잭션({@code NotifyClaimer})이 만들어 {@code NotifyScheduler}에 넘긴다.
 * 담는 것: 점유한 파이프라인의 {@code pipelineId}, 이번 점유에 새로 발급된 점유 확인용 {@code token},
 * 그리고 점유 트랜잭션 안에서 이미 커밋된 pipeline/task 행으로 만든 전송 내용 {@code payload}.
 * 전송 결과 기록({@code NotifyWriteBack})은 이 {@code token}이 행의 {@code pipeline.notify_claimed_by}와
 * 일치할 때만 기록을 허용한다.
 * 외부로 전송되는 값이 아니라서 dto가 아닌 model 패키지에 둔다(밖으로 나가는 것은 payload뿐이다).
 */
public record NotifyClaim(long pipelineId, String token, NotifyPayload payload) { }
