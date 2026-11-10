package com.mindcare.domain.slot;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class SlotControllerTest {

    // 테스트 실행 시각과 상관없이 항상 미래인 시각 (분 단위로 맞춤)
    private static final Instant BASE = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired SlotService slotService;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User trainee;
    User expert;
    User client;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.ACTIVE);
        expert = saveUser("expert", UserRole.EXPERT, UserStatus.ACTIVE);
        client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);
    }

    @Test
    void 수련생_슬롯은_코칭_길이_30분으로_만들어지고_매주_반복된다() throws Exception {
        mockMvc.perform(as(trainee, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + BASE + "\"], \"repeat_weeks\": 2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].start_at").value(BASE.toString()))
                .andExpect(jsonPath("$[0].end_at").value(BASE.plus(Duration.ofMinutes(30)).toString()))
                .andExpect(jsonPath("$[0].status").value("open"))
                .andExpect(jsonPath("$[2].start_at").value(BASE.plus(Duration.ofDays(14)).toString()));
    }

    @Test
    void 전문가_슬롯은_50분이다() throws Exception {
        mockMvc.perform(as(expert, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + BASE + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].end_at").value(BASE.plus(Duration.ofMinutes(50)).toString()));
    }

    @Test
    void 기존_슬롯과_겹치면_422_SLOT_OVERLAP() throws Exception {
        slotRepository.saveAndFlush(AvailabilitySlot.open(trainee.getId(), BASE, BASE.plus(Duration.ofMinutes(30))));

        mockMvc.perform(as(trainee, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + BASE.plus(Duration.ofMinutes(15)) + "\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("SLOT_OVERLAP"));
    }

    @Test
    void 과거_시각은_422() throws Exception {
        mockMvc.perform(as(trainee, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + Instant.now().minus(Duration.ofHours(1)) + "\"]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 내담자는_슬롯을_등록할_수_없다() throws Exception {
        mockMvc.perform(as(client, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + BASE + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 정지된_상담사는_슬롯을_등록할_수_없다() throws Exception {
        User suspended = saveUser("suspended", UserRole.TRAINEE, UserStatus.SUSPENDED);

        mockMvc.perform(as(suspended, post("/me/slots"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"starts\": [\"" + BASE + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void 내담자는_상담사의_open_슬롯만_본다() throws Exception {
        AvailabilitySlot open = saveSlot(trainee, BASE);
        AvailabilitySlot held = saveSlot(trainee, BASE.plus(Duration.ofHours(1)));
        slotService.hold(held.getId());

        mockMvc.perform(as(client, get("/providers/" + trainee.getId() + "/slots")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(open.getId().toString()))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void 내담자_슬롯_조회는_14일을_넘을_수_없다() throws Exception {
        mockMvc.perform(as(client, get("/providers/" + trainee.getId() + "/slots"))
                        .param("from", BASE.toString())
                        .param("to", BASE.plus(Duration.ofDays(15)).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 상담사의_내_슬롯_목록에는_예약_상태가_보인다() throws Exception {
        AvailabilitySlot held = saveSlot(trainee, BASE);
        slotService.hold(held.getId());

        mockMvc.perform(as(trainee, get("/me/slots")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("held"));
    }

    @Test
    void open_슬롯은_삭제되고_예약된_슬롯은_409_SLOT_BOOKED() throws Exception {
        AvailabilitySlot open = saveSlot(trainee, BASE);
        AvailabilitySlot held = saveSlot(trainee, BASE.plus(Duration.ofHours(1)));
        slotService.hold(held.getId());

        mockMvc.perform(as(trainee, delete("/me/slots/" + open.getId())))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(trainee, delete("/me/slots/" + held.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_BOOKED"));
    }

    @Test
    void 남의_슬롯은_삭제할_수_없다() throws Exception {
        AvailabilitySlot slot = saveSlot(expert, BASE);

        mockMvc.perform(as(trainee, delete("/me/slots/" + slot.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_RESOURCE"));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, UserRole role, UserStatus status) {
        String uid = name + "-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(status).build());
    }

    private AvailabilitySlot saveSlot(User provider, Instant start) {
        return slotRepository.saveAndFlush(AvailabilitySlot.open(provider.getId(), start, start.plus(Duration.ofMinutes(30))));
    }
}
