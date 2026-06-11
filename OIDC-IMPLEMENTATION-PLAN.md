# Metabase 社区版 OIDC 集成实施计划

## 1. 架构分析

### 1.1 现有基础（社区版已具备）
- `metabase.auth-identity.provider` - 多方法认证系统
- `metabase.sso.oidc.check` - OIDC 配置验证
- `metabase.sso.oidc.state` - 加密 cookie 状态管理
- `metabase.sso.oidc.discovery` - 发现文档获取
- `metabase.sso.oidc.http` - OIDC HTTP 客户端
- `metabase.sso.common/sync-group-memberships!` - 组同步

### 1.2 企业版代码（需要移植）
- `metabase-enterprise.sso.providers.oidc` - OIDC provider 实现
- `metabase-enterprise.sso.integrations.oidc` - OIDC 路由处理
- `metabase-enterprise.sso.api.oidc` - Admin CRUD API
- `metabase-enterprise.sso.settings` - OIDC 配置设置
- `metabase-enterprise.sso.integrations.sso-utils` - 工具函数
- `metabase-enterprise.auth-identity.provider` - SSO 用户字段

### 1.3 前端（需要移植）
- `metabase-enterprise/auth/components/OidcButton` - OIDC 登录按钮
- `metabase-enterprise/auth/components/SettingsOIDCForm` - 配置表单
- `metabase-enterprise/api/oidc` - RTK Query API
- `metabase-enterprise/auth/index.ts` - 插件注册

## 2. 改动点清单

### 改动点 A：后端 OIDC Provider + 认证流程
**文件**：
- 新建 `src/metabase/sso/providers/oidc.clj`
- 新建 `src/metabase/sso/integrations/oidc.clj`
- 修改 `src/metabase/server/auth_wrapper.clj`

**内容**：
1. 实现 `auth-identity/authenticate :provider/custom-oidc` 多方法
2. 实现 `auth-identity/login! :provider/custom-oidc` 多方法
3. 实现 OIDC 授权端点重定向 + code 交换 + token 验证
4. 集成 state/nonce 验证（复用 `metabase.sso.oidc.state`）
5. 注入 `/auth/sso/:key` 和 `/auth/sso/:key/callback` 路由

**测试**：
- 单元测试：token 解析、配置验证
- 集成测试：完整 OIDC 流程（mock IdP）

### 改动点 B：后端 Group Sync + 设置管理
**文件**：
- 修改 `src/metabase/sso/settings.clj`
- 修改 `src/metabase/sso/common.clj`（如有需要）
- 新建 `src/metabase/api/oidc.clj`（或并入现有 API）

**内容**：
1. 添加 `oidc-providers` setting（JSON 类型）
2. 添加 `oidc-enabled`、`oidc-configured` 辅助 setting
3. 添加 `oidc-user-provisioning-enabled?` setting
4. 添加 group sync 配置 setting
5. 实现 `sync-group-memberships!` 调用（登录后）
6. 实现 Admin CRUD API（或简化为环境变量配置）

**测试**：
- 设置读写测试
- Group sync 映射测试
- 用户创建/更新测试

### 改动点 C：前端 OIDC 登录按钮 + 插件注册
**文件**：
- 新建 `frontend/src/metabase/auth/components/OidcButton/OidcButton.tsx`
- 修改 `frontend/src/metabase/plugins/oss/auth.ts`
- 新建 `frontend/src/metabase/api/oidc.ts`

**内容**：
1. 创建 OIDC 登录按钮组件
2. 注册 `PLUGIN_AUTH_PROVIDERS` provider 函数
3. 从 settings 读取 `oidc-login-providers` 并渲染按钮
4. 点击跳转 `/auth/sso/:key?redirect=...`

**测试**：
- 组件渲染测试
- 点击跳转测试

### 改动点 D：前端 Admin 配置 + 测试回归
**文件**：
- 新建 `frontend/src/metabase/auth/components/SettingsOIDCForm/SettingsOIDCForm.tsx`
- 修改 `frontend/src/metabase/auth/selectors.ts`（如有需要）
- 修改相关测试文件

**内容**：
1. 创建 OIDC 配置表单（可选 - 若用环境变量可简化）
2. 确保本地登录、Google、LDAP 不受影响
3. 测试回归：所有现有认证方式

**测试**：
- E2E 测试：完整 OIDC 登录流程
- 回归测试：密码登录、Google 登录

## 3. 上游兼容性策略

### 3.1 目录结构
- 所有新文件放在社区版目录（`src/`、`frontend/src/`）
- 不修改企业版代码（`enterprise/`）
- 通过条件编译或配置开关控制功能

### 3.2 合并策略
```bash
# 定期同步上游
git remote add upstream https://github.com/metabase/metabase.git
git fetch upstream master
git merge upstream/master --no-ff
# 冲突解决：优先保留我们的 OIDC 改动，合并其他更新
```

### 3.3 关键兼容点
- 不修改 `auth-identity` 核心多方法签名
- 不修改 `sso.oidc.check/state` 等已有接口
- 新 provider 使用独立关键字 `:provider/custom-oidc`
- 前端插件系统完全兼容

## 4. 测试回归清单

| 测试项 | 预期结果 |
|--------|---------|
| 本地密码登录 | ✅ 正常工作 |
| Google SSO 登录 | ✅ 正常工作 |
| LDAP 登录 | ✅ 正常工作 |
| OIDC 登录（Keycloak） | ✅ 正常工作，创建/更新用户 |
| OIDC Group Sync | ✅ 用户自动加入映射组 |
| 密码登录禁用 | ✅ OIDC 模式下可禁用密码登录 |
| 会话管理 | ✅ 正常创建/过期/登出 |
| Admin 设置页面 | ✅ 显示 OIDC 配置 |
| 上游代码合并 | ✅ 无冲突或冲突可自动解决 |

## 5. 环境变量配置（Phase 1：简化配置）

为简化实现，Phase 1 使用环境变量配置：
```bash
MB_OIDC_ENABLED=true
MB_OIDC_ISSUER_URI=https://auth.home.martini.wang:50443/realms/martini
MB_OIDC_CLIENT_ID=metabase
MB_OIDC_CLIENT_SECRET=***
MB_OIDC_SCOPES=openid,email,profile
MB_OIDC_GROUP_SYNC_ENABLED=true
MB_OIDC_GROUP_ATTRIBUTE=groups
MB_OIDC_GROUP_MAPPINGS={"admin":[1],"data-team":[2]}
```

Phase 2 可添加 Admin UI CRUD。
