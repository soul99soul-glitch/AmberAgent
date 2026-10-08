# Phase 3 独立复审：Provider / Novel / History

审查者未参与这些 UI 实现；只写本报告，不改源码。对照 `/tmp/amber-harmony-review-20261002-baseline` 和 `audit-ui.md`，读取稳定实现后执行实际 page 方法 probes。遵循 harmony-arkts 规则；不连接真实 OAuth/付费模型。

## 发现并已回喂的真实问题

Provider `save()` 在 `await loadProviders` 前已经生成保存载荷，却在 await 后依可变的 `this.authMode` 选择 Codex / Grok token。实际 selector 没有 ioBusy 禁用，保存期间切换认证可产生 `{authMode:'codex_oauth', apiKey:'grok-current'}`。

独立 probe `/tmp/amber-phase3-provider-review-probes.cjs` 调用真实 save：deferred loadProviders；开始保存 Codex；期间切到 Grok；释放 KV。补修前断言失败，补修后持久结果 `{authMode:'codex_oauth',apiKey:'codex-current'}`，独立复跑通过。

实现者已窄补：OAuth 条件及 token 读取依据保存载荷 `openAi.authMode`；缺独立 tokens 的回退依据 latest 持久配置 / 保存前 apiKeySnapshot，不再取在途表单。未增加全页 busy、retry 或 fallback 层。

## 独立覆盖与结论

| 范围 | 实际行为验证 | 结论 |
|---|---|---|
| UI-01 OAuth 保存 | Codex/Grok、新建/已有、独立最新 token、旧表单快照、保存期间切模式 | 通过；保存凭据符合捕获的认证模式 |
| 普通配置保存 | API key 编辑值保存、配置名编辑保留、缺独立 token 时已有 provider 保持最新持久 key | 通过；没有回退为旧页面 key |
| UI-08 新模型参数入口 | 新 provider 不导航且提示明确保存；持久 provider 正常传 providerId/modelId | 通过；未隐式保存整个表单 |
| UI-04 通知分支 | 从 B 进入 A 定位 old job；成功初载消费 branch；手动切 B 后再 reload 保持 B | 通过；初次通知定位仍有效 |
| UI-04 新建/阻塞 | 从通知 A 新建 C 不返回 A；阻塞运行时 select 不切分支、不丢通知约束 | 通过；branch/job 在显式成功操作后一起撤销 |
| 分批归档 sourceRange | 三条候选、domain draft 只采用前两条，page sourceCount=2，range 从 first 到 second | 通过；未把未处理尾部冒充归档来源 |
| UI-07 History | 首次 appear/show 仅一读；从 reader 回页更新完成状态；反向异步结果过滤 | 通过 |
| History 生命周期/失败 | 隐藏和销毁时迟到结果丢弃；读取失败保留列表并记录错误；成功恢复清 loadError | 通过 |

Novel / History probes：`node /tmp/amber-phase3-lifecycle-review-probes.cjs`，全部实际方法检查通过。边界依赖只 mock KV/repository/OAuth token loader/router/model-running ports，production 方法不重写。

UI-06 已撤回：`Index.ets` 为不可达 legacy，当前 `ChatListPage` 已有 onPageShow 刷新；本轮未将其算作修复项。

## 验证记录和边界

- 独立 Provider probes：通过。
- 独立 Novel / History probes：通过。
- `novel_discussion_archive.test.ts`：5/5 通过，确认最早完整来源、累计摘要、canonical 原文保留和单条过大不外发。
- 最终受影响 UI 聚焦测试独立实跑 **27/27 通过**，包含其他 reviewer 的 edit-mode 长按、variant 切换、auth-mode 保存 snapshot 与 UI09 登录 session 回喂。此前施工中的红测试已闭环。

## 追加复核：登录请求在途时取消并换认证模式

主 agent 提出更早的 login-await 边界后继续验证，确认另一项真实问题：Grok exchange 在途时“取消”按钮只关闭 overlay，用户随后切 API key 并输入 custom key / URL；迟到 `confirmGrokLogin` callback 仍写回 Grok token / 固定 URL 并自动 save，最终持久 `{authMode:'api_key',apiKey:'grok-result',baseUrl:'https://api.x.ai'}`。

