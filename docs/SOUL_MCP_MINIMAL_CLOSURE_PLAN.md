# Amber Soul / MCP 最小闭环实施计划

> **Status:** Completed
> **Scope:** 在现有 Skill 安全发布闭环上，补齐 iOS Soul 自更新与 MCP 受控接入；不建设通用自进化平台
> **Baseline:** 以开工时实时代码、Git 状态和 [`PROJECT_STATE.md`](PROJECT_STATE.md) 为准；本文不把历史测试记录当作当前验证
> **Supersedes:** 不恢复已删除的 `SELF_EVOLUTION_AND_HOT_RELOAD_PLAN.md`，也不恢复其中的通用 Evolution / Experience / Evaluator / Promotion 架构

## 1. 最终结论

Amber 接下来只需要补两个小闭环：

1. **Soul**：Amber 可以从固定 Workspace 文件提出新的核心指令，用户看到 diff 后一次批准；应用前用 hash/CAS 防止候选或当前版本已经变化，并保留一个可回退版本。
2. **MCP**：修正现有权限策略没有真正约束网络执行的问题；把现有 `mcp_import_from_skill` 改为“本地准备 → 一次批准 → 复核 → 临时连通测试 → 一次写入”。

Skill 已经具备“只读 preview → 显式批准 → base/candidate CAS → 原子应用 → 单槽 previous 回退”，本轮只跑回归，不再重做。

这不是 Evolution Engine。完成后 Amber 具备的是三个**可用、可理解、可恢复的扩展闭环**，而不是自动评价自己、自动发布自己或无限重写运行时。

```text
Skill: Workspace package -> preview/hash -> approval -> CAS apply -> previous rollback
Soul:  /workspace/SOUL.md -> preview/hash -> approval -> CAS apply -> previous rollback
MCP:   installed Skill/mcp.json -> local preview/hash -> approval -> recheck/test -> batch add
```

## 2. 为什么这些改动是必要的

| 领域 | 当前事实 | 分类 | 本轮处理 |
| --- | --- | --- | --- |
| Skill | 已有稳定 package hash、只读 preview、一次批准、CAS、原子发布和单槽回退 | 已闭环 | 只回归，不重构 |
| iOS Soul | 共享设置有 `agentSoulMarkdown`，Android 会消费；iOS 真实请求仍未消费 | 真断链 | 接入 iOS 请求组装 |
| 默认 Soul | 共享默认正文仍含 Android 身份和 Android 专用工具说明 | 真平台错配 | 拆成平台中立核心 + Android 专用运行说明 |
| Soul 自更新 | 没有模型可用的候选、批准、CAS、应用、回退入口 | 真能力缺口 | 新增一个固定入口 `soul_import` |
| MCP 权限 | 设置页有 `ios.mcp.tool_call`，但执行路径仍可能把 MCP 一律视为启用 | 真策略断链 | 统一读取既有权限 owner，并在执行前复核 |
| MCP import | 现有导入会直接向 live config 逐项写入，缺少候选绑定和全包失败语义 | 真闭环缺口 | 改为准备后一次批准、临时测试和 batch add |
| `mcp_call` 常驻 fallback | iOS 的 resident policy 已包含 `mcp_call` | 正常 fallback | 保留，不改 |
| 新 `mcp__*` 通常下一 run 才刷新 | 当前 run 仍可用 `mcp_call` | 正常 fallback | 不做动态热加载 |
| MCP OAuth / stdio | 当前移动端范围未支持 | 已知能力边界 | 不在本轮实现 |
| Streamable HTTP 版本兼容 | 是独立的协议兼容问题 | 真问题但非本闭环前置 | 单列后续，不夹带实施 |

## 3. 成功标准

### 3.1 Soul

