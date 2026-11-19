package com.mindcare.domain.supervision.service;

import com.mindcare.domain.settlement.service.SettlementService;
import com.mindcare.domain.supervision.dto.SupervisionWriteRequest;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연 흐름 8단계: 전문가가 연계를 수락하면 포함 회기의 수련생마다 슈퍼비전을 만들고,
 * 전문가가 5대 항목을 작성·제출하면 정산이 풀리고 수련생이 확인한다 (프론트 E08·T07 형식).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupervisionWorkflowService {
    private static final Map<String, String> RISK_TO_DB = Map.of("appropriate", "appropriate", "underestimated", "under", "overestimated", "over");
    private static final Map<String, String> RISK_FROM_DB = Map.of("appropriate", "appropriate", "under", "underestimated", "over", "overestimated");

    private final JdbcTemplate jdbc;
    private final SensitiveTextCodec codec;
    private final JsonMapper json;
    private final SettlementService settlements;

    /** 연계 수락 시: 연계 리포트에 포함된 회기의 수련생마다 슈퍼비전 1건 (이미 있으면 건너뜀). 기한 72시간. */
    @Transactional
    public void createForReferral(UUID referralId) {
        jdbc.update("""
                insert into supervisions (id, kind, referral_id, report_id, expert_id, trainee_id, status, due_at)
                select gen_random_uuid(), 'referral', r.id, r.report_id, r.expert_id, t.trainee_id, 'pending', now() + interval '72 hours'
                  from referrals r
                  join (select distinct rs.report_id, s.provider_id as trainee_id
                          from report_sources rs join sessions s on s.id = rs.session_id) t on t.report_id = r.report_id
                 where r.id = ? and r.expert_id is not null
                on conflict (referral_id, trainee_id) do nothing
                """, referralId);
    }

    // ---- 전문가 ----

    public List<Map<String, Object>> expertList(UUID expertId) {
        return jdbc.query("""
                select sv.id, sv.status, sv.due_at, coalesce(pp.display_name, '수련생') as trainee_display,
                       (select count(*) from report_sources rs join sessions s on s.id = rs.session_id
                         where rs.report_id = sv.report_id and s.provider_id = sv.trainee_id) as session_count,
                       (select count(*) from supervisions o where o.referral_id = sv.referral_id) as referral_total,
                       (select count(*) from supervisions o where o.referral_id = sv.referral_id and o.status not in ('submitted','cancelled')) as referral_remaining,
                       exists (select 1 from settlements st join sessions es on es.id = st.session_id
                                where es.referral_id = sv.referral_id and st.status = 'locked') as settlement_locked
                  from supervisions sv left join provider_profiles pp on pp.user_id = sv.trainee_id
                 where sv.expert_id = ? and sv.status <> 'cancelled'
                 order by case when sv.status = 'submitted' then 1 else 0 end, sv.due_at nulls last, sv.created_at desc
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class));
            m.put("status", rs.getString("status"));
            m.put("trainee_display", rs.getString("trainee_display"));
            m.put("session_count", rs.getInt("session_count"));
            m.put("due_at", instant(rs.getTimestamp("due_at")));
            if (rs.getInt("referral_total") > 0) {
                m.put("referral_total", rs.getInt("referral_total"));
                m.put("referral_remaining", rs.getInt("referral_remaining"));
            }
            m.put("settlement_locked", rs.getBoolean("settlement_locked"));
            return m;
        }, expertId);
    }

    public Map<String, Object> context(UUID expertId, UUID id) {
        Map<String, Object> sv = owned(id, "expert_id", expertId);
        UUID trainee = (UUID) sv.get("trainee_id");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trainee_display", jdbc.query("select display_name from provider_profiles where user_id = ?",
                (rs, i) -> rs.getString(1), trainee).stream().findFirst().orElse("수련생"));
        out.put("trainee_reason", sv.get("referral_id") == null ? null
                : jdbc.queryForObject("select trainee_reason from referrals where id = ?", String.class, sv.get("referral_id")));
        out.put("trainee_risk_actions", List.of());
        out.put("referral_report", referralReport((UUID) sv.get("report_id")));
        List<Map<String, Object>> sessions = new ArrayList<>();
        List<Map<String, Object>> rows = sv.get("report_id") != null
                ? jdbc.queryForList("""
                    select rs.session_id, rs.session_no from report_sources rs join sessions s on s.id = rs.session_id
                     where rs.report_id = ? and s.provider_id = ? order by rs.session_no
                    """, sv.get("report_id"), trainee)
                : jdbc.queryForList("select ?::uuid as session_id, 1 as session_no", sv.get("coaching_session_id"));
        for (Map<String, Object> row : rows) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("session_id", row.get("session_id"));
            s.put("session_no", ((Number) row.get("session_no")).intValue());
            s.put("masked_transcript", transcript((UUID) row.get("session_id"), trainee));
            sessions.add(s);
        }
        out.put("trainee_sessions", sessions);
        List<UUID> open = jdbc.queryForList("""
                select id from supervisions where expert_id = ? and status in ('pending','draft') order by due_at nulls last, created_at
                """, UUID.class, expertId);
        int index = open.indexOf(id);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("total", Math.max(open.size(), 1));
        position.put("index", index < 0 ? 1 : index + 1);
        position.put("next_id", open.stream().filter(o -> !o.equals(id)).findFirst().orElse(null));
        out.put("position", position);
        out.put("saved", saved(sv));
        return out;
    }

    @Transactional
    public void write(UUID expertId, UUID id, SupervisionWriteRequest r) {
        Map<String, Object> sv = owned(id, "expert_id", expertId);
        String status = (String) sv.get("status");
        if ("submitted".equals(status)) throw new AppException(ErrorCode.ALREADY_SUBMITTED);
        if ("cancelled".equals(status)) throw new AppException(ErrorCode.INVALID_STATE);
        boolean draft = Boolean.TRUE.equals(r.draft());
        String risk = r.riskRating() == null ? null : RISK_TO_DB.get(r.riskRating());
        if (r.riskRating() != null && risk == null) throw new AppException(ErrorCode.VALIDATION_ERROR);
        SupervisionWriteRequest.Scores sc = r.scores();
        if (!draft && (blank(r.wellDone()) || blank(r.blindSpot()) || blank(r.riskComment()) || blank(r.nextAction()) || risk == null
                || r.alternativeResponses() == null || r.alternativeResponses().isEmpty() || sc == null
                || sc.rapport() == null || sc.empathy() == null || sc.risk() == null || sc.structure() == null)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("well_done", nz(r.wellDone()));
        content.put("blind_spot", nz(r.blindSpot()));
        content.put("alternative_responses", r.alternativeResponses() == null ? List.of() : r.alternativeResponses().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", a.sessionId());
            m.put("quote", nz(a.quote()));
            m.put("suggestion", nz(a.suggestion()));
            return m;
        }).toList());
        content.put("risk_comment", nz(r.riskComment()));
        content.put("next_action", nz(r.nextAction()));
        jdbc.update("""
                update supervisions set content_enc = ?, risk_rating = ?, score_rapport = ?, score_empathy = ?, score_risk = ?, score_structure = ?,
                       status = ?, submitted_at = case when ? then now() else submitted_at end
                 where id = ?
                """, codec.encrypt(json.writeValueAsString(content)), risk,
                sc == null ? null : sc.rapport(), sc == null ? null : sc.empathy(), sc == null ? null : sc.risk(), sc == null ? null : sc.structure(),
                draft ? "draft" : "submitted", !draft, id);
        if (!draft) afterSubmit(sv, expertId, id);
    }

    /** 제출 후: 전문가 슈퍼비전 정산 생성, 그 연계의 슈퍼비전이 모두 끝났으면 연계 후 첫 전문 세션 정산 잠금 해제. */
    private void afterSubmit(Map<String, Object> sv, UUID expertId, UUID id) {
        settlements.createForSupervision(id, expertId);
        Object referralId = sv.get("referral_id");
        if (referralId == null) return;
        Integer remaining = jdbc.queryForObject(
                "select count(*) from supervisions where referral_id = ? and status not in ('submitted','cancelled')", Integer.class, referralId);
        if (remaining != null && remaining == 0) {
            jdbc.queryForList("select id from sessions where referral_id = ? and track = 'professional' order by scheduled_start limit 1", UUID.class, referralId)
                    .forEach(settlements::unlockForSession);
        }
    }

    // ---- 수련생 ----

    public List<Map<String, Object>> traineeList(UUID traineeId) {
        return jdbc.query("""
                select sv.id, sv.submitted_at, sv.read_at, coalesce(pp.display_name, '전문가') as expert_display,
                       coalesce(cs.scheduled_start, (select min(s.scheduled_start) from report_sources rs join sessions s on s.id = rs.session_id
                                                       where rs.report_id = sv.report_id and s.provider_id = sv.trainee_id)) as session_date,
                       coalesce(ci.category, ri.category) as category
                  from supervisions sv
                  left join provider_profiles pp on pp.user_id = sv.expert_id
                  left join sessions cs on cs.id = sv.coaching_session_id
                  left join intakes ci on ci.id = cs.intake_id
                  left join referrals rf on rf.id = sv.referral_id
                  left join sessions rsrc on rsrc.id = rf.source_session_id
                  left join intakes ri on ri.id = rsrc.intake_id
                 where sv.trainee_id = ? and sv.status = 'submitted'
                 order by sv.submitted_at desc
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id", UUID.class));
            m.put("session_date", instant(rs.getTimestamp("session_date")));
            m.put("category", rs.getString("category"));
            m.put("expert_display", rs.getString("expert_display"));
            m.put("submitted_at", instant(rs.getTimestamp("submitted_at")));
            m.put("read_at", instant(rs.getTimestamp("read_at")));
            return m;
        }, traineeId);
    }

    @Transactional
    public Map<String, Object> traineeDetail(UUID traineeId, UUID id) {
        Map<String, Object> sv = owned(id, "trainee_id", traineeId);
        if (!"submitted".equals(sv.get("status"))) throw new AppException(ErrorCode.NOT_FOUND);
        jdbc.update("update supervisions set read_at = coalesce(read_at, now()) where id = ?", id);
        Map<String, Object> out = new LinkedHashMap<>(saved(sv));
        out.put("id", id);
        out.put("expert_display", jdbc.query("select display_name from provider_profiles where user_id = ?",
                (rs, i) -> rs.getString(1), sv.get("expert_id")).stream().findFirst().orElse("전문가"));
        out.put("session_count", sv.get("report_id") == null ? 1 : jdbc.queryForObject("""
                select count(*) from report_sources rs join sessions s on s.id = rs.session_id where rs.report_id = ? and s.provider_id = ?
                """, Integer.class, sv.get("report_id"), traineeId));
        out.put("submitted_at", instant((Timestamp) sv.get("submitted_at")));
        return out;
    }

    // ---- 내부 ----

    private Map<String, Object> owned(UUID id, String column, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from supervisions where id = ?", id);
        if (rows.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND);
        Map<String, Object> sv = rows.get(0);
        if (!userId.equals(sv.get(column))) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        return sv;
    }

    private Map<String, Object> saved(Map<String, Object> sv) {
        if (!(sv.get("content_enc") instanceof byte[] enc)) return null;
        Map<String, Object> content = new LinkedHashMap<>(json.readValue(codec.decrypt(enc), new TypeReference<Map<String, Object>>() { }));
        content.putIfAbsent("alternative_responses", List.of());
        content.put("risk_rating", sv.get("risk_rating") == null ? null : RISK_FROM_DB.get((String) sv.get("risk_rating")));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("rapport", sv.get("score_rapport"));
        scores.put("empathy", sv.get("score_empathy"));
        scores.put("risk", sv.get("score_risk"));
        scores.put("structure", sv.get("score_structure"));
        content.put("scores", scores);
        return content;
    }

    private Map<String, Object> referralReport(UUID reportId) {
        if (reportId == null) return null;
        List<byte[]> enc = jdbc.query("select content_enc from reports where id = ? and status = 'ready'", (rs, i) -> rs.getBytes(1), reportId);
        if (enc.isEmpty() || enc.get(0) == null) return null;
        Map<String, Object> c = json.readValue(codec.decrypt(enc.get(0)), new TypeReference<Map<String, Object>>() { });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chief_complaint", c.getOrDefault("chief_complaint", ""));
        out.put("progress", c.getOrDefault("progress", ""));
        return out;
    }

    /** 회기 대화록 (연락처·이메일·링크는 가림). 메시지가 없으면 null → 화면이 "대화록 없음" 표시. */
    private List<Map<String, String>> transcript(UUID sessionId, UUID traineeId) {
        List<Map<String, String>> lines = jdbc.query("""
                select sender_id, body_enc from messages where session_id = ? and kind = 'text' order by id limit 200
                """, (rs, i) -> Map.of("role", traineeId.equals(rs.getObject("sender_id", UUID.class)) ? "provider" : "client",
                "text", mask(codec.decrypt(rs.getBytes("body_enc")))), sessionId);
        return lines.isEmpty() ? null : lines;
    }

    static String mask(String text) {
        return text.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[이메일]")
                .replaceAll("(?:https?://|www\\.)\\S+", "[링크]")
                .replaceAll("\\d{2,4}[- .]?\\d{3,4}[- .]?\\d{4}", "[연락처]");
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
