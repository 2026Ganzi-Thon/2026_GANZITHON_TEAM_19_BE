package com.mindcare.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.repository.SlotRepository;
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
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 선택 필드를 빼고 보내도 400이 나지 않아야 한다 (SES-07 rating, INT-01 share_raw·notify_when_available). */
@SpringBootTest
@Transactional
class OptionalRequestFieldsTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = saveUser(UserRole.CLIENT);
    }

    @Test
    void 별점_없이_후기를_쓸_수_있고_두_번째_후기는_409() throws Exception {
        UUID sessionId = insertCompletedSession();

        review(sessionId, "{\"text\": \"좋았어요\"}").andExpect(status().isNoContent());
        entityManager.flush();
        Map<String, Object> row = jdbcTemplate.queryForMap("select rating, review_enc from sessions where id = ?", sessionId);
        assertThat(row.get("rating")).isNull();
        assertThat(row.get("review_enc")).isNotNull();

        review(sessionId, "{\"rating\": 5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 별점은_1에서_5_사이만() throws Exception {
        review(insertCompletedSession(), "{\"rating\": 6}").andExpect(status().is4xxClientError());
    }

    @Test
    void 인테이크는_share_raw와_notify_when_available_없이도_만들어지고_false로_저장된다() throws Exception {
        mockMvc.perform(as(client, post("/intakes")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"burnout\", \"text\": \"요즘 회사 일 때문에 너무 지치고 잠도 잘 못 자요\"}"))
                .andExpect(status().is2xxSuccessful());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select share_raw, notify_when_available from intakes where client_id = ?", client.getId());
        assertThat(row.get("share_raw")).isEqualTo(false);
        assertThat(row.get("notify_when_available")).isEqualTo(false);
    }

    private ResultActions review(UUID sessionId, String json) throws Exception {
        return mockMvc.perform(as(client, post("/sessions/" + sessionId + "/review"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(UserRole role) {
        String uid = "opt-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }

    private UUID insertCompletedSession() {
        User trainee = saveUser(UserRole.TRAINEE);
        Instant start = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
        UUID slotId = slotRepository.saveAndFlush(
                AvailabilitySlot.open(trainee.getId(), start, start.plus(Duration.ofMinutes(30)))).getId();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, 'completed', 15000)
                """, id, client.getId(), trainee.getId(), slotId,
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        return id;
    }
}
