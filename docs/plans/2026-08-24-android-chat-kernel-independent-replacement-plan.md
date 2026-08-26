# Amber Android 聊天内核独立替换执行计划

> 状态：已完成（限 Android 生产聊天/provider 内核；全产品依赖、品牌、端点、历史和法律关系未清零）
> 当前基线：`main@701e8c634ababe12fc1a0d03beccb36b2b55a0ca`
> RikkaHub 固定对照：`b270766f06671d7456ce3d248622dc667648b6d1`（tag `2.4.11`）
> 范围：只处理 Android 当前生产聊天链和 Provider 层；不读取或修改 iOS，不碰现有 Novel 及并发 Setting/Terminal/Workspace WIP。

## 1. 一句话目标

保留现有 Amber 产品、数据、UI 和三个月新增能力，只替换当前仍与 RikkaHub 高相似的 Provider 注册/契约、Claude/Google wire adapter，以及 `GenerationHandler`、`ChatService` 中确认存在的旧代码岛，最终让默认生产链完全由 Amber-owned 实现负责。

## 2. 不做什么

- 不重建 App，不换包名、商店条目或数据库。
- 不删除或改写 Git 历史，不宣称旧版本从未有过 fork 关系。
- 不启用当前 `useKernelPath = false` 的 dormant Agent Kernel 来冒充替换。
- 不重写 Novel、Memory、Workspace、MCP、Skills、Council、MiniApp 或现有 UI。
- 不引入 Koog、LangChain4j 或另一套完整 Agent 框架。
- 不为本任务新建通用插件系统、状态机框架或重复测试体系。
- 不修改当前 16 个 Novel/Novel Workspace WIP 文件，以及执行期间新增的 `SettingSandboxPage`、Terminal、Workspace 并发 WIP。

## 3. 当前真实结论

当前默认链：

```text
RouteActivity
  → ChatPage
    → ChatVM
      → SendMessageOrchestrator / RegenerateMessageOrchestrator
        → ChatService
          → GenerationHandler
            → ProviderManager
              → OpenAIProvider / ClaudeProvider / GoogleProvider
```

快速 normalized 对比用于定位工程范围，不作为法律结论：

| 文件 | ordered token 接近度 | Amber 侧 12-token shingle 重叠 | 处理结论 |
| --- | ---: | ---: | --- |
| `ProviderManager.kt` | 98.7% | 86.4% | 必须独立替换 |
| `Provider.kt` | 82.0% | 64.4% | 必须独立替换 |
| `GoogleProvider.kt` | 78.9% | 57.8% | 必须独立重写 wire adapter |
| `ClaudeProvider.kt` | 66.1% | 62.6% | 必须独立重写 wire adapter |
| `OpenAIProvider.kt` | 42.8% | 21.0% | 保留 Amber 新实现，迁移到新 contract 后复扫 |
| `GenerationHandler.kt` | 35.9% | 11.9% | 不推倒，只替换旧循环骨架和匹配代码岛 |
| `ChatService.kt` | 33.8% | 13.2% | 不推倒，只替换匹配代码岛并收口 owner |

## 4. 新边界：最小而明确

### 4.1 Provider contract

不再由一个旧式 `Provider<T>` 同时定义所有能力。新边界只按当前真实消费者拆分：

- `TextModelGateway<T>`：模型目录、余额、单次生成和流式生成；
- `ImageModelGateway<T>`：图片生成/编辑和能力判断；
- `ProviderCatalog`：根据 `ProviderSetting` 返回已注册实现，不自行持有聊天状态；
- OpenAI stored response API 直接注入现有 app `StoredResponseGateway`；
- Google OAuth 状态读取直接注入 `ProviderConfigTools`，不再从通用 catalog cast。

当前没有 embedding 生产消费者，因此不新增 `EmbeddingGateway`，也不把它当本轮产品能力。流式边界先复用现有 Amber `MessageChunk` / `MessageStreamAccumulator`，本计划不再创建第二套 `StreamEvent` reducer。Provider 只做 wire protocol ↔ Amber model 的转换，不执行工具、不写数据库、不决定审批。完整能力和消费者见 `docs/audits/android-chat-kernel-migration-ledger.json`。