- iOS 前台 Chat 和既有后台/子线程请求组装都只注入一次当前 `agentSoulMarkdown`。
- 默认 Soul 不再声称“Android assistant”，也不再引用平台不存在的工具。
- Android 现有行为不因默认正文平台化而丢失；Android 专用工具说明留在 Android 自己的 prompt 组装层。
- 模型只能从固定路径 `/workspace/SOUL.md` 发起 `soul_import`，不能指定任意设置字段或任意磁盘路径。
- 审批前只做本地读取、校验、diff 和 hash，不改设置、不发网络请求。
- 用户只批准一次；批准后重新读取候选并复核 candidate hash，同时复核当前 Soul 的 base hash。
- 任一 hash 变化都拒绝应用，并明确要求重新生成预览。
- 成功应用后保留一个 previous；回退时再次确认当前版本仍是上次应用版本，避免覆盖用户之后的手工修改。
- 新 Soul 从批准后的**下一次模型请求**生效，不为本轮新增 run snapshot 或 prompt 版本状态机。

### 3.2 MCP

- `ios.mcp.tool_call = Disabled` 时，`mcp_call`、`mcp__*`、`mcp_test` 和导入批准后的临时测试都不能发出网络请求；全局高风险自动批准不能绕过它。
- `Ask` 继续逐次审批；普通自动批准只影响普通 MCP 调用，不能跳过 `mcp_import_from_skill`。高风险自动批准可以跳过导入卡，但仍须走 digest / 连通测试 / CAS；`ios.mcp.tool_call=Disabled` 仍硬拒绝网络。
- `mcp_list` / `mcp_describe_tool` 继续作为本地只读能力，不因网络权限关闭而失效。
- `mcp_import_from_skill` 审批前只读取已安装 Skill 内的 `mcp.json`，严格解析、生成稳定 digest 和脱敏 preview，不写 live config。
- 用户批准后重新读取同一文件并复核 digest；再用临时 client 逐个 `connect -> listTools -> disconnect`，不发布状态、不持久化发现目录。
- 所有 server 都测试成功、且名称冲突预检通过后，才一次性写入 live config；任何一个失败都不留下部分 live server。
- 不新增 candidate 数据库、candidate ID、TTL、`tested_digest` 模型参数或 MCP 版本历史。

## 4. 实施顺序

### Phase 0：开工前边界确认

这是强制停止点，不是形式检查。

1. 读取根 `AGENTS.md`、`iosApp/AGENTS.md`、`docs/AGENTS.md`、`docs/PROJECT_STATE.md`。
2. 执行 Bridge mailbox 读取、`git status --short --branch`、本轮相关文件的 `git diff`。
3. 确认 Skill 安全发布链仍完整存在：
   - `skill_import` 只读准备；
   - stable package hash；
   - base/candidate CAS；
   - 一次显式批准；
   - 原子发布；
   - 单槽 previous 回退。
4. 当前工作树含有删除通用自进化 harness 的并发清理。不得恢复被删的 Evolution、Experience、Evaluator、Policy、Metrics、Receipt 或大型测试文件。
5. 若相关文件存在无法归属的并发改动、Skill 基线缺失，或计划与实时代码不一致，停止并报告；不要从其他 worktree 复制代码补齐前提。

验证：给出本轮 owner/文件边界和一句可证伪的成功标准后再编辑。

### Phase 1：把 Soul 变成真实的跨平台运行时输入

#### 1.1 平台化默认正文

修改共享默认 Soul，使其只包含平台中立内容，例如：Amber 的身份、工作方式、诚实使用工具、按证据完成任务、尊重用户授权。不要在共享正文列举 Android/iOS 专用工具名。

Android 当前依赖的工具发现、terminal、session、subagent、webview 等说明，移动到 Android 自己的 prompt builder；尽量原样移动，不趁机重写 Android 行为。

迁移必须保留用户自定义：

- 只有能证明当前值仍是旧 factory snapshot 时才替换为新默认；
- 已有自定义 Soul 原样保留；
- 复用现有 settings migration/rebrand 入口，不新建通用迁移框架。

#### 1.2 iOS 请求注入

在 iOS 真实请求组装层加入一个小而明确的 Soul system message helper，例如 `<agents_md>...</agents_md>`；正文为空时不注入。

要求：

- 前台 Chat 真实 provider request 可见；
- 既有后台/子线程 assembler 同样可见；
- 每个请求恰好一次；
- 不把 Soul 写进 UI message 或持久化聊天历史；
- Memory、Skill、网页、MCP 输出继续按不可信上下文处理；
- 不建立 `PromptFragment` 注册表，不重构 Chat loop / Provider / Kernel。

