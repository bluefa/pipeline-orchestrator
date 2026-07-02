package com.bff.pipeline.service.task.terraform;

import com.bff.pipeline.client.InfraManagerClient;
import com.bff.pipeline.dto.TerraformPoll;
import com.bff.pipeline.entity.Task;
import com.bff.pipeline.entity.TaskAttempt;
import com.bff.pipeline.entity.TerraformResult;
import com.bff.pipeline.exception.CallFailedException;
import com.bff.pipeline.exception.CallTimeoutException;
import com.bff.pipeline.repository.TerraformResultRepository;
import java.time.Clock;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * postCheck 관찰(확장 A) recorder다 — attempt가 판정으로 종결되는 turn에 finished job들의 result(= terraform log)를
 * 조회해 {@code terraform_result}에 남긴다(docs/terraform-client-and-postcheck-design.md §4.4의 쓰기 경로 정의).
 *
 * <p><b>상태 무관여가 계약이다.</b> 여기서 일어나는 어떤 조회·저장 실패도 태스크 판정을 바꾸지 않는다 — 닫힌 어휘
 * 호출 실패({@link CallFailedException}/{@link CallTimeoutException})와 중복 insert는 삼키고 로그만 남긴다.
 * {@code CallInterruptedException}과 진짜 버그는 전파한다(fail-fast) — 기록은 상태 전이 커밋 전에 일어나므로
 * 중단된 turn은 다음 폴 turn이 같은 판정을 다시 내려 재실행하고, 유니크 키 + 존재 선검사가 이미 저장된 행을
 * 건너뛴다(자기치유). job별로 독립 수행해 한 job의 실패가 나머지 job의 저장을 막지 않으며, 조회 실패 job도
 * 본문 없는 포인터 행({@code result = null} + {@code resultPath})으로 남긴다.
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
            record(task, attempt, finished.getKey(), finished.getValue());
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
                .resultPath(poll.resultPath())
                .result(truncated ? body.substring(body.length() - MAX_RESULT_CHARS) : body)
                .truncated(truncated)
                .createdAt(clock.instant())
                .build();
        try {
            repository.save(row);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("task {} attempt {} job {}: terraform result already recorded concurrently",
                    task.getId(), attemptNumber, jobId);
        }
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