### 4.2 运行时 owner

- `GenerationHandler` 继续拥有当前工具循环，直到 Phase 3 的替换 owner 验证通过。
- 新 owner 负责单次 run 的状态推进和现有 prompt/context/memory helper 调用，但不接管 Room repository、Compose UI 或后台服务生命周期。
- `ChatService` 继续拥有会话生命周期和持久化协调，直到 Phase 4 把已确认的旧代码岛换完。
- 每次切换只能有一个生产 owner；不双写 Conversation，不同时执行两套工具。

## 5. 总体验收门

全部 Phase 完成必须同时满足：

1. 默认生产链不再引用旧 `Provider`、`ProviderManager` 或 legacy provider contract。
2. Claude、Google 文本/流式/工具调用由新的独立 adapter 负责。
3. OpenAI 的 Responses、Chat Completions、Codex OAuth、stored response resume 不回退。
4. 工具审批、effect ledger、step limit、取消、断线恢复、pending/steer 消息语义不回退。
5. 会话编辑、删除、分支、重新生成、文件清理、标题和建议生成行为不回退。
6. 受影响 UI 不出现 loading 抖动、文本错位、按钮尺寸/间距退化、审批卡片状态错误。
7. 定点 JVM tests、相关模块 compile、`assembleDebug`、`git diff --check` 通过。
8. 新默认链相关文件复扫后，无未分类的高相似文件或大块代码岛。
9. 真机、真实 Provider、后台/kill-relaunch 证据单列；无法在本机完成时不得用单测或构建冒充。

---

## Phase 0：冻结基线、计划和可复现证据

### 目标

固定本轮改动边界和成功标准，避免替换过程中误伤 Amber 自有能力或 Novel WIP。

### 工作项

1. 记录当前 SHA、branch、dirty status 和 Rikka 对照 SHA。
2. 固定生产入口和 Provider 直接消费者清单。
3. 记录七个关键文件的 normalized 基线。
4. 建立 Phase/文件/测试/UI 影响矩阵。
5. 明确每 Phase 的 review 模板：逻辑闭环、调用链、持久化、取消/异常、UI、相似性。
6. 建立 `docs/audits/android-chat-kernel-migration-ledger.json`，冻结 capability、`Generator` 消费者、Phase 3 owner 和 ChatService/GenerationHandler 代码岛候选。
7. 建立 `scripts/chat_kernel_similarity.py` 与 `docs/audits/android-chat-kernel-baseline.json`，固定输入文件、SHA、namespace 归一化、12-token shingle、连续块阈值和 WIP 排除项。
8. 不改产品代码。

### 验证

- 计划文件存在且边界、Gate、回滚、验证命令完整。
- `git status` 中原 16 个 WIP 文件保持不变。
- 以下命令在固定 Rikka checkout 上产生 machine-readable JSON，且七个文件结果与本文基线一致：

  ```bash
  python3 scripts/chat_kernel_similarity.py \
    --manifest docs/audits/android-chat-kernel-baseline.json \
    --rikka-root /private/tmp/rikkahub-audit.7jxrGI/source
  ```

- subagent 独立复核计划是否错误推倒 Amber 自有实现、遗漏真实入口或引入过度设计。

### UI 审查

本 Phase 无 UI 改动。后续 UI 审查面已经冻结在 migration ledger：聊天流式/reasoning、`ChatInput` 与停止、Provider 设置/模型/连接测试/余额、工具与通知审批、图片生成/编辑、Model Council、DeepRead、Live 和后台完成入口。每个界面检查对齐、间距、尺寸、字体缩放、窄屏、主题、触控区域及 loading/approval/terminal 状态。

### Gate

计划 review 无 Critical/High 未解决问题，才能进入 Phase 1。

### 回滚

Phase 0 没有产品代码改动。若整 Phase 撤回，应一并移除本计划、baseline manifest、migration ledger 和相似性脚本；不得触碰任何产品 WIP。

---

## Phase 1：替换 Provider 注册器和核心契约

### 目标

先切断最高相似、体积最小的 `ProviderManager` 与 `Provider`，不改变任何 Provider wire 行为。

### 工作项

