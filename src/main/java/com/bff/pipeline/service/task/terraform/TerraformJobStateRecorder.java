package com.bff.pipeline.service.task.terraform;

import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.TerraformJobState;
import com.bff.pipeline.model.TerraformJobRef;
import com.bff.pipeline.repository.TerraformJobStateRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 진행-시점(in-progress) job 상태 관찰 recorder다 — attempt가 job 상태를 폴하는 매 turn에 관측된 job별 상태를
 * {@code terraform_job_state}에 제자리 upsert한다({@link TerraformResultRecorder}가 종결 후 로그를 담는 것과 대칭).
 *
 * 상태 무관여가 계약이다({@code terraform_result}와 동일). run 단계(tx 밖)에서 best-effort로 쓰이며 어떤 저장 실패도
 * 태스크 판정을 바꾸지 않는다 — 저장이 write-back을 막으면 pipeline이 lease 회수 → 재크래시 루프에 갇히므로,
 * 관찰 결손으로 강등해 로그만 남긴다. 유니크 키 {@code (task, attempt, job)}가 재실행(크래시/리스 회수 후 re-poll)
 * 멱등의 근거이고, 동시 중복 insert는 기대된 경합이라 조용히 삼킨다. {@code lastState}/{@code lastFailReason}은 매 폴
 * "마지막 관측"으로 덮어써 최신 폴의 관점을 유지한다(정상 폴은 {@code lastError}를 지워 그 폴에 오류가 없었음을 나타낸다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerraformJobStateRecorder {

    private final TerraformJobStateRepository repository;
    private final Clock clock;

    /**
     * 정상 폴 1회의 job 상태를 upsert한다 — 원시 상태·실패 사유·응답 원문을 최신값으로 덮고, 이 폴엔 호출 오류가
     * 없으니 {@code lastError}를 지운다. 원문(response)은 TEXT 컬럼이라 clamp하지 않는다.
     */
    public void recordObserved(TerraformJobRef job, TerraformPoll poll) {
        upsert(job, row -> {
            row.setLastState(clamp(poll.state(), TerraformJobState.STATE_LENGTH));
            row.setLastFailReason(clamp(poll.failReason(), TerraformJobState.DETAIL_LENGTH));
            row.setLastResponse(poll.response());
            row.setLastError(null);
        });
    }

    /** 폴 호출 자체가 실패한 job의 오류를 upsert한다 — {@code lastState}/{@code lastFailReason}은 직전 관측을 유지하고 {@code lastError}만 채운다. */
    public void recordCallError(TerraformJobRef job, String message) {
        upsert(job, row -> row.setLastError(clamp(message, TerraformJobState.DETAIL_LENGTH)));
    }

    private void upsert(TerraformJobRef job, Consumer<TerraformJobState> mutation) {
        try {
            TerraformJobState row = repository
                    .findByTaskIdAndAttemptNumberAndJobId(job.taskId(), job.attemptNumber(), job.jobId())
                    .orElseGet(() -> newRow(job.taskId(), job.attemptNumber(), job.jobId()));
            mutation.accept(row);
            row.setPollCount(row.getPollCount() + 1);
            row.setLastPolledAt(clock.instant());
            save(row);
        } catch (RuntimeException failure) {
            // harness-allow: targeted-catch — 관찰 전용 계약의 경계다. 어떤 기록 실패도 태스크 판정을 바꾸지 않는다.
            // 판정을 막으면 write-back이 불발돼 lease 회수 → 재크래시 루프에 갇히므로 관찰 결손으로 강등하고 소리 내어 남긴다.
            log.error("{}: terraform job state recording failed — observation lost", job, failure);
        }
    }

    private static TerraformJobState newRow(Long taskId, int attemptNumber, String jobId) {
        return TerraformJobState.builder()
                .taskId(taskId)
                .attemptNumber(attemptNumber)
                .jobId(jobId)
                .pollCount(0)
                .build();
    }

    private void save(TerraformJobState row) {
        try {
            repository.save(row);
        } catch (DataIntegrityViolationException violation) {
            // 유니크 제약(동시 중복 insert)은 기대된 멱등 경합이라 조용히 삼킨다. 그 밖의 무결성 위반은 상위 catch가 관찰 결손으로 강등한다.
            if (!isAttemptJobDuplicate(violation)) {
                throw violation;
            }
            log.debug("task {} attempt {} job {}: terraform job state already recorded concurrently",
                    row.getTaskId(), row.getAttemptNumber(), row.getJobId());
        }
    }

    private static boolean isAttemptJobDuplicate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && namesAttemptJobConstraint(constraintViolation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesAttemptJobConstraint(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(TerraformJobState.ATTEMPT_JOB_CONSTRAINT);
    }

    /** 외부 응답값은 컬럼 길이를 넘으면 잘라 저장이 무결성 위반으로 깨지지 않게 방어한다. */
    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
