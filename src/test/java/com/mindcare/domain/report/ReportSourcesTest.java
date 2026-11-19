package com.mindcare.domain.report;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** RPT-01 세션 리포트 응답의 sources: 프론트는 sources[0].session_id로 전문 상담 연계를 시작한다. */
@SpringBootTest
@Transactional
class ReportSourcesTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;
    User trainee;
    String displayName;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
        displayName = "src-" + trainee.getId().toString().substring(24);
        jdbcTemplate.update("insert into provider_profiles (user_id, track, display_name, real_name_enc) values (?, 'trainee', ?, ?)",
                trainee.getId(), displayName, new byte[] {1});
    }

    @Test
    void 세션_리포트의_sources에_그_회기와_회기_번호_상담사_활동명이_들어간다() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(14));
        insertCompletedSession(base);
        UUID second = insertCompletedSession(base.plus(Duration.ofDays(7)));
        UUID reportId = UUID.randomUUID();
        jdbcTemplate.update("insert into reports (id, scope, session_id, client_id, trainee_id, status, ready_at) values (?, 'session', ?, ?, ?, 'ready', now())",
                reportId, second, client.getId(), trainee.getId());

        mockMvc.perform(get("/me/reports/" + reportId).header(HttpHeaders.AUTHORIZATION, bearer(client)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].session_id").value(second.toString()))
                .andExpect(jsonPath("$.sources[0].session_no").value(2))
                .andExpect(jsonPath("$.sources[0].provider_display").value(displayName))
                .andExpect(jsonPath("$.sources[0].date").exists());
    }

    private UUID insertCompletedSession(Instant start) {
        UUID slot = UUID.randomUUID();
        jdbcTemplate.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?, ?, ?, ?, 'booked')",
                slot, trainee.getId(), Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, 'completed', 15000)
                """, id, client.getId(), trainee.getId(), slot, Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        return id;
    }

    private String bearer(User user) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return "Bearer " + token;
    }

    private User saveUser(UserRole role) {
        String uid = "rps-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }
}
