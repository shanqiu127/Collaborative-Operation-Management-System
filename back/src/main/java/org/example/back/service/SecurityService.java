package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.IpPolicyQueryDTO;
import org.example.back.dto.IpPolicySaveDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.SysIpPolicy;
import org.example.back.mapper.SysIpPolicyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SecurityService {

    @Autowired
    private SysIpPolicyMapper sysIpPolicyMapper;

    @Autowired
    private AuthService authService;

    public PageResult<SysIpPolicy> page(IpPolicyQueryDTO queryDTO) {
        requireSuperAdmin();

        LambdaQueryWrapper<SysIpPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getPolicyName()), SysIpPolicy::getPolicyName, queryDTO.getPolicyName())
                .eq(queryDTO.getStatus() != null, SysIpPolicy::getStatus, queryDTO.getStatus())
                .eq(queryDTO.getAllowFlag() != null, SysIpPolicy::getAllowFlag, queryDTO.getAllowFlag())
                .orderByAsc(SysIpPolicy::getPriority)
                .orderByDesc(SysIpPolicy::getId);

        Page<SysIpPolicy> page = sysIpPolicyMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SysIpPolicy getById(Long id) {
        requireSuperAdmin();
        SysIpPolicy policy = sysIpPolicyMapper.selectById(id);
        if (policy == null) {
            throw BusinessException.notFound("IP 策略不存在");
        }
        return policy;
    }

    public void create(IpPolicySaveDTO dto) {
        requireSuperAdmin();
        SysIpPolicy policy = new SysIpPolicy();
        policy.setPolicyName(dto.getPolicyName());
        policy.setIpCidr(dto.getIpCidr());
        policy.setAllowFlag(dto.getAllowFlag());
        policy.setStatus(dto.getStatus());
        policy.setPriority(dto.getPriority() != null ? dto.getPriority() : 100);
        policy.setRemark(dto.getRemark());
        sysIpPolicyMapper.insert(policy);
    }

    public void update(Long id, IpPolicySaveDTO dto) {
        requireSuperAdmin();
        SysIpPolicy existing = requirePolicy(id);
        // 逐字段赋值，避免 BeanUtils.copyProperties 覆盖受保护字段
        existing.setPolicyName(dto.getPolicyName());
        existing.setIpCidr(dto.getIpCidr());
        existing.setAllowFlag(dto.getAllowFlag());
        existing.setPriority(dto.getPriority() != null ? dto.getPriority() : 100);
        existing.setRemark(dto.getRemark());
        sysIpPolicyMapper.updateById(existing);
    }

    public void delete(Long id) {
        requireSuperAdmin();
        requirePolicy(id);
        sysIpPolicyMapper.deleteById(id);
    }

    public void updateStatus(Long id, Integer status) {
        requireSuperAdmin();
        SysIpPolicy existing = requirePolicy(id);
        existing.setStatus(status != null && status == 1 ? 1 : 0);
        sysIpPolicyMapper.updateById(existing);
    }

    public List<SysIpPolicy> listEnabled() {
        requireSuperAdmin();
        return sysIpPolicyMapper.selectEnabledPolicies();
    }

    private SysIpPolicy requirePolicy(Long id) {
        SysIpPolicy policy = sysIpPolicyMapper.selectById(id);
        if (policy == null) {
            throw BusinessException.notFound("IP 策略不存在");
        }
        return policy;
    }

    private void requireSuperAdmin() {
        LoginResponse.UserInfoVO userInfo = authService.getUserInfo();
        String role = userInfo == null ? "" : String.valueOf(userInfo.getRole()).trim().toLowerCase();
        if (!"superadmin".equals(role)) {
            throw BusinessException.forbidden("仅超级管理员可访问安全策略模块");
        }
    }
}
