package com.mindcare.domain.admin.dto;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** ADM-06·07. value는 키마다 모양이 달라서 JSON 그대로 돌려준다. */
public record SettingResponse(String key, JsonNode value, UUID updatedBy, Instant updatedAt) {
}