`Assistant.systemPrompt` 暂时保留为应用自带兼容提示，不重新引入多 Assistant/persona 编辑链，也不让 `soul_import` 双写它。自更新的唯一权威字段是 `AgentRuntimeSetting.agentSoulMarkdown`。

### Phase 2：`soul_import` 最小安全发布闭环

#### 2.1 工具契约

新增一个 iOS deferred 工具：

```text
soul_import()
```

- 无路径参数，固定读取 `/workspace/SOUL.md`。
- 工具说明写清：仅在用户明确要求更新 Amber 核心指令时调用。
- 归类为高风险设置变更。普通自动批准不能跳过；高风险自动批准可以跳过确认卡，但仍须 prepare + 双 CAS。
- 不新增 `soul_edit`、`soul_generate`、`soul_apply`、`soul_version_list` 等拆分工具。

#### 2.2 审批前 prepare

复用 `skill_import` 的现有形状，而不是抽象通用 proposal engine：

1. 从现有 Workspace owner 读取固定文件。
2. 拒绝空正文、超出现有合理文本上限或无法解码的内容。
3. 使用同一套轻量文本规范化规则计算 hash（统一换行；preview、apply、hash 必须基于完全相同的字节）。
4. 读取当前 Soul，计算 `baseHash`；计算候选 `candidateHash`。
5. 生成只读 diff、变更行数和简短 preview。
6. 把 prepared context 仅按 `toolCallId` 保存在当前进程内；冷启动后失效，不恢复、不自动应用。
7. 展示现有审批卡，只新增 Soul 所需的标题、diff 和 base/candidate 摘要，不新建 Dashboard、History 或审批状态机。

#### 2.3 批准后 apply

1. 再查既有权限/kill gate。
2. 再读 `/workspace/SOUL.md`，重算并匹配 `candidateHash`。
3. 再读当前 Soul，重算并匹配 `baseHash`。
4. 任一不匹配：返回 stale，设置完全不变。
5. 匹配：先保存一个 previous snapshot，再通过共享 Settings 的单一写入口更新 `agentSoulMarkdown`。
6. 写入成功后记录 promoted hash；失败时不得留下一个伪可回退状态。
7. prepared context 无论成功、失败或拒绝都清除。

previous 只保留一槽，使用一个小的 Soul 专用持久化记录即可；不要建数据库或通用 version store。

#### 2.4 回退

在现有 Agent Runtime/核心指令设置区域加一条“回退上一个核心指令”：

- 没有 previous 时不显示或禁用；
- 回退前显示确认；
- 只有当前 hash 等于 previous 记录的 promoted hash 时才允许恢复；
- 恢复成功后清掉这一槽；
- 不建 Soul 独立页面和版本历史。

### Phase 3：修正 MCP 权限的单一事实源

#### 3.1 统一策略读取

复用 App 已经持有、权限页已经编辑的同一个 `IOSPermissionStore`；不要再造 capability store 或兼容布尔开关。

把下列工具统一映射到 `ios.mcp.tool_call`：

- `mcp_call`
- 所有 `mcp__*`
- `mcp_test`
- `mcp_import_from_skill` 的批准后网络测试/写入阶段

执行语义：

| policy | 行为 |
| --- | --- |
| Disabled | 不声明网络型 MCP 工具，运行时仍硬拒绝；全局自动批准无权绕过 |
| Ask | 每次需要网络/变更时进入现有审批 |
| Auto high-risk | 普通 call/test 可按现有产品语义直跑并记账；import 仍强制批准 |

审批恢复和真正调用 `IOSMcpManager`/临时 client 之前都要复核一次，堵住“弹卡后用户改成 Disabled”这一真实时序。

本地 `mcp_list`、`mcp_describe_tool` 不发网络时继续可用。不要把旧的 `isAdvancedToolEnabled` 恒 true 当作权限 owner。

### Phase 4：把 `mcp_import_from_skill` 改成一次批准闭环

#### 4.1 审批前 prepare

