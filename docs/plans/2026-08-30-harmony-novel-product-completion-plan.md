# Harmony 小说创作产品闭环计划（2026-08-30 审计重排版）

> 状态：执行中。本文取代旧 `2026-08-30-novel-parity-plan.md` 的“已完成对齐”结论；
> 旧 N1-N3 只保留为历史实施记录，不再代表产品完整性。

## 目标

把 Harmony 小说创作从“独立非流式文本生成页”升级为完整作者工作台：

1. 用户与 Agent 的讨论、思考、工具、`ask_user`、审批、停止、流式 Markdown、
   跟底与终态交接，复用 Chat 的生产实现，不在 Novel 内重写。
2. 小说域保持正文权威，形成“计划/合同 → 写作候选 → 审稿 → 定向重写 →
   原子收录 → 状态同步 → 下一章”的持久代笔链。
3. 创作、状态同步、审稿三种职责可分别选择模型，并在项目级持久化。
4. 交互 run 与无人值守代笔均可停止、离页重连、失败恢复；提交 exactly-once。
5. 每个 Phase 都经过独立逻辑/调用链 review 与 UI 细节 review，精准修复后才提交。

## 已确认的旧结论纠正

- 当前 `NovelModelAdapter` 强制 `stream:false`，整包返回后才发一次 `delta/replace`；
  不能称为流式。
- `NovelMessage` 只有 `role + content`，无法承载 reasoning、tool、approval、usage。
- 历史消息和活动尾使用普通 `Text`，没有实时 Markdown、pacer、终态 drain。
- 当前 `ask_user` 是 fenced JSON 解析出的模拟卡，不是可恢复的 tool loop。
- 分支只切换 `isMain`，不隔离章节/消息/head；revision 只有自增，没有 expected
  revision 冲突校验。
- 单章润色/重生成只生成聊天消息，不会自动更新目标章节；版本产出不完整。
- 不存在章节合同、审稿职责、代笔计划、候选 provenance、后台 job 或恢复状态机。
- 历史真机证据只证明“非流式整章生成 → 手动收录”窄闭环。

## 架构边界

```text
NovelWorkspacePage
  ├─ InteractiveMessageTimeline     # 从 Chat 机械提取的共享呈现深模块
  └─ NovelAuthoringSession          # 页面唯一运行接口
          │
          ├─ Entry/NovelChatRunAdapter
          │      └─ 复用 Chat provider/tool loop/accumulator/stop/checkpoint
          │
          └─ NovelWorkflow           # 小说领域权威
                 plan → candidate → review → rewrite
                      → CAS atomic commit → state sync → next plan
```

所有权约束：

- Chat runtime 拥有 provider、reasoning/tool 消息、流合并、transformer、stop 和重试。
- 共享 Timeline 拥有 MessageRow、Markdown、思考/工具/ask 卡、滚动与终态交接。
- Novel domain 拥有项目、章节、素材、合同、候选、审稿、版本、分支和原子采纳。
- reasoning/tool input/tool output 不得进入正文或候选文本。
- 模型与工具不得直接写正史，只能产出候选或调用有校验的 Novel command。
- `chat -> deepread` 依赖方向不反转；跨域 adapter 放在 `entry`，禁止形成 HAR 环。

唯一消息协议：

- 以当前 Chat 的富 `UIMessage/UIMessagePart/MessageChunk` 线格式为 canonical；C1 将类型
  物理下沉到 DeepRead 的 agent 公共层，Chat 原路径只重导出，保持现有调用者兼容。
- DeepRead 现有 `kind + epoch + string approval` 消息格式只作为迁移输入；同一 Phase 内
  完成 producer/consumer 适配后停止生产，不新增第三套消息 schema。
- Novel domain 不直接依赖 Chat HAR；repository 保存 canonical transcript sidecar，正文域只
  保存 transcript 引用和 Novel metadata。

数据作用域：

