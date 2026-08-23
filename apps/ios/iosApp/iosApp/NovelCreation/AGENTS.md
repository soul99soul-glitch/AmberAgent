# Novel Creation Instructions

本文件适用于 `iosApp/iosApp/NovelCreation/`。先遵守仓库根 `AGENTS.md` 与 `iosApp/AGENTS.md`。

## Domain Authority

- 小说项目文档是人物、关系、世界观、剧情状态、章节、分支和运行记录的唯一权威来源；普通 Chat conversation、Memory、Lorebook、Workspace 和 UI cache 都不能覆盖它。
- `DefaultNovelCreation` actor 与 repository/reducer 链负责领域 mutation 和持久化。View/ViewModel 负责意图与呈现，不得绕过领域层直接伪造成功状态。
- 候选稿不是正式正文。只有 collect/replace/polish 等既有事务成功提交后，内容和派生剧情状态才可进入当前分支。讨论里的 `novel_revise_chapter` 必须先出审批卡，作者确认后再走既有 `saveManualEdit`，不得另开写通道。`novel_delete_chapters` 同样先出审批卡，确认后走既有 `deleteChapterFromManuscript`，可抽中间章。
- 手动编辑资料后，下一次 prompt/injection 必须读取已持久化的当前项目快照；不得继续使用生成建议、旧 ViewModel snapshot 或历史分支的缓存。
- receipt、输入证据与提交校验必须复用同一个 canonical projected-state 构造，不能在不同阶段各自拼装近似内容。

## Generation And Lifecycle

- durable run 与可见流式呈现是两个层次，但必须共享同一个 run identity。页面退出只解除观察，不取消 App 级运行。
- chunk 按单一 FIFO/pacer 发布；不要再造滚动 owner、直接写 offset 或在终态一次替换剩余长文。
- completed、interrupted、failed 和 persistence-blocked 都必须先收拢可见 partial，再落到对应 durable terminal/retry 入口。终态排空期间不可继续显示可用的 Stop，也不可放开下一次 mutation。
- cancel、background expiration、恢复、重试和旧回调都要核对 project/session/run identity；旧 run 不得结束或覆盖新 run。
- Quick Start、讨论、正文、润色、剧情同步和连续性检查不是同一种输出协议。只在对应路径使用结构化 decoder、工具循环、远端 background response 或 fact transaction。
- `needsSync` 时允许讨论规划，禁止正式正文生成、整章重写和润色；注入层与 reducer/`canStart`/retry 门禁必须一致，不能只靠 UI 文案劝阻。
- 项目 `collaborationMode`（共创/代笔）与分支本章合同经 reducer 落盘；切代笔前查 `NovelGhostwriteReadiness`（可不强制合同）；代笔写整章必须已确认合同；有确认合同时整章注入绑定 digest。代笔自动收录须走既有 collect 事务；合同验收与连续性审计走 `NovelModelRole.review`。
- 分支「下一弧」是有界软方向（最多 8 条），只注入整章 prose；不得做成完整离屏世界模拟器。代笔看板只读回执留在项目控制面板内，不新建独立页。
- provider 的 reasoning/thinking 只走 presentation 通道（`NovelModelEvent.reasoningDelta` → `NovelRunEvent.reasoningDelta` → 气泡 `ChatReasoningCard`），用于可见思考流与无输出计时刷新；**不得**写入 `partialContent`、sidecar、候选稿、collect/adopt、durable session 正文或结构化 JSON 解码输入。Manuscript 仍只吃 text delta/replacement。

## Input And Presentation

- 涉及中文输入法的保存、提交、改名、sheet 关闭和焦点切换，先通过 `NovelTextInputCommitter` 提交 marked text，再读取绑定值；不要依赖最后一次 `onChange` 恰好先到达。
- 不要在调用 committer **之前** 清空 `FocusState` 或自行 `resignFirstResponder`：先失焦会丢掉 marked text，随后读到的绑定会缺最后一次输入。主 composer 继续用 `ComposerInputController.committedText()` 直读 UITextView。
- Form 里多字段中文编辑（本章计划、往后几章、润修 brief 等）优先用 `NovelIMETextField` / `NovelIMETextEditor` + `NovelIMEFieldBank`：保存时 `commitAll()` 同步把 UIKit 文本写入绑定。纯 SwiftUI `TextField` 在 composition 下绑定仍可能丢字。
- workspace 快照回填本地编辑字段时，若字段 dirty 或仍有 marked text，不得覆盖用户输入。
- 资料建议确认面必须允许用户在写入前编辑关键字段；写入后编辑必须持久化并进入后续 Agent 上下文。
- 复用现有三入口信息架构（创作 / 正文 / 设定）和现有视觉 token。按钮视觉可以紧凑，但交互热区至少 44pt；固定单行控件中的动态文案不得挤压、折行或覆盖相邻控件。
- 小说列表复用 Native Timeline 的手势/跟随判定。用户浏览历史时冻结不可见流式尾部，回到底部后再追平；不以 GeometryReader 补偿或隐式动画掩盖高度变化。

## Verification

先跑最小行为测试，再按影响面组合以下套件：

- 会话、流式与终态：`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NovelGenerationLifecycleTests`
- 项目状态与注入：`NovelManualEditSyncTests`、`NovelInjectionPlannerTests`、`NovelFactTransactionLifecycleTests`
- 结构化输出与真实适配：`NovelStructuredOutputTests`、`NovelStructuredModelExecutorTests`、`NovelLiveModelAdapterTests`
- UI/接线：`NovelCreationPresentationTests`、`IOSNovelCreationWiringTests`、必要时 `IOSSettingsWiringTests`

触碰滚动或共享 pacing 时，同时执行 `iosApp/AGENTS.md` 的 Native Timeline/viewport 门禁。中文输入法、键盘、长文追底、后台切换和 Files 交互最终仍需真机或真实 provider 证据。
