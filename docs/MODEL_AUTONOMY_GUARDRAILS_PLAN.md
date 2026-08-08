# 模型自主性护栏拆除计划（iOS）

Status: Active — 第一梯队（G1-G5）与第二梯队（G6-G10）已于 2026-08-08 经用户批准、实施并提交；§3 其余名实不符项待后续按真实复现处理
Created: 2026-08-08
Scope: 仅 iOS runtime + 共享 prompt/planner 层；G6 因共享函数删除对 Android GenerationHandler 做了最小跟随（经用户授权）。

## 实施状态（2026-08-08）

- G1/G5/G4/G2/G3 全部落地并提交。G3 的「角色目录外置存储」经裁决**有意跳过**（一次性自定义角色已满足需求，外置存储属过度设计）；G4 最终仅收口 SubAgent 的真实越权路径，没有扩张通用 run-scoped grant；G5 已补 fresh-install 默认值契约测试（`IOSSharedSettingsStoreProvidersWriteBackTests.testEnableWebSearchDefaultsToTrueForFreshInstall`）。
- 已知有意行为变化：`mcp_test`/`mcp_import_from_skill` 前台审批收紧为仅高风险自动批准可跳过（与普通 mcp_call 对齐），升级后用户可能遇到更多确认卡，发布说明需提及。
- `mcp_describe_tool` 的 2KB schema 截断已在后续深审中移除，按“完整 schema”契约返回；`file_read_selected` 的 run 级复用在当前生产路径近乎不可达，仅记录而未扩张通用权限模型。
- 验证：iOS `build-for-testing` 已通过；Chat/Background/SubAgent/Memory 八类定点 173/173，MCP/Board/DeepRead/MiniApp/Memory/Skill 相关集合 111/111；共享 Kotlin 编译与适用 JVM 门禁通过。尚未覆盖真机后台强杀、Keychain 持久化和真实 provider 长链任务。

## 第二梯队实施状态（2026-08-08）

- G6：删 `shouldGenerateDirectWidgetWithoutTools`/`needsVisibleStreamingFallback`（Android `GenerationHandler` 最小跟随）；重试改「保留草稿 + 追加补绘 notice」，不再剥工具/关 reasoning；placeholder 判定结构化（剥 script/style 后可见文本 <24 才算，含真实图形元素豁免）；非 SLIDES 的 full_html 放宽为完整 HTML；二次失败把 notice 替换为「可视化未能生成，已保留原始回答」终态。
- G7：`chatMaxToolResumeCount` 进 iOS 本地设置（默认 12，clamp 4-24，设置页 Stepper，读侧双端 clamp）；耗尽后注入「预算已用完」收尾提示、工具保留；后台 maxSteps=6 保持（无人在场电池预算路径，有注释）。
- G8：讨论模板 v5→v6 软化（删除对抗用户表述，保留「不进正典」安全句）；v5 逐字节归档进 acceptedVersions，旧 receipt 校验仍通（checker 已实测）；快照哈希更新。
- G9：工具结果裁剪留 `[tool output compacted]` 占位 + 去重计数 + handoff 末条注入移除说明 + 截尾注记（带预算保护）；新增 `IOSContextCompactionCoordinatorTests`。
- G10：KMP 默认 24/6000（Android 默认同步变化）；`memory_tool` 声明补 read/search/query/status——checker 发现执行层原全部退化为 list，已修：read 按 id、search/query 复用召回打分（top 10、硬顶 20）、status 只回摘要、list 加上限 50/单条截 500。
- checker 复核（第二梯队）：0 阻断，2 应修（memory_tool 语义、占位误杀+失败终态）均已修并补 7 个测试；建议项中 G9「无新 compact 时计数注记丢失」「truncationNotice 预算角落」记录在案未修（小缺口，占位标记仍在）。
- 验证已完成：删除项和最新 `ChatGenerationCoordinator.swift` 均进入成功的真实 `build-for-testing`；上述 Chat/Background/SubAgent/Memory 集合 173/173，Generative Widget 单类最终 36/36。设备端真实 provider、后台强杀与长文手感仍需另行验收。

## 0. 背景与判断框架

随着模型能力增强，仓库里一批 2023-2024 年思路的护栏（关键词启发式、声明层裁剪、硬编码角色/上限）正在从「防呆」变成「限制发挥」。本计划逐项回答五个问题：

