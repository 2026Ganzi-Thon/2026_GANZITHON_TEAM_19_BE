package com.mindcare.domain.admin.dto;

import java.util.Map;

/** PUB-02. 프론트가 앱 시작 시와 5분마다 읽는다 (TRD 7-8). */
public record AppConfigResponse(
        Map<String, Boolean> featureFlags,
        Maintenance maintenance,
        String minClientVersion) {

    public record Maintenance(boolean on, String message, String until) {

        public static final Maintenance OFF = new Maintenance(false, null, null);
    }
}
