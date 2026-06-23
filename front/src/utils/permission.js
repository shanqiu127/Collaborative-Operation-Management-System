import { canAccessRoles, getDeptCode, getRole, hasDeptAccess } from '@/utils/auth'

/**
 * 自定义指令：v-permission
 * 作用：处理按钮级别的权限控制
 * 用法：<el-button v-permission="['admin']">删除</el-button>
 * 详情：如果当前用户的角色不在指令绑定的数组内，该 DOM 元素将被直接移除。
 */
export default {
  mounted(el, binding) {
    const { value } = binding
    const role = getRole()
    const deptCode = getDeptCode()

    let allowRoles = []
    let allowDeptCodes = []

    if (Array.isArray(value)) {
      allowRoles = value
    } else if (value && typeof value === 'object') {
      allowRoles = Array.isArray(value.roles) ? value.roles : []
      allowDeptCodes = Array.isArray(value.deptCodes) ? value.deptCodes : []
    } else {
      throw new Error(`需要指定权限角色，如 v-permission="['admin']" 或 v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"`)
    }

    if (allowRoles.length > 0 || allowDeptCodes.length > 0) {
      const roleAllowed = allowRoles.length === 0 || canAccessRoles(role, allowRoles)
      const deptAllowed = allowDeptCodes.length === 0 || hasDeptAccess(deptCode, allowDeptCodes, role)
      const allowed = roleAllowed && deptAllowed

      if (!allowed) {
        // 统一移除 DOM 元素，避免通过 DevTools 取消 disabled 恢复操作
        el.parentNode && el.parentNode.removeChild(el)
      }
    }
  }
}
