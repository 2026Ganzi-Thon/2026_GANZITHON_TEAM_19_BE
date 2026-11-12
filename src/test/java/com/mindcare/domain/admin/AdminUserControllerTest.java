package com.mindcare.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** ADM-04 회원 검색, ADM-05 상태 변경·강제 로그아웃. */
@SpringBootTest
@Transactional
class AdminUserControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User admin;
    User client;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        admin = saveUser("admin", "관리자계정", UserRole.ADMIN, UserStatus.ACTIVE);
        client = saveUser("client", "검색대상내담자", UserRole.CLIENT, UserStatus.ACTIVE);
    }

    @Test
    void 닉네임_일부로_찾는다() throws Exception {
        saveUser("other", "전혀다른사람", UserRole.CLIENT, UserStatus.ACTIVE);

        mockMvc.perform(as(admin, get("/admin/users").param("q", "검색대상")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(client.getId().toString()))
                .andExpect(jsonPath("$.items[0].role").value("client"))
                .andExpect(jsonPath("$.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void 활동명으로도_찾는다() throws Exception {
        User trainee = saveUser("trainee", "내부닉네임", UserRole.TRAINEE, UserStatus.ACTIVE);
        jdbc.update("""
                insert into provider_profiles (user_id, track, display_name, real_name_enc, specialties, career)
                values (?, 'trainee', '따뜻한활동명', '\\x00'::bytea, '{}', '[]'::jsonb)
                """, trainee.getId());

        mockMvc.perform(as(admin, get("/admin/users").param("q", "따뜻한")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].display_name").value("따뜻한활동명"));
    }

    @Test
    void 역할_상태로_거르고_커서로_넘긴다() throws Exception {
        saveUser("s1", "정지수련생하나", UserRole.TRAINEE, UserStatus.SUSPENDED);
        saveUser("s2", "정지수련생둘", UserRole.TRAINEE, UserStatus.SUSPENDED);

        String body = mockMvc.perform(as(admin, get("/admin/users")
                        .param("role", "trainee").param("status", "suspended").param("q", "정지수련생").param("limit", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].nickname").value("정지수련생둘"))
                .andReturn().getResponse().getContentAsString();
        String cursor = body.replaceAll(".*\"next_cursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(as(admin, get("/admin/users")
                        .param("role", "trainee").param("status", "suspended").param("q", "정지수련생")
                        .param("limit", "1").param("cursor", cursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nickname").value("정지수련생하나"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void 검색어의_퍼센트는_글자로_취급한다() throws Exception {
        mockMvc.perform(as(admin, get("/admin/users").param("q", "%")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 어드민이_아니면_403() throws Exception {
        mockMvc.perform(as(client, get("/admin/users")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void 정지하면_강제_로그아웃과_감사_로그가_남는다() throws Exception {
        Instant until = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"suspended\",\"reason\":\"반복된 노쇼\",\"until\":\"" + until + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("suspended"))
                .andExpect(jsonPath("$.suspended_until").value(until.toString()));
        em.flush();

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, client.getId()))
                .isEqualTo("suspended");
        assertThat(jdbc.queryForObject("select tokens_revoked_at is not null from users where id = ?", Boolean.class,
                client.getId())).isTrue();
        assertThat(jdbc.queryForObject(
                "select reason from audit_logs where action = 'change_user_status' and target_id = ?", String.class,
                client.getId())).isEqualTo("suspended: 반복된 노쇼");
    }

    @Test
    void 다시_active로_돌리면_해제_시각이_지워진다() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"restricted\",\"reason\":\"예약 남용\",\"until\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted_until").value("2099-01-01T00:00:00Z"));

        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"active\",\"reason\":\"소명 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.restricted_until").isEmpty());
    }

    @Test
    void 사유가_없으면_422() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())), "{\"status\":\"banned\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 이용_제한은_내담자만() throws Exception {
        User trainee = saveUser("trainee", "제한불가수련생", UserRole.TRAINEE, UserStatus.ACTIVE);

        mockMvc.perform(json(as(admin, patch("/admin/users/" + trainee.getId())),
                        "{\"status\":\"restricted\",\"reason\":\"테스트\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("client_only"));
    }

    @Test
    void 심사_대기는_바로_active로_올릴_수_없다() throws Exception {
        User pending = saveUser("pending", "심사대기수련생", UserRole.TRAINEE, UserStatus.PENDING_REVIEW);

        mockMvc.perform(json(as(admin, patch("/admin/users/" + pending.getId())),
                        "{\"status\":\"active\",\"reason\":\"테스트\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("credential_review_required"));
    }

    @Test
    void 심사_상태로는_바꿀_수_없다() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"pending_review\",\"reason\":\"테스트\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("not_allowed"));
    }

    @Test
    void 지난_시각으로는_정지할_수_없다() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"suspended\",\"reason\":\"테스트\",\"until\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("until"));
    }

    @Test
    void 다른_어드민과_자기_자신은_못_바꾼다() throws Exception {
        User otherAdmin = saveUser("admin2", "다른관리자", UserRole.ADMIN, UserStatus.ACTIVE);

        mockMvc.perform(json(as(admin, patch("/admin/users/" + otherAdmin.getId())),
                        "{\"status\":\"banned\",\"reason\":\"테스트\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(as(admin, patch("/admin/users/" + admin.getId())),
                        "{\"status\":\"banned\",\"reason\":\"테스트\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void 강제_로그아웃만_하면_상태는_그대로() throws Exception {
        mockMvc.perform(json(as(admin, post("/admin/users/" + client.getId() + "/revoke-tokens")),
                        "{\"reason\":\"기기 분실 신고\"}"))
                .andExpect(status().isNoContent());
        em.flush();

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, client.getId()))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject("select tokens_revoked_at is not null from users where id = ?", Boolean.class,
                client.getId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'revoke_tokens' and target_id = ?",
                Integer.class, client.getId())).isEqualTo(1);
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
                .firebaseUid("uid-admin-user-" + name)
                .role(role)
                .nickname(nickname)
                .status(status)
                .build());
    }
}
