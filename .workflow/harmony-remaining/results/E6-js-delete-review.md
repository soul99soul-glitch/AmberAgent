# E6 paused JS 会话删除取消链审阅

2026-09-28。仅源码只读审阅新增的会话归属与删除取消接线；未改产品、运行测试、编译 native 或操作设备。结论：本次范围未发现阻断问题，真实 waiting approval JS 资源在会话删除成功后有明确取消路径。

## 归属与调用链

- `NativePluginJsTransport.ets:5–9,76–85`：`PluginJsSession.conversationId` 在 transport factory 创建时捕获，实际 `start()` 登记到模块级 `activeSessions`。归属没有从 `request`、工具 metadata 或 checkpoint 获取。
- `EntryPluginRuntime.ets:10–16`：第四参数 `conversationId: string | null = null` 传到 `createNativePluginJsTransport(conversationId)`；现有三参数消费者仍兼容。
- `ConversationRunService.ets:70–88,504–505`：`DetachedConversationContext` 的只读 ID 来自载入的 canonical `conversation.id`。主工具 `ConversationRunTools.ets:255,261` 在初始化及 catalog 更新时都传入 `ctx.conversationId`。
- `ConversationRunTools.ets:153–163` → `AppContainer.ets:902–904` → `AppContainer.ets:736–738,829–832`：subagent 使用相同父会话 ID 创建独立 generation port 和 plugin adapter；没有读取全局当前页面或改用 child/checkpoint ID。

## 删除时机与精确取消

- 成功持久删除后才调用 `forgetDeletedConversation(id)`：`ChatListPage.ets:679–685`、`ChatPage.ets:2833–2841`、`ChatAssistantsPage.ets:328–346`。调用均在既有 run/restore 门内；`repo.delete()` 拒绝时不会进入取消分支。`ConversationRunGuard.ets:11–15,29–40` 阻止同一目标在该删除区间启动新 run。
- waiting approval 会让 `ConversationRunService.ets:625–637` 清空 `record`、释放 run 门；无 UI 订阅等占用时 `releaseIdle()` 会删除 Service session（`:181–185`），所以不能仅靠 Service session 查找 JS。
- `ConversationRunService.ets:288–299` 对已释放的 session 分支仍调用 `cancelNativePluginJsConversation(id)`（`:290`）；对存留 session，在 busy/write 门检查后调用同一函数（`:291–294`），再清理状态。
- `NativePluginJsTransport.ets:40–43` 从真实 `activeSessions.values()` 的快照逐项比较 `session.conversationId === conversationId`；只有匹配项进入 `stopSession()`（`:28–30`）。不存在解析保存的 execution ID、取消另一会话或调用全局取消函数的路径。
- `stopSession()` 同步调用 native cancel，然后 `forgetSession()` 移除原 abort listener 和 registry 条目（`:22–30`）。等待审批的 `host_call` 保留 registry/listener（`:68–73,92–94`），因此该资源仍能被删除取消定位。已排队的迟到 `host_call/finished` 继续沿 E5 的 receive 门拒绝（`:54–71`）。

## 既有 NAPI 契约

本次未变更 `plugin_js_core.*`、`plugin_js_napi.*` 或 `types/libamber_native/index.d.ts`。`pluginJsCancel(sessionId): void` 的声明（`index.d.ts:107`）、NAPI 入参/注册（`plugin_js_napi.cpp:178–183,200`）与 adapter 调用一致。`Runtime::Cancel()` 只关闭 execution ID 相符的 session（`plugin_js_core.cpp:416–419`）；`Session::Close()` 关闭 gate 并通知 paused host callback（`:102–110`），不引入 join 或硬终止。

保留的无 ID 消费者有具体边界：插件中心 `AppContainer.ets:1260` 仅调用 `prepare()` 并存档/导航（`PluginPageLaunch.ets:19–46`），执行随后进入带 ID 的主工具宿主；`ChatPage.ets:1076` 仅查询 `hasSession`。两处均不会登记无 owner 的执行 session。后台全局取消仍仅由既有 `EntryAbility.ets:370` 使用，与删除会话的定向取消分开。

这是源码链路结论，不是 JSVM 真机运行、TSFN 交付或删除 UI 的设备验证结果。沿用已冻结的 cooperative abandon 边界：关闭 paused gate 可使 callback 退出；持续纯 JS 脚本没有 native hard kill 承诺。
