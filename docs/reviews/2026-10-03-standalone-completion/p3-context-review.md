# P3 独立 review：上下文、实际模型预算与临时资料选择

审核范围：资料注入规则、领域 context_builder/model_running/creation、Chat novel_context_policy/novel_interactive_adapter、Entry 适配器与上下文预览入口。审核者未实现本模块；本次只写本报告，未修改实现。已读取 harmony-arkts skill。结论基于当前源码、真实领域／Chat 适配器管线和聚焦测试；截图验收边界见文末。

## 发现及修复复查

### 1. P1：预览的固定指令与真实工作区发送不同，已修复

最初 `creation.previewContext()` 调用 `buildNovelContext(..., runKind=null)`，而 `NovelWorkspacePage.send()` 根据模式发送 `discussion`、`prose_continuation` 或 `prose_whole_chapter`。前者选择 context_builder 内旧短指令，后者选择 prompt_catalog 的完整模板，导致同一输入的预览内容及预计 token 与实际请求不同。

实际管线复现使用真实 repository → creation → NovelInteractiveAdapter → provider spy，三种模式分别得到 `equal=false`。例如整章预览为“创作完整的新一章…”，实际请求为“你是小说章节写作引擎…”；讨论、续写同样不一致。既有集成测试曾把 generate 的 runKind 传 null，未覆盖真实工作区调用。

实现者现将模式映射收口为 `novelComposerRunKind`，creation 预览与工作区发送均调用它。原输入、领域事实、模板与模型选择保持既有边界。独立复跑三种实际 Workspace runKind，system 内容逐字相等，均得到 `equal=true`。修复后的集成测试同时检查强制包含／排除材料及预览只读。

证据：

- `harmony/deepread/src/main/ets/novel/context_builder.ts:22`：共享模式映射。
- `harmony/deepread/src/main/ets/novel/creation.ts:1028`：预览派生 runKind，再构建 context。
- `harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets:1394`：真实发送使用同一映射。
- `/tmp/amber-p3-review-preview-final.log`：独立实际管线三种模式复验。
- `harmony/chat/src/test/novel_interactive_adapter.test.ts`：actual Workspace send kinds 三模式子例。

### 2. P2：智能匹配使用了别名／标签，但模型没有收到它们，已修复

最初材料 renderer 只发送 kind、title、content。角色“阿布”的别名“小布”可触发智能匹配，但实际材料块未包含别名映射；iOS 的 `renderMaterial` 明确发送 Aliases 与 Tags。

实现者现只在共享 renderer 补入角色 Aliases 和排序后的 Tags，预览、预算估算和实际发送自然共用。真实发送集成测试确认 `Aliases: temporary-alias`、`Tags: a-tag, z-tag` 均存在，不仅检查 score 或材料 ID。

证据：`harmony/deepread/src/main/ets/novel/material_injection.ts:72`；iOS 当前源码 `iosApp/iosApp/NovelCreation/NovelInjectionPlanner.swift` 的 `renderMaterial`。

## 调用链与预算复核

| 检查点 | 当前链路与证据 | 结论 |
|---|---|---|
| 资料优先级 | effectiveNovelMaterials → classifyNovelMaterials → required/optional sections → context policy | 分支覆盖先于本次排除；always、强制包含为受保护块；smart 按相关性降序，再按 kind/title/id 确定顺序 |
| iOS score 对照 | 当前 iOS title/alias 命中加 100、去重 tags 每项加 20、正文查询词累计上限 20；Harmony 同规则 | 未以 updatedAt 等无关字段充当优先级 |
| 实际模型预算 | AppContainer 解析真实 runtime 模型窗口／输出上限 → Entry wrapper → adapter resolveNovelMaxOutputTokens → createNovelContextPolicy | 预览与实际发送共用配置解析、预算与估算函数；无固定 16k 伪预览 |
| 必要事实与超窗 | 必要 sections＋本次完整输入先 assertBudget；可选块整块取舍；provider 前再次校验实际输入与 tools | 必要资料不按 48k 字符截断；超窗明确失败，未调用 provider |
| 历史保留 | canonical history/checkpoint 保持完整；只裁剪发送副本 | 普通发送保留完整选中轮次；归档仅过滤发送副本；检查点不随预算丢失 |
| 工具续接 | continuationToolId 对应的来源 user 和所在整轮列为 required；执行结果保存后再续发 | 待答轮次即使命中归档过滤也完整保留；本轮工具结果过大时停止后续发送，原结果仍保存在 checkpoint |
| system 兼容 | guardProvider → mergeNovelSystemMessages → provider | 工具提示、领域 system 与动态 system 合并为首条，保留其文本 parts；Claude／Google／Responses 不会只读到一部分 |
| 本次选择 | PreviewPage 以 projectId/branchId 返回 → Workspace reload 后校验归属 → generate 传 overrides → 清空本次选择 | 不写 material 或项目设置；切换项目／分支不会沿用旧选择；选择只作用于下一次普通创作 |

补充实测：使用真实 adapter/tool loop、500 token 模型窗口、100 token 输出上限、tool system prompt 与真实工具定义，预览和发送共用 **400 token 输入预算**。预览估算为 79 token，实际首轮正文＋工具定义估算为 41 token，选出的非 system 输入内容一致，实际只发送一条合并 system 且包含工具提示，canonical 历史原两条保留。差值是预览计入最长 loop 提示的保守预留及估算消息边界，并非 provider 返回的实测 token 数；不能把预览数字报告为 wire 精确 token。最终 provider guard 核验实际组装后的消息和本步定义。

辅助审稿／状态同步的分块预算以及 P5 冻结润色角色旁路不在本次新增设计范围，本报告不据此扩展接口或增加防御层。

## 验证

独立复跑：

- `novel_interactive_adapter.test.ts` ＋ `novel_request_config.test.ts`：**30/30 通过**，日志 `/tmp/amber-p3-review-adapter-final.log`。
- `novel_material_injection.test.ts` ＋ `novel_context_builder.test.ts`：**15/15 通过**，日志 `/tmp/amber-p3-review-domain-final.log`。
- 三种真实 Workspace send kind 的额外管线复验：全部 system 文本相等；日志 `/tmp/amber-p3-review-preview-final.log`。
- 实际工具提示／工具定义预算管线额外复验：完成，400 输入预算内发送，合并 system、正文取舍和完整 canonical 历史符合上述结论。

额外复验脚本仅在 `/tmp/amber-p3-review-preview.ts` 与 `/tmp/amber-p3-review-tool-budget.ts`，未写入产品或替代持久测试。

## UI 与阶段收口边界

预览页源码已检查：V1 状态、主题 epoch 重挂载、20 vp 左右边距、12 vp 内容区间距、12 vp 资料卡内边距、至少 44 vp 选择控件及 48 vp 应用控件；文字显式左对齐，预算错误呈现，忙碌状态禁用选择；异步预览以 token 与 alive 阻止旧结果覆盖新选择。工作区入口、返回参数消费和发送后的单次清理已接通。

本审核没有操作设备，也没有把源码尺寸当作截图证明。最终 P3 安装包的亮／暗主题、长标题／大字号下的错位、换行、按钮间距和返回后单次选择提示，仍由 root 的阶段设备截图及独立 UI review 收口。**逻辑 review 的两个已复现问题均已修复并复验；阶段整体通过需并入该 UI 证据。**