`/tmp/amber-phase3-login-review-probe.cjs` 执行实际 confirmGrokLogin / selectAuthMode / save，deferred token exchange，在真实取消动作之后切模式。断言 author-entered-key 保留失败。源码同时证明 Codex requestDeviceCode 在途时 overlay 尚未显示、模式 selector 仍可用，后续 callback 没有模式隔离。

此外 `/tmp/amber-phase3-login-token-sync-probe.cjs` 提取 OAuth client 的真实 `syncAccessTokenToProvider`，对已经持久为 API key 的 provider 调用迟到 sync，key 从 author-entered-key 变为 late-oauth-token，动态复现成立。因此 page callback guard 单独修复不足以保护已经保存的新认证模式；需要同步 helper 限定对应 OAuth authMode。

UI09 page 已窄补 loginSequence / pageAlive / providerId / authMode owner guard，切模式、取消、销毁使旧 login session 失效；每个 callback 发布结果前核对；有意不在 onPageHide 取消，因为正常授权浏览器会隐藏 app。`/tmp/amber-phase3-login-review-probe.cjs` 已独立复跑通过，确认实际 exchange 先启动一次，取消并切模式后迟到结果不改 key / URL、不 save。

两 client sync helper 也已限定对应 OAuth 模式；正式 `oauth_provider_sync_entry.test.cjs` **20/20 独立复跑通过**，原 helper probe 同样 green。

### 已关闭：同 OAuth 模式取消时旧账号仍被底层替换

按主 agent 要求再次核查同模式取消。`/tmp/amber-phase3-oauth-cancel-review.cjs` 提取真实 Codex poll / Grok exchange，在 HTTP 已挂起后取消，再返回 200，两个函数都 `saveTokens` / `sync` 新账号覆盖 old-login。page owner guard 保护 UI，却不能取消函数内先发生的持久写入。

Codex 既有注释明确 shouldCancel 用于页面关闭 / 用户取消，但只在 loop 头检查，没有 HTTP/exchange await 后、persist 前检查；Grok exchange 无 shouldCancel。没有文档将“取消”定义为允许后台替换当前账号。此为真实取消语义遗漏，已动态红证明并反馈主 agent、UI 与 auth 实现者。

现已窄补并独立复跑 green：Codex 在 poll HTTP 与 authorization-code exchange 返回后各用既有 shouldCancel 检查，提交前取消则抛“已取消登录”；Grok exchange 新增可选 shouldCancel，在 HTTP 后、persist 前检查；page 传当前 login owner predicate。未增加 transport abort、登录重试、通用状态框架或回滚。

原 same-mode client probe 两个结果现在都为 `{cancelled:true,saved:'old-login',synced:'old-login'}`。跨模式 helper probe 也保持 author-entered-key。正式 tests 包含当前登录、无 callback Grok 兼容、Codex exchange 中取消，**20/20 通过**；真实 UI tests 包括 predicate 从当前 false 变为 cancel 后 true，**27/27 通过**。全部独立 probes 再跑通过，另归档领域 **5/5**，本范围正式测试共 **52/52**。

阶段复审结论：**通过；无待修真实问题。** `git diff --check` 通过。真实 OAuth 云端、模型服务以及 ArkUI 设备截图不由实际方法 mock 证明；统一 lint/build/设备验收由主 agent 负责。

## 设备发现二次裁定：新增 Codex OAuth 入口的统一“不支持”gate

只读结论：**P2 陈旧入口缺陷，不是当前明确 by design。**

