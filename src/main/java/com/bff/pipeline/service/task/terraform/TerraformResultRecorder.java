package com.bff.pipeline.service.task.terraform;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TerraformResult;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.repository.TerraformResultRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * postCheck 관찰(확장 A) recorder다 — attempt가 판정으로 종결되는 turn에 finished job들의 result(= terraform log)를
 * 조회해 {@code terraform_result}에 남긴다(docs/terraform-client-and-postcheck-design.md §4.4의 쓰기 경로 정의).
 *
 * 상태 무관여가 계약이다. 여기서 일어나는 어떤 조회·저장 실패도 태스크 판정을 바꾸지 않는다 — job별 경계
 * catch가 이 계약을 강제한다: {@code CallInterruptedException}만 전파하고(인터럽트 의미 보존 — 기록은 상태
 * 전이 커밋 전이라 다음 폴 turn이 같은 판정을 재실행하고, 유니크 키 + 존재 선검사가 이미 저장된 행을
 * 건너뛴다), 그 밖의 모든 실패는 관찰 결손으로 강등해 로그만 남긴다. 외부 응답값(result 본문, resultPath)이
 * 저장을 깨뜨려 write-back 자체를 막으면 pipeline이 lease 회수 → 재크래시 루프에 갇히므로, 관찰 전용
 * 컴포넌트에서는 fail-fast보다 이 계약이 우선한다(중복 insert는 debug, 그 외 저장 실패는 error 로그로 노출).
 * job별로 독립 수행해 한 job의 실패가 나머지 job의 저장을 막지 않으며, 조회 실패 job도 본문 없는 포인터 행
 * ({@code result = null} + {@code resultPath})으로 남긴다.
 */
@Slf4j
@Component
public class TerraformResultRecorder {

    /** utf8mb4 최악 4B/char ⇒ MEDIUMTEXT(16MB) 안전 상한. 초과분은 tail 우선 절단 — 실패 원인은 로그 끝에 몰린다. */
    static final int MAX_RESULT_CHARS = 4_000_000;

    private final InfraManagerClient infraManagerClient;
    private final TerraformResultRepository repository;
    private final Clock clock;

    public TerraformResultRecorder(InfraManagerClient infraManagerClient, TerraformResultRepository repository,
            Clock clock) {
        this.infraManagerClient = infraManagerClient;
        this.repository = repository;
        this.clock = clock;
    }

    /** 종결 turn에 finished로 관측된 job들의 result를 job별 독립으로 기록한다. 실패는 관찰 결손일 뿐이다. */
    public void recordFinishedJobs(Task task, TaskAttempt attempt, Map<String, TerraformPoll> finishedPolls) {
        for (Map.Entry<String, TerraformPoll> finished : finishedPolls.entrySet()) {
            try {
                record(task, attempt, finished.getKey(), finished.getValue());
            } catch (CallInterruptedException interrupted) {
                throw interrupted;   // 인터럽트 의미 보존 — 다음 폴 turn의 재실행이 자기치유한다(클래스 javadoc)
            } catch (RuntimeException failure) {
                // harness-allow: targeted-catch — 관찰 전용 계약의 경계다(인터럽트는 위에서 재전파, 원인은 error 로그).
                // 어떤 기록 실패도 태스크 판정을 바꾸지 않는다. 판정을 막으면 write-back이 불발돼
                // lease 회수 → 재크래시 루프에 갇히므로, 여기서 관찰 결손으로 강등하고 소리 내어 남긴다.
                log.error("task {} attempt {} job {}: terraform result recording failed — observation lost",
                        task.getId(), attempt.getAttemptNumber(), finished.getKey(), failure);
            }
        }
    }

    private void record(Task task, TaskAttempt attempt, String jobId, TerraformPoll poll) {
        int attemptNumber = attempt.getAttemptNumber();
        if (repository.existsByTaskIdAndAttemptNumberAndJobId(task.getId(), attemptNumber, jobId)) {
            return;
        }
        String body = fetchBody(task, jobId);
        boolean truncated = body != null && body.length() > MAX_RESULT_CHARS;
        TerraformResult row = TerraformResult.builder()
                .taskId(task.getId())
                .attemptNumber(attemptNumber)
                .jobId(jobId)
                .succeeded(poll.succeeded())
                .resultPath(clampResultPath(poll.resultPath()))
                .result(truncated ? body.substring(body.length() - MAX_RESULT_CHARS) : body)
                .truncated(truncated)
                .createdAt(clock.instant())
                .build();
        try {
            repository.save(row);
        } catch (DataIntegrityViolationException violation) {
            // 유니크 제약(동시 중복 insert)은 기대된 멱등 경합이라 조용히 삼킨다. 그 밖의 무결성 위반은
            // recordFinishedJobs의 경계 catch가 관찰 결손으로 강등하며 error 로그로 노출한다.
            if (!isAttemptJobDuplicate(violation)) {
                throw violation;
            }
            log.debug("task {} attempt {} job {}: terraform result already recorded concurrently",
                    task.getId(), attemptNumber, jobId);
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
        return value != null && value.toLowerCase(Locale.ROOT).contains(TerraformResult.ATTEMPT_JOB_CONSTRAINT);
    }

    /** resultPath는 외부 응답값이다 — 컬럼 길이를 넘으면 잘라 저장이 무결성 위반으로 깨지지 않게 방어한다. */
    private static String clampResultPath(String resultPath) {
        if (resultPath == null || resultPath.length() <= TerraformResult.RESULT_PATH_LENGTH) {
            return resultPath;
        }
        return resultPath.substring(0, TerraformResult.RESULT_PATH_LENGTH);
    }

    /** 본문 조회는 best-effort — 호출 실패는 포인터 행으로 강등한다(null 반환). CallInterrupted는 전파. */
    private String fetchBody(Task task, String jobId) {
        try {
            return infraManagerClient.terraformJobResult(jobId, task.getOperation());
        } catch (CallFailedException | CallTimeoutException fetchFailure) {
            log.warn("task {} job {}: terraform result fetch failed, keeping a pointer-only row: {}",
                    task.getId(), jobId, fetchFailure.getMessage());
            return null;
        }
    }
}
