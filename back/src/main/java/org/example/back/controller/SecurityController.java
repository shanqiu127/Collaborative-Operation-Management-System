package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.IpPolicyQueryDTO;
import org.example.back.dto.IpPolicySaveDTO;
import org.example.back.dto.IpPolicyStatusDTO;
import org.example.back.entity.SysIpPolicy;
import org.example.back.service.SecurityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/security/ip-policies")
@RequireAdmin("仅管理员可访问安全策略模块")
public class SecurityController {

    @Autowired
    private SecurityService securityService;

    @GetMapping("/page")
    public Result<PageResult<SysIpPolicy>> page(IpPolicyQueryDTO queryDTO) {
        return Result.success(securityService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<SysIpPolicy> getById(@PathVariable Long id) {
        return Result.success(securityService.getById(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(message = "请勿重复提交策略新增请求")
    public Result<Void> create(@Valid @RequestBody IpPolicySaveDTO dto) {
        securityService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreventDuplicateSubmit(message = "请勿重复提交策略编辑请求")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody IpPolicySaveDTO dto) {
        securityService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        securityService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/status")
    @PreventDuplicateSubmit(intervalMs = 800, message = "状态切换过于频繁，请稍后再试")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody(required = false) IpPolicyStatusDTO dto) {
        Integer status = null;
        if (dto != null) {
            if (dto.getStatus() != null) {
                status = dto.getStatus();
            } else if (dto.getEnabled() != null) {
                status = dto.getEnabled() ? 1 : 0;
            }
        }
        securityService.updateStatus(id, status);
        return Result.success();
    }

    @GetMapping("/enabled")
    public Result<List<SysIpPolicy>> enabledList() {
        return Result.success(securityService.listEnabled());
    }
}
