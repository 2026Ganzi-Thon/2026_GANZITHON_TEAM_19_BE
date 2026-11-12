package com.mindcare.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * TRD 19-3: {@code --app.task=seed}로 실행하면 시드를 넣고 종료한다 (웹 서버는 안 뜸, MindcareApplication 참고).
 * 운영: {@code docker compose run --rm app --app.task=seed}
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.task", havingValue = "seed")
class SeedTaskRunner implements ApplicationRunner {

    private final DemoSeeder demoSeeder;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            DemoSeeder.Result result = demoSeeder.seed();
            log.info("seed done: users={}, sessions={}, slots={}", result.users(), result.sessions(), result.slots());
        } catch (RuntimeException e) {
            log.error("seed failed", e);
            code = 1;
        }
        int exitCode = code;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