保留现有工具名和主要模型参数，避免 prompt/tool 迁移：

1. 定位已安装 Skill 的 `mcp.json`。
2. 严格解析当前明确支持的 URL transports；stdio 或未知 transport 返回清楚的 unsupported 错误，不静默降级为 HTTP。
3. 对完整候选字节计算 digest。
4. 预检空列表、重复名称、live shared/local 同名冲突和明显无效 URL。
5. 构建脱敏 preview：显示 Skill、server 名、transport、origin、header 名和启用意图；不显示 header 值、URL userinfo 或敏感 query。
6. 只把 prepared context 放进当前 pending approval；不写 `IOSMcpConfigStore`、不刷新 manager、不发布 `mcp__*`。
7. 默认显示一次显式批准。普通自动批准不能跳过；高风险自动批准可以直接 CAS 应用，不再弹卡。

不要增加 candidate store、candidate ID、TTL、`expected_digest`/`tested_digest` 模型参数。digest 是 host 绑定审批与应用的内部事实。

#### 4.2 批准后 test + apply

1. 再查 `ios.mcp.tool_call`；若已 Disabled，立即拒绝且零网络。
2. 再读同一 `mcp.json` 并匹配 prepared digest。
3. 再做全量名称冲突预检；不再沿用“已存在就跳过”的部分成功语义。
4. 为每个 server 创建临时 client，执行当前协议实现支持的 `connect -> listTools -> disconnect`。
5. 临时测试不得写 live manager status、不得持久化 discovery、不得改变当前 run catalog。
6. 任一 server 失败：全部拒绝，live config 不变。
7. 全部成功：调用一个最小的 `addBatch`/等价原子入口，一次发布完整 server 数组；沿用现有 Keychain/脱敏 owner，不复制凭证存储。
8. 成功后让现有 config change/sync 机制接管。直接 `mcp__*` 下一 run 才刷新是可接受行为；当前 run 可用 resident `mcp_call`。

`mcp_test` 保持“测试已配置 server”的现有诊断语义，不把它改造成候选协议的一部分。

本轮不做 MCP previous：导入只允许新增且遇冲突全包失败，用户可在现有 Settings 中禁用或删除。等出现真实误导入恢复痛点后再评估一键回退。

### Phase 5：最少测试与验证

#### 5.1 新测试上限

新增生产路径测试方法总数控制在 **4 个以内**；优先扩展现有测试文件，不写源码字符串测试，不建大矩阵：

1. **Soul request assembly**：真实 iOS 参数/request 组装中，当前 Soul 恰好注入一次；UI/persisted history 不含 Soul；同一契约覆盖一个既有后台或 child assembler。
2. **Soul mutation**：一次测试覆盖 prepare 无写入、批准应用、candidate/base stale 拒绝和 previous 回退 CAS。
3. **MCP permission**：Disabled + global auto on 时，`mcp_call`/`mcp__*`/`mcp_test` 均为 0 次网络；审批后切 Disabled 也拒绝。
4. **MCP import**：prepare 无 live 写入；文件变化拒绝；批准后临时测试；任一失败零写入；全部成功仅一次 batch publish。

既有 Skill 发布测试只运行回归，不复制一套 Soul/MCP 通用 harness。

#### 5.2 验证层级

按开工时 `iosApp/AGENTS.md` 的有效命令执行，至少报告：

1. 受影响 Swift 文件 `swiftc -parse`；
2. 受影响 KMP 模块编译/定点测试；
3. 上述四条 iOS 定点契约；
4. generic Simulator `build-for-testing`；
5. 若环境允许，仅做一个人工 canary：
   - 写 `/workspace/SOUL.md`，确认预览、批准、下一请求生效、回退；
   - 用用户可控 echo MCP，确认 prepare 不连接、批准后测试并添加、下一 run 可由 `mcp_call` 调用。

没有运行的层级必须明确写“未验证”；Simulator build 不能写成测试通过，fake client 不能写成真实 MCP 兼容。

## 5. 预计文件边界

以下是定位范围，不是要求全部修改。开工后以真实 owner 为准，只改必要文件。

