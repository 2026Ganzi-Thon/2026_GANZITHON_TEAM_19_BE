package com.mindcare.domain.admin;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * PUB-01 위기 연락처: 점검 중에도, 설정 행이 없어도 나와야 한다.
 * 캐시가 커밋 뒤에 비워져서 실제로 커밋하고, 끝나면 원래 값으로 되돌린다.
 */
@SpringBootTest
class CrisisContactsTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User admin;
    String original;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        original = jdbc.queryForObject("select value::text from price_configs where key = 'crisis_contacts'",
                String.class);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        admin = userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-crisis-admin-" + suffix).role(UserRole.ADMIN).nickname("위기관리" + suffix)
                .status(UserStatus.ACTIVE).build());
        given(tokenVerifier.verify("token-crisis-admin")).willReturn(admin.getFirebaseUid());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
                insert into price_configs (key, value) values ('crisis_contacts', cast(? as jsonb))
                on conflict (key) do update set value = excluded.value, updated_by = null""", original);
        jdbc.update("update price_configs set value = '{\"on\": false, \"message\": null, \"until\": null}', "
                + "updated_by = null where key = 'maintenance'");
        events.publishEvent(new AppConfigService.Changed("test-cleanup"));
        jdbc.update("delete from audit_logs where actor_id = ?", admin.getId());
        jdbc.update("delete from users where id = ?", admin.getId());
    }

    @Test
    void 어드민이_바꾸면_바로_반영되고_점검_중에도_나온다() throws Exception {
        mockMvc.perform(put("/admin/settings/crisis_contacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-crisis-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":[{\"name\":\"청소년 상담전화\",\"number\":\"1388\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/settings/maintenance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-crisis-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":{\"on\":true,\"message\":null,\"until\":null}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/crisis-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].number").value("1388"));
        mockMvc.perform(get("/public/terms")).andExpect(status().isOk());
    }

    @Test
    void 설정_행이_없어도_기본_연락처가_나온다() throws Exception {
        jdbc.update("delete from price_configs where key = 'crisis_contacts'");
        events.publishEvent(new AppConfigService.Changed("crisis_contacts"));

        mockMvc.perform(get("/public/crisis-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].number").value("109"));
    }
}
