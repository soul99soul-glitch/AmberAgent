# E6 多会话运行宿主预查

2026-09-28；独立只读研究。未修改产品、测试、构建、签名或操作设备。E6 仍为 pending，本报告不替代 E5 source gate、E6 实施或验收。已读取 harmony-arkts skill，以下依据当前源码；历史 Novel 记录仅用于定位，并已核查当前实现。

## 结论

最小改造是一个进程内 `ConversationRunService`，按 `conversationId` 管理一次活动运行及会话队列，继续调用现有 `createChatKernelHost → runInteractiveTurn → runChatTurn/runRegenerateAt/runToolLoopContinuation`。页面只发命令和订阅自己的快照。不要另建生成引擎、数据库、持久任务 ledger、通用资源 scheduler 或每页一份宿主。

仅移走 `abortController` 不足以完成 E6。发送、重生成、审批续跑、队列、raw checkpoint、辅助生成和多个工具闭包仍引用页面。JS/WebMount 的实际 WebView 也仍挂在页面，不能因为 TypeScript singleton 存活就宣称其原生宿主跨页存活。

## 已核实的真实链路

路径前缀：页面 `harmony/entry/src/main/ets/pages/`，平台 `harmony/entry/src/main/ets/platform_impl/`，DI `harmony/entry/src/main/ets/di/`，域 `harmony/chat/src/main/ets/chat/`。

| 入口/责任 | 当前位置与调用链 | E6 所需归属 |
|---|---|---|
| 普通发送 | `ChatPage.ets:4691` sendParts 同步 sending 占位 → full conversation → `:4718` runSendParts → resolveChatRuntime → buildToolLoop → `:4095` runViaKernel | service 同步按会话占位，之后才异步装载/水合；UI 发送材料化、草稿、附件选择仍留页面 |
| 重生成 | `:2003` regenerate → full conversation → `:2012` runRegenerate → invalidate compacts → runRegenerateAt | 同一宿主入口保存 nodeId/stopTarget，保留 assistant 中间节点重生成语义 |
| 编辑并重发 | `:3057` sendEdit → `:3071` runSendEdit → editMessage/save → regenerate | 编辑保存与后续重生成同属该会话命令，不能在切页后停在半个入口 |
| 批准/拒绝/回答 | `:4152/:4184` → `:4187` applyToolVerdict → applyToolApprovalToConversation → await save → `:4220` resumeToolLoop → runToolLoopContinuation | service 接收 conversationId + 现有 locator；按本会话最新状态裁定、保存后续跑；保持每步批准和冻结目标 |
| 手动 Recipe/Plugin | `:1178` 路由消费及 `:4238` manualId → continuation；E5 root 接线尚在收口 | 沿用同 adapter/manualToolCallId；无模型辅助调用和冷启不重演语义保留，最终以 E5 source 为准 |
| 停止 | `:1335` requestStop → kernelHost.cancel + EntryAbortController.abort → convertSteerQueue | cancel(conversationId, runId) 只操作该 record；kernel cancel 本身不 abort 网络，必须保留该 run 的 signal |
| STEER/FOLLOWUP | `:4122` drainSteerMessages；`:4410` drainPendingQueue；`:3143` persistQueue → KV 按 conversationId 保存/审计 | per-conversation session 持有 pendingQueue/drainActive/appendActive；消费不依赖 pageAlive |
| 仅追加不回答 | `:4505` sendWithoutAnswer → `:4399` appendUserParts/队列；appendUserParts 当前要求 pageAlive | 追加仍用既有 runAppendUserMessage，和运行入场保持同会话互斥，页面不拥有异步保存 |
| 数据与显示 | `:692` checkpointTail、`:729` raw capture；`:1384` final tail save；`:1393` finishCommon；`:1422` inactive finish | 宿主统一原始投影与最终保存，页面只生成 rows、streamingTail/regenTail 和滚动 |

`chat_kernel_host.ts:111–163` 与 `interactive_turn_runtime.ts` 已有独立于页面的单轮 façade。每个 record 创建自己的 kernel/controller 即可。kernel 的 `runId=kernel-${nowMs()}` 不适合作全局唯一身份；服务使用现有 `newId()`，无需改 provider 协议。现 kernel 调用 runInteractiveTurn 未传其 onSnapshot hooks；需要展示 transport/textDeltasLive 时仅给 façade 增加可选 snapshot 转发，复用 InteractiveTurn 的真实分类，不在 service 另包一个 accumulator/解析器。

## 当前会导致搬迁失败的具体边界

