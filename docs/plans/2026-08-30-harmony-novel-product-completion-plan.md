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

## 生产链冻结（C3 起）

```text
NovelProjectsPage / NovelWorkspacePage
  -> NovelWorkspaceService                 # 唯一页面业务入口
  -> NovelWorkspaceOwner + expected CAS    # branch/head/tree/owner 权威
  -> NovelWorkspaceStore                   # staged install + atomic file/ledger
  -> NovelBatchExecutor                    # 只消费同一 durable job/checkpoint
  -> WorkSchedulerExtensionAbility         # 平台唤醒，不复制业务状态机
```

- 现有 `NovelProjectRepository` 保留为页面兼容门面，但 C3 后只读写
  `amber.novel.workspace` v1；旧单文件 schema 仅作一次性迁移输入，禁止平行保留第二套生产数据源。
- `manifest.yaml/project.md/setting/inbox/drafts/branches` 是可交换工作区；commit、undo、session、
  unresolved、job、receipt 和 active branch 只在宿主私有 `.amber/`。
- 文件树、append-only ledger 与 active branch checkout 共同构成正史；UI 文案、内存 Map、
  provider transcript 都不是事实源。
- C3-C7 的所有 mutation 从一开始就携带 branch/head/tree/owner 语义；Phase 只逐步开放能力，
  不再更换存储模型或另造 orchestrator。

## Phase C3 — 工作区 v1、项目交换与书籍导出

交付：

- 定义并验证 `amber.novel.workspace` v1：`manifest.yaml`、`project.md`、`setting/`、
  `inbox/`、`drafts/`、`branches/<slug>/...` 与宿主私有 `.amber/`。
- 章节文件以 ASCII 三位序号命名（如 `031-title.md`），排序和导出不受系统 locale 数字影响。
- 单一 `NovelWorkspaceStore` 支持创建、打开、重命名、删除和旧 schema 一次迁移；现有项目入口
  迁入该 store，dual-read 只服务迁移，后续 single-write 工作区。
- 所有工具/ZIP 路径拒绝绝对路径、反斜杠、`.`、`..`、空 segment、隐藏目录和 root 越界；
  `.amber/` 仅宿主可访问。
- ZIP 导入先做格式、entry 数、单项/累计压缩与展开大小、路径、重复路径校验，再解到 sibling
  staging；完整校验通过后一次 rename 安装为新项目，任何失败清理 staging 且现有项目不变。
- ZIP 导出只包含可交换树，不包含 `.amber/`；TXT/Markdown/EPUB 只渲染当前 branch 正文，
  EPUB 必须是可打开的确定性归档。
- 项目列表、创建空白项目、重命名、删除全部读取真实工作区；删除失败不得让项目显示为成功删除。
- append-only ledger/head/tree/initial checkout 的最小骨架随项目创建/导入建立；C4 再开放 mutation。

验收：路径逃逸、大小/数量限制、重复 entry、导入中断、旧项目迁移、CRUD、ZIP 往返和三种
书籍导出均有定点测试；真机创建/重命名/导入/导出后，重启应用仍由同一工作区展示。

## Phase C4 — 分支正史、受控写入与一致性

交付：

- append-only commit ledger 记录 parent、branch、tree digest、mutation、receipt；checkout/head/tree
  校验互相一致，所有写提交都要求 expected branch/head/tree CAS。
- 从当前 head 创建分支和切换分支；章节、设定、plan/plot、伏笔、决定、unresolved、undo 和
  active session 全部来自目标分支，不回落项目级数组。
- 收录新章节、追加最后一章、手动编辑、单章重写、审核收录、润色、还原、创建分支都形成
  可追踪 commit，并在窄提交链中更新文件、ledger、head/tree、undo 与 receipt。
- 正文/剧情/branch metadata 等受保护写入只接受宿主 commit 或 durable proposal；批准/拒绝
  幂等，Agent 不得绕过。`setting/`、`inbox/`、`drafts/`、branch `setting/plan` 受控自由写。
