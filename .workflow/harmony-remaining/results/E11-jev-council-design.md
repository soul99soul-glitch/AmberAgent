# E11 Jev 分诊 / Web 自动化与议会搜索 / FULL 只读设计

2026-09-28。仅准备设计；E11 尚未获主 agent 的阶段实施授权。本轮只新增此报告，没有修改产品、state/index，未运行测试、构建或设备。已读 `harmony-arkts/SKILL.md`。以下区分当前源码事实与建议合同，实施前应按届时 E8–E10 的 provider 改动复核接缝。

## 1. 当前链路与跨平台参照

| 范围 | 当前鸿蒙事实 / 可复用边界 |
|---|---|
| Jev 协议 | `chat/jev_models.ts:8–20` 只有全局 OFF/SHADOW/ACTIVE、TYPESAFE/VERCEL 与独立 evaluation model；不是聊天 provider。`jev_client.ts:137–197` 严查缺题、有限 `[0,1]` Noul 和已知 Choice。不要把普通 Chat completion 接口用作 Jev。 |
| Jev 宿主 | `JevSupport.ets:75–123` 复用真实 HTTP、每日 200 计数、5 分钟错误冷却、4000 字 state 上限。HTTP 当前无 signal。`:224–226` 真实 wired 用途仅 discovery/recall/context/routing；SHADOW 调用返回标记，但尚无审批分诊观察记录。 |
| 审批定位 | 共享 `deepread/agent/message.ts:15–20,81–88` 的五种 `approvalState` 与 metadata；`tool_approval.ts:74–93,119–145` 已有批准/拒绝/回答及 messageId+partIndex 精确定位。分诊无须新增批准状态。 |
| pending 耐久边界 | `tool_loop.ts:447–480` 先固定 invocation metadata，再生成 pending+permission_trace、保存和展示。continuation 读取旧 part；不得重新固定 SSH/Mosh 目标。`:515–518` 同一 capture 也用于 package child。 |
| 包装工具真实动作 | `RecipeApprovalSummary.ets:16–34` / `PluginApprovalSummary.ets:25–80` 投影 checkpoint 中的 `pendingStep`。父 `recipe__*` / `plugin__*` 输入不能替代真正原始工具参数；import 则评估固定 candidate 与 baseline。ask_user 显示投影保留父 locator。 |
| 审批消费者 | `ChatMessageTools.ets:107–160` 已显示 package/SSH 摘要和原批准按钮；`ChatMessageToolPreviewSheet.ets:147–198` 已有详细参数/固定目标/搜索预览，可直接加三条中性事实。 |
| 议会模型 | `deepread/domain/model_config.ts:9–15` 是独立 `{id,label,baseUrl,apiKey,model}` 库，无 Chat provider 类型。`AppContainer.ets:915–933` 固定创建文字 runner。不能把当前选中的 Chat provider 偷换到所有席位。 |
| 议会执行 | `ModelCouncilRunner.ets:37–105` 手拼 `/v1/chat/completions` 文本 SSE，未传 tools，未消费 tool call，当前也未把 `reasoningLevel` 编入 body。`council/runner.ts:9–27` 只收发 text/warnings。 |
| 议会编排 | `council/manager.ts:320–406` 并发席位、按 modelId semaphore、席位子 signal/超时；`:250–312` 总 deadline/取消/失败聚合；裁判另有子 signal。`:493–538` transcript 为 JSONL，append 错误目前忽略，不能直接作为审批的保存确认。 |
| 议会显示 | `CouncilRunPage.ets:53–107,143–160,215–226` 是席位累计文本和轮次文本，没有工具卡、来源列表、pending 定位或回答入口。仅在文案中声称 FULL/联网不构成闭环。 |
| 搜索 primitive | `search/search_tools.ts:146–216` 的 `search_web` 调用真正 orchestrator，输出 items/title/url/text/source_service 等；另有 `scrape_web`、sources_status/strategy_explain。搜索顶层 signal 能及时结算 AbortError，但 `:209–215` 未传底层请求取消，不能宣称 HTTP 已停止。 |
| 浏览器真实宿主 | `WebMountSession.ets:1–16,58–78` 为 ChatPage 的单 WebView，含 mount/pageReady/site gate/bot-hand lease。`ConversationBrowserGuard.ets:12–49,61–82` 精确约束可见会话归属。未发现可调用的远程浏览器 service/endpoint；`WebMountTools.ets:9–12` 仍登记 run_goal 等未移植。 |