1. **离页即中止且捕获停止快照。** `ChatPage.ets:747–792` aboutToDisappear 设置 pageAlive=false、generationToken++，直接 abort，并 applyStopGeneration 后保存。`:1060` isGenerationOperationActive 同时把所有 raw/UI 回调绑到 pageAlive。须删除此处对运行的 abort/停止收口/全局 generating 修改；保留 UI 退订、键盘/定时器/草稿清理。`onPageHide:954` 当前只停止页面效果，可保留。
2. **全局 generating 被任意页面结束清零。** `:704–714` 每次 false 都写全局 bool 并停唯一 DATA_TRANSFER。A/B 同时运行时 A 完成不能停 B。聚合发布 `activeCount > 0`；后台申请、前台释放继续由 EntryAbility，生成归零清理由 service。仅计数仍不意味着 SSH/Python/Mosh 能后台继续：`EntryAbility.ets:366–379` 仍全局中断这些原生任务。
3. **restore guard 不是单会话生成锁。** `ConversationRunGuard.ets:11–24` beginConversationRun 允许计数增加，不拒绝同 ID 第二个 run；用于阻止 restore，不应改成所有辅助任务互斥的万能锁。service 在任何 await 之前检查/登记本会话活动 record，继续持有原 guard 到最终写入完成。
4. **repository 串写解决顺序，不解决陈旧全量内容。** `ChatRdbRepository.ets:101–107/301–319` 按 ID 排 mutation，又用既有全局文件变更排队，`:115–142` 在事务内替换整份 conversation/nodes。保留这些锁；host 负责 checkpoint→引擎 save→final save 的本轮顺序。kernel `chat_kernel_host.ts:128–131` 在真实 save resolve 前即更新 lastPersistedConversation，不能把它当持久化成功凭据。
5. **active 重连须先于 cold recovery/队列启动。** `ChatPage.ets:1186–1277` 目前从 tail window/full repo 读入，随后 recoverInterruptedRecipes、manual continuation、loadPendingQueue/drain。E6 新页先询问 active(id)/订阅即时快照；有活 run 时由它提供 full canonical state/queue，不能让迟到磁盘读取覆盖它，也不能另启 drain 或把它当冷启中断。无 active 才执行现有恢复路径。
6. **离页后仍需要正确运行的辅助任务。** `:2202` memory extraction、`:2260` title、`:2323` suggestions 当前被页面完成回调控制；title/suggestion 已使用最新会话加载并仅合并字段，不能倒退成保存旧 full snapshot。新宿主通过本会话同一 mutation 入口做条件合并，校验与写入在同一个序列内，避免新一轮消息被旧辅助结果覆盖。manual run 保持既有跳过这三类调用。
7. **删除入口原先靠“当前页在生成”判断。** `ChatPage.ets:3494–3500` 只拒绝删除当前 sending 会话；`ChatListPage.ets:638–654` 最终 delete 没问是否有活 run。E6 持续离页运行后，两处必须按目标 ID 查 service busy；延迟删除的实际 commit 时也查一次。继续由仓库 deletedGen 阻止迟到旧写，不重新实现墓碑。

## 必须从页面解绑的闭包

| 当前捕获 | 明确触发/影响 | 最小替代 |
|---|---|---|
| buildToolLoop `:3711` conversationProvider = this.conversation ?? conv | 旧 run 对 current/page 数据做 context/history 查询，回页后新实例无法提供旧状态 | `() => Promise.resolve(session.conversation)`，只属于原 ID |
| transformers `:570–605`、memory `:670`、recent context `:611`、compact `:3207` | 动态 callbacks 读取 agentRuntime/mode/lorebook/userNickname/miniAppSetting/this.conversation；离页或另会话会改变本轮事实 | 开跑水合后捕获非 UI seed；memory/recent/compact 输入从本 record 当前 canonical conversation 取；保留需要每次重读的仓库事实 |
| compact launch/onActive `:3230–3249` | precompact 完成继续更新旧组件 @State | 本 record 的 compact count/status emit，页面订阅绘制 Context |
| subagent `:3263–3280`、catalog `:3890–3897` | parentToolsProvider 惰性读取 page.baseToolsCache；缓存/refresh 在 A/B 之间不能共用 | tool catalog 与 supervisor closure 每 record 一份；E4 ScopedRecipeLoop 的原 scope/原生 guard 不变 |
| queue `:3816–3846/:3923`、`:4122` | pendingProvider/drain/error/requeue 回调依赖页面，离页就不继续或无法显示真实队列 | session queue 方法；KV 键及审计都传已冻结 conv.id |
| image source `:3810–3812/:2541` | generate_image(edit) 查 this.conversation 最近用户图 | 读取当前 record 的同会话消息；文件/data 解码 helper 无页面引用 |
| deep_read_open `:3871–3882/:4481` | tool.execute 直接 router.pushUrl，可以在用户已切到 B 时把其页面顶走 | 工具发带原 conversationId 的导航意图；只由当前附着且匹配 ID 的 UI 消费。无附着收集者时沿 existing bus 返回明确不可导航结果，不能假报 hasCollectors=true |
| MCP Activity port `:4021` / scopedToConversation `:4036` | 全局 ActivityStore scope/current 跨 await，见下文 | 显式会话活动端口/按会话 store；无需改 MCP 执行白名单架构 |
| notification/haptic `:2154–2184` | notification 文案取 this.conversation；完成离页时当前分支直接跳过整个完成副作用 | notification 使用 finalized + 已捕获助手/开关；haptic 仅匹配可见页面消费完成事件，后台不触碰旧 UIContext |

