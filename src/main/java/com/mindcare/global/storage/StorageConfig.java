package com.mindcare.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * app.storage.type=s3 이면 S3 (운영), 그 외에는 메모리 (로컬·테스트·CI).
 * S3 인증은 기본 자격 증명 체인 → EC2에서는 인스턴스 역할(mindcare-prod-ec2-role).
 */
@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    Storage s3Storage(@Value("${app.storage.bucket}") String bucket, @Value("${app.storage.region}") String region) {
        Region awsRegion = Region.of(region);
        return new S3Storage(
                S3Client.builder().region(awsRegion).build(),
                S3Presigner.builder().region(awsRegion).build(),
                bucket);
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "memory", matchIfMissing = true)
    Storage inMemoryStorage() {
        return new InMemoryStorage();
    }
}
