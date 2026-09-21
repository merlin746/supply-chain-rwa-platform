package com.rwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
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

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"byd\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("CORE_ENTERPRISE"));

        mockMvc.perform(put("/enterprise/invoice/2/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/token/mint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UNCIRCULATED"));

        MvcResult splitResult = mockMvc.perform(post("/token/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromTokenId\":\"RWA-DEMO-ROOT-001\",\"toEnterpriseId\":3,\"value\":500000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("SPLIT"))
                .andReturn();

        JsonNode splitJson = objectMapper.readTree(splitResult.getResponse().getContentAsString());
        String childTokenId = splitJson.at("/data/child/tokenId").asText();

        MvcResult loanResult = mockMvc.perform(post("/loan/apply")
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

        mockMvc.perform(put("/loan/{id}/approve", loanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(put("/loan/{id}/disburse", loanId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISBURSED"));

        mockMvc.perform(get("/token/{tokenId}/verify", childTokenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.mode").value("OFFCHAIN_DB"))
                .andExpect(jsonPath("$.data.chainDepth").value(2))
                .andExpect(jsonPath("$.data.loans[0].status").value("DISBURSED"));
    }
}
