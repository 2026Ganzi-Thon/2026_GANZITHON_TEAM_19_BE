package com.mindcare.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.session.entity.SessionStatus;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import com.mindcare.domain.slot.repository.SlotRepository;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import com.mindcare.global.web.IdempotencyFilter;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 예약 생성(SES-01) → 결제 확정(PAY-03) → 세션 confirmed (TRD 13-2, 15-1). */
@SpringBootTest
@Transactional
class SessionReservationFlowTest {

    private static final Instant START = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);

    @Autowired WebApplicationContext context;
    @Autowired IdempotencyFilter idempotencyFilter;
    @Autowired UserRepository userRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;
    User trainee;
    UUID slotId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(idempotencyFilter).build();
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
        slotId = saveSlot(trainee, START);
    }

    @Test
    void 예약하면_pending_payment_세션이_생기고_슬롯은_held() throws Exception {
        String body = reserve(client, "{\"slot_id\": \"" + slotId + "\"}", newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_payment"))
                .andExpect(jsonPath("$.track").value("coaching"))
                .andExpect(jsonPath("$.price").value(15000))
                .andExpect(jsonPath("$.provider_id").value(trainee.getId().toString()))
                .andExpect(jsonPath("$.hold_expires_at").exists())
                .andReturn().getResponse().getContentAsString();

        UUID sessionId = sessionIdOf(body);
        entityManager.flush();
        entityManager.clear();
        assertThat(slotRepository.findById(slotId).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getSlotId()).isEqualTo(slotId);
    }

    @Test
    void 같은_키로_다시_보내면_첫_응답을_주고_다른_키로_같은_슬롯은_409_SLOT_TAKEN() throws Exception {
        String key = newKey();
        String request = "{\"slot_id\": \"" + slotId + "\"}";
        MvcResult first = reserve(client, request, key).andExpect(status().isCreated()).andReturn();

        reserve(client, request, key)
                .andExpect(status().isCreated())
                .andExpect(content().json(first.getResponse().getContentAsString(), true));
        reserve(saveUser(UserRole.CLIENT), request, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_TAKEN"));
    }

    @Test
    void 멱등_키가_없으면_400() throws Exception {
        mockMvc.perform(as(client, post("/sessions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slot_id\": \"" + slotId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 상담사는_예약할_수_없다() throws Exception {
        reserve(trainee, "{\"slot_id\": \"" + slotId + "\"}", newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 남의_인테이크로는_예약할_수_없다() throws Exception {
        UUID othersIntake = insertIntake(saveUser(UserRole.CLIENT));

        reserve(client, "{\"slot_id\": \"" + slotId + "\", \"intake_id\": \"" + othersIntake + "\"}", newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_RESOURCE"));
    }

    @Test
    void 재예약은_완료된_내_세션이고_같은_상담사여야_하며_인테이크를_이어받는다() throws Exception {
        UUID intakeId = insertIntake(client);
        UUID previous = insertSession(client, trainee, saveSlot(trainee, START.minus(Duration.ofDays(7))), "completed", intakeId);

        String body = reserve(client, "{\"slot_id\": \"" + slotId + "\", \"rebook_of\": \"" + previous + "\"}", newKey())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        entityManager.flush();
        entityManager.clear();
        assertThat(sessionRepository.findById(sessionIdOf(body)).orElseThrow().getIntakeId()).isEqualTo(intakeId);
    }

    @Test
    void 완료되지_않았거나_다른_상담사의_세션으로는_재예약할_수_없다() throws Exception {
        UUID notCompleted = insertSession(client, trainee, saveSlot(trainee, START.minus(Duration.ofDays(7))), "confirmed", null);
        User otherTrainee = saveUser(UserRole.TRAINEE);
        UUID otherProvider = insertSession(client, otherTrainee, saveSlot(otherTrainee, START.minus(Duration.ofDays(7))), "completed", null);

        reserve(client, "{\"slot_id\": \"" + slotId + "\", \"rebook_of\": \"" + notCompleted + "\"}", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_ELIGIBLE"));
        reserve(client, "{\"slot_id\": \"" + saveSlot(trainee, START.plus(Duration.ofDays(1))) + "\", \"rebook_of\": \"" + otherProvider + "\"}", newKey())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_ELIGIBLE"));
    }

    @Test
    void 예약하고_결제_확정하면_세션이_confirmed() throws Exception {
        String body = reserve(client, "{\"slot_id\": \"" + slotId + "\"}", newKey())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID sessionId = sessionIdOf(body);

        mockMvc.perform(as(client, post("/identity/verify")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"김지원\", \"birth\": \"1999-03-01\", \"phone\": \"0105555" + (1000 + (int) (Math.random() * 8999)) + "\"}"))
                .andExpect(status().isOk());
        String order = mockMvc.perform(as(client, post("/payments/prepare")).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String orderId = order.replaceAll(".*\"order_id\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(as(client, post("/payments/confirm")).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"order_id\": \"" + orderId + "\"}"))
                .andExpect(status().isOk());

        entityManager.flush();
        entityManager.clear();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.CONFIRMED);
        assertThat(slotRepository.findById(slotId).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    private ResultActions reserve(User user, String json, String key) throws Exception {
        return mockMvc.perform(as(user, post("/sessions"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static UUID sessionIdOf(String body) {
        return UUID.fromString(body.replaceAll(".*\"session_id\":\"([^\"]+)\".*", "$1"));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private User saveUser(UserRole role) {
        String uid = "ses-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }

    private UUID saveSlot(User provider, Instant start) {
        return slotRepository.saveAndFlush(
                AvailabilitySlot.open(provider.getId(), start, start.plus(Duration.ofMinutes(30)))).getId();
    }

    private UUID insertIntake(User owner) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into intakes (id, client_id, category, text_enc) values (?, ?, 'burnout', ?)",
                id, owner.getId(), new byte[] {1});
        return id;
    }

    /** 과거 세션은 예약 API로 만들 수 없어서 SQL로 넣는다 (슬롯 시각 검증 우회). */
    private UUID insertSession(User owner, User provider, UUID slot, String status, UUID intakeId) {
        UUID id = UUID.randomUUID();
        Instant start = START.minus(Duration.ofDays(7));
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, intake_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, ?, ?, 15000)
                """, id, owner.getId(), provider.getId(), intakeId, slot,
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))), status);
        return id;
    }
}
