package com.mindcare.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindcare.global.response.PageResponse;
import com.mindcare.global.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * TRD 12-2 응답·오류 형식이 지켜지는지 확인 (프론트와의 계약).
 */
@SpringBootTest
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    RequestIdFilter requestIdFilter;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    @Test
    void AppException은_TRD_오류_형식으로_나간다() throws Exception {
        mockMvc.perform(get("/public/test/conflict").header(RequestIdFilter.HEADER, "req_abc123"))
                .andExpect(status().isConflict())
                .andExpect(header().string(RequestIdFilter.HEADER, "req_abc123"))
                .andExpect(jsonPath("$.error.code").value("SLOT_TAKEN"))
                .andExpect(jsonPath("$.error.message").value("이미 예약된 시간입니다"))
                .andExpect(jsonPath("$.error.details.slot_id").value("s-1"))
                .andExpect(jsonPath("$.error.request_id").value("req_abc123"));
    }

    @Test
    void 요청ID가_없으면_서버가_만든다() throws Exception {
        mockMvc.perform(get("/public/test/conflict"))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void 바디_검증_실패는_422와_snake_case_필드명() throws Exception {
        mockMvc.perform(post("/public/test/validate").contentType(MediaType.APPLICATION_JSON).content("{\"display_name\": \"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("display_name"))
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("not_blank"));
    }

    @Test
    void UUID_형식이_아니면_422() throws Exception {
        mockMvc.perform(get("/public/test/items/not-a-uuid"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("id"));
    }

    @Test
    void 깨진_JSON은_400() throws Exception {
        mockMvc.perform(post("/public/test/validate").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void 없는_경로는_404_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void 알수없는_예외는_500이고_내부_메시지를_숨긴다() throws Exception {
        mockMvc.perform(get("/public/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL"))
                .andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했습니다"));
    }

    @Test
    void 목록_응답은_items와_next_cursor() throws Exception {
        mockMvc.perform(get("/public/test/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].display_name").value("하늘"))
                .andExpect(jsonPath("$.next_cursor").isEmpty());
    }

    @RestController
    static class TestController {

        @GetMapping("/public/test/conflict")
        void conflict() {
            throw new AppException(ErrorCode.SLOT_TAKEN, Map.of("slot_id", "s-1"));
        }

        @PostMapping("/public/test/validate")
        void validate(@Valid @RequestBody NameRequest request) {
        }

        @GetMapping("/public/test/items/{id}")
        void item(@PathVariable UUID id) {
        }

        @GetMapping("/public/test/boom")
        void boom() {
            throw new IllegalStateException("db password is hunter2");
        }

        @GetMapping("/public/test/page")
        PageResponse<NameRequest> page() {
            return PageResponse.last(List.of(new NameRequest("하늘")));
        }
    }

    record NameRequest(@NotBlank String displayName) {
    }
}