1. 新建最小 `TextModelGateway<T>`、`ImageModelGateway<T>`；没有生产消费者的 embedding 不进入共享 contract。
2. 新建无状态 `ProviderCatalog`，只解析 `ProviderSetting → gateway`。
3. 让现有 OpenAI/Claude/Google 实现先通过明确 adapter 实现新 contract；adapter 不改请求体和响应解析。
4. 按 migration ledger 迁移所有生产消费者，包括 Chat、Memory、Context、OCR、Vision、图片、Live、Board、MiniApp、Council、DeepRead、设置、余额、连接测试和模型目录。
5. `StoredResponseGateway` 直接接收 OpenAI stored-response API；`ProviderConfigTools` 直接接收 Google OAuth 状态 source；不再从通用 catalog 做 concrete cast。
6. 保持 Google Vertex/service-account/`predict` 为 Google adapter 私有职责。
7. 更新 Koin wiring，删除旧 `ProviderManager` 生产绑定。
8. 删除旧 `Provider.kt`、`ProviderManager.kt`；不保留双 registry fallback。
9. 将 `RuntimeChainCanaryTest` 和相关构造点迁移到新 Catalog，并至少通过一次真实 Koin wiring；旧 canary 不能作为新 owner 证据。
10. `ImageGenerationRepository` 不再读取 `OpenAIProvider` 的 MIME helper/大小常量；公共文件输入策略由 repository 自己拥有，OpenAI adapter 仍在 wire 边界复核官方限制。
11. 只迁移直接相关测试构造点，不复制测试矩阵。

### 最小验证

- `:ai:testDebugUnitTest`（若模块无此 task，则运行该模块实际存在的最窄 JVM test task）。
- Provider 配置工具、图片仓库、runtime canary 的现有定点测试。
- `KoinGraphVerifyTest` 验证完整启动图；`KoinModulesVerifyTest` 验证 `Generator → ChatRunCoordinator` alias。
- `RuntimeChainCanaryTest` 改为构造新 Catalog/coordinator，覆盖一次纯文本和一次工具审批闭环。
- `:app:compileDebugKotlin`。
- 全仓 `rg` 确认生产源码无旧 contract/manager import。

执行命令：

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.amber.agent.di.KoinGraphVerifyTest \
  --tests app.amber.agent.di.KoinModulesVerifyTest \
  --tests app.amber.agent.canary.RuntimeChainCanaryTest
