package com.mindcare.domain.settlement.dto;

import java.util.List;
import java.util.Map;

/**
 * STL-01 응답. totals는 상태별 net 합계 (locked·held·pending·paid, 없으면 0).
 * month는 KST 기준 "yyyy-MM".
 */
public record SettlementSummaryResponse(String month, List<SettlementResponse> items, Map<String, Integer> totals) {
}
