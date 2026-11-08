package com.mindcare.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.payment.entity.PaymentStatus;
import com.mindcare.domain.payment.repository.PaymentRepository;
import com.mindcare.domain.payment.service.PaymentConfirmedEvent;
import com.mindcare.domain.payment.service.PaymentService;
import com.mindcare.domain.payment.service.PaymentService.RefundReason;
import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import com.mindcare.domain.slot.repository.SlotRepository;
import com.mindcare.domain.slot.service.SlotService;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 본인인증 → 결제 준비 → 결제 확정 → 환불 흐름 (TRD FR-BKG-03·04).
 * 세션은 BE-2 테이블이라 테스트에서는 SQL로 직접 넣는다.
 */
@SpringBootTest
@Transactional
@RecordApplicationEvents
class PaymentFlowTest {

    private static final Instant START = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);

    @Autowired WebApplicationContext context;
    @Autowired IdempotencyFilter idempotencyFilter;
    @Autowired UserRepository userRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired SlotService slotService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationEvents events;
    @Autowired EntityManager entityManager;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;
    User trainee;
    UUID slotId;
    UUID sessionId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(idempotencyFilter).build();
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
        slotId = slotRepository.saveAndFlush(
                AvailabilitySlot.open(trainee.getId(), START, START.plus(Duration.ofMinutes(30)))).getId();
        slotService.hold(slotId);
        sessionId = insertPendingSession(client, trainee, slotId, START);
    }

    @Test
    void 본인인증_응답에는_개인정보가_없고_DB에는_암호화되어_저장된다() throws Exception {
        verifyIdentity(client, "010-1234-5678")
                .andExpect(status().isOk())
                .andExpect(content().json("{\"verified\": true}", true));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select name_enc, provider from identity_verifications where user_id = ?", client.getId());
        assertThat(new String((byte[]) row.get("name_enc"))).doesNotContain("김지원");
        assertThat(row.get("provider")).isEqualTo("mock");
    }

    @Test
    void 다른_계정에서_인증한_휴대폰_번호는_409() throws Exception {
        User other = saveUser(UserRole.CLIENT);
        verifyIdentity(other, "01011112222").andExpect(status().isOk());

        verifyIdentity(client, "010-1111-2222")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_REGISTERED"));
    }

    @Test
    void 본인인증_없이_결제_준비하면_409() throws Exception {
        prepare(client, sessionId, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.details.reason").value("identity_required"));
    }

    @Test
    void 멱등_키가_없으면_400() throws Exception {
        mockMvc.perform(as(client, post("/payments/prepare"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\": \"" + sessionId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 결제_준비는_세션_가격으로_주문을_만들고_같은_키로_다시_보내면_첫_응답을_그대로_준다() throws Exception {
        verifyIdentity(client, "01022223333").andExpect(status().isOk());
        String key = newKey();

        MvcResult first = prepare(client, sessionId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.provider").value("mock"))
                .andReturn();
        prepare(client, sessionId, key)
                .andExpect(status().isOk())
                .andExpect(content().json(first.getResponse().getContentAsString(), true));
    }

    @Test
    void 홀드가_만료되면_결제_준비는_410_HOLD_EXPIRED() throws Exception {
        verifyIdentity(client, "01033334444").andExpect(status().isOk());
        jdbcTemplate.update("update availability_slots set hold_expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), slotId);
        entityManager.clear(); // SQL로 바꾼 값을 JPA가 다시 읽게

        prepare(client, sessionId, newKey())
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("HOLD_EXPIRED"));
    }

    @Test
    void 결제_확정하면_paid_슬롯_booked_세션_확정_이벤트가_발행되고_재요청은_첫_응답을_준다() throws Exception {
        String orderId = prepareOrder();
        String key = newKey();

        MvcResult first = confirm(client, orderId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.session_id").value(sessionId.toString()))
                .andReturn();

        AvailabilitySlot slot = slotRepository.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(slot.getSessionId()).isEqualTo(sessionId);
        assertThat(events.stream(PaymentConfirmedEvent.class))
                .singleElement().extracting(PaymentConfirmedEvent::sessionId).isEqualTo(sessionId);

        confirm(client, orderId, key)
                .andExpect(status().isOk())
                .andExpect(content().json(first.getResponse().getContentAsString(), true));
        confirm(client, orderId, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void 남의_주문은_확정할_수_없다() throws Exception {
        String orderId = prepareOrder();
        User other = saveUser(UserRole.CLIENT);

        confirm(other, orderId, newKey())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_RESOURCE"));
    }

    @Test
    void 어드민_수동_환불은_전액_환불되고_내담자는_못_한다() throws Exception {
        String orderId = prepareOrder();
        confirm(client, orderId, newKey()).andExpect(status().isOk());
        UUID paymentId = paymentRepository.findBySessionId(sessionId).orElseThrow().getId();
        User admin = saveUser(UserRole.ADMIN);

        mockMvc.perform(as(client, post("/payments/" + paymentId + "/refund"))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"test\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(admin, post("/payments/" + paymentId + "/refund"))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"상담사 사정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("refunded"))
                .andExpect(jsonPath("$.refund_amount").value(15000));
    }

    @Test
    void 내담자_취소는_24시간_전이면_전액_이내면_환불_없음() throws Exception {
        String orderId = prepareOrder();
        confirm(client, orderId, newKey()).andExpect(status().isOk());

        // 시작이 3일 뒤라 24시간 전 → 전액
        assertThat(paymentService.refundForCancellation(sessionId, RefundReason.CLIENT_CANCEL)).isEqualTo(15000);
        assertThat(paymentRepository.findBySessionId(sessionId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 시작_24시간_이내_내담자_취소는_환불하지_않는다() throws Exception {
        String orderId = prepareOrder();
        confirm(client, orderId, newKey()).andExpect(status().isOk());
        jdbcTemplate.update("update sessions set scheduled_start = ? where id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofHours(2))), sessionId);

        assertThat(paymentService.refundForCancellation(sessionId, RefundReason.CLIENT_CANCEL)).isZero();
        assertThat(paymentService.refundForCancellation(sessionId, RefundReason.PROVIDER_CANCEL)).isEqualTo(15000);
    }

    private String prepareOrder() throws Exception {
        verifyIdentity(client, "0104444" + (1000 + (int) (Math.random() * 8999))).andExpect(status().isOk());
        String body = prepare(client, sessionId, newKey()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"order_id\":\"([^\"]+)\".*", "$1");
    }

    private org.springframework.test.web.servlet.ResultActions verifyIdentity(User user, String phone) throws Exception {
        return mockMvc.perform(as(user, post("/identity/verify"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"김지원\", \"birth\": \"1999-03-01\", \"phone\": \"" + phone + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions prepare(User user, UUID session, String key) throws Exception {
        return mockMvc.perform(as(user, post("/payments/prepare"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\": \"" + session + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(User user, String orderId, String key) throws Exception {
        return mockMvc.perform(as(user, post("/payments/confirm"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"order_id\": \"" + orderId + "\"}"));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private User saveUser(UserRole role) {
        String uid = "pay-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }

    private UUID insertPendingSession(User client, User provider, UUID slot, Instant start) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?, 'coaching', ?, ?, ?, ?, ?, 'pending_payment', 15000)
                """, id, client.getId(), provider.getId(), slot,
                Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
        return id;
    }
}
