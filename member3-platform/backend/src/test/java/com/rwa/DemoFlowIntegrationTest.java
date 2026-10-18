package com.rwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class DemoFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completesTheOfflineDemoFlow() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.chainStatus").value("DISABLED"));

        String coreToken = login("byd", "CORE_ENTERPRISE");
        String firstSupplierToken = login("kdl", "SUPPLIER");
        String secondSupplierToken = login("jnyt", "SUPPLIER");
        String bankToken = login("ccb", "BANK");

        mockMvc.perform(put("/enterprise/invoice/2/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + coreToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/token/mint")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + coreToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UNCIRCULATED"));

        MvcResult splitResult = mockMvc.perform(post("/token/split")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + firstSupplierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromTokenId\":\"RWA-DEMO-ROOT-001\",\"toEnterpriseId\":3,\"value\":500000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("SPLIT"))
                .andReturn();

        JsonNode splitJson = objectMapper.readTree(splitResult.getResponse().getContentAsString());
        String childTokenId = splitJson.at("/data/child/tokenId").asText();

        MvcResult loanResult = mockMvc.perform(post("/loan/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondSupplierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenId\":\"" + childTokenId
                                + "\",\"supplierId\":3,\"bankId\":5,\"amount\":450000,"
                                + "\"interestRate\":4.2,\"termDays\":90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andReturn();

        long loanId = objectMapper.readTree(loanResult.getResponse().getContentAsString())
                .at("/data/id").asLong();

        mockMvc.perform(put("/loan/{id}/approve", loanId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bankToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(put("/loan/{id}/disburse", loanId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bankToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISBURSED"));

        mockMvc.perform(get("/token/{tokenId}/verify", childTokenId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bankToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.mode").value("OFFCHAIN_DB"))
                .andExpect(jsonPath("$.data.chainDepth").value(2))
                .andExpect(jsonPath("$.data.loans[0].status").value("DISBURSED"));
    }

    private String login(String username, String expectedRole) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value(expectedRole))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token").asText();
    }
}
