package com.mindcare.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.repository.SlotRepository;
import com.mindcare.domain.slot.service.SlotService;
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

/** USR-03 회원 탈퇴 (TRD 13-1, FR-ACC-06). */
@SpringBootTest
@Transactional
class WithdrawalTest {

    private static final Instant START = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired SlotService slotService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;
    User trainee;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
    }

    @Test
    void 세션이_없으면_202이고_withdrawn이_되며_이후_역할_API는_401() throws Exception {
        withdraw(client).andExpect(status().isAccepted());

        entityManager.flush();
        Map<String, Object> row = jdbcTemplate.queryForMap("select status, withdrawn_at from users where id = ?", client.getId());
        assertThat(row.get("status")).isEqualTo("withdrawn");
        assertThat(row.get("withdrawn_at")).isNotNull();

        mockMvc.perform(as(client, patch("/me")).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\": \"새닉네임\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 확정된_세션이_있으면_409_ACTIVE_SESSIONS_EXIST() throws Exception {
        insertSession(saveSlot(START), "confirmed");

        withdraw(client)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_SESSIONS_EXIST"));
        withdraw(trainee)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_SESSIONS_EXIST"));
    }

    @Test
    void 홀드가_살아있는_결제_대기_세션은_막고_만료된_건_막지_않는다() throws Exception {
        UUID slotId = saveSlot(START);
        slotService.hold(slotId);
        insertSession(slotId, "pending_payment");

        withdraw(client).andExpect(status().isConflict());

        jdbcTemplate.update("update availability_slots set hold_expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), slotId);
        withdraw(client).andExpect(status().isAccepted());
    }

    @Test
    void 상담사가_탈퇴하면_open_슬롯은_지워지고_완료된_세션은_남는다() throws Exception {
        UUID open = saveSlot(START);
        UUID past = saveSlot(START.minus(Duration.ofDays(7)));
        insertSession(past, "completed");

        withdraw(trainee).andExpect(status().isAccepted());

        assertThat(slotRepository.existsById(open)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sessions where provider_id = ?", Integer.class, trainee.getId()))
                .isEqualTo(1);
    }

    @Test
    void 취소된_세션이_가리키는_슬롯은_남지만_탈퇴_후에는_홀드할_수_없다() {
        UUID reopened = saveSlot(START);
        insertSession(reopened, "cancelled");

        slotService.deleteOpenSlotsOf(trainee.getId());
        assertThat(slotRepository.existsById(reopened)).isTrue();

        userRepository.findById(trainee.getId()).orElseThrow().withdraw(Instant.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> slotService.hold(reopened))
                .isInstanceOf(com.mindcare.global.error.AppException.class);
    }

    private ResultActions withdraw(User user) throws Exception {
        return mockMvc.perform(as(user, delete("/me")));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(UserRole role) {
        String uid = "wd-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }

    private UUID saveSlot(Instant start) {
        return slotRepository.saveAndFlush(
                AvailabilitySlot.open(trainee.getId(), start, start.plus(Duration.ofMinutes(30)))).getId();
    }

    private void insertSession(UUID slotId, String status) {
        AvailabilitySlot slot = slotRepository.findById(slotId).orElseThrow();
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, ?, 15000)
                """, UUID.randomUUID(), client.getId(), trainee.getId(), slotId,
                Timestamp.from(slot.getStartAt()), Timestamp.from(slot.getEndAt()), status);
    }
}
