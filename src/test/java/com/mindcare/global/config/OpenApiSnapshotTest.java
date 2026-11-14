package com.mindcare.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Generates the source-controlled external API contract used by the frontend (TRD B-2). */
@SpringBootTest
class OpenApiSnapshotTest {

    @Autowired
    WebApplicationContext context;

    @Test
    @EnabledIfSystemProperty(named = "openapi.output", matches = ".+")
    void writesCurrentOpenApiContract() throws Exception {
        String output = System.getProperty("openapi.output");

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String document = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Files.writeString(Path.of(output), document + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
