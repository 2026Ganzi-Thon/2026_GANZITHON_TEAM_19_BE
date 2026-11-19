package com.mindcare.domain.referral.service;

import com.mindcare.domain.referral.dto.ExpertReferralItem;
import com.mindcare.domain.referral.entity.Referral;
import com.mindcare.domain.referral.repository.ReferralRepository;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 전문가 쪽 연계 요청 목록·응답 (E05). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExpertReferralService {
    private final ReferralRepository referrals;
    private final JdbcTemplate jdbc;

    public List<ExpertReferralItem> list(UUID expertId) {
        return jdbc.query("""
                select r.id,
                       case when r.status = 'expert_selected' and es.scheduled_start is not null then 'booked' else r.status end as status,
                       u.nickname as client_nickname, i.category, es.scheduled_start, rp.risk_level, r.trainee_reason,
                       coalesce(r.respond_by, r.updated_at + interval '48 hours') as respond_by,
                       r.report_id, coalesce(rp.status = 'ready', false) as report_ready,
                       (select count(*) from report_sources rs where rs.report_id = r.report_id) as included_session_count,
                       exists (select 1 from consents c where c.type = 'report_transfer' and c.target_expert_id = r.expert_id
                                  and c.report_id = r.report_id and c.report_version = rp.version and c.revoked_at is null) as has_report_consent
                  from referrals r
                  join users u on u.id = r.client_id
                  join sessions src on src.id = r.source_session_id
                  left join intakes i on i.id = src.intake_id
                  left join reports rp on rp.id = r.report_id
                  left join lateral (select s.scheduled_start from sessions s
                                      where s.referral_id = r.id and s.status in ('confirmed','in_progress','completed')
                                      order by s.scheduled_start limit 1) es on true
                 where r.expert_id = ? and r.status not in ('suggested','cancelled')
                 order by r.updated_at desc
                """, (rs, i) -> {
            Timestamp start = rs.getTimestamp("scheduled_start");
            Timestamp respondBy = rs.getTimestamp("respond_by");
            return new ExpertReferralItem(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("client_nickname"),
                    rs.getString("category"), start == null ? null : start.toInstant(), rs.getString("risk_level"),
                    rs.getInt("included_session_count"), rs.getString("trainee_reason"),
                    respondBy == null ? null : respondBy.toInstant(), rs.getBoolean("has_report_consent"),
                    rs.getObject("report_id", UUID.class), rs.getBoolean("report_ready"));
        }, expertId);
    }

    @Transactional
    public void respond(UUID expertId, UUID referralId, String action, String reason) {
        Referral r = referrals.findById(referralId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!expertId.equals(r.getExpertId())) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        try {
            if ("accept".equals(action)) r.accept();
            else r.decline(reason);
        } catch (IllegalStateException e) {
            throw new AppException(ErrorCode.INVALID_STATE);
        }
    }
}
