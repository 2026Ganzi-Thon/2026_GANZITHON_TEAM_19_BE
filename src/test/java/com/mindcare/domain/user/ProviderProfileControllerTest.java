package com.mindcare.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.ProviderProfileRepository;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest
@Transactional
class ProviderProfileControllerTest {

    private static final String TRAINEE_PROFILE = """
            {"display_name":"하늘","real_name":"김하늘","service_label":"coaching",
             "specialties":["직장 번아웃","자존감"],"bio":"편하게 이야기해요","school":"한국대학교 상담심리"}""";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired ProviderProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void 심사_중인_수련생이_프로필을_만들면_GET_me에도_나온다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        putProfile(trainee, TRAINEE_PROFILE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.track").value("trainee"))
                .andExpect(jsonPath("$.display_name").value("하늘"))
                .andExpect(jsonPath("$.real_name").value("김하늘"))
                .andExpect(jsonPath("$.specialties[0]").value("직장 번아웃"))
                .andExpect(jsonPath("$.accepts_supervision").value(true));

        mockMvc.perform(as(trainee, get("/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider_profile.display_name").value("하늘"));
    }

    @Test
    void 실명과_소속은_DB에_암호화되어_저장된다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        putProfile(trainee, TRAINEE_PROFILE).andExpect(status().isOk());

        byte[] realNameEnc = jdbc.queryForObject(
                "select real_name_enc from provider_profiles where user_id = ?", byte[].class, trainee.getId());
        assertThat(new String(realNameEnc, StandardCharsets.UTF_8)).doesNotContain("김하늘");
    }

    @Test
    void 다시_PUT하면_전체가_바뀐다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        putProfile(trainee, TRAINEE_PROFILE).andExpect(status().isOk());

        putProfile(trainee, """
                {"display_name":"맑은 하늘","real_name":"김하늘","service_label":"listener","specialties":["이별"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("맑은 하늘"))
                .andExpect(jsonPath("$.service_label").value("listener"))
                .andExpect(jsonPath("$.specialties.length()").value(1))
                .andExpect(jsonPath("$.bio").value(""))
                .andExpect(jsonPath("$.school").isEmpty());
        assertThat(profileRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 수련생은_service_label이_없으면_422() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        putProfile(trainee, """
                {"display_name":"하늘","real_name":"김하늘","specialties":[]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("service_label"));
    }

    @Test
    void 전문가는_service_label을_보내도_저장하지_않는다() throws Exception {
        User expert = saveUser("expert", UserRole.EXPERT, UserStatus.PENDING_REVIEW);

        putProfile(expert, """
                {"display_name":"윤 선생님","real_name":"윤상담","service_label":"buddy","specialties":["가족 관계"],
                 "career":[{"org":"마음상담센터","from":"2019-03"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.track").value("expert"))
                .andExpect(jsonPath("$.service_label").isEmpty())
                .andExpect(jsonPath("$.career[0].org").value("마음상담센터"));
    }

    @Test
    void 사전에_없는_분야_태그는_422() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        putProfile(trainee, """
                {"display_name":"하늘","real_name":"김하늘","service_label":"coaching","specialties":["우주 탐사"]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("unknown_tag"));
    }

    @Test
    void 분야_태그가_5개를_넘으면_422() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        putProfile(trainee, """
                {"display_name":"하늘","real_name":"김하늘","service_label":"coaching",
                 "specialties":["이별","자존감","가족 관계","연애 갈등","청년 진로","생활 리듬"]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("specialties"));
    }

    @Test
    void 다른_상담사와_활동명이_겹치면_409() throws Exception {
        User first = saveUser("first", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        User second = saveUser("second", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        putProfile(first, TRAINEE_PROFILE).andExpect(status().isOk());

        putProfile(second, TRAINEE_PROFILE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_TAKEN"))
                .andExpect(jsonPath("$.error.details.field").value("display_name"));
    }

    @Test
    void 내담자는_상담사_프로필을_만들_수_없다() throws Exception {
        User client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);

        putProfile(client, TRAINEE_PROFILE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 정지된_상담사는_프로필을_고칠_수_없다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.SUSPENDED);

        putProfile(trainee, TRAINEE_PROFILE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void 내담자는_승인된_상담사의_공개_프로필을_실명_없이_본다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.ACTIVE);
        putProfile(trainee, TRAINEE_PROFILE).andExpect(status().isOk());
        jdbc.update("""
                insert into credentials (id, user_id, kind, cert_name, status)
                values (gen_random_uuid(), ?, 'enrollment', '상담심리 석사 재학', 'approved'),
                       (gen_random_uuid(), ?, 'completion', '심사 중인 자격', 'submitted')""",
                trainee.getId(), trainee.getId());
        User client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);

        mockMvc.perform(as(client, get("/providers/" + trainee.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider_id").value(trainee.getId().toString()))
                .andExpect(jsonPath("$.display_name").value("하늘"))
                .andExpect(jsonPath("$.cert_names.length()").value(1))
                .andExpect(jsonPath("$.cert_names[0]").value("상담심리 석사 재학"))
                .andExpect(jsonPath("$.rating_count").value(0))
                .andExpect(jsonPath("$.real_name").doesNotExist())
                .andExpect(jsonPath("$.school").doesNotExist());
    }

    @Test
    void 심사_중인_상담사의_공개_프로필은_404() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);
        putProfile(trainee, TRAINEE_PROFILE).andExpect(status().isOk());
        User client = saveUser("client", UserRole.CLIENT, UserStatus.ACTIVE);

        mockMvc.perform(as(client, get("/providers/" + trainee.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void 상담사는_다른_상담사의_공개_프로필_API를_쓸_수_없다() throws Exception {
        User trainee = saveUser("trainee", UserRole.TRAINEE, UserStatus.ACTIVE);
        User other = saveUser("other", UserRole.TRAINEE, UserStatus.ACTIVE);

        mockMvc.perform(as(other, get("/providers/" + trainee.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    private ResultActions putProfile(User user, String body) throws Exception {
        return mockMvc.perform(as(user, put("/me/provider-profile"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, UserRole role, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-profile-" + name)
                .role(role)
                .nickname("프로필" + name)
                .status(status)
                .build());
    }
}
