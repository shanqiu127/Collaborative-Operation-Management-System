package org.example.back.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.NoticeQueryDTO;
import org.example.back.dto.NoticeSaveDTO;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysNotice;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysNoticeMapper;
import org.example.back.vo.NoticeVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NoticeService {

    private static final String TARGET_ROLE_ADMIN = "admin";
    private static final String TARGET_ROLE_EMPLOYEE = "employee";
    private static final String TARGET_ROLE_ALL = "all";

    @Autowired
    private SysNoticeMapper sysNoticeMapper;

    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private AuthzService authzService;

    public PageResult<NoticeVO> page(NoticeQueryDTO queryDTO) {
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        if (currentUser == null) {
            throw BusinessException.unauthorized("用户未登录，请先登录");
        }
        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getTitle()), SysNotice::getTitle, queryDTO.getTitle())
                .eq(StringUtils.hasText(queryDTO.getTargetRole()), SysNotice::getTargetRole, normalizeTargetRole(queryDTO.getTargetRole()))
                .eq(queryDTO.getTargetDeptId() != null, SysNotice::getTargetDeptId, queryDTO.getTargetDeptId())
                .eq(queryDTO.getStatus() != null, SysNotice::getStatus, queryDTO.getStatus())
                .orderByDesc(SysNotice::getPublishTime)
                .orderByDesc(SysNotice::getId);

        applyVisibilityFilter(wrapper, currentUser);

        Page<SysNotice> page = sysNoticeMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<SysNotice> noticeRecords = page.getRecords() == null
                ? List.of()
                : page.getRecords().stream().filter(Objects::nonNull).toList();
        Map<Long, SysDept> deptMap = buildDeptMap(noticeRecords.stream()
                .map(SysNotice::getTargetDeptId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        List<NoticeVO> records = noticeRecords.stream()
                .map(item -> toVO(item, item.getTargetDeptId() == null ? null : deptMap.get(item.getTargetDeptId())))
                .toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public NoticeVO getById(Long id) {
        SysNotice notice = requireNotice(id);
        ensureCanView(notice, authzService.currentUser());
        return toVO(notice, notice.getTargetDeptId() == null ? null : sysDeptMapper.selectById(notice.getTargetDeptId()));
    }

    public List<NoticeVO> listAdminHomeLatest(int limit) {
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        if (!AuthzService.ROLE_ADMIN.equals(authzService.normalizeRole(currentUser.getRole()))) {
            throw BusinessException.forbidden("仅管理员首页可查看公告摘要");
        }

        LambdaQueryWrapper<SysNotice> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysNotice::getStatus, 1)
                .and(group -> group
                        .eq(SysNotice::getTargetRole, TARGET_ROLE_ALL)
                        .or(admin -> admin.eq(SysNotice::getTargetRole, TARGET_ROLE_ADMIN)
                                .and(scope -> scope.isNull(SysNotice::getTargetDeptId)
                                        .or()
                                        .eq(SysNotice::getTargetDeptId, currentUser.getDeptId()))))
                .orderByDesc(SysNotice::getPublishTime)
                .orderByDesc(SysNotice::getId)
                .last("LIMIT " + Math.max(limit, 1));

        List<SysNotice> notices = sysNoticeMapper.selectList(wrapper);
        Map<Long, SysDept> deptMap = buildDeptMap(notices.stream()
                .map(SysNotice::getTargetDeptId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        return notices.stream()
            .map(item -> toVO(item, item.getTargetDeptId() == null ? null : deptMap.get(item.getTargetDeptId())))
            .toList();
    }

    public void create(NoticeSaveDTO dto) {
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        SysNotice notice = new SysNotice();
        BeanUtils.copyProperties(dto, notice);
        applyAudience(notice, dto, currentUser);
        notice.setPublisher(currentRealName());
        if (notice.getStatus() != null && notice.getStatus() == 1 && notice.getPublishTime() == null) {
            notice.setPublishTime(LocalDateTime.now());
        }
        sysNoticeMapper.insert(notice);
    }

    public void update(Long id, NoticeSaveDTO dto) {
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        SysNotice notice = requireNotice(id);
        ensureCanManage(notice, currentUser);
        BeanUtils.copyProperties(dto, notice);
        applyAudience(notice, dto, currentUser);
        notice.setId(id);
        notice.setPublisher(currentRealName());
        if (notice.getStatus() != null && notice.getStatus() == 1 && notice.getPublishTime() == null) {
            notice.setPublishTime(LocalDateTime.now());
        }
        sysNoticeMapper.updateById(notice);
    }

    public void delete(Long id) {
        SysNotice notice = requireNotice(id);
        ensureCanManage(notice, authzService.currentUser());
        sysNoticeMapper.deleteById(id);
    }

    private SysNotice requireNotice(Long id) {
        SysNotice notice = sysNoticeMapper.selectById(id);
        if (notice == null) {
            throw BusinessException.notFound("公告不存在");
        }
        return notice;
    }

    private void applyVisibilityFilter(LambdaQueryWrapper<SysNotice> wrapper, LoginResponse.UserInfoVO currentUser) {
        if (currentUser == null) {
            throw BusinessException.unauthorized("用户未登录，请先登录");
        }
        String role = authzService.normalizeRole(currentUser.getRole());
        Long deptId = currentUser.getDeptId();
        if (AuthzService.ROLE_SUPERADMIN.equals(role)) {
            return;
        }
        if (AuthzService.ROLE_ADMIN.equals(role)) {
            wrapper.and(group -> {
                group.eq(SysNotice::getTargetRole, TARGET_ROLE_ALL)
                        .or(admin -> admin.eq(SysNotice::getTargetRole, TARGET_ROLE_ADMIN)
                                .and(scope -> {
                                    scope.isNull(SysNotice::getTargetDeptId);
                                    if (deptId != null) {
                                        scope.or().eq(SysNotice::getTargetDeptId, deptId);
                                    }
                                }));
                if (deptId != null) {
                    group.or(employee -> employee.eq(SysNotice::getTargetRole, TARGET_ROLE_EMPLOYEE)
                            .eq(SysNotice::getTargetDeptId, deptId));
                }
            });
            return;
        }
        wrapper.eq(SysNotice::getStatus, 1)
                .and(group -> {
                    group.eq(SysNotice::getTargetRole, TARGET_ROLE_ALL);
                    if (deptId != null) {
                        group.or(employee -> employee.eq(SysNotice::getTargetRole, TARGET_ROLE_EMPLOYEE)
                                .eq(SysNotice::getTargetDeptId, deptId));
                    }
                });
    }

    private void applyAudience(SysNotice notice, NoticeSaveDTO dto, LoginResponse.UserInfoVO currentUser) {
        String operatorRole = authzService.normalizeRole(currentUser.getRole());
        if (AuthzService.ROLE_SUPERADMIN.equals(operatorRole)) {
            String targetRole = normalizeTargetRole(dto.getTargetRole());
            if (!TARGET_ROLE_ADMIN.equals(targetRole) && !TARGET_ROLE_ALL.equals(targetRole)) {
                throw BusinessException.validateFail("超级管理员公告仅支持发送给管理员或全员");
            }
            notice.setTargetRole(targetRole);
            if (TARGET_ROLE_ALL.equals(targetRole)) {
                notice.setTargetDeptId(null);
                return;
            }
            if (dto.getTargetDeptId() != null) {
                authzService.requireDept(dto.getTargetDeptId());
            }
            notice.setTargetDeptId(dto.getTargetDeptId());
            return;
        }
        if (AuthzService.ROLE_ADMIN.equals(operatorRole)) {
            if (currentUser.getDeptId() == null) {
                throw BusinessException.validateFail("当前管理员未配置所属部门，无法发布公告");
            }
            notice.setTargetRole(TARGET_ROLE_EMPLOYEE);
            notice.setTargetDeptId(currentUser.getDeptId());
            return;
        }
        throw BusinessException.forbidden("当前角色无权发布公告");
    }

    private void ensureCanView(SysNotice notice, LoginResponse.UserInfoVO currentUser) {
        if (notice == null) {
            throw BusinessException.notFound("公告不存在");
        }
        String role = authzService.normalizeRole(currentUser.getRole());
        String targetRole = normalizeTargetRole(notice.getTargetRole());
        if (AuthzService.ROLE_SUPERADMIN.equals(role)) {
            return;
        }
        if (AuthzService.ROLE_ADMIN.equals(role)) {
            if (TARGET_ROLE_ALL.equals(targetRole)) {
                return;
            }
            if (TARGET_ROLE_ADMIN.equals(targetRole)) {
                if (notice.getTargetDeptId() == null || authzService.hasDeptAccess(notice.getTargetDeptId())) {
                    return;
                }
            }
            if (TARGET_ROLE_EMPLOYEE.equals(targetRole) && authzService.hasDeptAccess(notice.getTargetDeptId())) {
                return;
            }
            throw BusinessException.forbidden("无权限查看该公告");
        }
        if (!Integer.valueOf(1).equals(notice.getStatus())) {
            throw BusinessException.forbidden("无权限查看该公告");
        }
        if (TARGET_ROLE_ALL.equals(targetRole)) {
            return;
        }
        if (TARGET_ROLE_EMPLOYEE.equals(targetRole) && authzService.hasDeptAccess(notice.getTargetDeptId())) {
            return;
        }
        throw BusinessException.forbidden("无权限查看该公告");
    }

    private void ensureCanManage(SysNotice notice, LoginResponse.UserInfoVO currentUser) {
        if (notice == null) {
            throw BusinessException.notFound("公告不存在");
        }
        String role = authzService.normalizeRole(currentUser.getRole());
        if (AuthzService.ROLE_SUPERADMIN.equals(role)) {
            return;
        }
        if (AuthzService.ROLE_ADMIN.equals(role)
                && TARGET_ROLE_EMPLOYEE.equals(normalizeTargetRole(notice.getTargetRole()))
                && authzService.hasDeptAccess(notice.getTargetDeptId())) {
            return;
        }
        throw BusinessException.forbidden("无权限管理该公告");
    }

    private Map<Long, SysDept> buildDeptMap(Set<Long> deptIds) {
        if (deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SysDept::getId, deptIds);
        return sysDeptMapper.selectList(wrapper).stream().collect(Collectors.toMap(SysDept::getId, Function.identity()));
    }

    private NoticeVO toVO(SysNotice notice, SysDept dept) {
        NoticeVO vo = new NoticeVO();
        BeanUtils.copyProperties(notice, vo);
        vo.setTargetDeptName(dept == null ? null : dept.getDeptName());
        vo.setAuthor(notice.getPublisher());
        vo.setDate(notice.getPublishTime());
        return vo;
    }

    // 获取当前登录用户的真实姓名，若无法获取则返回默认值 "系统"
    private String currentRealName() {
        Object userInfo = StpUtil.getSession().get("userInfo");
        if (userInfo instanceof LoginResponse.UserInfoVO loginUser) {
            return loginUser.getRealName();
        }
        return "系统";
    }

    private String normalizeTargetRole(String targetRole) {
        if (!StringUtils.hasText(targetRole)) {
            return "";
        }
        return targetRole.trim().toLowerCase(Locale.ROOT);
    }
}