1. **拆什么**（精确到代码位置）；
2. **这个护栏是强护栏还是弱护栏**——两个轴：
   - *强制力*：代码硬阻断（执行层 gate，模型无法绕过）vs 软约束（提示词/声明层，只影响模型能看到什么）；
   - *承重性*：拆除后是否暴露真实攻击面（SSRF、沙箱逃逸、凭据泄漏、不可信代码执行）。
   - 「强护栏」= 硬阻断且承重（原则上不拆）；「弱护栏」= 软约束或不承重（本计划的主要拆除对象）。
3. **为什么拆**（对模型自主性的具体伤害，附证据）；
4. **用什么替代**——区分三种结局：
   - *自然提升*：拆掉后不需要替代物，模型能力自然回归（声明层裁剪大多属于此类）；
   - *防线后移*：可见性放开，但执行层审批/沙箱原样保留，安全不降级；
   - *换机制*：用更精确的机制替换粗糙的启发式。
5. **用户价值**（用户得到什么、失去什么、是否需要新设置项）。

不涉及拆除的保留清单见 §4。名实不符（护栏在说谎）的修复见 §3。

---

## 1. 第一梯队：声明层/默认值的弱护栏（拆除即自然提升）

这一梯队的共性：**限制发生在「模型能看到什么」而非「模型能做什么」**。执行层的安全门（审批、沙箱、SSRF 防护）一个不动，因此拆除是纯粹的防线后移，安全等级不变。

### G1. workspace 写工具按用户消息关键词声明

**现状**（`iosApp/iosApp/ChatViewModel.swift:2884-2919`，调用点 `:2805-2807`）：`workspace_file_write/edit/move/delete` 是否出现在工具目录，取决于用户最后一条消息是否同时命中「写动作词」（保存/写入/创建/save/write…）和「目标词」（workspace/文件/.md…）。skill/MCP 场景另有第三张词表。iSH 工具同样在「内置执行」和「外部交接」之间按关键词二选一（`:2875-2882, 2899-2902`）。

**护栏性质**：弱护栏。软约束（声明层），不承重——真正的安全门是执行层的 `freshHighRiskGate` 前台审批（`IOSLocalToolExecutor.swift:299-307`）和路径防穿越（`DocumentAccessStore.swift:685-704`），两者都不动。

**为什么拆**：
- 用户说「把刚才聊的整理一下」「这个结论以后用得到」——没有关键词，模型连「我可以帮你存到工作区」都说不出口，因为它看不到工具。主动性被从源头上阉割。
- 关键词表是中英双语的静态列表，本质是用正则代替理解，而理解恰恰是强模型最擅长的事。
- 误判不对称：漏声明（模型无能）远比误声明（模型多看到一个工具）代价大。误声明的最坏结果只是模型发起一次写请求，然后被审批门拦下——这正是审批门存在的意义。

**替代**：无替代物，自然提升。写工具恒声明（与读工具对齐），三张关键词词表整体删除。执行层审批、路径净化、overwrite 保护原样保留。

**用户价值**：模型可以主动提议落盘、整理、归档，而不是等用户说出魔法词。用户失去的是「模型默认不知道能写文件」这层隐性保护，但写动作仍需前台审批（除非用户自己开了自动批准），实际保护不降级。无需新设置项。

**风险与对策**：模型可能在用户没要求时提议写文件，造成轻微打扰。对策：保留 `ChatContextSupport.swift:215-223` 的工作区策略提示（「除非用户显式要求否则不调写工具」）作为软引导——提示词管意愿，审批门管安全，各归其位。

**验证**：`ChatViewModelGenerationParamsTests` 中关键词门控用例改写为「恒声明」契约；补一条「无关键词用户消息下写工具仍声明」测试。

---

### G2. MCP 工具只注入 name+description、无 input schema，且 40 个封顶

**现状**（`iosApp/iosApp/ChatContextSupport.swift:272-290`）：已发现 MCP 工具以 `- server=X, tool=Y: description` 纯文本注入，上限 40 个（`.prefix(40)`）；`IOSMcpClient` 不持久化 inputSchema（`IOSSkillMcpTools.swift:231` 注释自承 "No persisted input schemas"）。模型调用必须走 `mcp_call {server, tool, arguments}` 盲猜参数。

**护栏性质**：弱护栏。这不是安全设计，是上下文预算时代的妥协。安全门（server/tool enabled 四重校验、high-risk 审批、`IOSMcpManager.swift:92-115`）与 schema 注入无关。