- project-global：项目 identity/title、三职责模型策略、UI 偏好、branch registry、删除元数据。
- branch-scoped：章节、会话/游标、素材/事实/decision、合同/计划、候选/review/state delta、
  版本/head、workflow checkpoint 和所有 receipt。
- fork 在明确 checkpoint 上复制 branch state（首版直接持久快照，暂不做 copy-on-write）；
  后续 mutation 只写目标 branch，任何内容 artifact 不得回落项目级数组。

## 全程执行纪律

每个 Phase 固定执行以下闭环：

1. 主 agent 实现，保持改动职责单一。
2. 跑受影响域单测；涉及 chat/deepread 时两域全量测试均须 0 failure。
3. ArkTS lint 0 error。
4. 构建 AGC 签名包并真机 `install -r`。
5. subagent A：逻辑、数据不变量、生命周期、调用链、失败/恢复路径 review。
6. subagent B：UI 状态、错位、对齐、边距、间距、字号、点击区、暗色、滚动 review。
7. 主 agent 逐条复核，只修有代码/日志/截图证据的问题，不做推测性兜底。
8. 重新测试、构建、安装和截图/必要时视频复核；通过后中文提交，一 Phase 一提交。

禁止：

- 直接把巨型 `ChatPage` 嵌入 Novel。
- 复制 Markdown parser/cache/highlighter/pacer、流 accumulator、tool loop、stop 状态机。
- 新增第三套 `UIMessage` 或继续用字符串 fence 模拟新 `ask_user`。
- 页面消费 provider delta、原地累加长字符串或自行实现假打字机。
- 非流式整包结果标为 live streaming。
- writer/reviewer 阶段直接覆盖章节。
- 为理论风险新增多层 fallback、万能接口或泛化 event-sourcing。

## Phase C0 — 契约与基线纠偏

交付：

- 本计划、workflow artifact、旧计划/总账纠偏。
- 为 Chat 消息行、stream accumulator、stop、timeline follow、Markdown terminal handoff
  补/确认 characterization tests，冻结可复用行为。
- 记录当前两域测试数、lint/build/真机基线和真实流式资格判据。

验收：旧“N1-N3 已完成对齐”不再作为当前结论；行为基线可自动回归。

## Phase C1 — 共享消息呈现深模块

交付：

- 从 `ChatPage` 机械提取 `AgentMessageRow`，保持 reasoning/tool/ask/subagent/council/
  Markdown/footer 现有行为。
- 将现有 Chat 富消息类型下沉为 DeepRead agent 公共 canonical；`chat/message.ts` 保留重导出，
  DeepRead 旧 `kind` 格式完成迁移适配后不再产生。不得通过另一份平行 interface 规避迁移。
- `AgentMessageRow` 接受明确的 host capability：copy/branch/favorite/regenerate/edit 等未由
  host 支持的 Chat action 必须隐藏，不允许显示无效按钮；Novel 的收录、采纳、拒绝、
  审稿详情使用 host slot/footer，不借用 Chat 分支或重生成语义。
- 提取 `InteractiveMessageTimeline`，复用窗口加载、活动尾、regen tail、三态跟底和跳底。
- ChatPage 先迁移为该模块的第一个调用者；Chat 行为和截图必须不变。
- Novel 只做 legacy `NovelMessage -> text-only UIMessage` 单向投影，接共享 Timeline；
  小说特有“收进章节/候选状态”使用行外 slot/footer，不侵入通用 renderer。
- Timeline 是横向 gutter 的唯一 owner；MessageRow 不再叠加页面外层 gutter。验收 user bubble、
  assistant 正文、reasoning/tool/ask、候选/审稿卡与输入器左右基线一致，禁止出现 16+20vp
  的双重缩进。

验收：Chat 与 Novel 使用同一消息行/Timeline；Novel 生产 UI 不再用普通 `Text`
渲染 assistant 正文，也不再持有自建 follow 逻辑。

## Phase C2 — 共享交互运行时与真实流式

交付：

