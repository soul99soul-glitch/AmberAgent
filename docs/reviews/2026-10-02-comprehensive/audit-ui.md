# Harmony UI / state / lifecycle 二次复核

审查基线：HEAD `5ea51f7a` + 当前全部 WIP/untracked，2026-10-02。仅写本报告，不编辑源码，不操作设备。读取了 harmony-arkts skill，并逐项检查调用链。初审列出 8 项；Phase3 入口复核撤回不可达 legacy Index 的 UI-06，其余 7 项确认为当前产品问题。前 5 项由原始方法提取后 TypeScript transpile 执行复现，后 3 项为明确 UI 生命周期/路由证据，设备验收仍由主 agent 统一执行。

## 已确认问题

### UI-01 [P1] OAuth 登录成功后被详情页 save 写回旧 token，新增 OAuth provider 变成不可选

- 位置：`harmony/entry/src/main/ets/pages/ChatProviderDetailPage.ets:439-440,449-457,545-563,617-624`。
- 触发：新增 Codex 或 Grok OAuth provider 完成登录，或在已保存但 `apiKey` 为空的 provider 上登录。
- 调用链：CodexOAuthClient:249-250 / GrokOAuthClient:211-212 已将 tokens.accessToken 写入独立 token KV，并尝试同步持久 provider；页面仍持有登录前 `this.provider`，成功回调调用 `this.save()`。save 的 OAuth 分支使用旧 `o.apiKey`，随后替换刚更新过的列表条目。新增 provider 尚未存在，客户端 sync 根本无目标，页面首次 save 也会保存空 apiKey。
- 实际影响：`hasUsableAuth` (`harmony/chat/src/main/ets/chat/provider_settings.ts:214-220`) 必须 apiKey 非空；模型菜单与 `resolveChatProviderChoice` (`AppContainer.ets:1359-1384`) 在 runtime 取独立 token 之前就过滤该 provider，因此“稍后刷新 token”无法救回空 key provider。
- 二次复核：从当前源文件抽取完整 save 方法，用 TypeScript transpile + mock KV 执行：存储初值 new-token，页面旧值空，save 完成结果 `{apiKey:"", savedMsg:"已保存"}`。不是凭 UI 装饰器推断。
- 窄修建议：明确保存登录返回 token 到页面/保存载荷；保存已存在 OAuth 配置时采用最新 token 凭据，避免把表单旧快照覆盖登录/刷新结果。兼顾新增流，不强制额外 provider 接口层。
- 回归：分别新增 Codex/Grok、已有空 key 登录、已有 key 重新登录，然后断言持久 apiKey 与返回 token 一致且出现在 picker；配置保存不回退 token。

### UI-02 [P2] 两次发送在异步校验完成前接受同一草稿，产生重复发送和排队

- 位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:3709-3750`，输入实际入口 `ChatInput.ets:312,381`；父组件 `ChatPage.ets:4767` disabled 没有发送预检状态。
- 触发：点击发送/回车两次，第一次的 `resolveEffectiveChatChoice` 或图片 OCR 健康检查尚未完成。
- 原因：两次调用都快照同一 text/images/docs；没有同步设置的预检占用门。第一回调 clearAcceptedComposer 并 start，第二回调只检查 pageAlive/pageVisible/conversation id，发现 sending=true，将同一旧 inputParts 入队。
- 二次复核：直接提取当前 send/clearAcceptedComposer 原始方法，以 deferred gate 驱动：输出 `[{kind:"send", text:"accepted draft"},{kind:"queue", text:"accepted draft"}]`。service start 互斥不能阻止第二次，因为第二次走 enqueue。不是“假设 ArkUI 双击事件会触发”：回车与按钮也是两个可达入口。
- 窄修建议：发送预检阶段单一占用状态，普通/长按路径共用；完成/失败时释放，保留生成中的合法新草稿排队，不加时间 debounce 或 token 重试。
- 回归：deferred model gate 双入口，只派发一次；校验失败恢复可发送；第一次已受理后新草稿仍可排队。

### UI-03 [P1] 长按发送校验回调无快照保护，删除校验期间新增的草稿和附件

- 位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:3618-3636`。
- 触发：长按发送一个旧草稿；图片 OCR/模型预检在途时继续输入新文本或添加附件。
- 原因：sendWithoutAnswer 只快照 inputParts，却在成功回调无条件 `inputText=''` + `invalidatePicker(true)`；后者清空所有当前附件。普通 send 的 acceptedText/images/docs + clearAcceptedComposer 已处理同场景，长按遗漏。且长按成功回调没有 pageAlive/pageVisible/owner 守卫。
- 二次复核：提取当前 sendWithoutAnswer 原始方法执行：长按捕获 accepted draft；未完成 gate 时写入新 draft 与 new.pdf；gate 完成后只 append 旧 draft，当前新 draft 及 new.pdf 同时被清空。输出 `{inputText:"",pendingDocs:[]}`。
- 窄修建议：沿用普通发送既有 accepted snapshot 与 owner 检查；清除被受理的那部分输入，勿取消用户后来添加的附件。append/enqueue 成功后再消费草稿，保留失败输入。
- 回归：deferred 校验时新文本/附件保留；离页不 append；enqueue/append 失败不损失已接受输入。

