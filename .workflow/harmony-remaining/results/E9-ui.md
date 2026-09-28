# E9 Google 配置 UI 与凭据提交链

2026-09-28。已按 E8 检查点后的 E9 合同实施；读取 harmony-arkts skill、E9 Google 设计、现有详情页 load/save/catalog/export/delete 及冻结 A/B/C DTO。独占修改 `pages/ChatProviderDetailPage.ets`，新增 `pages/GoogleProviderAuthPage.ets` 与 `platform_impl/GoogleProviderConfigSupport.ets`。保留其它 owner 的 domain/auth/secure/DI/routes 编辑；未操作设备、发起授权/网络请求、运行测试、构建或签名。

## 实际调用链

- 新页面走已有 Provider UUID：详情页 → `pages/GoogleProviderAuthPage` + `{providerId}` → 真实存储 lookup。未落库的 Google 服务商可以先按原按钮保存空 Key，再进入认证页；空配置状态不冒称可用。Root 已登记页面路由。
- API Key / Vertex API Key 保留原详情页表单。OAuth / SA 时隐藏 API Key 与原 Vertex 开关，详情页普通保存保留原 `authMode/useServiceAccount/email/privateKey/privateKeyRef/oauthClient*`，不强清到 API Key。返回后刷新真实 Google 模式及变动字段，现有模型参数和其它草稿继续沿原行为。
- 认证页分别选择 API Key、Vertex 服务账号、Code Assist、Antigravity。显式填写 Client ID 与精确注册 Redirect URI；Client Secret 以密码字段接收并只提交 secure 引用。Code Assist 文案说明 Standard/Enterprise 与个人 tier 的真实停用边界。状态来自 `loadGoogleAuthStatus`，区分 configured、signed_out、onboarding_required、ready 和 error；保存本地配置不等同真实授权。
- 登录只使用已保存 setting → `beginGoogleLogin` → 系统浏览器 → actual loopback completion。合法浏览器暂后台不取消；返回、页面销毁、配置变化、显式取消、退出会取消未完成 attempt。服务开通重试使用现有 EntryAbortController，离开或配置变化会 abort；重新显示只读取状态，不重复发起登录。服务器给出 validationUrl 时提供实际验证页入口。
- Google OAuth / SA 模型拉取走 `loadGoogleModelCatalog(setting,signal)`，不会用 API Key 代替 OAuth/SA。普通 Google API Key（包括原 Vertex API Key 配置）继续沿旧 `{baseUrl}/models` + `x-goog-api-key` 请求和 `models[].name` 解析。unsupported OAuth catalog 显示 B 的真实原因并保留原手动添加。新增目录模型取真实 modelId/displayName，已有同 API ID 模型保留原 metadata。

## 真实凭据寿命

- SA JSON 只读取 Google `type/project_id/client_email/private_key`，限定 PKCS#8 PEM，文件限 128KiB。私钥原样传 `stageGoogleSecret`，页面只保临时 ref/email/project，不把私钥放到 UI State。成功保存认证配置时 fresh lookup 合并当前 Provider（保留 models/其它字段），再回收旧 owned ref。Client Secret 采用相同 stage→commit→reclaim 顺序。
- 保存失败清理本次新 ref，保留原 provider/secret。存储读取或 Provider 已消失的早期失败也会清理 handed-over 私钥 ref。未保存导入 ref 在取消/离开时清理；保存开始后 helper 接管它，页面不会误删提交中的 ref。若导入提交失败，页面提示重新导入。
- 旧 Preferences `privateKey` 仅在显式 SA 保存成功后迁入安全存储并从 provider metadata 清空。新页明确账号授权与新 SA 私钥安全保存，API Key 保留旧配置存储；不称全部凭据已加密。
- 详情页导出增加模式、服务账号邮箱、Client ID/Redirect 等非秘密 metadata；继续不导出 API Key、私钥、privateKeyRef、Client Secret/ref、OAuth tokens。模型只导出原最小公开字段，无秘密 overwrite 载荷。
- 删除先提交 Provider JSON 删除，再用 fresh 删除对象调用 `forgetGoogleProvider` 清两 OAuth 身份、runtime/cache，与 C 的 owned ref 删除。后置清理失败提示“服务商已删除，但凭据清理失败”，不回滚已删除 metadata、不触碰其它 Provider。

## 必要验证与 review 回喂

- 三 owned 文件一次 lint：`/tmp/e9-ui-lint.log` 退出 0，No defects。
- helper 真实 domain/DI/B/C/官方 SDK 窄 types：`/tmp/e9-google-config-types.log` 为 0 diagnostics；无接口 stub。
- `git diff --check` 通过。新认证页 417 行、helper 142 行；详情页约 1277 行，认证逻辑在小页中，没有继续堆到原详情页。
- 独立 review 的实际 P1 已精准修复：保存/导入 busy 时标题返回忽略、hardware `onBackPress` 消费，避免旧详情页读旧 ref 后覆盖新提交。OAuth authBusy 不在该门，仍可取消离开。受影响单页 lint `/tmp/e9-ui-backfix-lint.log` 退出 0，No defects。
- 独立 review 的 P2 存储说明已修为上述准确范围。D 另外将 commit 后 `logoutGoogle(prior)` 不应覆盖同 mode 新 binding 的实际窗口交给 B；B 已确认按匹配旧 binding 精准处理，D 不另建锁或协议。
- 独立 review 的 API Key catalog P2 已精准修复：详情页第 265 行仅 OAuth/SA 分支进入新目录端口，普通 Google API Key 恢复原请求路径、原 Google header、原解析器，不扩 Vertex API Key 目录能力。受影响详情页一次 lint `/tmp/e9-ui-catalogfix-lint.log` 退出 0、No defects。
- 配合 B 的旧 E6 snapshot authority 修复：仅真实 Providers JSON 提交成功后 `await updateGoogleAuthConfiguration(updated)`，然后进行原旧身份退出/owned ref 回收。该 hook 无 HTTP；普通状态读取不成为配置写入 authority。hook 在提交失败回滚 catch 外，后置失败不会误删已提交新 ref，而返回已保存但授权更新/旧凭据清理失败的真实提示。
- 最终 hook 的受影响 helper 检查：`/tmp/e9-google-config-authority-types.log` 为 0 diagnostics，`/tmp/e9-ui-authority-lint.log` 退出 0、No defects。B 已实际导出所冻接口，检查使用真实模块，没有新 suite/模拟接口。
- Root 真实 CompileArkTS `/tmp/e9-release-build.log` 的四条 owned 类型诊断已机械修复：helper 两处 rethrow 明确 `as Error`，详情页与认证页的两个 Promise.catch 参数明确 `Error`。保持原控制流与消息，未新增测试或重复既有验证，交 root 统一重编译。
- 新页所有说明/状态可全文换行、统一 16vp 左右对齐、既有按钮最小 44vp、滚动页与原主题/safe area。未声称截图视觉验收；实际 ArkTS compiler、亮暗布局、API12 socket/crypto/secure store 与真实 Google 账户仍由 root 外部门收口。