MCP 执行链已有正确的按轮授权快照：`ChatPage.ets:3761–3777` 两个工厂都传 `assistantIds:[...rt.assistant.mcpServers]`，manager `mcp_manager.ts:233/247/257/284` 接收并沿用。不要推倒全局 manager；只让工具不可遗漏该参数，tools-warning 也用同一快照查询，取消全局 setAssistantIdsProvider 作为 run 正确性的依赖。

Subagent model/assistant 水合另有真实语义边界：`AppContainer.ets:730–804` singleton generation port 的 resolveAssistant 固定读 assistants[0]，fallback model 读全局 Chat 选择。主 run 的 ChatRuntime 则按 conv.assistantId 解析 (`:1539–1610`)。E6 应让 child 水合用原 parent 的已解析助手/模型选择，保留明确 role override/Jev 路由；不要通过重新设置全局“当前助手”实现并发绑定。providerByModelId Map 本身不证明冲突，同一配置 UUID 的缓存不应无证据扩修。

## 共享能力的真实并发边界

| 资源 | 当前源事实 | E6 建议与不能宣称的内容 |
|---|---|---|
| Provider HTTP/SSE | `RcpHttpClient.ets:55/138` 每次 fetch/stream 都 createHttp；abort 闭包各自销毁自己的 request | 按 run signal 并行即可，不新增全局 provider 锁 |
| AgentToolActivityStore | `tool_activity.ts:129–190` 只有一个 current，withConversation 在 await 期间改共享 scope；complete 只认当前 toolCallId | 显式按会话/调用保活动；最小是 per-conversation store + 工厂注入，或当前 store 按 ID 保存并显式 convId。不要保留跨 await 全局 scope 再用 UI 过滤掩盖数据串属 |
| legacy javascript_execute | `JsSandboxTool.ets:29–33/73/143–189` 一个 controller、Promise lock、页面 Web；execute 不接 AbortSignal | 同一原生执行真实串行/占用。若采用跨 run busy，在真实 evaluate settle 前保持占用，取消不可假装强杀。页面 Web detach 后无法承诺旧计算/后续调用继续；不把 E5 JSVM 偷换进此工具而改变既有语义 |
| WebMount | `WebMountSession.ets:59–66/92/186/262/389/407` 单 controller/url/tabs/hooks/UIContext，abort page wait 调 shared stop；`:554` Host 与 ChatPage `:5442` 均是页面树 | 一个实际 browser owner，占用期间其他会话 wm_* 返回明确 busy。不能只锁单个 wm_open 后就让 B 改页、A wm_extract 读 B；owner 覆盖该工具链。切页 detach 是实际 host loss，须明示工具失败/不可用且不重演，不取消其它会话模型流。若要求 Web 工具也跨页不中断，则必须另做经 API12 官方文档核验的稳定 ArkUI 宿主；当前 loadContent/router 源不具有它 |
| E5 JSVM | `plugin_js_core.cpp:349` 已明确 active/abandoned 单 slot runtime_busy；E5-contract `:13–14` | 原 busy/abandon/关闭 gate 语义保留；旧 VM 未退出不能以新 runId 重新开放宿主能力。以 E5 source gate 后最终接口接线 |
| embedded Python | `python_core.cpp:120–134/202–212` process Owner + 一个线程/FIFO；`python/runtime.ts:28/92` 请求 ID Map/独立 cancel | 真实是隔离请求串行执行，可保留其现有排队或在入口公开 busy；不能称多个 Python 同时执行。cancel(A) 只取消 A ID，不能清空 Owner 或调用全局 background interrupt |
| SSH / Mosh | job_runtime `:21`、session_runtime `:22`、mosh/runtime `:40` 各 ID Map + 独立 stream/controller；tools 固定 sourceConversationId/target | 复用 singleton，独立 job/session 可以并行。对相同 session 的现有串行写入保留；cancel run 不调用 stopAll/background interrupt。已经返回给用户的持久 session 不因其页面离开自动销毁 |
| Workspace / RDB | Workspace 根固定只读 deps (`workspace.ts:126–140`)，文件名仍属同一个用户 Workspace；RDB 已串事务 | 不为 Workspace 制造每会话目录或全局锁。相同路径修改仍是原有真实共享副作用、沿批准执行；RDB 继续串写，但模型流可以并行 |

