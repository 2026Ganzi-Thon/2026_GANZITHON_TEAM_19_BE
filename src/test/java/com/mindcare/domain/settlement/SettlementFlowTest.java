package com.mindcare.domain.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.settlement.service.SettlementService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
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

@SpringBootTest
@Transactional
class SettlementFlowTest {

    private static final String THIS_MONTH = YearMonth.now(ZoneId.of("Asia/Seoul")).toString();

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired SettlementService settlementService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User trainee;
    User expert;
    User client;
    User admin;
    int slotOffset;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.ACTIVE);
        expert = saveUser("expert", UserRole.EXPERT, UserStatus.ACTIVE);
        client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);
        admin = saveUser("admin", UserRole.ADMIN, UserStatus.ACTIVE);
    }

    @Test
    void 코칭_세션이_끝나면_수수료_20퍼센트를_뺀_정산이_pending으로_생긴다() throws Exception {
        UUID session = insertSession(trainee, "coaching", 15000);
        settlementService.createForSession(session, trainee.getId(), "coaching", 15000, false);
        em.flush();

        mockMvc.perform(as(trainee, get("/me/settlements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(THIS_MONTH))
                .andExpect(jsonPath("$.items[0].kind").value("session"))
                .andExpect(jsonPath("$.items[0].session_id").value(session.toString()))
                .andExpect(jsonPath("$.items[0].gross").value(15000))
                .andExpect(jsonPath("$.items[0].fee").value(3000))
                .andExpect(jsonPath("$.items[0].net").value(12000))
                .andExpect(jsonPath("$.items[0].status").value("pending"))
                .andExpect(jsonPath("$.totals.pending").value(12000))
                .andExpect(jsonPath("$.totals.paid").value(0));
    }

    @Test
    void 같은_세션으로_두_번_불러도_정산은_한_건() {
        UUID session = insertSession(trainee, "coaching", 15000);
        UUID first = settlementService.createForSession(session, trainee.getId(), "coaching", 15000, false);
        UUID second = settlementService.createForSession(session, trainee.getId(), "coaching", 15000, false);
        em.flush();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from settlements where session_id = ?", Integer.class, session))
                .isEqualTo(1);
    }

    @Test
    void 연계_첫_전문_세션은_locked로_생기고_슈퍼비전이_끝나면_풀린다() throws Exception {
        UUID session = insertSession(expert, "professional", 80000);
        settlementService.createForSession(session, expert.getId(), "professional", 80000, true);
        em.flush();

        mockMvc.perform(as(expert, get("/me/settlements")))
                .andExpect(jsonPath("$.items[0].status").value("locked"))
                .andExpect(jsonPath("$.items[0].status_reason").value("supervision_pending"))
                .andExpect(jsonPath("$.totals.locked").value(64000));

        // 슈퍼비전 제출 → 슈퍼비전 정산 + (모두 끝났다고 BE-2가 판단하면) 잠금 해제
        UUID supervision = insertSupervision(expert, trainee);
        settlementService.createForSupervision(supervision, expert.getId());
        assertThat(settlementService.unlockForSession(session)).isTrue();
        assertThat(settlementService.unlockForSession(session)).isFalse(); // 두 번째는 아무 일 없음
        em.flush();

        mockMvc.perform(as(expert, get("/me/settlements")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totals.locked").value(0))
                .andExpect(jsonPath("$.totals.pending").value(64000 + 20000))
                .andExpect(jsonPath("$.items[?(@.kind == 'supervision')].net").value(20000));
    }

    @Test
    void 어드민이_pending을_지급하면_paid가_되고_알림과_감사_로그가_남는다() throws Exception {
        UUID id = createPendingCoaching();

        mockMvc.perform(json(as(admin, post("/admin/settlements/pay")), "{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_count").value(1))
                .andExpect(jsonPath("$.paid_net").value(12000));
        em.flush();

        assertThat(jdbc.queryForObject("select status from settlements where id = ?", String.class, id))
                .isEqualTo("paid");
        assertThat(jdbc.queryForObject(
                "select count(*) from notifications where user_id = ? and type = 'settlement_paid'", Integer.class,
                trainee.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'pay_settlement' and target_id = ?", Integer.class,
                id)).isEqualTo(1);
    }

    @Test
    void pending이_아닌_정산이_섞여_있으면_전부_안_바뀌고_409() throws Exception {
        UUID pending = createPendingCoaching();
        UUID lockedSession = insertSession(expert, "professional", 80000);
        UUID locked = settlementService.createForSession(lockedSession, expert.getId(), "professional", 80000, true);
        em.flush();

        mockMvc.perform(json(as(admin, post("/admin/settlements/pay")),
                        "{\"ids\":[\"" + pending + "\",\"" + locked + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"))
                .andExpect(jsonPath("$.error.details.not_pending[0]").value(locked.toString()));
        em.clear();
        assertThat(jdbc.queryForObject("select status from settlements where id = ?", String.class, pending))
                .isEqualTo("pending");
    }

    @Test
    void 없는_정산_id면_404() throws Exception {
        mockMvc.perform(json(as(admin, post("/admin/settlements/pay")), "{\"ids\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 어드민_목록은_상태_종류로_거르고_CSV로도_받는다() throws Exception {
        createPendingCoaching();
        UUID lockedSession = insertSession(expert, "professional", 80000);
        settlementService.createForSession(lockedSession, expert.getId(), "professional", 80000, true);
        em.flush();

        mockMvc.perform(as(admin, get("/admin/settlements").param("month", THIS_MONTH).param("status", "locked")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.provider_id == '%s')]".formatted(expert.getId())).isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.status != 'locked')]").isEmpty());

        mockMvc.perform(as(admin, get("/admin/settlements").param("kind", "session")
                        .accept(MediaType.parseMediaType("text/csv"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("settlements-current.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id,provider_id,kind,session_id,supervision_id,gross,fee,net,status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(expert.getId().toString())));
    }

    @Test
    void 남의_정산은_내_목록에_안_나온다() throws Exception {
        createPendingCoaching();
        User otherTrainee = saveUser("other", UserRole.TRAINEE, UserStatus.ACTIVE);

        mockMvc.perform(as(otherTrainee, get("/me/settlements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 내담자는_정산_API를_못_쓰고_상담사는_어드민_정산을_못_쓴다() throws Exception {
        mockMvc.perform(as(client, get("/me/settlements")))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(trainee, get("/admin/settlements")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(as(trainee, post("/admin/settlements/pay")), "{\"ids\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 정지된_상담사도_정산_내역은_본다() throws Exception {
        User suspended = saveUser("suspended", UserRole.TRAINEE, UserStatus.SUSPENDED);

        mockMvc.perform(as(suspended, get("/me/settlements"))).andExpect(status().isOk());
    }

    @Test
    void month_형식이_틀리면_422() throws Exception {
        mockMvc.perform(as(trainee, get("/me/settlements").param("month", "2026/11")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("month"));
    }

    private UUID createPendingCoaching() {
        UUID session = insertSession(trainee, "coaching", 15000);
        UUID id = settlementService.createForSession(session, trainee.getId(), "coaching", 15000, false);
        em.flush();
        return id;
    }

    // 세션 행은 BE-2 도메인이라 테스트에서는 FK만 맞춰서 직접 넣는다
    private UUID insertSession(User provider, String track, int price) {
        Instant start = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS)
                .plus(Duration.ofHours(slotOffset++));
        Instant end = start.plus(Duration.ofMinutes(30));
        UUID slot = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?,?,?,?,'booked')",
                slot, provider.getId(), java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        jdbc.update("""
                insert into sessions (id, track, client_id, provider_id, slot_id, scheduled_start, scheduled_end, status, price)
                values (?,?,?,?,?,?,?,'completed',?)""",
                session, track, client.getId(), provider.getId(), slot, java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(end), price);
        return session;
    }

    private UUID insertSupervision(User expertUser, User traineeUser) {
        UUID coachingSession = insertSession(traineeUser, "coaching", 15000);
        UUID request = UUID.randomUUID();
        UUID supervision = UUID.randomUUID();
        jdbc.update("insert into supervision_requests (id, trainee_id, session_id, question_enc) values (?,?,?,?)",
                request, traineeUser.getId(), coachingSession, new byte[] {1});
        jdbc.update("""
                insert into supervisions (id, kind, request_id, coaching_session_id, expert_id, trainee_id, status)
                values (?, 'request', ?, ?, ?, ?, 'submitted')""",
                supervision, request, coachingSession, expertUser.getId(), traineeUser.getId());
        return supervision;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, UserRole role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-settlement-" + name)
                .role(role)
                .nickname("정산" + name)
                .status(status)
                .build());
    }
}
