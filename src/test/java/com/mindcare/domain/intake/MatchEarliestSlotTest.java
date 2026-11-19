package com.mindcare.domain.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mindcare.domain.intake.service.IntakeTagger;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** 매칭의 earliest_slot은 지난 슬롯이 아니라 아직 시작하지 않은 open 슬롯 중 가장 이른 시각이어야 한다. */
@SpringBootTest
@Transactional
class MatchEarliestSlotTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean TokenVerifier tokenVerifier;
    @MockitoBean IntakeTagger intakeTagger;

    MockMvc mockMvc;
    User client;
    User trainee;
    Instant future;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        given(intakeTagger.tag(anyString(), anyString())).willReturn(Optional.empty());
        client = saveUser(UserRole.CLIENT);
        trainee = saveUser(UserRole.TRAINEE);
        jdbcTemplate.update("""
                insert into provider_profiles (user_id, track, display_name, real_name_enc, specialties)
                values (?, 'trainee', ?, ?, array['burnout'])
                """, trainee.getId(), "slot-" + trainee.getId().toString().substring(0, 8), new byte[] {1});
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        insertSlot(hour.minus(Duration.ofDays(5)));
        future = hour.plus(Duration.ofDays(2));
        insertSlot(future);
    }

    @Test
    void 지난_open_슬롯은_가장_가까운_시간으로_보이지_않는다() throws Exception {
        String created = mockMvc.perform(as(client, post("/intakes")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"burnout\", \"text\": \"요즘 회사 일이 너무 많아서 주말에도 쉬지 못하고 지쳐요\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String intakeId = JsonPath.read(created, "$.intake_id");

        String matches = mockMvc.perform(as(client, get("/intakes/" + intakeId + "/matches")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> slots = JsonPath.read(matches,
                "$[?(@.provider_id == '" + trainee.getId() + "')].earliest_slot");

        assertThat(slots).hasSize(1);
        assertThat(Instant.parse(slots.get(0))).isEqualTo(future);
    }

    @Test
    void 지난_슬롯만_있는_상담사는_추천하지_않는다() throws Exception {
        jdbcTemplate.update("delete from availability_slots where provider_id = ? and start_at > now()", trainee.getId());

        String created = mockMvc.perform(as(client, post("/intakes")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"burnout\", \"text\": \"요즘 회사 일이 너무 많아서 주말에도 쉬지 못하고 지쳐요\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> providers = JsonPath.read(created, "$.matches[*].provider_id");

        assertThat(providers).doesNotContain(trainee.getId().toString());
    }

    private void insertSlot(Instant start) {
        jdbcTemplate.update("insert into availability_slots (id, provider_id, start_at, end_at, status) values (?, ?, ?, ?, 'open')",
                UUID.randomUUID(), trainee.getId(), Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(30))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(User user,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(UserRole role) {
        String uid = "slt-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(role).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
    }
}