- 从 ChatPage 装配中提取小而深的 `InteractiveTurnRuntime` façade；Chat 先迁移。
- Entry 层 `NovelChatRunAdapter` 复用 provider 解析、MessageStreamAccumulator、transformer、
  retry、tool loop、stop、raw/visual checkpoint。
- Novel 页面不再消费 `delta/replace`，改订阅不可变 `UIMessage[]` snapshot。
- 区分 `generationActive` 与 `textDeltasLive`，禁止把 Markdown reveal 当成网络流式。
- 真实流式资格门：`firstParsedDeltaAt < dataEndAt`，且 UI 在请求 promise resolve 前收到
  delta。传输状态只允许 `live / buffered / unavailable`。
- API 12 真机用受控延迟 SSE + 真实 provider 验证；若现有 NetStack 路径挂死，沿 Chat
  已验证 transport seam 修复，禁止在 Novel 另写 SSE 解析器。

验收：Novel 可见 reasoning/text/tool 增量；stop 真正 abort；非流整包立即完整显示且不冒充流式。

## Phase C3 — 持久会话、真实工具与职责模型

交付：

- Novel transcript 持久化完整 `UIMessage.parts`；legacy schema dual-read、single-write。
- 新讨论使用真实 Novel 工具目录和完整 JSON Schema；`ask_user`、approve、deny、answer
  保存 pending/resolved 状态并从同一 run/tool call 续跑。
- 项目级 `creation / stateSync / review` 模型策略及全局默认；工作区模型选择复用 Chat
  现有模型菜单/解析，不另建 registry。
- 工作区提供三个带职责标签的模型入口/摘要，分别显示“继承小说默认”或固定的
  provider/model；单模型 `TopModelMenu` 作为选择控件复用，而不是把三职责压成一个入口。
  固定模型失效时在对应职责行原位报错并提供重选；退出重进后的 UI 与 run receipt 一致。
- 显式固定模型失效 fail closed 并提示重选；不得静默换模型。
- reasoning/tool/approval transcript 与小说正文存储分离。
- 所有 mutating Novel tool 以稳定 `toolCallId/commandId` 作为幂等键；domain 在项目事务中
  持久化 command receipt 与输出。工具副作用成功但 transcript output 尚未保存时，恢复必须
  返回已有 receipt，禁止再次创建素材、章节、分支或提交。

验收：退出重进后 pending 卡、回答和工具结果不丢；三职责可选不同模型并在 receipt 中固定。

## Phase C4 — 候选、合同、审稿与原子提交

交付：

- 结构化章节合同：目标、must happen、must not happen、可见事实、验收条件、digest。
- durable prose/polish candidate：base revision/head、plan ID/digest、来源版本、状态和 hash。
- review 使用独立职责模型，产出严格结构化 verdict/issues/rewrite brief/state delta/next plan。
- 候选/审稿 UI 状态矩阵：待审、合格、需改写、冲突、审稿 issues、rewrite 前后差异、
  已提交；所有状态来自 durable artifact，不由页面推测。
- 不合格候选最多按明确 issue 定向重写；严重冲突暂停等待用户，禁止无限自愈。
- review 后的 state sync 和 next-plan completion 都是 commit 前阶段；若 review 未提供合格
  state delta/next plan，则先调用对应职责模型补齐，任何一项失败都停在 pre-commit，不提前
  写正史。`NovelProjectCommitPort` 提供 expected revision CAS、同目录 temp+rename、
  command/commit receipt；正文、版本、剧情状态与计划轮换一次原子提交。
- 修正现有分支/版本假闭环：按前述作用域把章节、消息、素材/事实、合同/计划、候选/
  review/state delta、版本/head、workflow/receipt 全部纳入 branch state；手动编辑、重生成、
  润色和收录均产生可恢复版本。

验收：任意 revision 漂移都不覆盖用户正文；commit 重放 exactly-once；候选未采纳前正史字节不变。

## Phase C5 — 完整代笔流水线

交付：