```

### 调用链 review

- migration ledger 中的 Chat、Council、DeepRead、Stored Response、Vision、余额、图片和设置入口都已迁移；不存在仍依赖旧 manager 的旁路。
- 图片消费者通过独立 image capability；stored response 与 Google OAuth 走 ledger 指定的 provider-specific port，不能从文本 gateway 临时 cast。
- Provider 设置页、余额和模型目录保持真实可达，不用 UI 假数据兜底。

### UI 审查

- Provider 设置页模型列表、连接测试、余额、错误信息无错位或状态丢失。
- Loading indicator、按钮 enable/disable、长模型名截断、横竖屏宽度保持原行为。
- 本 Phase 不做视觉改版；只修因 contract 迁移造成的可见退化。

### Gate

旧 manager/contract 生产引用为零，现有定点测试和 compile 通过，subagent review 无 Critical/High。

### 回滚

整 Phase 作为一个 contract 切片回退；不得同时保留两套 registry 由运行时随机选择。

---

## Phase 2：独立重写 Claude/Google wire adapter

### 目标

根据官方 API 文档和独立 fixtures 重写当前最大面积的 Provider 相似区，不改变 Amber 上层 message/tool 语义。

### 工作项

1. 为 Claude 和 Google 分别固定最小真实事件序列 fixtures：文本、reasoning、图片输入、工具调用与结果、usage、错误终止；只覆盖协议 reducer 的真实分支。
2. 从公开官方协议重新定义 provider-private DTO；不沿用旧私有函数布局和控制流。
3. Claude：重写 message content blocks、tool use/result、cache control、SSE event reducer、usage。
4. Google：重写 contents/parts、role/tool mapping、SSE event reducer、grounding metadata、usage。
5. Provider adapter 只输出现有 Amber `MessageChunk`，由唯一 `MessageStreamAccumulator` 归并；不直接更新 UI message 或执行工具。
6. 保持 API key rotation、自定义 endpoint/header/body 和现有模型设置真实行为。
7. OpenAI 只迁移到新 contract，不在本 Phase 顺手重写。
8. 删除被新 adapter 取代的旧 parser/mapper 和无用测试；不保留 fallback。

### 最小验证

- Claude/Google fixture tests：覆盖上面列出的真实事件序列，每个 reducer 分支只保留一个最小代表，不做组合穷举。
- 现有 provider unit tests。
- `:ai:compileDebugKotlin`、`:app:compileDebugKotlin`。
- 能使用本机非敏感配置时运行真实 Provider smoke；没有凭证则明确标为未验证。

### 调用链 review

- Provider adapter 不依赖 `ChatService`、Room、tool dispatcher 或 Compose。
- chunk 合并只发生一次；tool call ID/arguments 不重复或丢失。
- cancel 传播到 OkHttp/SSE call，不吞 `CancellationException`。
- HTTP/协议错误以明确失败上送，不静默改为成功文本。

### UI 审查

- Claude/Google 流式文本不跳行、不重复、不闪回。
- reasoning 与正文层级、间距、展开状态保持一致。
- Tool approval card 的名称、参数、按钮和 loading 状态不因 chunk 顺序改变。
- 图片/grounding annotation 不越界、不挤压正文。

### Gate

Claude/Google 新 adapter 成为唯一 owner；旧 parser/mapper 删除；fixtures、compile 和 subagent review 通过。

### 回滚

Claude 与 Google 各自独立切片回滚；不能在同一 provider 内按异常 fallback 到旧 adapter。

---

## Phase 3：替换 GenerationHandler 残留循环骨架

### 目标

保留 Amber 已有 durable/tool/generative UI 能力，用新的小型 run coordinator 替换确认来自旧实现的模型—工具循环骨架。

### 工作项

1. 固定当前 run 输入、事件、终止状态和工具审批语义。
2. 新建单一 `ChatRunCoordinator` 并实现现有 `Generator` contract，保持 SubAgent、DeepRead 和 Novel 消费者的调用契约；Novel dirty WIP 只做编译兼容，不编辑。
3. 复用现有 `AgentToolDispatcher`、`ToolEffectLedger`、capability permissions、terminal store 和 transformers；不重写这些 Amber owners。
4. `ChatRunCoordinator` 继续负责 context/memory/token budget、input/output/visual transforms、stream flush、generative UI repair、vision fallback 和 retry classification；provider adapter 负责 wire，现有专用 owners 负责工具、effect、permission、terminal/recovery。
5. 保持 `WAITING_USER`、`STEP_LIMIT`、cancel、steer、speculative execution、generative UI fallback 和 stored response resume。
6. 将 runtime canary 改为新 coordinator + Catalog + 真实 Koin binding，证明新 owner，而不是继续实例化旧 handler/manager。
7. 将 `ChatService`、`ChatTurnAgent`、`DeepReadAgentRunManager` 从 concrete `GenerationHandler` 改为现有 `Generator`；SubAgent 和 Novel 继续使用同一 contract。
8. 将 `GenerationHandler` 缩成兼容入口后迁移全部调用者，随后删除 legacy handler。
9. 不启用 dormant Agent Kernel，不引入第二状态机、第二 stream reducer 或第二 event store。

### 最小验证

- 已迁移到新 coordinator/Catalog/Koin binding 的 runtime chain canary。
- 工具审批、step limit、取消、terminal/effect ledger 的现有定点测试。
- 一个代表性的纯文本流和一个工具调用流 fixture。
- `:app:compileDebugKotlin`。

### 调用链 review

- 一个 run 只有一个 coordinator owner、一个 tool execution owner、一个 terminal publish。
- waiting approval 时不继续请求模型；批准后从同一个 tool call 恢复。
- 非幂等副作用在执行前已 prepare，结果未知不自动重试。
- stop/cancel 不发布 completed；step limit 不映射 completed。

### UI 审查

- 流式刷新频率不导致列表抖动、自动滚动争抢或 Markdown 闪烁。
- Stop 按钮状态和实际 run owner 一致。
- Waiting User、Step Limit、失败、取消在聊天页展示正确。
- Tool card 执行前后尺寸变化不造成明显跳动；按钮触控区域和间距保持一致。

### Gate

旧 `GenerationHandler` 默认生产引用为零；关键终止/审批/取消语义通过；subagent review 无 Critical/High。

### 回滚

按整个 run coordinator 切片回滚；不得按单个异常切回旧循环。

---

## Phase 4：替换 ChatService 残留代码岛并收口入口

### 目标

不重做 3600 行服务，只独立替换复扫确认仍匹配的会话生命周期、审批、编辑/删除等代码岛，并确保上层产品能力继续走同一 owner。

### 工作项

1. 以 migration ledger 的候选函数、行号、匹配块和保留不变量为起点，对 Phase 3 后的 `ChatService` 重新扫描。
2. 只重写复扫仍命中的函数；ledger 候选不等于自动重写授权。
3. 保留 Amber 新增的 pending queue、steer、durable recovery、response resume、Memory/Context/Novel/MCP/Skills 等接线。
4. 将 Provider/run 细节从 `ChatService` 移出，服务只协调 conversation owner 与 persistence。
5. 核对所有入口：前台 Chat、通知审批、FGS/Worker、MiniApp、History/Session、辅助生成和系统入口。
6. 不为“更整齐”拆分未命中的函数或重排整个文件。

### 最小验证

- send/edit/delete/fork/regenerate/tool approval 的现有定点测试。
- pending/steer、conversation persistence、文件引用清理的现有测试。
- runtime chain canary。
- `:app:compileDebugKotlin`。

### 调用链 review

- 所有入口都经过同一个 conversation/run owner；不存在绕回旧 handler/provider 的旁路。
- 删除会话后后台 checkpoint 不能复活 tombstone 会话。
- edit/regenerate 与正在生成的 job 串行关系不回退。
- pending queue、approval resume、conversation save 顺序闭环。

### UI 审查

- 发送、排队、取消排队、编辑、重新生成、分支操作状态及时更新。
- 消息操作菜单、错误提示、审批卡和输入区不存在重叠、错位、裁切。
- 小屏、横屏、字体放大下检查聊天输入区、停止按钮、长错误文本和长工具参数。
- 只修能够复现的布局问题，不借机统一全 App spacing。

### Gate

ChatService 复扫无未分类高相似代码岛；生产入口清单全部指向新 owners；subagent review 无 Critical/High。

### 回滚

按独立函数组回滚，conversation schema 不变；不得用数据迁移或旧 DB fallback 掩盖问题。

---

## Phase 5：全链路、UI、相似性和发布门禁

### 目标

证明新实现完整接管，而不是“代码存在但生产没走”。

### 工作项

1. 运行最终生产调用链扫描和 dead-path 检查。
2. 使用 Phase 0 脚本运行七个关键区域的 normalized similarity 复扫并人工分类所有命中。Amber 侧 12-token shingle 超过 5%，或最长非 import 连续块达到 12 行时必须进入 ledger；阈值用于触发 review，不驱动无意义改名或控制流扰动。
3. 运行 focused tests → compile → `assembleDebug`。
4. 使用可用 emulator/真机检查聊天、Provider 设置、工具审批、错误和取消；无设备则明确缺口。
5. 按 `audit` 维度检查受影响 UI：a11y、touch target、对齐、间距、尺寸、主题、字体缩放、窄屏和渲染性能。
6. 只修真实发现的问题，再复跑受影响验证。
7. 更新计划执行记录、最终 status 和未验证证据边界。

### 最终验证顺序

```text
provider fixtures
  → runtime/tool focused JVM tests
    → conversation focused JVM tests
      → :app:compileDebugKotlin
        → :app:assembleDebug
          → device/provider/UI smoke（环境可用时）
            → similarity + dependency + source scan
