package org.example.back.config;

import jakarta.annotation.PostConstruct;
import org.example.back.common.util.ClientIpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 可信代理配置类
 * <p>
 * 从 application.properties 读取可信代理 IP 列表，
 * 在启动时注入到 ClientIpUtil，用于防止 X-Forwarded-For 请求头伪造。
 * </p>
 */
@Configuration
public class TrustedProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxyConfig.class);

    @Value("${app.security.trusted-proxies:127.0.0.1,0:0:0:0:0:0:0:1,::1}")
    private String trustedProxiesRaw;

    @PostConstruct
    public void init() {
        Set<String> proxies = Arrays.stream(trustedProxiesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        ClientIpUtil.setTrustedProxies(proxies);
        log.info("已加载可信代理 IP 列表: {}", proxies);
    }
}
