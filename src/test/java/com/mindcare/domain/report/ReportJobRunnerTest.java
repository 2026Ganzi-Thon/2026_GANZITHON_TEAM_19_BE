package com.mindcare.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindcare.domain.job.entity.Job;
import com.mindcare.domain.job.entity.JobStatus;
import com.mindcare.domain.job.repository.JobRepository;
import com.mindcare.domain.report.service.ReportJobRunner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 리포트 잡 처리 결과가 DB에 저장되는지 확인한다.
 * 테스트 자체에 @Transactional을 걸면 처리 쪽 트랜잭션 누락이 가려지므로 일부러 걸지 않는다.
 */
@SpringBootTest(properties = "app.jobs.report-delay-ms=3600000")
class ReportJobRunnerTest {

    @Autowired ReportJobRunner runner;
    @Autowired JobRepository jobs;

    UUID jobId;

    @AfterEach
    void cleanUp() {
        if (jobId != null) jobs.deleteById(jobId);
    }

    @Test
    void 처리한_잡의_상태가_저장된다() {
        // 리포트가 이미 지워진 잡은 바로 성공 처리되는 경로라 AI 서버 없이 확인할 수 있다
        Job job = jobs.save(new Job("generate_session_report", "test-" + UUID.randomUUID(),
                Map.of("report_id", UUID.randomUUID().toString())));
        jobId = job.getId();

        runner.run();

        Job saved = jobs.findById(jobId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(saved.getAttempts()).isEqualTo((short) 1);
    }
}
