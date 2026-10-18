package com.rwa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class DashboardIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void overviewIsScopedToTheLoggedInEnterprise() throws Exception {
        String supplier = login("kdl");
        mvc.perform(get("/dashboard/overview").header("Authorization", supplier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetAmount").value(2000000))
                .andExpect(jsonPath("$.data.tokenCount").value(1))
                .andExpect(jsonPath("$.data.financedAmount").value(0));

        String bank = login("ccb");
        mvc.perform(get("/dashboard/overview").header("Authorization", bank))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assetAmount").value(350000))
                .andExpect(jsonPath("$.data.tokenCount").value(1))
                .andExpect(jsonPath("$.data.financedAmount").value(300000));
    }

    @Test
    void supplierCannotReadAnotherEnterpriseAssetOrCreditLimit() throws Exception {
        String supplier = login("kdl");
        mvc.perform(get("/token/RWA-DEMO-MATURED-002/verify").header("Authorization", supplier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/enterprise/1").header("Authorization", supplier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("比亚迪"))
                .andExpect(jsonPath("$.data.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.data.creditUsed").doesNotExist());
    }

    private String login(String username) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(response).at("/data/token").asText();
    }
}