WebMount 的 bot/hand 租约、E5 pluginUrlGate 和 conversation browser owner 是不同现有事实：用户 hand 模式及站点/插件域交集不能被新 owner 覆盖。它们可以局部检查组合，不需要通用 broker。报告没有声称这些非重入工具在新宿主中已可跨页运行。

## 最少宿主形态

```ts
// 形态示意，不是新增实现/API 承诺。
interface ConversationRunHandle {
  conversationId: string;
  runId: string;
  subscribe(listener: (snapshot: ConversationRunSnapshot) => void): () => void;
}
// service: start(command), active(conversationId), subscribe(conversationId),
// cancel(conversationId, runId), verdict(conversationId, locator, verdict),
// enqueue/cancelPending/append/edit 等本会话命令；一个 Map<string, session>。
```

session 持有 full canonical conversation、pendingQueue 及同会话 mutation 顺序；active run record 私有持有唯一 runId、controller、kernel、operation/send 或 regenerate 目标、writeTail、raw snapshot 与 checkpoint 时间、冻结 ChatRuntime/seed/tool catalog。UI 快照只提供必要展示字段：conversationId/runId/phase、canonical conversation、visual messages、启动时 historyCount/regenNodeId、transport/retry/error/compact/activity/queue。鉴权配置与原生 handle 不进入展示快照或持久 metadata。终态保存已结算、无待派发队列且无 UI 订阅时释放本会话内存记录；回页读仓库，不无限保留已完成 full conversation/鉴权闭包。

具体闭环：

1. start 在 await 前注册 preparing record 并取得原 restore guard；加载全量会话/待发队列与依赖后仍检查该 controller 是否已 abort，再调用真实 kernel。仅同 conversationId busy，A 与 B 互不拦截。
2. deps.store.save、raw checkpoint、final stop 投影都经该 record writeTail；入队前计算不可变内容，保留 current checkpoint 的原始消息与 visual transform 分离。引擎 save 的 Promise 必须仍 await 真实落库，E4/E5 before-effect 保存语义不能变成 emit 后即完成。
3. deps.onUpdate/onRaw/onRetry/onCompact 写 record 并发射替换快照，无 Page/Scroller/UIContext 引用。等待批准是已持久化 waiting_user 状态，不计 active generating、不自动重新执行。错误/取消终态在 final save 结算后发布/释放入场锁；保存失败作为本会话错误，不能只 swallow 后显示已持久成功。
4. subscribe 立即回放最新快照；新页先按 ID 附着，再处理磁盘历史窗口。页面仍只显示原尾窗，active tail 以启动 historyCount/nodeId 分隔，不能从最新 checkpoint 的节点数重新计算导致重复/消失。终态使用现有 `refreshRowsAfterConversationChange` 和 E2 timeline revision，不全量改 UUID key。
5. 页面退订只停 UI 观察；cancel 先匹配 ID/runId，再 host.cancel + 本 controller.abort；旧按钮/迟到完成不能取消新 run。保留用户 stop 的 steer→followup 与后续排队语义，操作仅该 session。
6. activeCount 聚合供 AppStorage/后台保活；停止 A 而 B 存活时仍为 true。网络中断/后台原生断开仍沿真实 E2/E3 状态；不自动重发不可知副作用或会话命令。

现存参考已源核实：`novel/creation.ts:204–222/768–803` 每项目 Map、handle.subscribe 回放最新事件、activeRun；`NovelWorkspacePage.ets:218–230/330–379/1049–1088` 离页仅退订、回页 reattach 并使用 historyCount。只复用这些简单原则，不复刻整套 Novel 域或 emitter 的全部结构。

## 文件责任与必要后续验证

建议最多四个清晰边界，每个 <1000 行：域 `conversation_run_service.ts`（admission、快照、取消、保存）；Entry `ConversationRunDependencies.ets`（水合、transform/context）；Entry `ConversationRunTools.ets`（catalog、队列工具、原 adapter/guard）；Entry `ConversationRunAuxiliary.ets`（title/suggestion/memory/通知字段合并）。实际命名由主 agent 按 E5 最终集成判断。DI 只返回 singleton、聚合状态/后台桥；ChatPage 收缩成事件转发与展示，不把上述三套 deps 复制进另一个巨类。

E6 实施后只需一个必要的并发交错回归覆盖 A/B 交替更新、A 取消、B 正常结束、同 ID 重入、旧回调/最终 checkpoint 顺序；沿既有 native/Recipe/Plugin 回归证明原信号与 before-effect 未退化，避免新增排列组合。真实 UI 最小走 A 生成→切 B 生成→回 A reattach→停止 A→B 继续并保存，再核一次原生 busy/host loss 文案。此报告没有执行这些检查，也没有给 E6 A/B/C PASS。
