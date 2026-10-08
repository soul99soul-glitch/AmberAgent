# Phase 3 UI / lifecycle 修复

范围：只修改 ChatPage.ets、ChatProviderDetailPage.ets、NovelWorkspacePage.ets、DeepReadHistoryPage.ets，以及新增 ui_send_provider_lifecycle.test.cjs。Index.ets 未修改；保留所有已有 WIP，不提交、不安装、不签名。

## 完成项

- UI-01：Provider save 在写回时读取当前独立 OAuth token；新 provider 与已有 provider 都保留最新凭据。登录回调同步返回 access token，缺独立 token 时保存已有持久 key 或保存前 API key 快照。独立 review 找到 await 期间表单切 authMode 会混用 token 的竞态，已按保存载荷 updated.authMode 取凭据，保持整个 save 的输入快照。
- UI-02：普通与长按共享同步 sendPrechecking / sendPreflightSeq；模型与图像校验只接受当前页面、会话、序号，失败/离页释放。不设置 debounce，不加入 ChatInput.disabled，用户在预检/持久提交期间仍可编辑草稿和附件。
- UI-03：长按采用 accepted text/images/docs 快照，durable append/enqueue 成功才消费对应输入；失败保留。普通 send/edit 的真实 restore-version 拒绝也不再清草稿。独立交叉 review 找到编辑长按 cancelEdit 会清新 draft、同 node 切 variant 可应用旧内容到新 message 两项漏检；均先补实际方法红测，再仅清同 node+message 编辑身份，且预检后核对两字段。未更改显式“取消编辑”行为。
- UI-04：小说通知成功初定位后消费 branch 约束，保留初始 job 展示；用户主动 switch/create branch 清通知 branch/job 定位，reload 不再撤销用户选择。
- UI-05：抽屉标题/消息搜索统一请求序号，迟到成功和失败均不能覆盖最新查询。不增 cache/cancel/debounce。
- UI-07：深度阅读历史首次加载和回页刷新，序号隔离隐藏/销毁/旧请求；错误保留已有列表，成功清旧 loadError。
- UI-09：独立 review 追加确认取消/切模式/销毁后迟到 OAuth 登录覆盖当前配置。页面采用一个 loginSequence 配合 pageAlive/provider id/authMode 身份，复用 codexCancelled；设备码、poll、目录与交换令牌回调逐阶段检查，Grok 取消按钮真实失效会话。取消后重登旧 session 不能借新 cancelled=false 复活。正常打开授权浏览器的 onPageHide 不取消；页面销毁的 aboutToDisappear 取消。Grok exchange 透传同一身份的 shouldCancel，客户端 HTTP 后、持久化前检查及 sync 的 authMode 守卫由 authworker 独立负责。
- UI-08：新 provider 模型参数入口先确认已显式保存，未保存提示；不隐式持久化草稿。
- CHAT01 接线：ChatPage 手动 title 已 await prepareProviderApi；保留 title 的 reasoning off / custom headers / custom body 参数。
- SPEC01 接线：归档来源展示首尾按 draft.sourceMessageIds 实际预算批次筛选，避免 UI 仍展示全部消息的末条。

## UI-06 撤回证据

初审发现 Index 方法的 recent 状态缺少返回刷新，但该页不是当前产品入口。Index.ets 第 1-2 行标明 legacy，main_pages.json 无 Index，entry/src/main 全部源码没有 pages/Index 路由引用。真实首页 ChatListPage.ets 第 240-245 行在返回时 refresh/loadContinue/loadLastOpened。因此 UI-06 转入排除项，不计产品 bug，删除对应新增红测，不修改 Index。

## 验证

- 首轮 actual-method 红测 16 项，2 pass / 14 fail；剔除 UI-06 后对应 15 项。
- 回喂新增 OAuth auth-mode snapshot、真实 cancelEdit 新草稿、普通/长按同 node variant identity 行为回归，当时正式 actual-method suite 19/19 GREEN；UI-09 四项新增实际方法 4/4 RED→GREEN，再补当前两种 OAuth 正向成功与取消 poll 响应。独立真实客户端证明同模式取消仍可能持久化后，另补页面实际 exchange 方法的 shouldCancel 接线测试 RED→GREEN，最终 27/27 GREEN。
- focused suite：ui_send_provider_lifecycle + chat_reply_actions + chat_timeline_identity + deepread_history，44/44 GREEN；输出 /tmp/amber-phase3-ui-focused.log。
- 最终 4 页 scoped ArkTS lint：0 errors / 4 warnings，退出 0；输出 /tmp/amber-phase3-ui-lint.log；UI-09 更新后 ProviderDetail 单页再跑 0 errors / 0 warnings，输出 /tmp/amber-phase3-ui-login-lint.log。警告为既有 ChatPage deep clone、loop state 访问、transition 以及 History date 创建位置，本次未顺手整理。
- 所有新增行为测试从实际页面提取方法并 TypeScript transpile 执行，只 mock KV/repository/平台/模型域边界；不以源字符串匹配代替数据丢失和竞态验证。
- 独立审者：audit_chat（发送/search/title）与 fix_backup（Provider/Novel/History）最终均 PASS。audit_chat 独立 actual-method 25/25；fix_backup 正式 UI 27 + OAuth client 20 + 归档 5 共 52/52，并重跑五组真实源码探针（包括原同模式取消红反例）全部通过。集成交叉审者 review_phase2_tools 再跑 UI 27 + client 20 共 47/47，确认无源码阻塞。源码冻结，整体 build/sign/install/device 验收由 root 执行。

## UI09 设备回喂：初始账户查询与切模式错误状态

root 在 `device/final-provider-apikey-mode.jpeg` 确认已切 API key，旧 Codex 登录失效提示仍显示。此项归入 UI09 已确认的异步认证状态归属，不另计问题。

最终窄补 selectAuthMode 只在 mode 变化时清 unsupportedAuthMsg；applyProvider 发起 Codex/Grok 初始 token 查询时捕获现有 loginSequence，两家的成功回调先经 loginCurrent 检查 provider / mode / pageAlive / sequence，切模式、新登录、销毁后迟到结果不能写账户或错误提示。未更改凭据、HTTP、OAuth client 或认证能力。

新增两家共 6 项 actual-method 回归，正常查询原先 2 PASS，旧错误清理 / 迟到归属原先 4 RED，修后 6 GREEN。正常 missing token 继续提示失效；当前 email 继续显示并保持初始错误清理；同模式选择不额外清错误。迟到 null/token 响应覆盖 mode、新登录、leave 三种归属变化，均保持当前 account/error。

日志 `/tmp/amber-phase4-provider-initial-auth-red.log` / `/tmp/amber-phase4-provider-initial-auth-green.log`；当前 UI33 + entry7 + fieldcontract1 共 41/41。scoped Detail lint 0 defects / exit 0，`/tmp/amber-phase4-provider-initial-auth-lint.log`，owned diff check PASS。源码冻结，root 完整重建安装，fix_backup 追加独立复审。

追加独立复审最终 PASS：fix_backup 自行运行正式 41/41，并用真实 applyProvider / loginCurrent 做 8 项独立 deferred 反例，验证两家正常 current、providerId 替换及 null/email 迟到结果。其亲看最新最终 HAP 重录 API key 模式截图与 JSON，旧 OAuth 错误提示消失、名称草稿保留，见 `phase3-provider-lifecycle-review.md`。UI09 此追加小项闭环，源码保持冻结。
- 完整 ArkTS build、签名与设备截图由 root 统一执行。本阶段没有宣称云 OAuth、付费模型或设备 UI 已验收。
