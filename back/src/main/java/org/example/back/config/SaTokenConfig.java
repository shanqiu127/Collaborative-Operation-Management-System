package org.example.back.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 配置类
 * 配置登录校验拦截器和权限校验
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 不需要登录校验的路径（白名单）
     */
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/auth/login",         // 登录接口
            "/auth/register",           // 注册接口（仅普通用户）
            "/auth/depts",              // 注册页部门下拉
            "/auth/captcha",            // 验证码接口（如有）
            "/swagger-ui/**",           // Swagger UI
            "/swagger-resources/**",    // Swagger 资源
            "/v3/api-docs/**",          // Swagger 文档
            "/webjars/**",              // Swagger WebJars
            "/doc.html",                // Knife4j 文档
            "/favicon.ico",             // 网站图标
            "/error"                     // 错误页面
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器，校验登录状态
        registry.addInterceptor(new SaInterceptor(handle -> {
            // SaRouter.match() 匹配指定的路由数组
            SaRouter
                    .match("/**")          // 拦截所有路径
                    .notMatch(WHITE_LIST)  // 排除白名单路径
                    .check(r -> StpUtil.checkLogin()); // 校验是否登录
        })).addPathPatterns("/**");
    }
}
