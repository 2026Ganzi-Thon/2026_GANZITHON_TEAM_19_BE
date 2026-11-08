package com.mindcare.domain.risk;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RiskLevel {
    NONE("none"), LOW("low"), MEDIUM("medium"), HIGH("high"), CRITICAL("critical");
    private final String value;
    RiskLevel(String value) { this.value = value; }
    @JsonValue public String getValue() { return value; }
}
