# Phase 3 OAuth token 同步修复

## UI-09 的第二处真实问题

独立 review 的实际 helper 探针确认：Codex/Grok 登录或 refresh 的 HTTP 完成后，`syncAccessTokenToProvider` 只检查 provider id/type，尚未确认持久化 provider 是否仍使用对应 OAuth 模式。用户已经改为 API-key、另一种 OAuth 或 Token Plan 时，迟到 token 会覆盖其新 key。page 层 session/cancel 检查只能保护页内状态，不能阻止 OAuth client 内部的同步，因此需要在实际写入边界校验。

修复仅两处已有条件：Codex 要求 `authMode === 'codex_oauth'`，Grok 要求 `authMode === 'grok_oauth'`；其余情况沿既有返回原 provider 路径处理。不新增重试、兜底或全局状态。OAuth token 自身的保存/刷新协议和对应模式下 provider key 同步保持原行为。

## RED → GREEN

新增 `harmony/chat/src/test/oauth_provider_sync_entry.test.cjs`，提取并执行两个生产 `syncAccessTokenToProvider` 及实际 `refresh*Tokens`，只替换 KV/HTTP/令牌解析端口。

- 对两个 client 分别验证 API-key、另一个 OAuth、MiMo Plan 不被迟到 token 覆盖。
- 验证对应 OAuth 更新仅 key，重复同 token 不重复写。
- 验证 provider id/type 边界。
- 验证真实 refresh 仍保存 tokens 并同步 active OAuth provider。
- 验证真实 refresh HTTP pending 期间改成 API-key，返回后保留作者新 key。

修改前 **14 项中 6 pass / 8 fail**；修改后 **14/14 pass**。连既有 runtime 和 Phase 2 Entry auth 用例共 **49/49 pass**。

日志：`/tmp/phase3-oauth-sync-red.log`、`/tmp/phase3-oauth-sync-green.log`。

两个 OAuth client 定向 ArkTS lint **0 defect**，日志 `/tmp/phase3-oauth-sync-lint.log`；`git diff --check` 通过。此验证覆盖真实生产 helper/refresh 编排，不代表实际 OAuth 云端登录验收。构建/签名/设备验证由主 agent 集成执行。

已通知 Phase 3 reviewer `fix_backup` 独立复核此两行修复和新增测试。

## 独立复核补充：同模式取消后的迟到持久化

reviewer 的实际 `pollCodexDeviceCode` / `exchangeGrokCode` probe 证明，取消发生在 HTTP pending 期间，即使 provider 仍是同一 OAuth 模式，也会继续保存新账号 tokens 并同步 provider key。页内 owner/session 判断无法挡住 client 内部写入。Codex 已有 `shouldCancel` 契约，但此前仅在轮询开头检查。

最小补修：Codex 在 poll HTTP 返回后、authorization-code exchange 返回后各检查已有 `shouldCancel`，复用原有“已取消”异常分支；Grok `exchangeGrokCode` 新增可选第 4 参数 `shouldCancel?: () => boolean`，HTTP 返回后持久化前检查。UI worker 已获准确接口，负责透传现有 login owner 判断。无 transport abort、无新重试、无新状态。

新增两种 HTTP pending 取消、Codex exchange pending 取消、两个正常 current login 以及 Grok 无 callback 兼容用例。扩展后 **20 项 RED 17 pass / 3 fail → GREEN 20/20**；连 Phase 2 auth/runtime 共 **55/55 pass**。日志 `/tmp/phase3-oauth-cancel-red.log` 和 `/tmp/phase3-oauth-cancel-green.log`。再次定向 lint **0 defect**，`git diff --check` 通过，已再次通知 `fix_backup` 独立复核。

UI worker 已将现有 `loginCurrent` owner 判断透传 Grok 第 4 参数，并通过实际 page 方法反例验证 current=false/cancel=true 语义；其 UI focused 27/27 通过。主 agent 已冻结生产源码并对最终 page+client 启动全量 CJS 和构建，此处不提前宣称集成结果。

最终独立 reviewer `fix_backup` 已通过复核：重跑 client 20/20、UI 27/27；原始 same-mode cancel probe 两 client 均保留 old-login 的 saved/synced 值，原跨模式 token-sync probe 均保留作者 API-key。结论见 `phase3-provider-lifecycle-review.md`，未发现待修问题。