**为什么拆**：这是全仓对「模型自主连接 MCP」伤害最大的一处。用户已经可以通过 skill-creator 自主接入 MCP（已有能力），但接进来之后模型对参数化工具基本只能盲调——40 个以外的工具甚至完全不可见。自主性的最后一公里断在这里。

**替代**（换机制，不是自然提升）：
1. `IOSMcpClient` 发现工具时持久化 inputSchema（JSON Schema 原文，带每工具 ~2KB 截断）；
2. 注入改为两层：目录层（server/tool/description，不封顶或提高到 100）+ 模型按需调用新增的 `mcp_describe_tool {server, tool}` 拉取完整 schema；
3. 热门路径优化：当某 server 只有少量工具时可内联 schema，省一次往返。

**用户价值**：MCP 从「能连上」变成「能用好」。用户配置的任何 MCP 服务器（文件系统、数据库、线性看板）模型都能正确传参。代价是每轮注入增加约几百 token 的目录文本，可忽略。

**风险与对策**：schema 可能很大（如复杂 JSON Schema），对策是按需拉取 + 截断；schema 内容来自外部服务器，属不可信文本——沿用记忆注入同款「untrusted context」标注即可。

**验证**：`IOSSkillMcpToolsTests` 补 schema 持久化/拉取用例；`ChatContextSupport` 注入契约测试更新。

---

### G3. 子代理角色锁死、未知角色静默回退、上限写死

**现状**（`iosApp/iosApp/SubAgentRunner.swift:26-123`）：6 个角色硬编码；`resolve(roleId:)` 对未知 id **静默回退 explorer**（`:100-103`）；maxTurns 2-4、timeout 180-300s、outputBudget 6-12K 全部写死在角色描述符里；`subagent_dispatch` 声明 schema 只有 `objective`/`role_id`（`Tool.kt:475-487`），模型无法传 `tool_scope`（裁剪逻辑因此是死代码）；自定义角色 UI 明确不可用（`SubAgentRoleView.swift:210-217`）。

**护栏性质**：弱护栏。软约束（能力面裁剪）+ 不承重（子代理只读白名单 `readOnlyParentToolNames` 和防嵌套 denied 集是真正的安全门，均保留）。

**为什么拆**：子代理是模型分解复杂任务的核心手段。现在模型想派一个「对比三个 provider 定价」的任务，只能在 6 个写死的角色里挑一个气质不符的；填错 roleId 也不报错，父模型基于错误假设继续推理。角色上限（fixer 只有 2 轮）对稍复杂任务直接不够用。

**替代**（换机制）：
1. 未知 roleId 返回结构化错误（列出合法 id），不静默回退；
2. `subagent_dispatch` schema 增加可选参数：`custom_role {name, lens, system_prompt}`、`max_turns`、`output_budget_chars`（clamp 到 2-8 轮 / 4-24K 字符，仍防失控）；
3. 角色目录从硬编码迁到可扩展存储（复用 skill 目录模式），内置 6 角色作为 seed 保留；
4. 删除 `tool_scope` 死代码路径或真正暴露给模型（二选一，建议后者：让模型声明「这个子代理只需要搜索」）。

**用户价值**：模型可以按需组建临时专家（「派一个懂 SwiftUI 动画的评审」），复杂调研任务不再被 2-4 轮掐死。用户得到的是更强的问题分解能力；失去的是「子代理行为完全可预测」——但只读白名单保证可预测性的安全部分不变。可选设置项：子代理单轮预算上限（进 ExecutionSettings）。

**风险与对策**：自定义角色 prompt 来自模型自身，不存在提示注入升级（子代理工具面不变）；预算参数 clamp 防失控；I-5 防打转守护和超时任然生效。

**验证**：`SubAgentRunner` 现有测试更新 + 新增「未知 roleId 报错」「自定义角色 clamp」用例。

---

### G4. `allowOncePerRun` 被 UI 阉割，run 级复用形同虚设

**现状**：数据模型有 `allowOncePerRun`（`IOSPermissionModels.swift:130`），gate 也声明 `allowRunScopedReuse=true`（reusableSensitiveGate），但 UI 把它从可选列表过滤掉（`PermissionsApprovalView.swift:170`）、显示时归一为「每次询问」（`:188, 197, 309`）。结果：search、workspace 读、webmount 观察这类**敏感但不危险**的工具，一个 10 步任务要弹 10 次确认卡。

