package com.mindcare.domain.admin.service;

import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * price_configs 운영 설정 조회 (TRD 11-4, 19-3). 값은 어드민 운영 설정(ADM)에서 바뀐다.
 * 다른 도메인은 이 서비스로 트랙별 가격·상담 길이를 읽는다. 예: 예약 생성 시 price, 슬롯 등록 시 duration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceConfigService {

    public static final String COACHING = "coaching";
    public static final String PROFESSIONAL = "professional";

    private final JdbcTemplate jdbcTemplate;

    /** track: "coaching" | "professional" */
    public TrackPricing trackPricing(String track) {
        if (!COACHING.equals(track) && !PROFESSIONAL.equals(track)) {
            throw new IllegalArgumentException("Unknown track: " + track);
        }
        List<TrackPricing> rows = jdbcTemplate.query("""
                select (value->>'price')::int as price,
                       (value->>'duration_min')::int as duration_min,
                       (value->>'fee_rate')::numeric as fee_rate
                  from price_configs where key = ?
                """, (rs, i) -> new TrackPricing(rs.getInt("price"), rs.getInt("duration_min"), rs.getBigDecimal("fee_rate")),
                track);
        if (rows.isEmpty()) {
            log.error("price_configs '{}' is missing", track);
            throw new AppException(ErrorCode.INTERNAL);
        }
        return rows.getFirst();
    }

    public record TrackPricing(int price, int durationMinutes, BigDecimal feeRate) {
    }
}
