package com.mindcare.domain.settlement.dto;

/** STL-03 응답. 지급 처리한 건수와 net 합계. */
public record SettlementPayResponse(int paidCount, int paidNet) {
}