实际参考的是独立 `/Users/arquiel/Downloads/AI/amberagent-ios` 与 `/Users/arquiel/Downloads/AI/amberagent-Android`，不是本仓库 Android 旧副本：

- iOS `IOSJevApprovalTriage.swift:6–13,77–83,139–154,191–208,241–253`：仅中性分诊，只读/可逆/与任务相关，`≥0.65 是 / ≤0.35 否 / 中间未知`；不改变批准状态、按钮或顺序，先显示卡片再异步补标签；明确外发 scope 和凭据排除。`JevApprovalTriageChips.swift:3–35` 三态同色，大字号能换行。
- Android `core/jev/JevModels.kt:8–9,24–42,59–70`：判断仅选择有限候选，预算/执行/权限属于本地调用方，按用途模式与外发 scope；当前列举用途没有 APPROVAL_TRIAGE，不能说 Android 已有相同三事实卡。
- iOS `CouncilRunner.swift:458–465,1618–1655` 的席位联网实际是发言前 `maxSearches=2,maxScrapes=2` 调研、注入该席材料，且受本轮 consent 控制；这不是席位工具循环。
- Android `AppCouncilHostToolProvider.kt:38–62,121–133,195–218,258` 仅主持人 allowlist `search_web/scrape_web/get_time_info`，ask_user 向房间交回；注释明确没有完整 kernel/耐久审批。`ProviderModelCouncilTextRunner.kt:44` 席位仍 `tools=emptyList()`。不可把这条只读主持人链路当作 FULL 的实现证据。

## 2. 最小 Jev 分诊合同

建议在 `chat/jev_approval.ts` 放纯函数与类型，在 Entry 一个小 helper 做既有 `jevEvaluate()` 调用与异步回填；不做新审批系统或通用决策框架。

```ts
type JevFact = 'yes' | 'no' | 'unknown';
// metadata.jev_approval_v1；只保存判定及其绑定，不保存原始参数/任务文本。
interface JevApprovalTriage {
  subjectHash: string;
  readonly: JevFact;
  reversible: JevFact;
  goalAligned: JevFact;
}
```

`subjectHash` 用现有 Entry SHA-256 helper 对“父 locator + 实际动作 toolName/input + 固定 SSH/Mosh target/protocol + package hash/当前 step（import 含 baseHash）”求摘要。hash 在本地；不是把密钥、完整 command 或 checkpoint 外发。它解决真实的 package 共用父 toolCallId、异步结果回来时已换步骤的问题。单凭父 toolCallId 不够。

实际 subject 来自已持久 pending 的 part：普通工具本身；Recipe/Plugin 为已保存 pendingStep；import 为固定 candidate/基线。父 locator 始终不变；ask_user 仍只能回答，不让分诊服务把它当普通可批准动作。未知原始参数、无固定目标、不可解析 checkpoint 时保留未知，不猜测。

三道 Noul 与 iOS 保持一致的中性定义：

1. `readonly`：仅读取/观察，不修改状态、不向外发送内容；信息不足给中间概率。`policy.mutates=false` 只说明本地未变更，不能证明搜索/HTTP 外发是只读。
2. `reversible`：效果能轻易撤销/恢复；无法确认给中间概率。
3. `goal_aligned`：直接服务该次工具提出时的最新用户请求；扩大范围/偏离/缺信息给否或中间概率。

只使用现有 policy 的确定事实；未知项才请求模型。参数外发只用明确允许字段的有限标量摘要；不能仅凭字段名过滤，就把包含 token 的 SSH command/脚本/URL 查询串外发。若某动作需参数才能判断但没有可发送摘要，相关事实为未知。候选与摘要作为待评估数据，prompt 不把工具输入或网页文字当指令。

结果必须经过现有 `decodeJevResponse()`；不用模型生成 yes/no 文案，不虚构 confidence。有限概率过 `0.65/0.35` 三态带；本地已确定事实不被模型覆盖。缺题/非法概率/HTTP/预算/冷却失败保留原审批流程。

