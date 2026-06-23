import { createRouter, createWebHistory } from "vue-router"
import { ElMessage } from "element-plus"
import { canAccessRoles, getDeptCode, getRole, getToken, hasDeptAccess, isSuperAdmin } from "@/utils/auth"

const SUPERADMIN_ALLOWED_PATHS = new Set([
  '/',
  '/home',
  '/system/notice',
  '/system/super-admin',
  '/system/dept-approval',
  '/system/user',
  '/system/security-ip-policy',
  '/system/login-log',
  '/system/operation-log',
  '/assistant/project',
  '/403'
])

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/login",
      name: "Login",
      component: () => import("../views/LoginView.vue")
    },
    {
      path: "/register",
      name: "Register",
      component: () => import("../views/RegisterView.vue")
    },
    {
      path: "/",
      component: () => import("../layout/index.vue"),
      redirect: "/home",
      children: [
        {
          path: "home",
          name: "Home",
          component: () => import("../views/HomeView.vue")
        },
        // 基础资料页面 (由于普通员工只能查看，故不使用角色锁，在页面中利用 v-permission 控制操作按钮即可)
        {
          path: "base/supplier",
          name: "BaseSupplier",
          component: () => import("../views/base/SupplierView.vue"),
          meta: { roles: ['admin'], deptCodes: ['warehouse'] }
        },
        {
          path: "base/goods",
          name: "BaseGoods",
          component: () => import("../views/base/GoodsView.vue"),
          meta: { roles: ['admin'], deptCodes: ['warehouse'] }
        },
        {
          path: "business/purchase",
          name: "BusinessPurchase",
          component: () => import("../views/business/PurchaseView.vue"),
          meta: { roles: ['admin'], deptCodes: ['purchase'] }
        },
        {
          path: "business/purchase-return",
          name: "BusinessPurchaseReturn",
          component: () => import("../views/business/PurchaseReturnView.vue"),
          meta: { roles: ['admin'], deptCodes: ['purchase'] }
        },
        {
          path: "business/sales",
          name: "BusinessSales",
          component: () => import("../views/business/SalesView.vue"),
          meta: { roles: ['admin'], deptCodes: ['sales'] }
        },
        {
          path: "business/sales-return",
          name: "BusinessSalesReturn",
          component: () => import("../views/business/SalesReturnView.vue"),
          meta: { roles: ['admin'], deptCodes: ['sales'] }
        },
        {
          path: "business/sales-chart",
          name: "BusinessSalesChart",
          component: () => import("../views/business/SalesChartView.vue"),
          meta: { roles: ['admin'], deptCodes: ['finance'] }
        },
        {
          path: "business/stock-warning",
          name: "BusinessStockWarning",
          component: () => import("../views/business/StockWarningView.vue"),
          meta: { roles: ['admin'], deptCodes: ['warehouse', 'purchase', 'sales'] }
        },
        {
          path: "system/notice",
          name: "SystemNotice",
          component: () => import("../views/system/NoticeView.vue"),
          meta: { roles: ['admin', 'superadmin'] }
        },
        {
          path: "system/user",
          name: "SystemUser",
          component: () => import("../views/system/UserView.vue"),
          meta: { roles: ['admin', 'superadmin'] }
        },
        {
          path: "system/dept",
          name: "SystemDept",
          component: () => import("../views/system/DeptView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/employee",
          name: "SystemEmployee",
          component: () => import("../views/system/EmployeeView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/hr-chart",
          name: "SystemHrChart",
          component: () => import("../views/system/HrChartView.vue"),
          meta: { roles: ['admin'], deptCodes: ['hr'] }
        },
        {
          path: "system/void-approval",
          name: "SystemVoidApproval",
          component: () => import("../views/system/VoidApprovalView.vue"),
          meta: { roles: ['admin'], deptCodes: ['warehouse'] }
        },
        {
          path: "system/super-admin",
          name: "SystemSuperAdmin",
          component: () => import("../views/system/SuperAdminDashboardView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/dept-approval",
          name: "SystemDeptApproval",
          component: () => import("../views/system/DeptApprovalView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/security-ip-policy",
          name: "SystemSecurityIpPolicy",
          component: () => import("../views/system/SecurityIpPolicyView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/login-log",
          name: "SystemLoginLog",
          component: () => import("../views/system/LoginLogView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/operation-log",
          name: "SystemOperationLog",
          component: () => import("../views/system/OperationLogView.vue"),
          meta: { roles: ['superadmin'] }
        },
        {
          path: "system/work-requirement",
          name: "SystemWorkRequirement",
          component: () => import("../views/system/WorkRequirementView.vue"),
          meta: { roles: ['admin'] }
        },
        {
          path: "work-requirement/:assignId",
          name: "WorkRequirementDetail",
          component: () => import("../views/WorkRequirementDetailView.vue"),
          meta: { roles: ['employee', 'admin'] }
        },
        {
          path: "assistant/project",
          name: "ProjectAssistant",
          component: () => import("../views/system/ProjectAssistantView.vue"),
          meta: { roles: ['superadmin', 'admin', 'employee'] }
        }
      ]
    },
    // 将无权限页面重定向
    {
      path: "/403",
      name: "Forbidden",
      component: () => import("../views/ForbiddenView.vue")
    }
  ]
})

// 添加前置路由全局守卫：处理登录拦截与角色权限
router.beforeEach((to, from, next) => {
  const token = getToken()
  const role = getRole()
  const deptCode = getDeptCode()

  if (to.path !== '/login' && to.path !== '/register') {
    if (!token) {
      return next('/login')
    } else {
      if (isSuperAdmin(role) && !SUPERADMIN_ALLOWED_PATHS.has(to.path)) {
        ElMessage.warning('超级管理员仅开放首页与超管中心模块')
        return next('/home')
      }

      if (to.meta && to.meta.roles) {
        const allowRoles = to.meta.roles
        if (!canAccessRoles(role, allowRoles)) {
          ElMessage.error('无权限访问该页面')
          return next('/403')
        }
      }

      if (to.meta && Array.isArray(to.meta.deptCodes) && to.meta.deptCodes.length > 0) {
        if (!hasDeptAccess(deptCode, to.meta.deptCodes, role)) {
          ElMessage.error('当前部门无权限访问该页面')
          return next('/403')
        }
      }
      return next()
    }
  } else {
    // 已登录用户访问登录/注册页时重定向到首页
    if (token) {
      return next('/home')
    }
    next()
  }
})

export default router