- `NovelWorkflowRun` 持久阶段：planning、writing、reviewing、rewriting、state_syncing、
  planning_next、committing、waiting_user、paused、failed、completed。state sync/next plan
  必须先完成并 checkpoint，`committing` 才把全部 mutation 一次写入正史。
- 单章与 1-N 章批次：计划确认 → 候选 → 审稿 → 定向重写 → 原子收录 →
  状态同步 → 下一章。
- 支持逐章人工确认与自动代笔两种明确模式；审稿报告和重写差异可见。
- 统一阶段卡展示当前章节、planning/writing/reviewing/rewriting/committing/state_sync/
  planning_next、暂停、等待用户、失败可重试及恢复点；退出重进恢复同一张卡。
- stop/pause/resume/retry/cancel；每个 phase checkpoint，进程死亡后只恢复一次。
- workflow domain 在 C5 自身实现原子 run claim、attempt generation、owner lease 和 execution
  token；同一 project/branch/run 同时只有一个 owner，旧 attempt 永久失去写权限。C6 的
  WorkScheduler 只消费该协议，不再创建第二套后台所有权状态机。
- 现有单章/批量润色迁入同一 candidate/review/adopt 流水线，移除内存 Map 作为真相源。

验收：在每个阶段后模拟进程终止，重开只继续未完成阶段；已提交章节不重复，失败可定位可重试。

## Phase C6 — 无头后台、通知与深链

交付：

- 按官方文档接 `WorkSchedulerExtensionAbility`（API 12 基线内）与无头 Novel workflow。
- job、execution token、owner lease、进度/失败/完成 receipt 持久化；旧 worker 失去写权限。
- 前台/后台使用同一 workflow，不在 Extension 中复制生成逻辑。
- 进度、失败、完成通知；点击带 projectId/runId 深链进入对应工作区和阶段卡。
- 后台 UI 明确区分排队、运行、等待系统窗口、已暂停、失败、完成；通知深链必须落到
  与页面相同的 durable 阶段卡，不能只打开小说列表或创建瞬态 toast。
- 系统窗口不足时准确暂停并等待下次调度/用户继续，不伪报完成。

验收：离页、锁屏、进程回收、重新打开均不重复提交；通知点击准确恢复项目。

## Phase C7 — 产品终验与旧链清退

交付：

- 删除已无生产 caller 的 `NonStreamNovelModelAdapter`、字符串 delta/replace 事件、
  legacy fenced ask-user 新写路径和 Novel 私有 Markdown/滚动轮子；删除另列清单后精准执行。
- 修正文档、parity backlog、能力矩阵和实际测试数字。
- 真机覆盖：Quick Start、讨论工具、ask-user、真实流式、思考卡、整章候选、审稿、
  定向重写、采纳、版本/分支、批量代笔、停止/恢复、后台、通知深链。
- 8k-12k 中文混合 Markdown：自动跟底、上滑暂停、回底恢复、终态无跳变；用视频/
  帧证据验证滚动，截图验证布局/暗色/间距；AX/布局边界验证主要交互最小 44vp 命中区。
- 暗色状态矩阵覆盖 reasoning、tool/ask、候选、审稿、进度、失败和后台恢复；Novel 新 UI
  禁止使用浅色专用硬编码，必须消费主题令牌。
- 长文本样本同时覆盖正文、reasoning、tool output、审稿报告和 rewrite diff；终态 drain
  不重播、不闪白、不重复块，footer 不跳位，用户暂停后任何增量都不得抢滚动。

最终完成条件：

```text
用户讨论 → Agent 工具/ask-user 可见且可恢复 → 计划确认
→ 写作模型候选 → 审稿模型结论 → 定向重写
→ 原子收录/状态同步 → 下一章 → 后台继续
→ 任意离页/停止/崩溃不丢状态、不重复提交
```

只有上述链路、两域回归、lint、签名构建和真机证据全部成立，才可把 Harmony 小说标为“产品完整”。