| 领域 | 可能涉及 |
| --- | --- |
| Soul 默认/迁移 | `core/types/.../Settings.kt`、`shared/.../IosSettingsMutations.kt`、Android `GenerationPrompts.kt` / `GenerationHandler.kt` |
| iOS Soul 注入 | `ChatContextSupport.swift`、真实前台与后台/child 参数组装点 |
| Soul tool/审批 | `Tool.kt`、一个小型 Soul 专用 service、`ChatToolRuntime.swift`、`ChatGenerationCoordinator.swift`、现有审批卡/时间线映射 |
| Soul 设置/回退 | `IOSSharedSettingsStore.swift`、现有 Agent Runtime 设置 View |
| MCP 权限 | `IOSPermissionModels.swift`、`ChatViewModel.swift`、`ChatToolRuntime.swift` |
| MCP import | `IOSSkillMcpTools.swift`、`IOSMcpConfigStore.swift`；临时连接若确有必要才最小改 `IOSMcpManager.swift` / `IOSMcpClient.swift` |
| 测试 | 现有 Soul/Chat request、`IOSSkillMcpToolsTests.swift`、`IOSMcpExpandedToolTests.swift` 或最接近的生产路径测试 |

不要触碰 Novel/vendor、Provider、Kernel、Recipe 泛化框架、Android UI 清理或当前并发删除的 Evolution 文件。

## 6. 明确不做

- 不恢复 Evolution Engine、Experience Miner、Evaluator、Policy Engine、Promotion Receipt、Metrics。
- 不建候选数据库、通用 proposal store、事件溯源、版本树、Dashboard、History 页面。
- 不让模型自评后自动上线；不做 LLM judge、A/B、shadow/canary rollout。
- 不做后台自动修改、不做定时“反思后改自己”。
- 不做 PromptFragment / Capability Runtime / ToolEffectManifest 新框架。
- 不重构 Provider、Kernel、Chat loop、thread orchestration。
- 不新增多 Assistant/persona；不恢复已删除的 Assistant 参数写入口。
- 不做 MCP OAuth、stdio、协议版本协商或 Streamable HTTP 2026 改写。
- 不为直接 `mcp__*` 同 run 热刷新重建工具桥；保留 `mcp_call` fallback。
- 不增加超过四条的新测试方法，不写源码字符串锚点和大矩阵。

## 7. 可直接发给 AI 的实施 Prompt

