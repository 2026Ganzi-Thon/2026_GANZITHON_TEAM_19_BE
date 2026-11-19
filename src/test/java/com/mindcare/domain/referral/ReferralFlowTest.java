package com.mindcare.domain.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 연계 화면 흐름: 연계 생성 → 상세(REF-02) → 포함 회기 선택(REF-03) → 동의 후 잠금. */
@SpringBootTest
@Transactional
class ReferralFlowTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean TokenVerifier tokenVerifier;
    @Autowired com.mindcare.global.crypto.SensitiveTextCodec codec;

    MockMvc mockMvc;
    User client;
    User trainee;
    User expert;
    UUID first;
    UUID second;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
        expert = saveUser(UserRole.EXPERT);
        profile(trainee, "trainee");
        profile(expert, "expert");
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(14));
        first = completedSession(base);
        second = completedSession(base.plus(Duration.ofDays(7)));
    }

    @Test
    void 연계를_만들면_시작_회기가_포함되고_연계_리포트_생성이_등록된다() throws Exception {
        String referralId = createReferral(second);

        mockMvc.perform(as(client, get("/referrals/" + referralId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referral.id").value(referralId))
                .andExpect(jsonPath("$.referral.included_locked_at").doesNotExist())
                .andExpect(jsonPath("$.eligible_sessions.length()").value(2))
                .andExpect(jsonPath("$.included_session_ids.length()").value(1))
                .andExpect(jsonPath("$.included_session_ids[0]").value(second.toString()))
                .andExpect(jsonPath("$.candidates[?(@.provider_id == '" + expert.getId() + "')]").exists())
                .andExpect(jsonPath("$.report.status").value("generating"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from jobs where type = 'generate_referral_report'", Integer.class)).isPositive();
    }

    @Test
    void 포함_회기를_바꾸면_연계_리포트_버전이_오르고_회기_번호는_시간순이다() throws Exception {
        String referralId = createReferral(second);

        mockMvc.perform(as(client, put("/referrals/" + referralId + "/sessions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_ids\": [\"" + second + "\", \"" + first + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.included_session_ids[0]").value(first.toString()))
                .andExpect(jsonPath("$.included_session_ids[1]").value(second.toString()))
                .andExpect(jsonPath("$.report.version").value(2));

        String reportId = JsonPath.read(mockMvc.perform(as(client, get("/referrals/" + referralId)))
                .andReturn().getResponse().getContentAsString(), "$.report.id");
        // 내담자는 생성 중인 연계 리포트도 상태를 받는다 (화면이 폴링)
        mockMvc.perform(as(client, get("/me/reports/" + reportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("generating"))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[1].session_no").value(2));
    }

    @Test
    void 동의하면_포함_회기가_잠기고_남의_연계는_볼_수_없다() throws Exception {
        String referralId = createReferral(second);

        mockMvc.perform(as(client, post("/consents")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"transcript_transfer\", \"version\": \"1.0\", \"target_expert_id\": \"" + expert.getId()
                                + "\", \"referral_id\": \"" + referralId + "\"}"))
                .andExpect(status().isCreated());
        entityManager.flush();

        mockMvc.perform(as(client, get("/referrals/" + referralId)))
                .andExpect(jsonPath("$.referral.included_locked_at").exists());
        mockMvc.perform(as(client, put("/referrals/" + referralId + "/sessions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_ids\": [\"" + first + "\"]}"))
                .andExpect(status().isConflict());

        User other = saveUser(UserRole.CLIENT);
        mockMvc.perform(as(other, get("/referrals/" + referralId))).andExpect(status().isForbidden());
    }

    @Test
    void 전문가는_예약된_연계를_수락하고_동의된_연계_리포트를_객체로_본다() throws Exception {
        String referralId = createReferral(second);
        mockMvc.perform(as(client, put("/referrals/" + referralId + "/expert")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expert_id\": \"" + expert.getId() + "\"}")).andExpect(status().isOk());
        UUID reportId = jdbcTemplate.queryForObject("select report_id from referrals where id = ?::uuid", UUID.class, referralId);
        jdbcTemplate.update("update reports set status = 'ready', ready_at = now(), risk_level = 'low', content_enc = ?, client_summary_enc = ? where id = ?",
                codec.encrypt("{\"chief_complaint\": \"번아웃\", \"timeline\": [{\"session_no\": 1, \"session_date\": \"2026-11-06\", \"trainee_label\": \"수련생 A\", \"key_points\": [\"지침\"]}]}"),
                codec.encrypt("{\"chief_complaint\": \"번아웃\"}"), reportId);
        entityManager.flush();
        entityManager.clear(); // JDBC로 바꾼 리포트 상태를 JPA가 다시 읽게 한다
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofDays(1));
        UUID slot = UUID.randomUUID();
        jdbcTemplate.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?, ?, ?, ?, 'booked')",
                slot, expert.getId(), Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(50))));
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price, referral_id)
                values (?, 'professional', ?, ?, ?, ?, ?, 'confirmed', 50000, ?::uuid)
                """, UUID.randomUUID(), client.getId(), expert.getId(), slot, Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(50))), referralId);
        mockMvc.perform(as(client, post("/consents")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\": \"report_transfer\", \"version\": \"1.0\", \"target_expert_id\": \"" + expert.getId()
                        + "\", \"referral_id\": \"" + referralId + "\", \"report_id\": \"" + reportId + "\", \"report_version\": 1}"))
                .andExpect(status().isCreated());
        entityManager.flush();

        mockMvc.perform(as(expert, get("/expert/referrals")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(referralId))
                .andExpect(jsonPath("$[0].status").value("booked"))
                .andExpect(jsonPath("$[0].client_nickname").value(client.getNickname()))
                .andExpect(jsonPath("$[0].included_session_count").value(1))
                .andExpect(jsonPath("$[0].has_report_consent").value(true))
                .andExpect(jsonPath("$[0].report_ready").value(true))
                .andExpect(jsonPath("$[0].respond_by").exists());
        mockMvc.perform(as(expert, post("/expert/referrals/" + referralId + "/respond")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"accept\"}")).andExpect(status().isNoContent());
        entityManager.flush();
        mockMvc.perform(as(expert, get("/expert/referrals"))).andExpect(jsonPath("$[0].status").value("accepted"));

        mockMvc.perform(as(expert, get("/expert/reports/" + reportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.chief_complaint").value("번아웃"))
                .andExpect(jsonPath("$.content.timeline[0].key_points[0]").value("지침"))
                .andExpect(jsonPath("$.sources_meta[0].session_no").value(1))
                .andExpect(jsonPath("$.sources_meta[0].duration_min").value(30));

        User otherExpert = saveUser(UserRole.EXPERT);
        mockMvc.perform(as(otherExpert, post("/expert/referrals/" + referralId + "/respond")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"decline\"}")).andExpect(status().isForbidden());
    }

    private String createReferral(UUID sessionId) throws Exception {
        String body = mockMvc.perform(as(client, post("/referrals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source_session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        entityManager.flush();
        return JsonPath.read(body, "$.id");
    }

    private UUID completedSession(Instant start) {
        UUID slot = UUID.randomUUID();
        jdbcTemplate.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?, ?, ?, ?, 'booked')",
                slot, trainee.getId(), Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, 'completed', 15000)
                """, id, client.getId(), trainee.getId(), slot, Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        jdbcTemplate.update("insert into reports (id, scope, session_id, client_id, trainee_id, status, ready_at) values (?, 'session', ?, ?, ?, 'ready', now())",
                UUID.randomUUID(), id, client.getId(), trainee.getId());
        return id;
    }

    private void profile(User user, String track) {
        jdbcTemplate.update("insert into provider_profiles (user_id, track, display_name, real_name_enc) values (?, ?, ?, ?)",
                user.getId(), track, "ref-" + user.getId().toString().substring(24), new byte[] {1});
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(UserRole role) {
        String uid = "ref-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }
}
