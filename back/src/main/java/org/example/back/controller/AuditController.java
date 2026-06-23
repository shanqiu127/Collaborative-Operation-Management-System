package org.example.back.controller;

import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.LoginLogQueryDTO;
import org.example.back.dto.OperationLogQueryDTO;
import org.example.back.entity.SysLoginLog;
import org.example.back.entity.SysOperationLog;
import org.example.back.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/system/audit")
@RequireAdmin("仅管理员可访问审计日志模块")
public class AuditController {

    @Autowired
    private AuditService auditService;

    @GetMapping("/login-logs/page")
    public Result<PageResult<SysLoginLog>> loginLogPage(LoginLogQueryDTO queryDTO) {
        return Result.success(auditService.loginLogPage(queryDTO));
    }

    @GetMapping("/login-logs/{id}")
    public Result<SysLoginLog> loginLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getLoginLogById(id));
    }

    @GetMapping("/operation-logs/page")
    public Result<PageResult<SysOperationLog>> operationLogPage(OperationLogQueryDTO queryDTO) {
        return Result.success(auditService.operationLogPage(queryDTO));
    }

    @GetMapping("/operation-logs/{id}")
    public Result<SysOperationLog> operationLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getOperationLogById(id));
    }
}
