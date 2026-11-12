package com.mindcare.domain.settlement.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** STL-03 요청. 한 번에 최대 200건. */
public record SettlementPayRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> ids) {
}
