package com.mindcare.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    WebApplicationContext context;

    @Test
    @EnabledIfSystemProperty(named = "openapi.output", matches = ".+")
    void writesCurrentOpenApiContract() throws Exception {
        String output = System.getProperty("openapi.output");

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        String response = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = objectMapper.readTree(response);
        // SpringDoc derives this from the request host (localhost in tests, production URL in a server).
        // It is deployment metadata rather than an API contract and would make the snapshot non-deterministic.
        ((ObjectNode) document).remove("servers");

        Files.writeString(Path.of(output), objectMapper.writeValueAsString(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