建议只给两个新增用途增加默认 OFF 的可选配置项与所需外发选择，保留四个已接用途的现有全局模式语义，不在本批复制整个 Android coordinator。全局 OFF 总关；全局 SHADOW 不允许新增用途执行 ACTIVE 行为。`approval_triage` 需要工具元数据/选定任务文本允许；`web_automation` 另需页面内容允许。已有 Jev 独立 model/apiMode/endpoint 继续生效；不新增第二套 Chat provider 选择。

OFF 不调用；SHADOW 调用和保存窄观察记录（用途/subjectHash/模型/三态/结果状态，无原始资料），但不向卡片应用模型标签；ACTIVE 才异步补充卡片。`jev_status`/设置页只在真实消费者接通后更新 wired 用途。

### 不阻塞的回填位置

主 Chat 优先在 `ConversationRunService.perform()` 已完成并确定 `awaitingTools` 后启动分诊（`:625–644`），不把网络 await 塞进同步 `captureInvocationMetadata` 或执行 dispatcher.before，也不延迟 pending 保存和展示。

结果经现有 `mutateStoredConversation()`（`:429–442`）串行加载最新 canonical 后局部合并；同一 locator 仍是 pending、subjectHash 仍相同、用途仍允许 ACTIVE 才应用。已批准/拒绝/回答、已删除或 package 换步就丢弃结果。只更新 metadata，保留 permission_trace、terminal_target/protocol/label、recipe_v1/plugin_v1 和现有 output；不得重写整个旧 conversation。分诊回填失败不导致 pending 卡片消失，也不能执行工具。

UI 在原卡/预览内添加“只读：是/否/未知 · 可逆：… · 与任务相关：…”的同色标签；复用当前字号/间距，小屏或大字号换行，不能加推荐批准、绿色安全、风险评分等新决策控件。resume 显示保存的同一 subject 标签，不能拿新 default 或当前步骤标签覆盖。

## 3. Jev Web 自动化：先暴露计划依赖

`docs/plans/2026-09-28-harmony-full-parity-plan.md:45` 明确这项依赖 WebMount 远程能力；当前顺序 E11 在 E12 run_goal 之前（remaining-execution `:44–45`），而代码只有本地 WebView。这是实施依赖，不是创建一个不存在的 service 就能解除的边界。

建议 E11 可以完成纯决策与真实本地 primitive 的小接缝，但远程用途必须等 E12 提供真实 transport/capability 后验收；在那之前保持未 wired，不能靠 dry-run、throw 或 README 关闭该项。若主 agent 把本地 run_goal 作为实际产品范围，应明确它只在原页面宿主可见且租约为 bot 时有效，不能标称远程或跨页持续运行。

最小循环沿 Android/iOS 已验证合同：真实 observe → 本地构造允许动作及可见元素/文本候选 → Jev Choice 选下一动作或 handback → 复查同 session/页面 revision/授权/lease → 经原 primitive 和原审批执行 → 新 observe 验证进展/完成。Jev 不能生成任意 JS、URL、文本或工具名，不能直接执行；文本来自主模型已提供的有限候选。DONE 由真实页面完成条件验证，不能由模型自行宣布完成。

OFF 不循环；SHADOW/dry-run 只记录决策轨迹、零点击/输入；ACTIVE 也保留 site gate、原审批、bot-hand、revision 和取消。预算只用必要的 step/deadline/no-progress 上限；stale revision 重新观察而不执行旧元素，unknown Choice/缺题/取消/缺 capability 明确 handback。不要在 E11 扩张 replay cache、探测优化、第三方远程桌面或任意自动重试。

## 4. 议会席位搜索与 FULL 的最小真实闭环

### 功率与模型范围

当前鸿蒙没有 `FULL` 或 tool power 字段，跨平台上述链路也不足以定义“全部自动批准”。建议先冻结 `CouncilToolMode = 'off' | 'search' | 'full'`，在 task/run 中固定实际值，旧配置默认 off。

