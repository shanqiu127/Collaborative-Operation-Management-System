package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注册接口 IP 速率限制
 * 防止攻击者通过脚本批量注册垃圾账号
 */
@Service
public class RegisterRateLimitService {

    /**
     * 每个 IP 在时间窗口内允许的最大注册次数
     */
    @Value("${app.security.register-max-per-ip:5}")
    private int maxPerIp;

    /**
     * 时间窗口（毫秒），默认 1 小时
     */
    @Value("${app.security.register-window-ms:3600000}")
    private long windowMs;

    private final ConcurrentHashMap<String, WindowCounter> counterMap = new ConcurrentHashMap<>();

    /**
     * 检查 IP 注册频率是否超限，超限则抛出异常
     */
    public void check(String ip) {
        long now = System.currentTimeMillis();
        WindowCounter counter = counterMap.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new WindowCounter(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (counter.count.get() > maxPerIp) {
            throw BusinessException.forbidden("注册过于频繁，请稍后再试");
        }
    }

    /**
     * 定时清理过期计数器，防止内存泄漏（每小时执行一次）
     */
    @Scheduled(fixedDelay = 3600000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        counterMap.entrySet().removeIf(entry -> now - entry.getValue().windowStart > windowMs);
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count;

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicInteger(1);
        }
    }
}
