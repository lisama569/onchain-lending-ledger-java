package com.github.lisama569.onchainlendingledgerjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用账本配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class Config {
}