**护栏性质**：名义上是强护栏（执行层硬阻断），但承重的不是「每次问」而是「问过」——逐次重复询问不产生额外安全，只产生审批疲劳（用户开始无脑点同意，反而削弱所有审批）。

**为什么拆**：审批疲劳是真实的安全负债。run 级复用是业界标准解法（Claude Code、Cursor 均采用）：用户在一次任务上下文里批准一次，模型在任务内自主推进。

**替代**（换机制，主要是恢复已有机制）：UI 恢复 `allowOncePerRun` 选项（reusableSensitiveGate 类能力：search、workspace 读、subagent、council、file_read_selected）；高风险类（workspace 写、memory 写、MCP、iSH、webmount 破坏性）**不开放**，仍每次确认或高风险自动批准。

**用户价值**：多步研究型任务从「点 10 次确认」变成「点 1 次」。用户保留随时撤销权（设置页改回每次询问）。这是本计划中用户体感最强的一项。

**风险与对策**：run 内复用的边界必须清晰——grant 绑定 runId，run 结束即失效（`DocumentAccessStore.swift:1701-1723` 的授权书模式已有先例）。审批卡文案需明示「本次任务内不再询问」。

**验证**：权限 store 契约测试 + 审批卡 UI 文案测试。

---

### G5. `enableWebSearch` 默认 false

**现状**（`core/types/src/commonMain/kotlin/app/amber/core/settings/Settings.kt:60`）：默认关闭时搜索工具根本不声明（`ChatViewModel.swift:2795-2798`）。

**护栏性质**：弱护栏。不是安全门（每次搜索本就有审批卡和 SSRF 三层防护），是产品默认值保守。

**为什么拆**：联网是 agent 的基本感官。默认关闭意味着新用户的模型是「盲人」，而大多数用户不会发现这个开关。

**替代**：自然提升——默认值改 true。审批门（每次询问/run 级复用，见 G4）和 SSRF 防护全部保留；无搜索 provider 配置时的内置 DDG/Bing 回退链已存在（`IOSSearchExecutor.swift:413-496`）。

**用户价值**：开箱即用。顾虑（搜索消耗、隐私）由审批卡承接——用户不批准就不联网，决定权仍在用户。

**验证**：Settings 默认值快照测试更新。

---

## 2. 第二梯队：行为层护栏（价值大，需逐个裁决）

### G6. show-widget 强制重试抹掉可见草稿；配套的三处误判源

**现状**（`iosApp/iosApp/ChatGenerationCoordinator.swift:2011-2042, 545-568`）：判定「该有 widget 而没有」时，删除用户正在看的最后一条 assistant 消息，注入修复 system 提示静默重跑一次；重试同时剥光全部工具、强制 reasoning off。误判源有三：
- placeholder 子串匹配（含 "TODO" 即拒，`IOSGenerativeWidgetParser.swift:172-181`）；
- full_html 必须含 `<section class="slide">`（单页海报被误杀，`:1164-1166`）；
- 关键词路由判定「直接画图」时**清空整个工具目录**（`GenerativeUiPlanner.kt:159-174` + `ChatGenerationCoordinator.swift:523-524`）。

**护栏性质**：行为层强干预 + 不承重（widget 安全由 sanitizer 负责，`:46-138`，不动）。这组机制是「替模型做决定」的典型：路由、格式、重试全部由代码裁决。

**为什么拆/改**：强模型给出一段合理文字但没带 widget 时，整条回复被抹掉重跑——用户看到草稿消失、token 双倍消耗；关键词路由误判（「给我画个流程图的参考资料」）会剥夺全部工具。

**替代**（换机制，分四步）：
1. 重试改「追加修复」：保留原草稿，追加一条可见的「可视化未生成完整，正在补绘」状态，第二轮输出作为新内容接续，不删历史；
2. 重试不再剥工具、不关 reasoning（让模型用工具补救）；
3. placeholder 判定从子串改为结构化（widget_code 去标签后有效文本 < 阈值才视为占位）；
4. 关键词路由**只注入提示、不清空工具目录**——`shouldGenerateDirectWidgetWithoutTools` 的 tools 清空分支删除，保留 prompt 引导，是否用工具还给模型。

**用户价值**：回复不再神秘消失；画图请求里模型仍可查资料再画。失去的是「必出图」的强硬保证——第二次仍失败时现在会留下文字回答 + 可见的失败状态而非静默重来，这其实是更诚实的交互。

**验证**：`IOSGenerativeWidgetParserTests`、前台 stale-run 定点测试更新；真机验收「流程图请求仍稳定出图」。

