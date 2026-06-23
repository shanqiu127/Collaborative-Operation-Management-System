package org.example.back.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 配置 CORS 跨域、静态资源处理等
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LoginIpWhitelistInterceptor loginIpWhitelistInterceptor;

    /**
     * CORS 允许的来源模式，逗号分隔。
     * 开发环境默认 "*"（允许所有）；生产环境改为具体前端域名。
     */
    @Value("${app.cors.allowed-origin-patterns:*}")
    private String[] corsAllowedOriginPatterns;

    public WebConfig(LoginIpWhitelistInterceptor loginIpWhitelistInterceptor) {
        this.loginIpWhitelistInterceptor = loginIpWhitelistInterceptor;
    }

    /**
     * 配置 CORS 跨域
     * 允许的来源模式通过 application.properties 中 app.cors.allowed-origin-patterns 配置
     * 开发环境默认 "*"；生产环境应限定为具体前端域名
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(corsAllowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 配置静态资源处理
     * Knife4j 文档路径映射
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");

        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");

    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginIpWhitelistInterceptor)
                .addPathPatterns("/auth/login");
    }
}