- 实际 `ProviderTemplatePickerSheet.ets:17-19` 同列 Codex / Gemini OAuth，`:118` 两行都调用无参 `onOauthPick()`；`ChatProviderSettingsPage.ets:451-453` 无法区分行，统一 toast“鸿蒙版暂不支持 OAuth 登录，请使用 API Key 预置”，不建立 draft、不导航。
- `docs/harmony-parity-gap-plan.md:3,81` 的 2026-09-27 初始盘点确实将这一 toast 记录为“OAuth 登录全缺”的缺口，不是永久保留规格。
- 随后 `docs/plans/2026-09-27-harmony-parity-execution.md:56` 明确 C3-2 Codex 设备码→轮询→token→模型目录的端到端目标。
- `docs/plans/2026-09-28-harmony-full-parity-plan.md:8` 明确 C0–C6 已闭环；`:43` 明示延期的是 Antigravity OAuth，不是 Codex。`2026-09-28-harmony-remaining-execution.md:14-16` 明确真实入口闭环和占位入口不算实现。
- Git `db9baeead`（2026-09-27，C3 Codex/Grok 完整化）在这些 UI 文件中只改详情页 +557/-17，没有同步模板 picker / Settings callback；旧“全 OAuth 不支持”注释/处理保留，符合陈旧接线遗漏。

建议：给 OAuthRow / callback 增加最小行标识，只有 Codex 建立新的独立 Codex OpenAI draft（现有 authMode / factory / fixed-base / Responses）并进入已实现详情登录。Gemini 行保留原 gate、提示文案仅指 Gemini；不统一开放、不新增 Google OAuth 能力、不自动发登录请求。

此遗漏仅是专门新增按钮的入口问题；已存在 OpenAI 配置可在详情选择 Codex，导入配置同样可达，所以 UI-01 / UI-09 的实际调用链结论仍成立。设备直接发现由 root 留证，我没有修改源码或启动授权网络。

## Phase 4 设备回喂 UI09 同根因补审：初始账户查询与旧提示

结论：**通过，没有真实 blocker。** 此项归原 UI09 异步登录 UI 状态归属，不另增问题组；仅复审 Detail，不修改产品源码、不扩大领域。

此前 UI12 验证中独立目看旧 API key 模式截图，发现模式已切 API key 但仍显示 `Codex 登录已失效,请重新登录。`。这是真实旧认证状态残留，不能因字段刷新已通过而忽略；applyProvider 初始 loadCodex/GrokTokens 也缺少既有登录 ownership 判断，迟到查询可以重新写回旧账户/错误提示。

最终窄修：applyProvider 捕获当前 loginSequence，两家 loadTokens 的 then 回调均先用既有 loginCurrent(providerId, authMode, sequence) 验证；selectAuthMode 仅在模式改变时清 unsupportedAuthMsg。复用现有 pageAlive/provider id/mode/sequence 语义，没有新状态机、fallback 或 authclient/http 改动。

独立复跑最终正式 `ui_send_provider_lifecycle.test.cjs` + `provider_template_entry.test.cjs` + `provider_field_binding.test.cjs`，**41/41 通过**。其中新六项 actual-method tests 验证：两家当前正常账户/缺凭据提示保持；同模式不清错误、切模式清旧提示；查询在途 mode change/new login/page leave 后，null 或旧 email 均不覆盖当前 account/error。另自行写 `/tmp/amber-phase4-ui09-initial-query-review.mts`，提取真实 applyProvider/loginCurrent，以 deferred token query 验证两家 current 与 provider id 更换、null/email 共八种结果，全部通过；身份变更不会写入旧查询。

最新 HAP SHA256 `13ee6572a8eb04975a197f8f04b3ff8d37e2aeb0af604f82f4683a31748da270` 经 root 构建、签名验证和同签名覆盖安装。独立亲看重录 `device/final-provider-apikey-mode.jpeg` 并读取对应 JSON：API Key 选中、名称草稿仍为 `OpenAI Codex OAuthReviewDraft-1002`、Base URL 与 Path 正确、没有 `登录已失效` 旧提示。root 设备脚本另证初始 Codex 缺凭据提示仍存在、typing/model/config 草稿保留、返回首页丢弃未保存 draft；本审查直接目看的 runtime GREEN 是最后 API key 模式无旧提示。

旧图里的 UI09 残留现已闭环；最新总计 **4001/4001**（CJS515、chat2486、deepread1000）与 build/lint/sign/install/返回首页回执由 root 统一汇总，未宣称云端 OAuth 账户登录已实测。
