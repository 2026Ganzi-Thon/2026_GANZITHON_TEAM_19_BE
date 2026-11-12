package com.mindcare.domain.admin.dto;

import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** ADM-07. value 형식은 키별로 SettingSchemas가 검사한다. reason은 감사 로그용 (선택). */
public record SettingUpdateRequest(JsonNode value, @Size(max = 500) String reason) {
}
