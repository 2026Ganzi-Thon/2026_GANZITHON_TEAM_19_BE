package com.mindcare.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
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

@SpringBootTest
@Transactional
class RegisterControllerTest {

    private static final String TOKEN = "valid-token";
    private static final String UID = "firebase-uid-register-test";
    private static final String REQUIRED_CONSENTS = """
            [{"type":"terms","version":"1.0"},{"type":"privacy","version":"1.0"},
             {"type":"sensitive","version":"1.0"},{"type":"age14","version":"1.0"}]""";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        given(tokenVerifier.verify(TOKEN)).willReturn(UID);
    }

    @Test
    void 내담자는_가입하면_바로_active이고_동의가_저장된다() throws Exception {
        register("client", "지친마케터", REQUIRED_CONSENTS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("client"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.nickname").value("지친마케터"))
                .andExpect(jsonPath("$.provider_profile").isEmpty());

        User user = userRepository.findByFirebaseUid(UID).orElseThrow();
        em.flush(); // 동의는 save만 하고 아직 flush 전이라 JDBC로 세기 전에 내보낸다
        Integer consents = jdbc.queryForObject(
                "select count(*) from consents where user_id = ?", Integer.class, user.getId());
        assertThat(consents).isEqualTo(4);
    }

    @Test
    void 수련생은_심사_전이라_pending_review로_가입된다() throws Exception {
        register("trainee", "새내기코치", REQUIRED_CONSENTS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_review"));
    }

    @Test
    void 가입하면_GET_me가_404에서_200으로_바뀐다() throws Exception {
        register("client", "지친마케터", REQUIRED_CONSENTS).andExpect(status().isCreated());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("지친마케터"));
    }

    @Test
    void 선택_동의가_없으면_수신_플래그는_켜지지_않는다() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"client","nickname":"지친마케터","consents":%s,
                                 "marketing_opt_in":true}""".formatted(REQUIRED_CONSENTS)))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByFirebaseUid(UID).orElseThrow().isMarketingOptIn()).isFalse();
    }

    @Test
    void 필수_동의가_하나라도_빠지면_422() throws Exception {
        register("client", "지친마케터", """
                [{"type":"terms","version":"1.0"},{"type":"privacy","version":"1.0"},
                 {"type":"sensitive","version":"1.0"}]""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("required_missing"));
        assertThat(userRepository.findByFirebaseUid(UID)).isEmpty();
    }

    @Test
    void 이미_가입한_계정이면_409_ALREADY_REGISTERED() throws Exception {
        saveUser(UID, "먼저가입");

        register("client", "다른닉네임", REQUIRED_CONSENTS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_REGISTERED"));
    }

    @Test
    void 닉네임이_겹치면_409_NICKNAME_TAKEN() throws Exception {
        saveUser("someone-else", "지친마케터");

        register("client", "지친마케터", REQUIRED_CONSENTS)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"));
    }

    @Test
    void 닉네임_규칙에_안_맞으면_422() throws Exception {
        register("client", "a", REQUIRED_CONSENTS)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("size"));
        register("client", "010-1234", REQUIRED_CONSENTS)
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("invalid_characters"));
        register("client", "연락01012345", REQUIRED_CONSENTS)
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("looks_like_contact"));
        register("client", "진짜관리자", REQUIRED_CONSENTS)
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("forbidden_word"));
    }

    @Test
    void 닉네임_앞뒤_공백은_지우고_저장한다() throws Exception {
        register("client", "  지친마케터 ", REQUIRED_CONSENTS)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("지친마케터"));
    }

    @Test
    void 어드민으로는_가입할_수_없다() throws Exception {
        register("admin", "운영해요", REQUIRED_CONSENTS)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("role"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions register(String role, String nickname, String consents) throws Exception {
        return mockMvc.perform(post("/auth/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"role":"%s","nickname":"%s","consents":%s}""".formatted(role, nickname, consents)));
    }

    private void saveUser(String firebaseUid, String nickname) {
        userRepository.saveAndFlush(User.builder()
                .firebaseUid(firebaseUid)
                .role(UserRole.CLIENT)
                .nickname(nickname)
                .status(UserStatus.ACTIVE)
                .build());
    }
}