### UI-04 [P2] 通知携带的 branchId 永久生效，用户手动切分支马上被切回

- 位置：`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets:323-324,518-524,527-540,955-962`。
- 可达入口：`EntryAbility.ets:98-104` 将小说任务通知的 projectId/branchId/jobId 传进工作区。
- 触发：点润色任务通知进 A 分支工作区；任务已结束、允许切换时，打开分支面板选择 B。
- 原因：requestedBranchId 从路由赋值后从未消费/清空。selectWorkspaceBranch 先 switchBranch(B)，后 reload 发现当前不是 requested A，再 switchBranch(A)。新建分支也在 reload 中受相同约束。requestedPolishJobId 若仍定位旧任务，也不能无条件留到用户其他分支，否则 :539 报任务非当前分支。
- 二次复核：直接提取 reload/selectWorkspaceBranch 原始方法执行：`{manualTarget:"B",actualBranch:"A",switches:["B","A"]}`。不是 by design：显式选择分支 UI 已允许操作，通知定位应解释初始入口，不能无提示撤销用户切换。
- 窄修建议：显式用户切换/创建分支时撤销通知 branch/job 定位约束；或成功首次定位后消费 branchId。保留通知初始任务展示，别只修 branchId 却留下旧 jobId。
- 回归：通知进入 A 定位任务，切 B 留在 B，新建 C 留在 C；返回阅读器后保持用户选择；历史通知在活动任务冲突时仍按既有规则拒绝切换。

### UI-05 [P2] 抽屉标题搜索迟到结果覆盖当前查询