```

### UI 验收

- 无新增 Critical/High a11y 或布局问题。
- 受影响控件触控区域不小于 Android 推荐尺寸；图标和文本基线对齐。
- 相邻控件边距使用现有设计 token/惯例，不出现局部硬编码漂移。
- 长文本、中文/英文、字体放大、窄屏不遮挡主操作。
- 流式消息、reasoning、tool card 更新不产生明显布局跳动。
- 深浅主题颜色与现有 Amber token 一致。

### Gate

总体验收门 1–9 全部有证据；subagent final review 无 Critical/High；Medium 仅允许与本任务无关且有明确证据边界的问题。

### 回滚

若最终门禁失败，回到最近一个已通过 Phase Gate 的 owner，不把旧 Rikka-derived runtime 作为线上 fallback。

---

## 6. 每个 Phase 的强制 review 模板

每个 Phase 完成实现和本地验证后，必须交给独立 Luna Max subagent，只读检查：

1. **范围**：是否只改 Phase 声明的文件/行为，是否碰到 Novel WIP。
2. **逻辑闭环**：输入、状态推进、持久化、终止、取消、错误是否闭环。
3. **调用链**：默认入口、后台入口、设置入口是否实际走新 owner。
4. **并发与副作用**：是否出现双 owner、双写、重复工具执行或错误重试。
5. **UI**：受影响状态是否正确显示；对齐、间距、大小、裁切、触控区域和字体缩放是否有证据。
6. **测试**：是否只跑/新增与 Phase 风险直接相关的最小验证。
7. **反过度工程**：新增 abstraction、test、fallback、guard 是否都有真实需求。
8. **相似性**：Phase 目标文件是否确实减少未批准的旧实现重叠。

review 输出按 Critical / High / Medium / Low 分类。Root agent 核验并修复 Critical/High；Medium 只修本 Phase 引入或真实影响用户的问题；Low 不自动扩张范围。

## 7. 执行记录

| Phase | 状态 | 实现证据 | 验证 | subagent review | UI 结果 |
| --- | --- | --- | --- | --- | --- |
| 0 | 已完成 | 本文、baseline manifest、migration ledger、repro script | 固定 SHA 的七组基线、5%/12 行 review gate；冻结时 21 个外部 WIP，current manifest 持续跟踪执行期新增至 30 个 | 三轮独立复核；最终 Go，0 Critical/High | 无 UI 改动，已扩展并固定审查面 |
| 1 | 已完成 | `TextModelGateway`、`ImageModelGateway`、`ProviderCatalog` 成为唯一生产契约；旧 `Provider`/`ProviderManager` 删除；Stored Response、Google OAuth、图片能力改为明确端口；未使用 embedding 路径删除 | `:ai:compileDebugKotlin`、`:app:compileDebugKotlin`、5 组定点测试及补充 `KoinModulesVerifyTest` 通过；旧契约生产引用 0；Catalog 对 Manager 的 shingle 降至 2.2%，公共请求 DTO 仅保留无控制流字段并人工分类 | Luna Max 最终 GO；0 Critical、0 High；补齐 reviewer 指出的 StoredResponse 两个 Koin binding 断言后复测通过 | Compose 布局结构、padding、spacing、尺寸均未改；设置、余额、连接测试、图片入口静态接线无退化，设备视觉验证留到 Phase 5 |
| 2 | 已完成 | `AnthropicMessagesAdapter`、`GeminiGenerateContentAdapter` 成为 Claude/Google 文本 wire 唯一 owner；provider 本体只保留 transport、认证、模型目录及 Google image predict；旧 mapper/parser 删除 | `:ai:testDebugUnitTest` 全量通过；`:app:compileDebugKotlin` 通过；`git diff --check` 通过；Claude/Google provider ordered similarity 降至 30.5%/31.6%，新 adapter 最长连续块仅 6/7 行 | Luna Max 复核 GO；0 Critical、0 High；reviewer 指出的 Gemini reasoning/index/error/late-id/usage-only 边界全部修复后复审通过 | 本 Phase 未改 Compose 布局；tool stream index 与 provider id 保持稳定，未发现工具卡跳行、闪回、错位、边距或尺寸回归；设备视觉验证留到 Phase 5 |
| 3 | 已完成 | `ChatRunCoordinator` 接管现有 `Generator` contract；`ChatService`、`ChatTurnAgent`、`DeepReadAgentRunManager` 只依赖 contract；旧 `GenerationHandler` 删除；工具 finalization、审批快照和 result merge 旧代码岛重写；durable path 的 ledger `prepare` 失败改为审批/执行前明确中止 | RuntimeChain、Koin、审批、terminal、speculative tool 定点集通过；app/test Kotlin 编译通过；旧 handler 生产引用 0；ordered/shingle 降至 32.4%/7.4%，最长连续块 11 行；`git diff --check` 通过 | Luna Max 最终 GO；0 Critical、0 High、0 Medium；确认 coordinator → dispatcher → ledger 单 owner，prepare 异常无副作用旁路 | 未改 Compose 布局；stream/Stop/Waiting User/失败/取消/tool card 接口不变，静态 UI 无新增布局风险；设备视觉验证留到 Phase 5 |
| 4 | 已完成 | 仅重写复扫命中的 `ChatService` 代码岛：session/reference/jobs、regenerate/approval、invalid-tool sanitation、文件清理、fork/delete/本地媒体复制和取消 tail patch；conversation schema、tombstone、pending/steer、通知审批与外部 WIP 均保留 | RuntimeChain、Koin、ConversationEditVariant、ConversationSessionQueue、ChatServiceNotification 定点集及 app compile 通过；ordered/shingle/longest 从 33.8%/13.2%/34 行降至 30.4%/7.6%/10 行；剩余块已逐项人工分类；`git diff --check` 通过 | 独立 Luna Max reviewer GO；0 Critical、0 High、0 Medium；确认前台/通知/后台入口、持久化、文件副作用与单 owner 闭环 | 未改 Compose layout/padding/spacing/尺寸；相关 UI diff 仅替换 provider 注入和调用名，静态审查无可确认回归；设备视觉验证留到 Phase 5 |
| 5 | 已完成 | 最终生产 owner 扫描确认旧 `Provider`、`ProviderManager`、`GenerationHandler` 引用均为 0；最终相似性与产品级残留写入 `android-chat-kernel-final-evidence.json` | `:ai:testDebugUnitTest`、12 组 app 定点测试、`:app:compileDebugKotlin`、`:app:assembleDebug`、`git diff --check` 全部通过；两份 Debug APK 已产出并记录 SHA-256 | 独立 Luna Max final reviewer scoped GO；0 Critical、0 High、0 Medium；2 个 Low 均为明确的计划外边界 | 相关 Compose surface 没有 layout/padding/spacing/尺寸 diff，静态审查无可确认回归；本机无设备/模拟器，因此真机视觉、字体缩放、窄屏和真实交互未验证 |

## 8. 最终边界

本计划定义的 Android 生产聊天/provider 内核已经完成替换。当前唯一生产链为：

```text
ChatService / ChatTurnAgent / DeepRead
  → Generator
    → ChatRunCoordinator
      → ProviderCatalog / provider wire adapters
        → AgentToolDispatcher / ToolEffectLedger
