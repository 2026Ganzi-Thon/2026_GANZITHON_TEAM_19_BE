package com.mindcare.domain.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.intake.service.IntakeTagger;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** INT-01 접수 시 AI-01 태깅 결과를 저장하고, AI 실패 시에는 fallback으로 접수를 계속한다. */
@SpringBootTest
@Transactional
class IntakeTaggingTest {

    private static final String TEXT = "요즘 회사 일이 너무 많아서 주말에도 쉬지 못하고 아무것도 하기 싫어요";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean TokenVerifier tokenVerifier;
    @MockitoBean IntakeTagger intakeTagger;

    MockMvc mockMvc;
    User client;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String uid = "int-" + UUID.randomUUID();
        client = userRepository.saveAndFlush(User.builder()
                .firebaseUid(uid).role(UserRole.CLIENT).nickname(uid.substring(0, 20)).status(UserStatus.ACTIVE).build());
        given(tokenVerifier.verify("token-" + uid)).willReturn(uid);
    }

    @Test
    void AI_태깅이_성공하면_태그를_응답하고_ok로_저장한다() throws Exception {
        given(intakeTagger.tag(eq("burnout"), anyString())).willReturn(Optional.of(Map.of(
                "emotions", List.of("지침", "무기력"), "distortions", List.of("과잉일반화"), "keywords", List.of("번아웃", "과로"))));

        createIntake()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai_fallback").value(false))
                .andExpect(jsonPath("$.ai_tags.emotions[0]").value("지침"))
                .andExpect(jsonPath("$.ai_tags.keywords[1]").value("과로"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select ai_status, ai_tags::text as tags from intakes where client_id = ?", client.getId());
        assertThat(row.get("ai_status")).isEqualTo("ok");
        assertThat((String) row.get("tags")).contains("번아웃");
    }

    @Test
    void AI_태깅이_실패해도_인테이크는_접수되고_fallback으로_저장한다() throws Exception {
        given(intakeTagger.tag(anyString(), anyString())).willReturn(Optional.empty());

        createIntake()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai_fallback").value(true))
                .andExpect(jsonPath("$.ai_tags.keywords").isEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "select ai_status from intakes where client_id = ?", String.class, client.getId())).isEqualTo("fallback");
    }

    @Test
    void AI_서버_주소가_없으면_호출하지_않고_빈_값을_돌려준다() {
        assertThat(new IntakeTagger("", "").tag("burnout", TEXT)).isEmpty();
    }

    private ResultActions createIntake() throws Exception {
        return mockMvc.perform(post("/intakes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-" + client.getFirebaseUid())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\": \"burnout\", \"text\": \"" + TEXT + "\"}"));
    }
}