- 位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:3080-3102`。
- 触发：快速输入 old 再 new；search(new) 先完成，search(old) 后完成。或输入后迅速清空。
- 原因：标题 request.then 仅检查 pageAlive，未检查 query/请求序号；并列消息全文搜索已检查 drawerSearchText，但 catch 也可能清新结果。
- 二次复核：提取原始 refreshDrawerConversations 方法，deferred repo reverse resolve：`{visibleQuery:"new",displayed:[{id:"old-hit"}]}`。
- 窄修建议：标题/消息成功及失败都复用同一查询或请求序号守卫；无需 debounce/搜索缓存/取消框架。
- 回归：反向完成与反向失败都不能覆盖新结果，清空查询不复活旧 hits。

### UI-06 撤回：不可达 legacy Index 的最近会话刷新缺失（不计产品问题）

- 位置：`harmony/entry/src/main/ets/pages/Index.ets:158-172,197-204`。
- Phase3 复核：Index.ets:1-2 明确 legacy 首页已从 main_pages 移除；当前 main_pages.json 无 Index，entry/src/main 全部源码不存在 pages/Index 路由引用。真实首页 ChatListPage.ets:240-245 已在返回时 refresh。该候选虽然方法级 stale 可复现，但用户不可到达，撤回、不改源码，下面保留初审证据供审计。
- 触发：首页 push 聊天页，发新消息生成标题/创建新会话，或在聊天抽屉删除会话，然后返回首页。
- 证据：loadRecent 只有 aboutToAppear 调用；onPageShow 刷新 modelLabel/continue，却完全没有刷新 recent。首页 ForEach:476 使用 recentItems 快照，不订阅 repository。router push 返回保留首页实例，项目 NovelProjectsPage 的 onPageShow 首次/返回区分机制佐证该生命周期模式。
- 影响：新会话不出现，已删除会话仍可点进“会话不存在”，标题日期排序不更新。
- 二次复核：全文件检索 loadRecent 仅定义 + 首载调用，无刷新/observer 补偿；不是有意的冻结列表，因为该 UI 文案为最近会话且相邻继续列表主动刷新。
- 窄修建议：pageShow 回页时加载 recent，轻量序号防在途旧请求；避免首次重复读取即可，不增 repository 观察抽象。
- 回归：首页创建/删/改会话后回退最近列表反映最新状态。

### UI-07 [P2] 深度阅读历史从文章返回后仍显示旧“失败/未完成”状态

- 位置：`harmony/entry/src/main/ets/pages/DeepReadHistoryPage.ets:57-73`。
- 触发：历史中打开失败/部分文章，显式继续生成直至完成，回到历史页。
- 证据：only aboutToAppear loadHistory；reopen pushUrl 保留历史页；没有 onPageShow 或 observe 补偿。行 status/date/expired 都来自一次性 HistoryEntry 快照。
- 二次复核：全文件无其他 items 写入刷新路径；DeepReadArticlePage 执行后仓库确实会更新，历史 UI 没有订阅。
- 窄修建议：返回时重读历史并清 loadError，配 pageAlive/请求序号，保留列表不闪空。
- 回归：部分→完成、失败→成功、重新生成更新日期/过期状态，返回即时正确。

### UI-08 [P2] 新增 provider 模板的模型“参数”入口指向未保存 provider，直接显示“模型不存在”

- 位置：`harmony/entry/src/main/ets/pages/ChatProviderDetailPage.ets:1188-1193`；`ChatModelParamsPage.ets:107-142,411-412`。
- 触发：Provider 设置添加带默认模型的模板（ProviderDefaults.ets:47,54,116），尚未保存，切“模型”tab 点击任一参数行。
- 证据：新增流仅 setPendingProvider + isNew route (`ChatProviderSettingsPage.ets:446-449`)；详情 applyProvider 显示暂存模型，但点击参数页只传 providerId/modelId，参数页仅 loadProviders 查持久数据，因此 provider undefined→loaded=true→“模型不存在”。
- 二次复核：不是 detached 草稿编辑模式：参数页没有 PENDING_PROVIDER 读写或父页 draft 传递；相比 openGoogleAuth:190-201 明确检查先保存，这里遗漏。
- 窄修建议：先检查 provider 已保存，未保存明确提示保存后编辑；不要隐式持久化整个未提交配置，也不为此引入 detached 编辑框架。
- 回归：新增模板未保存点击参数有明确门；保存后参数编辑正常且保留父页未保存草稿。

### UI-09 [P1] OAuth 取消、切认证模式或离页后，迟到登录回调覆盖当前配置

- 独立 Phase3 审者追加确认；实现前原 confirmGrokLogin 的 deferred exchange→真实 overlay cancel→selectAuthMode(api_key)→输入新 key/URL→旧 exchange 结果，最终错误自动 save API-key 配置为 Grok access token + xAI endpoint。
- Codex 获取设备码在途时切模式可复活旧 modal；取消后重登共享 codexCancelled=false 能让旧 poll 继续，目录回调也可晚到保存旧模式。
- 正式页面实际方法 4 项 RED→GREEN：Grok cancel/mode form 污染、Codex设备码切模式、Codex取消后重登旧会话复活、页面销毁迟到结果；追加两种正常登录与取消poll响应回归通过。
- 页面窄修：单 loginSequence，当前 provider/authMode + pageAlive 守卫，取消/切模式/销毁失效。Grok cancel 按钮调用真正 cancel；Codex shouldCancel 捕获 session 身份，Grok exchange 透传同一身份的 shouldCancel，接线另有 actual-method RED→GREEN。没有 retry/cache/全页禁用。
- 独立真实客户端复现同模式取消在 HTTP 返回后仍持久化 tokens；客户端 HTTP 后、持久化前的取消检查及 authMode 变更后仍 sync token 的持久边界由 authworker 修复。只有页面守卫不足以保护持久配置。

## 已排除 / by design

- `NativeMarkdownText.inlineRuns()` 排除 image/math 不代表丢图片/公式：MarkdownBlockView.supportsNativeInlines :295 明确将这些 token 转旧富文本/MathRichBlock 路径。
- 页面主题的 export let + themeEpoch / ForEach 纪元重挂载是本项目明确现行机制，不按“export let 一概错误”报告。可见无意义 opacity 不单独视为数据刷新缺陷。
- ChatPage sheet 逐个稳定 host :4228+ 已避免同宿主 bindSheet 覆盖，未发现仅因非 $$ 写法导致同步失败（各 sheet 的 onWillDismiss 会清状态）。
- NovelPolishCreateSheet aboutToAppear resetRange 的初始化时机没有真实问题：父工作区与 reader 都通过 if(open) 条件创建，chapters 同步传入，不是常驻空列表后打开。
- 自定义模板 WebView/图解文字化、Workspace PDF 不支持预览、模型覆写只支持移除：当前代码和说明明确为产品边界，本轮不擅自扩展。
- Chat actions 先 onDismiss 再操作没有“row 被同步清空”问题：closeActionsSheet 只清 open；clearActionsSheet 另行处理，动作仍持有 row。
- API 高版本能力仅因 SDK 26 编译并不能证明 API12 可用，但 UI 审查未凭名称记忆报版本 bug；兼容性由主 agent 原生/API 路线统一查文档复核。

## 本轮证据边界

直接执行的是从 `.ets` 原始方法提取并用本仓库 TypeScript transpile 后的控制流，mock 的只是不属于 UI 子任务的 KV/repository/模型检查/service 依赖。已证明状态/派发顺序与保存结果，未声称真实 OAuth 云端完成或设备图像/UI 验收。本文没有把 regex-only 断言当行为证明，也未新增源码测试或操作设备。

## Phase 3 入口复核修正

UI-06 撤回，不再计入真实产品问题。`pages/Index.ets` 为明确标注的 legacy 页，main_pages 中无该页且无有效路由入口；当前冷启动首页为 ChatListPage，其 onPageShow 已执行 refresh。以最终 findings.md 的二次核实清单为准。本轮未修改或删除 Index 源码。
