package com.mindcare.domain.user;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class MeControllerTest {

    private static final String TOKEN = "valid-token";
    private static final String UID = "firebase-uid-me-test";

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    TokenVerifier tokenVerifier;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void Bearer_형식이_아니면_401() throws Exception {
        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 잘못된_토큰이면_401() throws Exception {
        given(tokenVerifier.verify("bad-token")).willThrow(new AppException(ErrorCode.UNAUTHENTICATED));

        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 토큰은_맞는데_가입_전이면_404_USER_NOT_REGISTERED() throws Exception {
        given(tokenVerifier.verify(TOKEN)).willReturn(UID);

        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_REGISTERED"));
    }

    @Test
    void 가입한_사용자는_내_정보를_snake_case로_받고_firebase_uid는_노출되지_않는다() throws Exception {
        User user = saveUser(UserRole.CLIENT, UserStatus.ACTIVE);
        given(tokenVerifier.verify(TOKEN)).willReturn(UID);

        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.role").value("client"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.nickname").value("지친마케터"))
                .andExpect(jsonPath("$.ethics_training_required").value(false))
                .andExpect(jsonPath("$.firebase_uid").doesNotExist());
    }

    @Test
    void 심사_중인_계정도_내_정보는_볼_수_있다() throws Exception {
        saveUser(UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        given(tokenVerifier.verify(TOKEN)).willReturn(UID);

        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("trainee"))
                .andExpect(jsonPath("$.status").value("pending_review"));
    }

    @Test
    void 공개_경로는_토큰_없이_통과한다() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    private User saveUser(UserRole role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid(UID)
                .role(role)
                .nickname("지친마케터")
                .status(status)
                .build());
    }
}