- off 沿当前纯文本。
- search 暴露现有 `search_web/scrape_web/search_sources_status/search_strategy_explain`（需要时再加入实际存在的时间 primitive），保留本轮联网允许状态；工具结果回流给该席模型再发言。不能只在 prompt 写“允许搜索”。
- full 使用该次运行的 Assistant 已启用、平台实际可用的工具 scope 和现有 FULL profile。工具发现、Recipe/Plugin 的 primitive resolver 必须使用同一过滤后的 registry；FULL 不等于设 `autoApproveTools/autoApproveHighRiskTools=true`。沿用运行时已有批准设置与 mandatory/ask_user/SSH/Mosh 固定目标门。

不要把未准备好的 WebView/远程服务工具加入 council scope；有页面能力才保留其既有 owner gate。FULL 能执行实际 scope 中的写工具才可称 FULL，只有 search allowlist 不能冒充。是否允许席位再创建 subagent/council 由同一现有 child scope 决定，不能通过 tools_list/tool_search 或动态包重新暴露父 scope 外的工具。

模型方面，保留旧 `ModelConfig` 的 OpenAI 兼容席位；需要 tool calls 时使用现有 Chat provider API/`asChatStreamProvider`（而非 `createOpenAiTextStream` 文本 sink），每步 `makeProviderForStep()` 传真实工具 definitions、该席 model/reasoning/temperature。若 E8–E10 已把席位绑定迁至 `(providerId,modelId)`，用该冻结 pair 和 `buildProviderApi`，尊重 providerOverwrite；不得失败后悄悄切当前 Chat 默认模型。模型不支持 tools 要明确失败或要求 off，不能丢失 tools 后宣称联网成功。

### 复用环路、审批与来源

在 Entry 抽一个小的 `CouncilToolRunner.ets`，调用共享 `runAgenticToolLoop()` / `runToolLoopContinuation()`，复用 `AgentToolDispatcher`、权限 trace、budget/duplicate guard、package adapters。参考现有 `subagent_runner.ts:88–135` 的 provider/deps 构造，但不能直接采用其 `approval_required` 摘要终止（`:379–388`），那会丢失 FULL 待审批的真实 part/continuation。不要再写一个只读 inline 执行器。

DeepRead → Chat 的反向模块依赖不能新增；窄 callback 类型使用已在 DeepRead 定义的 `UIMessage/UIMessagePartTool`，必要结构在既有 `council/runner.ts` 中加。建议 generate request 增加 runId/round/seatId、固定 toolMode、工具快照回调与一个返回批准/拒绝/回答 Promise 的 `requestApproval(part, locator)`；无这些可选参数的动态席位规划调用仍走 off。manager 只增加当前席位 pending 定位/批准回调，不做第二个调度器。

真实过程：

1. 每个 `(runId,round,seatId)` 持有独立 Conversation/工具 transcript/store、registry、factory 固定目标和 signal；不可复用一个 `id='subagent'` store 或全局当前页内容。保存 barrier 成功后，给 manager/UI 发布同一 snapshot。
2. tool_loop 返回 pending 时保留完整 part 和 locator。manager 给对应席位登记待批准项，generate 暂停于人类 verdict Promise；显示原 `ChatMessageTools`/AskUser/预览（必要时小组件组合），批准按钮携带 run+seat+round+父 toolCallId+locator，不能把所有席位批量批准。
3. 用户批准/拒绝/回答只对当前 pending 精确应用并保存，然后调用 continuation；Recipe/Plugin child 的批准仍交给父 runner，ask_user answered 与 answer 原样传回。terminal_target/protocol 等沿用最初保存 metadata；无持久批准上下文不执行旧调用。
4. 工具输出由 dispatcher 写回同一席位 transcript，再作为 provider 上下文；最终 text/warnings/sources 进入 `ModelCouncilTurn` 和裁判 prompt。工具失败的 structured result 不能显示为成功搜索；有未决工具的席位不能记 completed 或进入成功裁决。
5. 将实际搜索 items 与 scrape URL 汇总为少量 `CouncilSource {title,url,service}`，来源只能从真实结果获得；turn 的工具 parts/来源和最终文本保存在 transcript。`CouncilRunPage` 加该席来源链接与工具状态/审批卡，裁判也保留来源而不是凭文字伪造。原轮次排布与 32ms 文本合批保持。

审批保存不得沿用现有忽略 append 错误的 transcript 方法作确认；可以沿用现有 ConversationRepository/序列化保存口保存席位工具上下文，transcript 作为展示归档。必须先保存 pending/approved/输出再发布或恢复，写失败不得执行。没有完整冷启动 manager 恢复时，应如实标 interrupted；不能在重开 UI 时重放已 started 的写工具或重新执行 JSVM。

