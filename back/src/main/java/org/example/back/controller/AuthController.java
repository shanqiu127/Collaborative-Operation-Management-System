package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.util.ClientIpUtil;
import org.example.back.dto.LoginRequest;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.RegisterRequest;
import org.example.back.common.result.Result;
import org.example.back.service.AuthService;
import org.example.back.service.DeptService;
import org.example.back.service.LoginLogService;
import org.example.back.service.RegisterRateLimitService;
import org.example.back.vo.OptionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 认证控制器
 * 提供登录、登出、获取用户信息等接口
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private LoginLogService loginLogService;

    @Autowired
    private DeptService deptService;

    @Autowired
    private RegisterRateLimitService registerRateLimitService;

    /**
     * 用户登录接口
     *
     * @param request 登录请求（包含用户名和密码）
     * @return 登录响应，包含 token 和用户信息
     */
    @PostMapping("/login")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "登录请求过于频繁，请稍后再试")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = ClientIpUtil.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        try {
            LoginResponse response = authService.login(request);
            Long userId = response != null && response.getUserInfo() != null
                    ? response.getUserInfo().getId()
                    : null;
            loginLogService.record(userId, request.getUsername(), clientIp, userAgent, true, null);
            return Result.success(response);
        } catch (BusinessException e) {
            loginLogService.record(null, request.getUsername(), clientIp, userAgent, false, e.getMsg());
            throw e;
        } catch (Exception e) {
            loginLogService.record(null, request.getUsername(), clientIp, userAgent, false, "系统异常");
            throw e;
        }
    }

    /**
     * 用户注册接口（仅创建普通用户）
     */
    @PostMapping("/register")
    @PreventDuplicateSubmit(intervalMs = 2000, message = "请勿重复提交注册请求")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String clientIp = ClientIpUtil.getClientIp(httpRequest);
        registerRateLimitService.check(clientIp);
        authService.register(request);
        return Result.success();
    }

    @GetMapping("/depts")
    public Result<List<OptionVO>> registerDeptOptions() {
        return Result.success(deptService.publicOptions());
    }

    /**
     * 获取当前登录用户信息接口
     * 前端在初始化或刷新时调用，返回用户角色等信息
     *
     * @return 用户信息
     */
    @GetMapping("/userinfo")
    public Result<LoginResponse.UserInfoVO> getUserInfo() {
        LoginResponse.UserInfoVO userInfo = authService.getUserInfo();
        return Result.success(userInfo);
    }

    /**
     * 用户登出接口
     */
    @PostMapping("/logout")
    @PreventDuplicateSubmit(intervalMs = 800, message = "请勿重复提交退出请求")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 检查当前用户是否登录
     */
    @GetMapping("/check")
    public Result<Boolean> checkLogin() {
        // 此接口用于前端轮询检查登录状态
        return Result.success(true);
    }
}
