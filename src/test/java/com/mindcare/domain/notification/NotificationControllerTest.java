package com.mindcare.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mindcare.domain.notification.repository.NotificationRepository;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.TokenVerifier;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class NotificationControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired EntityManager em;
    @MockitoBean TokenVerifier tokenVerifier;

    MockMvc mockMvc;
    User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = saveUser("me", UserStatus.ACTIVE);
    }

    @Test
    void 최신순으로_보이고_안_읽은_개수가_같이_온다() throws Exception {
        notifyMe("첫 알림");
        notifyMe("둘째 알림");

        mockMvc.perform(as(user, get("/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("둘째 알림"))
                .andExpect(jsonPath("$.items[0].read_at").isEmpty())
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.next_cursor").isEmpty())
                .andExpect(jsonPath("$.unread_count").value(2));
    }

    @Test
    void next_cursor로_다음_페이지를_이어서_받는다() throws Exception {
        for (int i = 1; i <= 5; i++) {
            notifyMe("알림 " + i);
        }

        String first = mockMvc.perform(as(user, get("/notifications").param("limit", "2")))
                .andExpect(jsonPath("$.items[0].title").value("알림 5"))
                .andExpect(jsonPath("$.items[1].title").value("알림 4"))
                .andExpect(jsonPath("$.next_cursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.next_cursor");

        String second = mockMvc.perform(as(user, get("/notifications").param("limit", "2").param("cursor", cursor)))
                .andExpect(jsonPath("$.items[0].title").value("알림 3"))
                .andExpect(jsonPath("$.items[1].title").value("알림 2"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(as(user, get("/notifications").param("limit", "2")
                        .param("cursor", (String) JsonPath.read(second, "$.next_cursor"))))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("알림 1"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @Test
    void 위조한_커서는_400() throws Exception {
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes()) + ".AAAAAAAAAAAAAAAAAAAAAA";

        mockMvc.perform(as(user, get("/notifications").param("cursor", forged)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(as(user, get("/notifications").param("cursor", "not-a-cursor")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limit은_1에서_100까지() throws Exception {
        mockMvc.perform(as(user, get("/notifications").param("limit", "101")))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(as(user, get("/notifications").param("limit", "0")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void 읽음_처리하면_unread_only_목록에서_빠진다() throws Exception {
        UUID first = notifyMe("첫 알림");
        notifyMe("둘째 알림");

        mockMvc.perform(as(user, post("/notifications/" + first + "/read")))
                .andExpect(status().isNoContent());
        // 두 번 읽어도 그대로
        mockMvc.perform(as(user, post("/notifications/" + first + "/read")))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(user, get("/notifications").param("unread_only", "true")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("둘째 알림"))
                .andExpect(jsonPath("$.unread_count").value(1));
        mockMvc.perform(as(user, get("/notifications")))
                .andExpect(jsonPath("$.items[1].read_at").isNotEmpty());
    }

    @Test
    void 전체_읽음_처리() throws Exception {
        notifyMe("첫 알림");
        notifyMe("둘째 알림");
        User other = saveUser("other", UserStatus.ACTIVE);
        notificationService.notify(other.getId(), "test", "남의 알림", "본문", null, Map.of());

        mockMvc.perform(as(user, post("/notifications/read-all")))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(user, get("/notifications")))
                .andExpect(jsonPath("$.unread_count").value(0));
        assertThat(notificationRepository.countByUserIdAndReadAtIsNull(other.getId())).isEqualTo(1);
    }

    @Test
    void 남의_알림은_읽음_처리할_수_없고_목록에도_안_보인다() throws Exception {
        User other = saveUser("other", UserStatus.ACTIVE);
        notificationService.notify(other.getId(), "test", "남의 알림", "본문", null, Map.of());
        em.flush();
        UUID othersId = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(other.getId())).findFirst().orElseThrow().getId();

        mockMvc.perform(as(user, post("/notifications/" + othersId + "/read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_RESOURCE"));
        mockMvc.perform(as(user, get("/notifications")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 없는_알림은_404() throws Exception {
        mockMvc.perform(as(user, post("/notifications/" + UUID.randomUUID() + "/read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 심사_중이거나_정지된_계정도_알림은_본다() throws Exception {
        User pending = saveUser("pending", UserStatus.PENDING_REVIEW);
        User suspended = saveUser("suspended", UserStatus.SUSPENDED);

        mockMvc.perform(as(pending, get("/notifications"))).andExpect(status().isOk());
        mockMvc.perform(as(suspended, get("/notifications"))).andExpect(status().isOk());
    }

    private UUID notifyMe(String title) {
        notificationService.notify(user.getId(), "test", title, "본문", "/c", Map.of("k", "v"));
        em.flush();
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(user.getId()) && n.getTitle().equals(title))
                .findFirst().orElseThrow().getId();
    }

    private MockHttpServletRequestBuilder as(User u, MockHttpServletRequestBuilder request) {
        String token = "token-" + u.getFirebaseUid();
        given(tokenVerifier.verify(token)).willReturn(u.getFirebaseUid());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private User saveUser(String name, UserStatus status) {
        return userRepository.saveAndFlush(User.builder()
                .firebaseUid("uid-notification-" + name)
                .role(UserRole.CLIENT)
                .nickname("알림" + name)
                .status(status)
                .build());
    }
}