```text
请在仓库 /Users/arquiel/Downloads/AI/amberagent-ios 中实施 docs/SOUL_MCP_MINIMAL_CLOSURE_PLAN.md，严格完成“Skill 已有闭环之上的 Soul + MCP 最小闭环”，不要扩成通用自进化平台。

开始前必须：
1. 按仓库协议运行 Bridge mailbox 读取。
2. 完整读取根 AGENTS.md、iosApp/AGENTS.md、docs/AGENTS.md、docs/PROJECT_STATE.md 和本计划。
3. 核对 git status --short --branch 及相关文件 diff，先写出本轮 owner/文件边界和一句可证伪成功标准。
4. 确认现有 Skill 安全发布链仍完整：只读 preview、稳定 package hash、base/candidate CAS、一次显式批准、原子应用、单槽 previous 回退。Skill 本轮只回归，不重构。
5. 当前工作树有并发清理正在删除通用 Evolution/Experience/Evaluator/Policy/Metrics/Receipt 和大型测试。保留这些删除，不得恢复文件、不得从其他 worktree 复制代码。若 owner 不清、前提缺失或版本不一致，立即停止并报告。

只实现以下内容：

A. Soul 运行时闭环
- 把共享 DEFAULT_AGENT_SOUL_MARKDOWN 改为平台中立核心；Android 专用工具说明留在 Android prompt builder，避免 Android 行为回退。
- 迁移只替换可证明仍是旧 factory snapshot 的值，保留所有用户自定义 Soul。
- iOS 真实前台 Chat 与既有后台/child request assembler 每个请求恰好注入一次 agentSoulMarkdown；不写入 UI message/聊天历史。不要建 PromptFragment 框架。
- agentSoulMarkdown 是唯一可自更新 Soul owner；保留 Assistant.systemPrompt 兼容行为，但不要恢复多 Assistant/persona 编辑链，也不要双写。
- 新增一个 deferred 工具 soul_import()，固定读取 /workspace/SOUL.md，无任意路径参数。
- 审批前仅本地读取、校验、统一换行、diff、baseHash/candidateHash；prepared context 只按 toolCallId 留在内存。
- soul_import 默认一次显式批准；普通自动批准不能跳过，高风险自动批准可以跳卡后直接双 CAS 应用。stale 时零写入。
- 成功后保存一槽 previous 并更新共享 Settings；在现有 Agent Runtime 设置区提供最小回退入口。回退前校验当前 hash 仍等于上次 promoted hash；不要建 Soul 独立页、数据库或版本历史。
- 新 Soul 从批准后的下一次模型请求生效，不实现 run snapshot。

B. MCP 权限与导入闭环
- 让 App 已有的同一个 IOSPermissionStore 成为唯一事实源。ios.mcp.tool_call=Disabled 时，mcp_call、mcp__*、mcp_test、导入批准后的临时测试均不得发网络；global high-risk auto approve 不能绕过。Ask 保持逐次批准；普通 Auto high-risk 保持现有语义。mcp_list/mcp_describe_tool 的纯本地读取继续可用。
- 在工具声明、前台、后台、审批恢复和真正网络调用前复核策略；弹卡后切 Disabled 再批准也必须拒绝。
- 保留工具名 mcp_import_from_skill。审批前只读取已安装 Skill/mcp.json，严格解析当前支持的 URL transport，计算内部 digest，做名称/URL 冲突预检并返回脱敏 preview；不得写 live config、刷新 manager 或发布 catalog。
- import 默认一次显式批准；普通自动批准不能跳过，高风险自动批准可以跳卡。不要新增 candidate store、candidate ID、TTL、expected_digest/tested_digest 模型参数。
- 批准后重新读取并核对 digest，再检查权限与全部名称冲突；用 ephemeral client 对每个 server 做 connect -> listTools -> disconnect，且不写 live status/discovery/catalog。
- 任一 server 失败时 live config 零变化；全部成功后通过 IOSMcpConfigStore 的最小 batch 入口一次发布完整数组，沿用现有 Keychain/脱敏 owner。
- 保持 mcp_test 为“已配置 server 诊断”原语义。导入后 direct mcp__* 下一 run 刷新是正常 fallback，当前 run 用 resident mcp_call；不要为此重构动态工具桥。

明确禁止：
- 不恢复或新建 Evolution Engine、Experience、Evaluator、Policy Engine、Promotion Receipt、Metrics、候选数据库、Dashboard/History、LLM judge、自动晋级、后台自改。
- 不做 MCP OAuth、stdio、协议协商、Streamable HTTP 2026 重写。
- 不重构 Provider、Kernel、Chat loop、thread orchestration，不碰 Novel/vendor/无关 UI，不顺手清理。
- 不 commit、push、stash、reset，不覆盖用户或其他 agent 的并发改动。

测试严格精简：新增生产路径测试方法总数不超过 4，禁止源码字符串测试和大矩阵：
1. Soul 最终 request 恰好注入一次，并覆盖一个现有 background/child assembler。
2. Soul prepare/apply + base/candidate stale + previous rollback CAS。
3. MCP Disabled 即使 global auto on 或审批后切换也保持 0 次网络。
4. MCP import prepare 零写入、stale 拒绝、临时测试、失败全包零写入、成功一次 batch publish。

按 iosApp/AGENTS.md 运行定点验证：Swift parse、受影响 KMP 编译/测试、四条 iOS 契约、generic Simulator build-for-testing。真实 Soul/MCP canary 仅在环境具备时运行；没有运行的层级如实列为未验证，不能把 build-for-testing/fake client 写成真机或真实 server 验收。

完成时：
- 只在实际事实变化后最小更新 docs/PROJECT_STATE.md，不恢复已删除的大型计划。
- 检查本轮 diff 和 git status，确认没有越界。
- 用中文报告：改动文件、行为变化、测试/构建结果、未验证项、并发 WIP/跨 worktree 集成风险。
```
