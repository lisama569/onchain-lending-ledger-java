package com.github.lisama569.onchainlendingledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端冒烟：成功与失败都返回可区分 reasonCode；重复 requestId 返回 DUPLICATE_REQUEST。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ledger.snapshot-path=target/web-smoke-snapshot.json")
class WebApiTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    private static String amount(String requestId, String user, String amount) throws Exception {
        return M.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("requestId", requestId);
            put("userId", user);
            put("amount", amount);
        }});
    }

    @Test
    void supplyThenDuplicateThenStateAndFailureReasons() throws Exception {
        // 成功
        mvc.perform(post("/api/ledger/supply").contentType(MediaType.APPLICATION_JSON)
                        .content(amount("w1", "alice", "1000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reason").value("OK"));

        // 重复请求：幂等重放
        mvc.perform(post("/api/ledger/supply").contentType(MediaType.APPLICATION_JSON)
                        .content(amount("w1", "alice", "1000")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.details.firstOutcome").value("OK"));

        // 超额提取：可区分原因
        mvc.perform(post("/api/ledger/withdraw").contentType(MediaType.APPLICATION_JSON)
                        .content(amount("w2", "alice", "1001")))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.reason").value("INSUFFICIENT_SUPPLY"));

        // 状态查询
        mvc.perform(get("/api/ledger/state"))
                .andExpect(jsonPath("$.pool.cash").value("1000.000000000000000000"))
                .andExpect(jsonPath("$.recordedRequests").value(2));
    }
}
