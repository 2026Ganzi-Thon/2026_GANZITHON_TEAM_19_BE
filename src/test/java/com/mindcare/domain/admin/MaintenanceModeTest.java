package com.mindcare.domain.admin;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenRevoker;
import com.mindcare.global.auth.TokenVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 커밋 뒤에 일어나는 일(설정 캐시 비우기, Firebase 토큰 폐기)을 보는 테스트라 실제로 커밋하고, 끝나면 되돌린다.
 */
@SpringBootTest
class MaintenanceModeTest {

    private static final String OFF = "{\"on\": false, \"message\": null, \"until\": null}";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean TokenVerifier tokenVerifier;
    @MockitoBean TokenRevoker tokenRevoker;

    MockMvc mockMvc;
    User admin;
    User client;
    private final List<UUID> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        admin = saveUser("admin-" + suffix, "점검관리자" + suffix, UserRole.ADMIN);
        client = saveUser("client-" + suffix, "점검내담자" + suffix, UserRole.CLIENT);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("update price_configs set value = cast(? as jsonb), updated_by = null where key = 'maintenance'", OFF);
        jdbc.update("""
                update price_configs set updated_by = null,
                       value = '{"p1_certificates": false, "p1_supervision_requests": false, "p1_complaints": false,
                                 "p1_credential_auto_verify": false}'
                 where key = 'feature_flags'
                """);
        events.publishEvent(new AppConfigService.Changed("test-cleanup"));
        for (UUID id : createdUsers) {
            jdbc.update("delete from audit_logs where actor_id = ? or target_id = ?", id, id);
            jdbc.update("delete from users where id = ?", id);
        }
    }

    @Test
    void 점검을_켜면_일반_API는_503이고_어드민_공개_API와_GET_me는_열려_있다() throws Exception {
        mockMvc.perform(as(client, get("/notifications"))).andExpect(status().isOk());

        mockMvc.perform(json(as(admin, put("/admin/settings/maintenance")),
                        "{\"value\":{\"on\":true,\"message\":\"서버 점검 중이에요\",\"until\":\"2099-01-01T00:00:00Z\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(client, get("/notifications")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE"))
                .andExpect(jsonPath("$.error.message").value("서버 점검 중이에요"))
                .andExpect(jsonPath("$.error.details.until").value("2099-01-01T00:00:00Z"));
        // 토큰 없어도 503 (인증보다 먼저 본다)
        mockMvc.perform(get("/notifications")).andExpect(status().isServiceUnavailable());

        mockMvc.perform(as(client, get("/me"))).andExpect(status().isOk());
        mockMvc.perform(as(admin, get("/admin/settings"))).andExpect(status().isOk());
        mockMvc.perform(get("/public/app-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance.on").value(true))
                .andExpect(jsonPath("$.maintenance.message").value("서버 점검 중이에요"));

        mockMvc.perform(json(as(admin, put("/admin/settings/maintenance")), "{\"value\":" + OFF + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(client, get("/notifications"))).andExpect(status().isOk());
    }

    @Test
    void 기능_플래그를_켜면_공개_설정에_바로_보인다() throws Exception {
        mockMvc.perform(get("/public/app-config")).andExpect(jsonPath("$.feature_flags.p1_complaints").value(false));

        mockMvc.perform(json(as(admin, put("/admin/settings/feature_flags")),
                        "{\"value\":{\"p1_certificates\":false,\"p1_supervision_requests\":false,"
                                + "\"p1_complaints\":true,\"p1_credential_auto_verify\":false}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/app-config")).andExpect(jsonPath("$.feature_flags.p1_complaints").value(true));
    }

    @Test
    void 정지는_커밋된_뒤_Firebase_토큰을_폐기한다() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"banned\",\"reason\":\"테스트\"}"))
                .andExpect(status().isOk());

        verify(tokenRevoker).revoke(client.getFirebaseUid());
    }

    @Test
    void 실패한_요청은_토큰을_폐기하지_않는다() throws Exception {
        mockMvc.perform(json(as(admin, patch("/admin/users/" + client.getId())),
                        "{\"status\":\"suspended\",\"reason\":\"테스트\",\"until\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isUnprocessableContent());

        verify(tokenRevoker, never()).revoke(client.getFirebaseUid());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        String token = "token-" + user.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(user.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, String nickname, UserRole role) {
        User user = userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-maint-" + name)
                .role(role)
                .nickname(nickname)
                .status(UserStatus.ACTIVE)
                .build());
        createdUsers.add(user.getId());
        return user;
    }
}
