package com.mindcare.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.notification.repository.DeviceTokenRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** USR-02 닉네임 수정, USR-04 기기 토큰, USR-07 윤리교육 완료. */
@SpringBootTest
@Transactional
class MeUpdateControllerTest {

    private static final String FCM_TOKEN = "fcm-token-abc:APA91bExample";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User client;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        client = saveUser("client", "원래닉네임", UserRole.CLIENT, UserStatus.ACTIVE);
    }

    @Test
    void 닉네임을_바꾸면_GET_me에도_반영된다() throws Exception {
        mockMvc.perform(json(as(client, patch("/me")), "{\"nickname\":\" 새닉네임 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"));
        em.flush();
        assertThat(jdbc.queryForObject("select nickname from users where id = ?", String.class, client.getId()))
                .isEqualTo("새닉네임");
    }

    @Test
    void 지금_닉네임과_같으면_그대로_200() throws Exception {
        mockMvc.perform(json(as(client, patch("/me")), "{\"nickname\":\"원래닉네임\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 남이_쓰는_닉네임이면_409() throws Exception {
        saveUser("other", "남의닉네임", UserRole.CLIENT, UserStatus.ACTIVE);

        mockMvc.perform(json(as(client, patch("/me")), "{\"nickname\":\"남의닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"));
    }

    @Test
    void 닉네임_규칙은_가입과_같다() throws Exception {
        mockMvc.perform(json(as(client, patch("/me")), "{\"nickname\":\"운영자입니다\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("forbidden_word"));
    }

    @Test
    void 정지된_계정도_닉네임은_바꿀_수_있다() throws Exception {
        User suspended = saveUser("suspended", "정지된사람", UserRole.CLIENT, UserStatus.SUSPENDED);

        mockMvc.perform(json(as(suspended, patch("/me")), "{\"nickname\":\"새출발\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 정지된_수련생이_윤리교육을_마치면_플래그가_꺼진다() throws Exception {
        User trainee = saveUser("trainee", "정지수련생", UserRole.TRAINEE, UserStatus.SUSPENDED);
        jdbc.update("update users set ethics_training_required = true where id = ?", trainee.getId());
        em.clear();

        mockMvc.perform(as(trainee, post("/me/ethics-training/complete")))
                .andExpect(status().isNoContent());
        em.flush();
        assertThat(jdbc.queryForObject("select ethics_training_required from users where id = ?", Boolean.class,
                trainee.getId())).isFalse();
    }

    @Test
    void 윤리교육_완료는_수련생만() throws Exception {
        mockMvc.perform(as(client, post("/me/ethics-training/complete")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 기기_토큰을_등록하고_해제한다() throws Exception {
        mockMvc.perform(json(as(client, post("/me/device-tokens")), "{\"token\":\"" + FCM_TOKEN + "\"}"))
                .andExpect(status().isNoContent());
        // 같은 토큰 다시 등록해도 한 줄
        mockMvc.perform(json(as(client, post("/me/device-tokens")), "{\"token\":\"" + FCM_TOKEN + "\"}"))
                .andExpect(status().isNoContent());
        em.flush();
        assertThat(deviceTokenRepository.findByUserId(client.getId())).hasSize(1);
        assertThat(deviceTokenRepository.findByToken(FCM_TOKEN).orElseThrow().getPlatform()).isEqualTo("web");

        mockMvc.perform(as(client, delete("/me/device-tokens/" + FCM_TOKEN)))
                .andExpect(status().isNoContent());
        em.flush();
        assertThat(deviceTokenRepository.findByToken(FCM_TOKEN)).isEmpty();
    }

    @Test
    void 같은_브라우저에서_다른_계정으로_등록하면_토큰_주인이_바뀐다() throws Exception {
        User other = saveUser("other", "다른계정", UserRole.CLIENT, UserStatus.ACTIVE);
        mockMvc.perform(json(as(client, post("/me/device-tokens")), "{\"token\":\"" + FCM_TOKEN + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(json(as(other, post("/me/device-tokens")), "{\"token\":\"" + FCM_TOKEN + "\"}"))
                .andExpect(status().isNoContent());
        em.flush();

        assertThat(deviceTokenRepository.findByToken(FCM_TOKEN).orElseThrow().getUserId()).isEqualTo(other.getId());
        assertThat(deviceTokenRepository.findByUserId(client.getId())).isEmpty();
    }

    @Test
    void 남의_토큰은_해제되지_않는다() throws Exception {
        User other = saveUser("other", "다른계정", UserRole.CLIENT, UserStatus.ACTIVE);
        mockMvc.perform(json(as(other, post("/me/device-tokens")), "{\"token\":\"" + FCM_TOKEN + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(client, delete("/me/device-tokens/" + FCM_TOKEN)))
                .andExpect(status().isNoContent());
        em.flush();
        assertThat(deviceTokenRepository.findByToken(FCM_TOKEN)).isPresent();
    }

    @Test
    void 플랫폼_값이_틀리면_422() throws Exception {
        mockMvc.perform(json(as(client, post("/me/device-tokens")),
                        "{\"token\":\"" + FCM_TOKEN + "\",\"platform\":\"desktop\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, String nickname, UserRole role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-me-update-" + name)
                .role(role)
                .nickname(nickname)
                .status(status)
                .build());
    }
}
