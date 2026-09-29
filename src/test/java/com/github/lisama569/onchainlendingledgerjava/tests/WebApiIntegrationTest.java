package com.github.lisama569.onchainlendingledgerjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端：走完整 Spring 链路验证错误码可区分、成功/重放响应结构一致。
 * 使用独立快照文件（application-test.properties 指定到 target 临时路径）。
 */
@SpringBootTest(properties = {
        "ledger.snapshot-path=target/test-data/web-it-snapshot.json",
        "ledger.bootstrap-assets=ETH,USDC"
})
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetSnapshot() throws Exception {
        // 每个类运行前清空快照，保证用例可重复（文件在 target 下）
        java.nio.file.Path p = java.nio.file.Path.of("target/test-data/web-it-snapshot.json");
        java.nio.file.Files.deleteIfExists(p);
    }

    @Test
    void endToEndSupplyBorrowAndErrorCodes() throws Exception {
        long now = System.currentTimeMillis();
        String ethPrice = "{\"asset\":\"ETH\",\"seq\":1,\"timestamp\":" + now + ",\"price\":2000}";
        String usdcPrice = "{\"asset\":\"USDC\",\"seq\":1,\"timestamp\":" + now + ",\"price\":1}";
        // 价格上报
        mockMvc.perform(post("/api/prices").contentType("application/json").content(ethPrice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/prices").contentType("application/json").content(usdcPrice))
                .andExpect(status().isOk());
        log.info("[HTTP] 价格上报成功");

        // 存入
        mockMvc.perform(post("/api/pools/ETH/supply").contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"userId\":\"alice\",\"amount\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重复提交 -> replayed=true
        mockMvc.perform(post("/api/pools/ETH/supply").contentType("application/json")
                        .content("{\"requestId\":\"w1\",\"userId\":\"alice\",\"amount\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        log.info("[HTTP] 重复请求返回 replayed=true");

        // 非法金额 -> 400 + INVALID_AMOUNT
        mockMvc.perform(post("/api/pools/ETH/supply").contentType("application/json")
                        .content("{\"requestId\":\"w2\",\"userId\":\"alice\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT"));

        // 不存在资金池 -> 400 + POOL_NOT_FOUND
        mockMvc.perform(post("/api/pools/BTC/supply").contentType("application/json")
                        .content("{\"requestId\":\"w3\",\"userId\":\"alice\",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("POOL_NOT_FOUND"));
        log.info("[HTTP] 业务失败返回可区分 errorCode");

        // 健康度查询结构
        mockMvc.perform(get("/api/users/alice/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.liquidatable").value(false));
    }
}
