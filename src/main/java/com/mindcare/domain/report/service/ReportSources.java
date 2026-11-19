package com.mindcare.domain.report.service;

import com.mindcare.domain.report.dto.ReportResponse;
import com.mindcare.domain.report.entity.Report;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * 리포트가 다루는 코칭 회기 목록. 세션 리포트는 그 회기 하나, 연계 리포트는 report_sources에 담긴 회기들이다.
 * 프론트는 sources[0].session_id로 전문 상담 연계(POST /referrals)를 시작한다.
 */
@Component
@RequiredArgsConstructor
class ReportSources {
    private static final RowMapper<ReportResponse.Source> ROW = (rs, i) -> new ReportResponse.Source(
            rs.getObject("session_id", java.util.UUID.class), rs.getInt("session_no"),
            rs.getTimestamp("scheduled_start") == null ? null : rs.getTimestamp("scheduled_start").toInstant(),
            rs.getString("provider_display"));

    private final JdbcTemplate jdbc;

    List<ReportResponse.Source> of(Report report) {
        if ("session".equals(report.getScope()) && report.getSessionId() != null) {
            // 회기 번호 = 이 내담자의 완료된 회기 중 시간순 위치
            return jdbc.query("""
                    select s.id as session_id, s.scheduled_start, coalesce(pp.display_name, '상담사') as provider_display,
                           (select count(*) from sessions s2 where s2.client_id = s.client_id and s2.status = 'completed'
                               and s2.scheduled_start <= s.scheduled_start) as session_no
                      from sessions s left join provider_profiles pp on pp.user_id = s.provider_id
                     where s.id = ?
                    """, ROW, report.getSessionId());
        }
        return jdbc.query("""
                select rs.session_id, s.scheduled_start, coalesce(pp.display_name, '상담사') as provider_display, rs.session_no
                  from report_sources rs join sessions s on s.id = rs.session_id
                  left join provider_profiles pp on pp.user_id = s.provider_id
                 where rs.report_id = ?
                 order by rs.session_no
                """, ROW, report.getId());
    }

    /** 회기별 상담 시간(분): 실제 시작·종료가 있으면 그 값, 없으면 예약 시간. */
    List<com.mindcare.domain.report.dto.ExpertReportResponse.SourceMeta> meta(Report report) {
        String sessionIds = "session".equals(report.getScope())
                ? "select ?::uuid as session_id, 1 as session_no"
                : "select session_id, session_no from report_sources where report_id = ?";
        return jdbc.query("""
                select src.session_no,
                       greatest(0, round(extract(epoch from (coalesce(s.ended_at, s.scheduled_end) - coalesce(s.started_at, s.scheduled_start))) / 60))::int as duration_min
                  from (%s) src join sessions s on s.id = src.session_id
                 order by src.session_no
                """.formatted(sessionIds), (rs, i) -> new com.mindcare.domain.report.dto.ExpertReportResponse.SourceMeta(rs.getInt("session_no"), rs.getInt("duration_min")),
                "session".equals(report.getScope()) ? report.getSessionId() : report.getId());
    }
}