---

### G7. 工具循环 6 次封顶 + 耗尽后强制无工具收尾

**现状**（`ChatGenerationCoordinator.swift:672, 2064-2067, 2763-2775`）：前台 `maxToolResumeCount=6`，第 6 次后续跑清空工具目录强制裸收尾；引擎 `maxSteps=8`（`IOSAgentToolEngine.swift:506-509`）。

**护栏性质**：弱护栏（防浪费，非防攻击）。真正的防打转是 `IOSToolLoopGuard`（同签名 2 提醒 3 停），它与步数无关，保留。

**为什么改**：6 次对「搜索→读页→再搜→对比→写文件」链式任务偏紧；耗尽后强制无工具收尾可能让任务死在最后一步（差一次写文件却没了工具）。

**替代**（换机制）：上限参数化进设置（默认从 6 提到 12，clamp 4-24）；耗尽时把选择权给模型——注入「预算已用完」系统提示让模型用最后一轮总结，而非静默清工具（模型至少知道发生了什么，能向用户解释）。

**用户价值**：长链任务成功率提升；任务失败时得到模型解释而非突兀收尾。代价：单轮 token 消耗上限提高，由用户设置自控。

**验证**：`ChatViewModelGenerationParamsTests` 上限契约更新；loop guard 用例不变。

---

### G8. 小说讨论模式 HARD RULES 顶掉用户明确指令

**现状**（`iosApp/iosApp/NovelCreation/NovelPromptCatalog.swift:177-179`）：「即使用户说 go ahead 也不许写超过 3 段示例散文」。

**护栏性质**：弱护栏（提示词层），且是提示词**对抗用户意图**——产品在替你决定「你不是真的想现在写」。

**为什么改**：这是用 prompt 实现的状态机，而状态机应该由代码保证（讨论模式的产物不进正文已由收录流程保证，见 Phase 0-2 的 canStart/reducer 门禁）。prompt 层只需要解释状态，不需要对抗用户。

**替代**（换机制）：改写为状态说明 + 软引导：「当前是讨论模式，产出不会被收进正文；若用户确认要动笔，建议引导其切换到写作流程」。代码层的收录门禁（`canStart(.prose)` 等）不变，防误收正文的保护不降级。

**用户价值**：用户在讨论里让模型「先写一段试试」可以如愿；是否进正文的决定权仍在用户（收录动作本来就需要人手确认）。

**验证**：`NovelPromptCatalogTests` 快照哈希更新；共创门禁测试不动。

---

### G9. 上下文压缩对历史的静默加工缺乏标注

**现状**（`iosApp/iosApp/IOSContextCompactionCoordinator.swift`）：`editPreparedContext` 发送前静默裁剪/清空旧消息的工具结果（`:862-865`）；`auto_fit` 二次压缩可能压掉最近轮次（`:184-222`）。模型看到被加工的历史却不知道哪里缺了。

**护栏性质**：这不是护栏，是上下文管理的副作用——但它造成的「模型基于残缺历史自信作答」是自主性事故的高发源。

**替代**（换机制，增强而非拆除）：压缩 handoff 消息中显式标注「N 条旧消息的工具结果已被移除，如需原始内容请重新调用工具」；被裁剪的工具消息原位留占位符（`[tool output compacted]`），模型可识别空洞并主动重读（workspace 文件、搜索结果都可重取）。

**用户价值**：长会话后半段回答质量提升，模型不再把「没看到」当「不存在」。纯增益，无用户代价。

**验证**：compaction 契约测试补标注断言。

---

### G10. 记忆召回偏保守 + 被动注入为主

**现状**（`ChatContextSupport.swift:33-34, 170`）：默认 12 条/2000 字符、单条截 500。

**护栏性质**：弱护栏（上下文预算妥协）。

**替代**（自然提升 + 小改）：默认值上调至 24 条/6000 字符（clamp 上限不动）；确认 `memory_tool` 的 list/read/search 动作完整声明给模型，让模型从「被动等注入」转向「主动召回」——配合 G2 的按需 describe 模式，这是同一个设计哲学：**目录常驻、详情按需**。

**用户价值**：跨会话连续性变好（「上次说到哪」类体验）。代价是每轮注入 token 增加约 1-2K，可由设置调低。

**验证**：`IOSMemoryRecallPolicyTests`（待其 API 漂移修复后）与召回打分用例更新默认值。

---

