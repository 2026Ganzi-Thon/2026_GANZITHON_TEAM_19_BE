package com.mindcare.domain.job.entity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
public enum JobStatus {
    QUEUED("queued"), RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled");
    private final String value;
    JobStatus(String value) { this.value = value; }
    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<JobStatus, String> {
        public String convertToDatabaseColumn(JobStatus value) { return value == null ? null : value.value; }
        public JobStatus convertToEntityAttribute(String value) { return Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst().orElseThrow(); }
    }
}