- Agent 文件工具仅开放 list/read/grep/status 和上述受控 write；manifest、project、branch
  metadata、`.amber/` 永远由宿主管理。
- 编辑中间章后，该章及后续章节标记 unresolved；用户可重写后续或显式解决。plot digest 与
  branch tree 不一致时标记 plot stale，阻止依赖旧剧情状态的危险提交并提供显式同步入口。
- 一层 undo 还原文件树并产生新 commit，不删除历史 ledger；重放相同 command/receipt
  exactly-once，revision/head/tree 漂移 fail closed。

验收：branch head/tree CAS、分支切换、proposal approve/deny/replay、manual edit、undo、
unresolved、plot stale 和部分写恢复均有测试；任意漂移不覆盖用户正文。

### C4 执行记录（2026-08-31）

- 正史已由可见树、严格 ledger、checkout、branch snapshot 与 commit snapshot 共同校验；所有
  `NovelCreation` 生产 mutation 经 fresh CAS + receipt 提交，断链、重复或正文漂移 fail closed。
- fork/switch 已是真实隔离 branch state；durable proposal、one-level append-only undo、unresolved、
  plot digest stale/sync 均持久化并有聚焦测试。
- durable proposal 已接入 `NovelCreation` production service seam；C5 的 Agent tool/审批卡直接复用，
  不再创建平行接口。
- 真机完成主线 → 创建分支 `2` → 自动切换 → 切回主线；重装签名包后状态仍在。分支面板经 review
  补齐整体滚动、键盘态、按条目收缩和可见错误反馈。
- 验证：DeepRead 697/697、Chat 2022/2022、聚焦 30/30；ArkTS lint 0 defects；AGC build、安装、
  逻辑 review 与 UI review 全部通过。

## Phase C5 — 小说工作台、持久对话与职责模型

交付：

- 工作区固定“创作 / 正文 / 设定”三视图并显示/切换 active branch；所有列表、状态卡和
  禁用原因只读取 C3/C4 durable state。
- 创作输入明确区分“讨论”和“写正文”：讨论用于规划/设定，写正文只产 durable candidate；
  草稿可收录新章节或追加最后一章，未经用户确认不得进入正史。
- Novel transcript 保存完整 canonical `UIMessage.parts`；legacy dual-read、single-write。
  reasoning/tool/approval 与正文文件分离，退出重进仍能恢复 pending/resolved 卡。
- 使用真实 Novel 工具目录和完整 JSON Schema；`ask_user`、approve、deny、answer 从同一
  run/tool call 续跑。mutating tool 以 `toolCallId/commandId` 持久 receipt 并 exactly-once。
- 项目级 writing/review 模型策略：写作可跟随全局，审核可跟随写作；需要状态同步时使用
  明确的内部职责策略。模型选择复用 Chat 菜单/解析，固定模型失效原位 fail closed，不静默替换。
- 正文视图支持目录、阅读、手改、单章重写、undo、主动一致性检查；设定视图支持合法设定
  文件、未解决/已解决伏笔、已确认决定和 plan/future/preferences 编辑。
- 复用 C1/C2 的 reasoning、tool、停止、真实流、实时 Markdown 与跟底；窄屏、长文案、
  44vp 点击区及 loading/empty/error/disabled/dark 状态真机复核。

验收：真机从项目列表进入三视图，与 Agent 讨论、回答 ask-user、看到真实流式思考/Markdown，
生成候选并审批收录；退出重进 transcript、模型策略、branch 与 proposal 状态一致。

## Phase C6 — Durable ghostwrite 代笔闭环

交付：

- 1-10 章，默认 5；启动前必须存在 `plan/this-chapter.md`，原子冻结 plan 正文、plan ID/digest、
  branch、head、tree 和唯一 owner token，运行期间禁止修改绑定计划。
