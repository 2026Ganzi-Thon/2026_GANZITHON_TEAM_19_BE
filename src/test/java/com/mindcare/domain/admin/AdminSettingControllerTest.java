package com.mindcare.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
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

/**
 * ADM-06·07 운영 설정, PUB-02 기본값.
 * 점검 모드·기능 플래그를 실제로 켜 보는 건 캐시가 커밋 뒤에 비워져서 MaintenanceModeTest(커밋하는 테스트)에서 한다.
 */
@SpringBootTest
@Transactional
class AdminSettingControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        admin = userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-admin-setting")
                .role(UserRole.ADMIN)
                .nickname("설정관리자")
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    void 운영_설정_전체를_본다() throws Exception {
        mockMvc.perform(as(admin, get("/admin/settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.key == 'coaching')].value.price").value(15000))
                .andExpect(jsonPath("$.items[?(@.key == 'feature_flags')].value.p1_complaints").value(false))
                .andExpect(jsonPath("$.items[?(@.key == 'maintenance')].value.on").value(false));
    }

    @Test
    void 가격을_바꾸면_변경자와_감사_로그가_남는다() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/coaching")),
                        "{\"value\":{\"price\":12000,\"duration_min\":30,\"fee_rate\":0.15},\"reason\":\"프로모션\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("coaching"))
                .andExpect(jsonPath("$.value.price").value(12000))
                .andExpect(jsonPath("$.updated_by").value(admin.getId().toString()));

        assertThat(jdbc.queryForObject("select (value->>'price')::int from price_configs where key = 'coaching'",
                Integer.class)).isEqualTo(12000);
        assertThat(jdbc.queryForObject(
                "select reason from audit_logs where action = 'update_setting' and actor_id = ?", String.class,
                admin.getId())).isEqualTo("coaching: 프로모션");
    }

    @Test
    void 범위를_벗어나면_필드별로_422() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/coaching")),
                        "{\"value\":{\"price\":-1,\"duration_min\":30,\"fee_rate\":1.5}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("value.price"))
                .andExpect(jsonPath("$.error.details.fields[1].field").value("value.fee_rate"))
                .andExpect(jsonPath("$.error.details.fields[1].reason").value("range"));
    }

    @Test
    void 모르는_항목이_섞이면_422() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/feature_flags")),
                        "{\"value\":{\"p1_certificates\":true,\"p1_supervision_requests\":false,\"p1_complaints\":false,"
                                + "\"p1_credential_auto_verify\":false,\"p1_unknown\":true}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("value.p1_unknown"))
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("unknown_key"));
    }

    @Test
    void 위기_연락처는_비울_수_없다() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/crisis_contacts")), "{\"value\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("size"));
    }

    @Test
    void 점검_종료_시각_형식이_틀리면_422() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/maintenance")),
                        "{\"value\":{\"on\":true,\"until\":\"내일 아침\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("value.until"));
    }

    @Test
    void 값이_없으면_422() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/refund_policy")), "{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("required"));
    }

    @Test
    void 모르는_키는_404() throws Exception {
        mockMvc.perform(json(as(admin, put("/admin/settings/nope")), "{\"value\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 어드민이_아니면_403() throws Exception {
        User client = userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-admin-setting-client")
                .role(UserRole.CLIENT)
                .nickname("설정보는내담자")
                .status(UserStatus.ACTIVE)
                .build());

        mockMvc.perform(as(client, get("/admin/settings"))).andExpect(status().isForbidden());
    }

    @Test
    void 공개_설정은_로그인_없이_본다() throws Exception {
        mockMvc.perform(get("/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feature_flags.p1_certificates").value(false))
                .andExpect(jsonPath("$.maintenance.on").value(false))
                .andExpect(jsonPath("$.min_client_version").value("2026.10.26"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
