package com.rwa;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rwa.entity.SysUser;
import com.rwa.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    void loginReturnsOpaqueTokenAndMeLogoutFollowItsLifecycle() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kdl\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kdl\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.username").value("kdl"))
                .andExpect(jsonPath("$.data.role").value("SUPPLIER"))
                .andExpect(jsonPath("$.data.enterpriseId").value(2))
                .andExpect(jsonPath("$.data.enterpriseName").value("科达利"))
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(jsonPath("$.data.sessionMode").value("IN_MEMORY"))
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.at("/data/token").asText();
        assertThat(token).hasSize(43).doesNotContain("kdl", "123456");

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("kdl"))
                .andExpect(jsonPath("$.data.enterpriseId").value(2))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        SysUser migrated = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", "kdl"));
        assertThat(migrated.getPassword()).startsWith("pbkdf2$");
        assertThat(migrated.getPassword()).doesNotContain("123456");

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void businessApisRequireBearerTokenAndWriteRoutesEnforceRole() throws Exception {
        mockMvc.perform(options("/dashboard/overview")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "http://localhost:5173"));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/chain/status"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        String supplierToken = login("kdl", "123456");
        mockMvc.perform(post("/token/mint")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supplierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        // 权限判定必须使用 Spring 的规范化匹配模板，不能被编码路径或矩阵参数绕过。
        mockMvc.perform(request(HttpMethod.POST, java.net.URI.create("/token/%6dint"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supplierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/token/mint;ignored=true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + supplierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        String coreToken = login("byd", "123456");
        mockMvc.perform(post("/loan/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + coreToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokenId\":\"RWA-DEMO-ROOT-001\",\"bankId\":5,\"amount\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token").asText();
    }
}