## 3. 第三梯队：名实不符修复（护栏在说谎，不修则所有讨论失去基础）

这些不是拆除对象，但会让用户和模型对边界形成错误预期，且修复成本极低：

| 项 | 位置 | 问题 | 修法 |
|---|---|---|---|
| capability gates 恒 true、set 为 no-op | `IOSSharedSettingsStore.swift:98-105` | 设置页开关是装饰 | 要么接真存储，要么从 UI 移除 |
| `allowGlobalAutoApproval=false` 与执行器行为矛盾 | `IOSPermissionModels.swift:1657-1673, 1900-1905` | UI 显示 "Auto-approval ignored"，实际放行 | 统一口径：字段如实反映执行行为 |
| iSH `autoApproveHighRisk` 选项永远无效 | `IOSLocalToolExecutor.swift:259-271` | UI 提供选项，执行器无视 | 明确意图：要么 honor 策略，要么 UI 隐藏该选项 |
| `skill_validate` 只查三字段非空 | `IOSSkillMcpTools.swift:152-177` | 「校验通过」无保证，误导模型 | 对齐 frontmatter 解析 + 正文非空 + 名称规范，或改名 `skill_check_basic` |
| 子代理独立页提示词列出不可用工具 | `SubAgentsView.swift:54-61` | 引导模型调注定失败的工具 | 提示词按实际 executors 生成 |

## 4. 明确保留的强护栏（不动）

以下为硬阻断且承重的真实安全边界，模型再强也不拆：

- workspace 路径防穿越、写操作前台审批门（freshHighRiskGate）、不静默覆盖；
- SSH host key pinning、探测阶段不发真密码、Keychain 凭据；
- 公网 SSRF 三层防护（URL 校验 + DNS 重解析 + 重定向逐跳复验 + 禁 HTTPS 降级）；
- MiniApp 全套沙箱（CSP、HTML 静态校验、图片代理、敏感 API 逐次确认、50 次/天/应用配额、launch 限频）——它跑的是模型生成的不可信代码；
- 记忆注入「untrusted context」标注、写审批 + expectedUpdatedAt 防陈旧、损坏停写保护；
- WebMount 域名白名单 + 逐导航过策略 + 敏感字段脱敏；
- `IOSToolLoopGuard` 防打转、`parseInputStrict` fail-closed、输出截断先于工具执行；
- 后台生成期间审批类工具一律 deny（无前台在场人，审批无意义）。

## 5. 实施顺序与门禁

按「风险递增、依赖先行」排序，每刀独立可验证：

1. **G4 + G5**（纯配置/默认值，风险最低，用户体感最强）；
2. **G1**（删词表，声明恒在；定点测试改写）；
3. **G2**（MCP schema 持久化 + describe 工具，机制新增但隔离性好）；
4. **G10 + G9**（默认值 + 标注，行为不变只增信息）；
5. **G3**（子代理 schema 扩展 + 角色外置，改动面最大放后面）；
6. **G6**（widget 重试交互变更，需真机验收「画图仍稳」）；
7. **G7 + G8**（上限参数化、prompt 改写，最后做因为要动小说门禁快照）；
8. **§3 名实不符**：随做随修，不单独排队。

每刀门禁：对应定点测试改写后红→绿；受影响回归集（`IOSSkillMcpToolsTests`、`ChatViewModelGenerationParamsTests`、`IOSGenerativeWidgetParserTests`、`NovelCollaborationModeTests` 视刀而定）全绿；双 scheme `xcodebuild build` 成功。G6 额外需要真机真实 provider 验收一轮画图/幻灯片请求。

## 6. 总体用户价值账

**用户得到**：模型从「等指令的执行器」变成「能看、能记、能分解、能主动提议的协作者」——能主动落盘（G1）、正确使用 MCP（G2）、按需组专家（G3）、一次批准连续推进（G4）、开箱联网（G5）、回复不再神秘消失（G6）、长链任务不死（G7）、长会话不自欺（G9）。

**用户失去**：几乎不失去真实保护——所有写/网络/不可信代码执行仍需审批或沙箱。真正的变化是「默认体验从谨慎转向能干」，谨慎模式仍可通过设置逐项恢复（搜索开关、每次询问、低循环上限都在）。

**新增的授权责任**：G4 的 run 级复用和 G7 的上限上调把更多决定权交给用户设置，这要求审批卡和设置页文案把「放开了什么」讲清楚——文案工作是本计划不可省的一部分，不是可选打磨。