- durable stage 至少覆盖 writing、reviewing、rewriting 1/2、committing、planning，以及
  paused、waiting_user、failed、cancelled、completed；页面重开/冷启动只恢复同一 job。
- 写作只落 job-private candidate，绑定 job/branch/chapter/plan ID+digest，不写正史、普通 draft
  或 proposal；健康章节严格为一次写作 + 一次只读联合审核。
- 联合审核返回结构化 verdict/issues/rewrite instructions/state delta/next plan，严格校验
  candidate/chapter/plan 关联。只有缺失必写、违反禁写或可定位连续性硬伤才 blocking。
- rewriteRequired 携明确 issue 定向重写，同一 plan ID/digest 最多两次；第三次停止并展示原因。
- 通过后在 owner token + expected branch/head/tree CAS 下，原子提交章节、受限状态增量、
  旧计划消费、下一计划、ledger、undo、receipt/progress。候选在 commit 前不改变正史字节。
- 非最终章优先使用 review next plan，缺失时最多一次只读规划；最终章删除当前计划且不规划。
- progress 只统计绑定 job receipt 且 commit 仍在 branch ancestry 的章节；先持久化 commit/receipt，
  pause/cancel 只影响下一章。
- 支持 pause/resume/retry/cancel；claim/attempt/owner lease 使旧 execution、错误分支和 CAS
  冲突在写正史前拒绝。部分写失败由 ledger/receipt 恢复，绝不重复收录。

验收：计划冻结、候选隔离、审核关联、blocking 分类、两次重写上限、计划轮换/最终章、旧 owner、
原子收录、暂停/冷恢复及 5/10 章连续运行全部有测试和真实 provider 证据。

## Phase C7 — Durable 润色、后台执行、通知与深链

交付：

- 批量润色选择已有章节起止范围，复用 candidate/review/commit/CAS/owner/checkpoint 协议；
  同一 project/branch 的 ghostwrite 与 polish 通过 durable claim 互斥。
- 润色支持 pause/resume/retry/cancel/冷恢复；现有内存 Map 不再作为状态源。
- 上下文按开关注入当前剧情、伏笔、角色/关系邻域、已确认决定；限制总体积并保存来源边界。
  plot stale 或中间章 unresolved 时同时向模型和用户显示明确警告。
- 查阅官方文档后接 `WorkSchedulerExtensionAbility`（API 12 基线内）与无头 executor；后台只
  claim/驱动同一 durable job，不复制生成/提交状态机。
- 进度、失败、完成通知携 projectId/branchId/jobId 深链，点击准确进入工作区 durable 阶段卡。
  排队、运行、等待系统窗口、暂停、失败、完成均来自持久 stage，不用 toast 模拟。
- 系统执行窗口不足时持久暂停点并等待下次调度/用户继续，不伪报完成；旧 worker 永久失去写权。

验收：代笔/润色互斥、润色部分失败、pause/resume/cold recovery、后台唤醒、进程回收、通知深链
有测试与真机证据；任何路径均不重复提交。

## Phase C8 — 产品终验与旧链清退

交付：

- 删除已无生产 caller 的 `NonStreamNovelModelAdapter`、字符串 delta/replace 事件、
  legacy fenced ask-user 新写路径和 Novel 私有 Markdown/滚动轮子；删除另列清单后精准执行。
- 删除旧单文件 Novel 生产写路径、内存 batch 真相源和并行入口；迁移 reader 仅在仍有真实旧数据
  需要时保留，并记录退出条件。修正文档、parity backlog、能力矩阵和实际测试数字。
- 真机覆盖：Quick Start、讨论工具、ask-user、真实流式、思考卡、整章候选、审稿、
  定向重写、采纳、版本/分支、设定编辑、ZIP 导入导出、TXT/Markdown/EPUB 打开、批量代笔、
  批量润色、停止/恢复、杀进程恢复、后台、通知深链。
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

只有上述链路、工作区/两域回归、lint、签名构建和真机证据全部成立，才可把 Harmony 小说标为“产品完整”。
