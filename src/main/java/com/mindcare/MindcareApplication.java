package com.mindcare;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MindcareApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MindcareApplication.class);
        // --app.task=seed 같은 일회성 작업은 웹 서버 없이 돌고 끝난다 (운영 서버 옆에서 실행해도 포트가 안 겹침)
        if (Arrays.stream(args).anyMatch(arg -> arg.startsWith("--app.task="))) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
