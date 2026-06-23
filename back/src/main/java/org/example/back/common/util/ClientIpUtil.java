package org.example.back.common.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public final class ClientIpUtil {

    private static final String UNKNOWN = "unknown";
    private static final String IPV6_LOOPBACK = "0:0:0:0:0:0:0:1";
    private static final String IPV6_LOOPBACK_SHORT = "::1";
    private static final String IPV4_LOOPBACK = "127.0.0.1";

    /** 可信代理 IP 集合，由 TrustedProxyConfig 在启动时注入 */
    private static volatile Set<String> trustedProxies = Collections.emptySet();

    private ClientIpUtil() {
    }

    /**
     * 注入可信代理 IP 列表（由配置类调用）
     *
     * @param proxies 可信代理 IP 集合（已归一化）
     */
    public static void setTrustedProxies(Set<String> proxies) {
        trustedProxies = proxies == null ? Collections.emptySet()
                : proxies.stream().map(String::trim).filter(s -> !s.isEmpty())
                        .map(ClientIpUtil::normalizeIp).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 获取客户端真实 IP 地址。
     * <p>
     * 安全策略：仅当 TCP 直连来源（{@code request.getRemoteAddr()}）属于可信代理时，
     * 才解析 X-Forwarded-For 等代理头提取真实客户端 IP；否则直接使用 TCP 层地址，
     * 防止攻击者通过伪造代理头绕过 IP 白名单。
     * </p>
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remoteAddr = request.getRemoteAddr();
        // 非可信代理直连 → 忽略所有代理头，直接使用 TCP 层地址
        if (!isTrustedProxy(remoteAddr)) {
            return normalizeIp(remoteAddr);
        }
        // 来自可信代理 → 按优先级从代理头提取真实客户端 IP
        String ip = firstValidHeaderIp(request, "X-Forwarded-For");
        if (isEmpty(ip)) {
            ip = firstValidHeaderIp(request, "Proxy-Client-IP");
        }
        if (isEmpty(ip)) {
            ip = firstValidHeaderIp(request, "WL-Proxy-Client-IP");
        }
        if (isEmpty(ip)) {
            ip = firstValidHeaderIp(request, "HTTP_X_FORWARDED_FOR");
        }
        if (isEmpty(ip)) {
            ip = remoteAddr;
        }
        return normalizeIp(ip);
    }

    /**
     * 判断给定 IP 是否为可信代理
     */
    static boolean isTrustedProxy(String ip) {
        if (ip == null || trustedProxies.isEmpty()) {
            return false;
        }
        return trustedProxies.contains(normalizeIp(ip));
    }

    /**
     * IP 归一化：IPv6 环回地址统一为 127.0.0.1，去除首尾空白
     */
    private static String normalizeIp(String ip) {
        if (ip == null) {
            return "";
        }
        String trimmed = ip.trim();
        if (IPV6_LOOPBACK.equals(trimmed) || IPV6_LOOPBACK_SHORT.equals(trimmed)) {
            return IPV4_LOOPBACK;
        }
        return trimmed;
    }

    /**
     * 从指定的请求头中提取第一个有效的 IP 地址（多级代理取首个）
     */
    private static String firstValidHeaderIp(HttpServletRequest request, String header) {
        String ip = request.getHeader(header);
        if (isEmpty(ip)) {
            return "";
        }
        int idx = ip.indexOf(',');
        if (idx > -1) {
            ip = ip.substring(0, idx);
        }
        return ip.trim();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isBlank() || UNKNOWN.equalsIgnoreCase(value);
    }
}
