package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.ApprovalDecisionDTO;
import org.example.back.dto.DeptQueryDTO;
import org.example.back.dto.DeptSaveDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysEmployee;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysEmployeeMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.DeptVO;
import org.example.back.vo.OptionVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
// 部门 Service
@Service
public class DeptService {

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_APPROVED = 2;
    private static final int STATUS_REJECTED = 3;
    private static final int APPROVAL_TEXT_MAX_LEN = 200;

    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private SysEmployeeMapper sysEmployeeMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private AuthzService authzService;

    private void requireDeptModuleAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_HR, "仅人事部门管理员可访问部门管理");
    }

    @Scheduled(cron = "0 0 * * * ?")
    public void cleanupExpiredRejectedDepts() {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getStatus, STATUS_REJECTED)
                .lt(SysDept::getRejectedAt, LocalDateTime.now().minusDays(1));
        sysDeptMapper.delete(wrapper);
    }

    // 部门分页查询
    public PageResult<DeptVO> page(DeptQueryDTO queryDTO) {
        requireDeptModuleAccess();
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getDeptName()), SysDept::getDeptName, queryDTO.getDeptName())
                .like(StringUtils.hasText(queryDTO.getRequesterName()), SysDept::getRequesterName, queryDTO.getRequesterName())
                .eq(queryDTO.getStatus() != null, SysDept::getStatus, queryDTO.getStatus())
                .orderByDesc(SysDept::getCreateTime)
                .orderByDesc(SysDept::getId);

        Page<SysDept> page = sysDeptMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<DeptVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
    // 部门选项列表
    public List<OptionVO> options() {
        if (authzService.isAdmin() && !authzService.isDeptAdmin(AuthzService.DEPT_HR)) {
            SysDept currentDept = requireDept(authzService.currentDeptId());
            return List.of(new OptionVO(currentDept.getId(), currentDept.getDeptName()));
        }
        return publicOptions();
    }

    public List<OptionVO> publicOptions() {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getStatus, STATUS_APPROVED)
            .ne(SysDept::getDeptCode, AuthzService.DEPT_SYSTEM_MANAGEMENT)
                .orderByAsc(SysDept::getDeptName);
        return sysDeptMapper.selectList(wrapper).stream()
                .map(item -> new OptionVO(item.getId(), item.getDeptName()))
                .toList();
    }

    public DeptVO getById(Long id) {
        requireDeptModuleAccess();
        SysDept dept = requireDept(id);
        return toVO(dept);
    }

    public void create(DeptSaveDTO dto) {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_HR, "仅人事部门管理员可提交部门审批请求");
        LoginResponse.UserInfoVO requester = authzService.currentUser();
        SysDept dept = new SysDept();
        BeanUtils.copyProperties(dto, dept);
        dept.setDeptCode(CodeGenerator.deptCode());
        dept.setStatus(STATUS_PENDING);
        dept.setRequesterId(requester.getId());
        dept.setRequesterName(requester.getRealName());
        checkDeptNameUnique(dto.getDeptName(), null);
        sysDeptMapper.insert(dept);
    }

    public void update(Long id, DeptSaveDTO dto) {
        requireDeptModuleAccess();
        SysDept dept = requireDept(id);
        assertDeptApproved(dept);
        assertNotSystemManagementDept(dept, "系统管理部为系统预置部门，不允许编辑");
        checkDeptNameUnique(dto.getDeptName(), id);
        dept.setDeptName(dto.getDeptName());
        dept.setLeader(dto.getLeader());
        dept.setPhone(dto.getPhone());
        dept.setDescription(dto.getDescription());
        sysDeptMapper.updateById(dept);
    }

    public void approve(Long id, ApprovalDecisionDTO dto) {
        authzService.requireSuperAdmin("仅超级管理员可审批部门新增请求");
        LoginResponse.UserInfoVO approver = authzService.currentUser();
        SysDept dept = requireDept(id);
        assertPendingDept(dept);
        dept.setStatus(STATUS_APPROVED);
        dept.setApproverId(approver.getId());
        dept.setApproverName(approver.getRealName());
        dept.setApprovalRemark(normalizeRemark(dto));
        dept.setApprovedAt(LocalDateTime.now());
        dept.setRejectedAt(null);
        sysDeptMapper.updateById(dept);
    }

    public void reject(Long id, ApprovalDecisionDTO dto) {
        authzService.requireSuperAdmin("仅超级管理员可审批部门新增请求");
        LoginResponse.UserInfoVO approver = authzService.currentUser();
        SysDept dept = requireDept(id);
        assertPendingDept(dept);
        dept.setStatus(STATUS_REJECTED);
        dept.setApproverId(approver.getId());
        dept.setApproverName(approver.getRealName());
        dept.setApprovalRemark(normalizeRemark(dto));
        dept.setApprovedAt(null);
        dept.setRejectedAt(LocalDateTime.now());
        sysDeptMapper.updateById(dept);
    }

    public void delete(Long id) {
        authzService.requireSuperAdmin("仅超级管理员可删除部门");
        SysDept dept = requireDept(id);
        assertNotSystemManagementDept(dept, "系统管理部为系统预置部门，不允许删除");
        if (!Integer.valueOf(STATUS_APPROVED).equals(dept.getStatus())) {
            sysDeptMapper.deleteById(id);
            return;
        }
        LambdaQueryWrapper<SysEmployee> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysEmployee::getDeptId, id);
        if (sysEmployeeMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("该部门下仍有关联员工，无法删除");
        }
        LambdaQueryWrapper<SysUser> userWrapper = new LambdaQueryWrapper<>();
        userWrapper.eq(SysUser::getDeptId, id);
        if (sysUserMapper.selectCount(userWrapper) > 0) {
            throw BusinessException.validateFail("该部门下仍有关联用户账号，无法删除");
        }
        sysDeptMapper.deleteById(id);
    }
    // 根据 ID 获取部门，若不存在则抛出异常
    private SysDept requireDept(Long id) {
        SysDept dept = sysDeptMapper.selectById(id);
        if (dept == null) {
            throw BusinessException.notFound("部门不存在");
        }
        return dept;
    }

    private void checkDeptNameUnique(String deptName, Long excludeId) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getDeptName, deptName)
                .ne(excludeId != null, SysDept::getId, excludeId);
        if (sysDeptMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("部门名称已存在");
        }
    }

    private void assertPendingDept(SysDept dept) {
        if (!Integer.valueOf(STATUS_PENDING).equals(dept.getStatus())) {
            throw BusinessException.validateFail("仅待审批部门可执行审批操作");
        }
    }

    private void assertDeptApproved(SysDept dept) {
        if (!Integer.valueOf(STATUS_APPROVED).equals(dept.getStatus())) {
            throw BusinessException.validateFail("仅已生效部门允许编辑");
        }
    }

    private void assertNotSystemManagementDept(SysDept dept, String message) {
        if (AuthzService.DEPT_SYSTEM_MANAGEMENT.equals(authzService.normalizeDeptCode(dept.getDeptCode()))) {
            throw BusinessException.validateFail(message);
        }
    }

    private String normalizeRemark(ApprovalDecisionDTO dto) {
        String remark = dto == null ? null : dto.getRemark();
        if (!StringUtils.hasText(remark)) {
            return null;
        }
        String normalized = remark.trim();
        if (normalized.length() > APPROVAL_TEXT_MAX_LEN) {
            throw BusinessException.validateFail("审批备注长度不能超过" + APPROVAL_TEXT_MAX_LEN + "个字符");
        }
        return normalized;
    }
    // 将部门实体转换为 VO 对象
    private DeptVO toVO(SysDept dept) {
        DeptVO vo = new DeptVO();
        BeanUtils.copyProperties(dept, vo);
        vo.setManager(dept.getLeader());
        vo.setContactPhone(dept.getPhone());
        return vo;
    }
}