### 取消和失败

复用 manager 原根 signal → seatSignal → provider/dispatcher/native/package 的级联；席位 deadline 覆盖模型、工具与待人类决定整个 generate。只超时一个席位不取消其余席位；总 deadline/取消全部终止当前 run。批准等待也要监听 signal，移除 waiter/pending UI；迟到 verdict、sources、onChunk 不得写入已终态席位或开始下一次工具调用。

SSH/Python/Mosh/Plugin 使用同一 seat signal，避免仅结束 generate Promise 后留 owner 运行。搜索目前只保证调用方立即取消且后续模型/执行不再推进；若本阶段要承诺底层 HTTP 取消，需要精准把 signal 贯穿 orchestrator/service 的真实请求边界，不能靠顶层 race 作该证据。后台/离页浏览器能力继续遵守 E6 owner，不能为 council 绕过它。

## 5. 窄文件所有权与必要验证

建议四个生产 owner + 主 agent 集成，彼此不改共享文件：

| owner | 文件/责任 |
|---|---|
| Jev domain/runtime | 新 `chat/jev_approval.ts`、Entry `JevApprovalTriage.ets`，以及原 `jev_models/client/JevSupport/SettingJevPage` 的窄用途/signal 接线；三事实/subject 校验/SHADOW 观察。 |
| Council domain | `deepread/council/models.ts,runner.ts,manager.ts,validator.ts,prompts.ts`；固定功率、pending 定位/人类回调、席位/总取消、turn 工具/来源与裁判回流。manager 已 544 行，新增审批小 helper，保持单文件 <1000 行。 |
| Council Entry/tool loop | 新 `CouncilToolRunner.ets` 与小 scope/source helper；真实 provider step factory/store/dispatcher/package/terminal guard。旧 `ModelCouncilRunner.ets` 保留 off 路径。不接不存在的远程服务。 |
| UI | `ChatMessageTools.ets`、`ChatMessageToolPreviewSheet.ets` 的分诊展示及 `CouncilPage/CouncilRunPage/SettingModelCouncil*` 真正功率/来源/批准/回答入口；仅复用现有组件和间距。 |
| 主 agent | `AppContainer`、`ConversationRunService/ConversationRunTools` 接线；E8–E10 provider 合同复核；E11 Web 依赖与 E12 先后次序裁定。当前 E7 owner 文件不触碰。 |

实现阶段只针对真实缺口做必要测试，不跑新功能的伪全套：

- 三事实解码边界 `0.35/0.65`、NaN/缺题/未知 Choice；OFF 零 HTTP、SHADOW 零行为、ACTIVE 标签回填但 approvalState 原样；请求不含 secret/raw command。
- 真实 pending 保存 → 快速批准/改 package step/切默认 SSH/Mosh/删除 → 延迟 Jev 返回：只能同 subject 合并，不复活、不覆盖其他 metadata；Recipe/Plugin ask_user 保留父定位与回答。
- 真实席位 stream tool call → search primitive → 工具 result → 后续模型最终文本/来源；同 run 两席模型/工具上下文不串，scope 外工具连 discovery/动态 primitive 都不可见。
- FULL 写工具 pending → 卡片批准/拒绝/回答 → continuation；SSH/Mosh 目标变化不执行；保存失败零后续执行；有限 step budget 及 duplicate guard 生效。
- provider 流中/搜索工具中/等待批准时取消、单席超时、总超时、迟到批准/事件；一席失败其余可完成，空响应/未决调用不能 completed。验证 native signal 取消资源与 UI 终态分别成立。
- Web 用真实 observe/候选/执行 fixture 验证 SHADOW 零动作、stale revision 零旧动作、未知答案 handback、实际完成条件；远程服务未提供时不标 wired，也不验收“远程已通”。
- 修改文件 lint/type/必要域测试，再由主 agent 按 skill 构建/签名/设备截图；重点检查三标签换行、批准/拒绝点击区、来源长 URL、跨席 tab 对齐与间距、等待审批/取消终态。源码或测试通过不能替代设备 UI 或 provider/远程链路验收。