```

旧 `Provider`、`ProviderManager`、`GenerationHandler` 不再拥有生产调用链，也没有保留异常时切回旧实现的 fallback。`ChatRunCoordinator` 与 `ChatService` 的最长 normalized 连续匹配块分别为 11 行和 10 行，低于本计划的 12 行代码岛审查门；Provider 中较长的剩余块已经人工分类为官方 HTTP transport、认证、图片 predict 或余额请求，不是聊天/run 控制流。

这不等于“整个 Amber Android 已与 RikkaHub 完全无关系”。计划外仍存在 6 个已解析的 Rikka Maven 坐标、112 个 HugeIcons import 文件、`https://api.rikka-ai.com/v1/search`、2 个 About 归属链接和打包资源 `assets/icons/rikkahub.svg`，Git/provenance 与许可证事实也不能被本轮代码替换抹去。因此准确结论是：**Android 聊天/provider kernel 已完成独立接管；全产品 provenance、依赖、品牌和法律脱离尚未完成。**

UI 结论同样有边界：本轮相关 UI 只迁移 provider 注入和调用名，没有修改 Compose 布局、边距、间距、尺寸、裁切或触控区域；静态检查及 `ComposerInteractionTest` 未发现回归。本机没有连接设备或模拟器，所以不能把它表述为真机视觉、字体缩放、窄屏、真实 Provider 或后台 kill/relaunch 已通过。
