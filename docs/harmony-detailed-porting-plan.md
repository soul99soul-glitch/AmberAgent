# AmberAgent 鸿蒙化详细执行计划

> **权威等级：执行权威**。本文档是 AmberAgent 从 Android 版迁移到 HarmonyOS NEXT 的权威执行计划：阶段编号（Phase 0–6）、里程碑 ID（M0–M7）、Gate 验收标准、第一周清单以此为准。
> 配套战略文档：`docs/harmony-native-migration-plan.md`（战略概览，回答"为什么这么排优先级"、风险全景、AI 流水线组织）。两份在 Phase 0–4 语义一致；本计划的 Phase 5（体验打磨）+ Phase 6（测试发布）对应战略计划的 Phase 5（收敛上架）。本计划的里程碑标记为 `M0–M7`，战略里程碑标记为 `S0–S5`，互不冲突。
> 审阅记录：2026-06-17 经一次 plan review，补入 ArkTS 技术风险（§5.3）、三方依赖 availability 表（§5.2）、Gate 量化预算（§10.4）、i18n 前置约束（§10.6）。

本文档面向 AmberAgent 从 Android 版迁移到 HarmonyOS NEXT 原生版的执行。它包含原 Android 项目的背景、位置、技术栈、模块架构、迁移映射、阶段计划、风险和验收标准。

当前结论很明确：鸿蒙版应按 ArkTS + ArkUI 原生应用重写。Android 的 Kotlin、Jetpack Compose、Room、DataStore、AccessibilityService、OkHttp SSE、JNI 不能直接平移；可以复用的是产品语义、协议契约、数据模型、fixtures、测试思路，以及少量纯 Rust/纯算法逻辑。

## 1. 项目背景

### 1.1 原 Android 项目位置

原 Android 项目位于：

```text
/Users/mi/Downloads/AI/AmberAgent
```

当前鸿蒙预研 worktree 位于：

```text
/Users/mi/Downloads/AI/AmberAgent-harmony-preview
```

当前分支：

```text
harmony-preview
```

`AmberAgent-harmony-preview` 是独立 worktree，用于鸿蒙迁移准备，不应混入原目录里已有的 Android/ModelCouncil 相关未提交改动。

### 1.2 产品形态

AmberAgent 当前是一个 Android 端 AI Agent 应用。它不是单纯聊天壳，而是包含：

- 多 provider 对话。
- 流式 Chat UI。
- Assistant 配置。
- Conversation / Message tree。
- Message transformer。
- 本地工具调用。
- 搜索、网页、文件、文档、TTS 等扩展能力。
- Android Accessibility 自动化。
- 通知监听。
- 本地 terminal / external CLI / ModelCouncil。
- Rust native 加速模块。

因此鸿蒙化不能按“换 UI 框架”处理。它更像是一次平台重建：保留产品行为，替换底层运行时和系统能力。

### 1.3 当前鸿蒙迁移资产

当前已完成 Phase 0 source-only 准备：

- `docs/harmony-native-migration-plan.md`：总迁移计划。
- `docs/harmony-capability-matrix.md`：能力矩阵。
- `docs/harmony-phase0-spike-plan.md`：Phase 0 spike 计划。
- `docs/harmony-phase0-delivery-summary.md`：source-only 交付总结。
- `docs/harmony-gate0-report.md`：本机 Gate 0 检查报告。
- `docs/harmony-gate0-setup-checklist.md`：工具链安装 checklist。
- `docs/harmony-inventory/`：Android 源码迁移 inventory。
- `harmony/tests/fixtures/`：source-only fixtures/contracts。
- `harmony/spikes/source-only/`：source-only skeletons。
- `scripts/harmony-inventory/`：inventory、fixture、contract、Gate 0 检查脚本。

最新 source-only 校验结果：

```text
Fixture validation passed (54 JSON/JSONL records checked).
Source contract checks passed (10 check groups).
```

当前阻塞点是 Gate 0 本机工具链：缺 DevEco Studio 或 Huawei Command Line Tools、`hvigor`、`ohpm`、`hdc` 和真机/模拟器 target。

## 2. Android 项目技术栈

### 2.1 构建系统

Android 项目是 Gradle 多模块工程：

- 根项目名：`amberagent`。
- Android Gradle Plugin：`9.2.0`。
- Kotlin：`2.3.21`。
- Kotlin Serialization。
- KSP。
- Firebase / Google Services / Crashlytics。
- Rust native 编译由自定义 Exec 任务驱动，不再依赖旧 rust-android-gradle 插件。

主 app 配置：

- namespace：`app.amber.agent`
- applicationId：`app.amber.agent`
- minSdk：`26`
- targetSdk：`37`
- compileSdk：`37`
- versionName：`2.6.8`
- versionCode：`396`
- ABI：`arm64-v8a`

### 2.2 主要依赖

关键依赖包括：

- Jetpack Compose / Material3 / Navigation。
- Room。
- DataStore Preferences。
- OkHttp / OkHttp SSE。
- Ktor client/server。
- Koin。
- Kotlin coroutines / Flow。
- Kotlin serialization。
- Coil。
- Firebase Analytics / Crashlytics / Remote Config。
- WorkManager。
- AndroidX Browser / WebKit / Credentials / Security Crypto。
- QuickJS。
- MLKit barcode scanning。
- Media3。
- sqlite-vector / sqlite-android。
- MCP Kotlin SDK。
- Rust native crates for markdown/highlight/tokenizer/json-expr/document parsing.

这些依赖在 HarmonyOS NEXT 上多数不能直接复用，需要按能力拆开重建。

## 3. Android 项目模块架构

### 3.1 顶层模块

`settings.gradle.kts` 中包含大量模块，核心分组如下：

```text
app
app:baselineprofile
ai
common
document
highlight
search
tts
core:*
feature:*
```

### 3.2 app 模块

`app` 是 Android 主应用模块，负责：

- Android application packaging。
- `RouteActivity` 路由入口。
- Compose UI 装配。
- Manifest 权限和 service 声明。
- Firebase / XMS / OAuth / signing / build type 配置。
- 与 feature/core 模块集成。

Android 入口和系统能力集中在这里，HarmonyOS 侧不能复用，但可以参考其页面路由和功能组织。

### 3.3 ai 模块

`ai` 模块包含 provider 抽象和 provider 实现。inventory 显示：

- `Provider.generateText(...)`
- `Provider.streamText(...): Flow<MessageChunk>`
- `ProviderManager`
- `OpenAIProvider`
- `GoogleProvider`
- `ClaudeProvider`
- OpenAI Chat Completions API。
- OpenAI Responses API。
- Gemini OAuth。
- Codex OAuth。
- OkHttp SSE EventSource。

鸿蒙版不能复用 OkHttp SSE，也不能直接复用 Kotlin Flow。需要重写为 ArkTS provider client + streaming parser + accumulator。

### 3.4 core 模块

`core/*` 是业务内核层，包含：

- `core:model`：Conversation、Assistant、Message 等核心模型。
- `core:settings`：设置模型和设置读写。
- `core:agent-runtime`：AgentRunner、ToolSession、PermissionBroker 等 agent runtime 抽象。
- `core:agent-runtime-impl`：runtime 实现。
- `core:agent-store-room`：Room 存储相关。
- `core:ai:api`、`core:ai:generation:api`、`core:ai:transformers:api`：AI 调用和 transformer 抽象。
- `core:llm`：token counter 等 LLM 辅助能力。
- `core:automation:api`：自动化抽象。
- `core:memory:api`、`core:context:api`、`core:sync:api`：记忆、上下文、同步能力。
- `core:event`、`core:usage`、`core:app-infra`：事件、用量、应用基础设施。

鸿蒙迁移时，`core` 的“语义”比代码更重要。建议把模型和接口重新定义为 ArkTS 版本，不做逐行翻译。

### 3.5 feature 模块

`feature/*` 包含产品功能：

- `feature:chat:api`
- `feature:history`
- `feature:task`
- `feature:workspace`
- `feature:webview`
- `feature:terminal`
- `feature:tools:*`
- `feature:modelcouncil`
- `feature:subagent`
- `feature:board`
- `feature:live`
- `feature:office`
- `feature:deepread`
- `feature:icloud`
- `feature:system`

这些功能不能一口气搬。鸿蒙版先保 P0 Chat，再按 P1/P2 拆迁。

### 3.6 native 模块

Android 项目包含 Rust native workspace，inventory 中列出：

- tokenizer native。
- office parser native。
- MuPDF native。
- highlight parser native。
- markdown parser native。
- html diff normalizer。
- json-expr。
- jni-common。

这些当前通过 JNI 接入 Android。HarmonyOS NEXT 侧如果要复用 Rust，需要改成 NAPI 或 HarmonyOS 支持的 native bridge。由于 Chat 主链路可以先用 ArkTS fallback，Rust NAPI 不作为 P0 生死门。

## 4. Android 核心数据和运行时背景

### 4.1 Conversation

Conversation 是持久化会话线程，包含：

- assistant 绑定。
- title。
- create/update time。
- pin 状态。
- MessageNode tree。
- chat suggestions。
- compact/context 记录。

鸿蒙版第一版可以先做线性消息列表，但数据库 schema 要预留 branch/tree 字段，避免后续重构。

### 4.2 UIMessage

UIMessage 是平台无关消息抽象，包含：

- role：user / assistant / system / tool。
- content parts：text、image、document、reasoning、tool call、tool result 等。
- model id。
- token usage。
- annotations。
- streaming chunk merge 语义。

鸿蒙版应把 UIMessage 作为核心契约重写。UI 不应该直接拼字符串，必须通过 streaming accumulator 生成 partial/final message。

### 4.3 MessageNode

MessageNode 支持分支消息：

- 一个 node 可以有多个 alternative messages。
- `selectIndex` 表示当前选中分支。
- regenerate 后不覆盖旧消息。

鸿蒙 MVP 可以先不做完整分支 UI，但底层模型要保留 `nodeId`、`parentId`、`selectIndex` 等概念。

### 4.4 Message Transformer

Android 项目有输入/输出 transformer pipeline：

- TemplateTransformer。
- ThinkTagTransformer。
- RegexOutputTransformer。
- DocumentAsPromptTransformer。
- Base64ImageToLocalFileTransformer。
- OcrTransformer。

鸿蒙版迁移策略：先迁纯逻辑 transformer。涉及 Android 文件、OCR、图片处理的 transformer 后置。

### 4.5 Agent runtime 和 tools

Agent runtime inventory 显示 Android 项目有：

- `AgentRunner`
- `ModelRouter`
- `ToolSession`
- `PermissionBroker`
- `ToolCallResult`
- `AgentToolSetFactory`
- `ConversationContextTools`
- `ConversationHistoryTools`
- `ExternalFileTools`
- `ScreenAutomationTools`
- `SystemAccessTools`
- `WorkspaceArtifactTools`
- `FeishuOfficeTools`
- `ICloudDriveTools`
- `ModelCouncil`
- `ExternalCliModelCouncilRunner`

鸿蒙版第一阶段不迁完整 tool runtime。先保留 ToolCall 数据结构，执行器默认禁用或 mock。等 Chat MVP 稳定后，再按工具类型逐个验证。

## 5. Android 到 HarmonyOS NEXT 映射

| Android 侧 | HarmonyOS NEXT 侧 | 迁移策略 |
|---|---|---|
| Kotlin | ArkTS / TypeScript | 原生重写 |
| Jetpack Compose | ArkUI | 原生重写，不逐行翻译 UI |
| Navigation Compose | ArkUI Navigation | 重建路由 |
| ViewModel / StateFlow | ArkTS state management | 重建状态层 |
| Room | RDB | 按 schema 契约重建 |
| DataStore / SharedPreferences | Preferences | 重建设置层 |
| OkHttp / OkHttp SSE | HarmonyOS HTTP / streaming API | 先做真实 SSE spike |
| Kotlin Flow | AsyncGenerator / callback state | 重写 streaming pipeline |
| Koin | ArkTS DI 或轻量 service registry | MVP 可先手工注入 |
| Android Manifest services | HarmonyOS abilities/extensions | 按能力单独验证 |
| AccessibilityService | AccessibilityExtensionAbility | P2，高风险验证 |
| NotificationListenerService | HarmonyOS 通知相关能力 | P2，高风险验证 |
| WorkManager / ForegroundService | HarmonyOS background/task ability | P2 验证 |
| Android WebView | HarmonyOS Web 组件 | P1/P2，根据工具能力验证 |
| JNI native | Rust NAPI / native bridge | P1/P2，必须有 ArkTS fallback |
| Gradle | hvigor / ohpm | 使用 DevEco 生成工程 |
| Android Studio | DevEco Studio | 官方工具链 |

### 5.2 三方库 availability 表

Android 版依赖的下列三方库在 HarmonyOS NEXT 上**不能直接复用**，需逐项确认是否有官方对应物、ohpm 替代、或必须自研/放弃。本表在 Gate 0 解锁后随 spike 更新；`缺口` 项必须进风险表并确认 P0/P1 是否受影响。

| Android 依赖 | 鸿蒙对应物 / 替代 | 当前判定 | 影响范围 | 迁移策略 |
|---|---|---|---|---|
| Room | `@ohos.data.relationalStore` (RDB) | **有** | 存储 | 按 schema 契约重建 DAO 层 |
| DataStore / SharedPreferences | `@ohos.data.preferences` | **有** | 设置 | 语义重建 |
| OkHttp / OkHttp SSE | `@ohos.net.http` `requestInStream()` 或 RCP | **有** | Provider streaming | Phase 0 Spike A 验证 streaming 稳定性 |
| Kotlin Coroutines / Flow | ArkTS `Promise` / `AsyncGenerator` / 回调 | **替代** | 全局并发模型 | 重写 streaming pipeline，见 §5.3 并发风险 |
| Koin (DI) | 无官方对应物 | **替代** | 全局 | MVP 手工注入；后续自建轻量 service registry |
| Jetpack Compose / Navigation | ArkUI / ArkUI Navigation | **替代** | UI | 原生重写，不逐行翻译 |
| Coil (图片加载) | 无直接对应物 | **缺口** | 图片消息、附件缩略图 | 自建基于 `@ohos.multimedia.image` 的简易缓存，或评估 ohpm 第三方 |
| **sqlite-vector**（向量索引） | RDB 不支持向量索引 | **缺口** | memory / embedding 检索 | 鸿蒙侧降级：纯 ArkTS 暴力检索（小规模）或服务端检索；**memory 能力可能降级**，进风险表 |
| **MCP Kotlin SDK** | ArkTS 版 MCP SDK **不存在** | **缺口** | Agent tools / MCP 集成（产品核心卖点之一） | 评估按 MCP 协议规范 ArkTS 自研 client；**这是 P1 的重大未知**，需在 Phase 1 早期 spike |
| **QuickJS**（JS 运行时） | ArkTS 运行时已是 JS 引擎 | **需重新评估** | 工具沙箱、动态脚本 | 评估是否仍需独立 JS 沙箱；可能直接复用 ArkTS 子上下文或放弃 |
| MLKit barcode scanning | 无直接对应物 | **缺口** | 二维码扫描 | 评估 `@ohos.zxing` 三方或 `@kit.ScanKit`；缺失则降级 |
| Media3 (ExoPlayer) | `@ohos.multimedia.media` / AVPlayer | **替代** | 音频播放（TTS） | API 重写 |
| Firebase (Analytics/Crashlytics/Remote Config) | HiAP / Analytics Kit / Crash Kit | **替代** | 埋点、崩溃、远程配置 | 替换为华为套件，**注意数据合规边界** |
| WorkManager | Background Tasks Kit (短时任务 / 长时任务 / Work Scheduler) | **替代** | 后台生成 | Phase 0 Spike 验证长时生成是否被允许 |
| AndroidX Security Crypto (AES) | `@ohos.security.cryptoFramework` | **有** | API key 加密 | 验证算法可用性与密钥存储方案（HUKS） |
| Rust native crates（tokenizer/markdown/highlight/office/json-expr/sync-crypto） | Node-API (C/C++) + Rust 静态库 shim | **待验证** | 性能热点 | 见 §12.4，P1 优化项，**不阻塞 Chat** |

**已知硬缺口**（影响产品核心，需优先决策）：

1. **sqlite-vector**：若鸿蒙 RDB 无向量索引，memory/embedding 检索能力必须降级或迁移到服务端。
2. **MCP ArkTS SDK**：产品把"Agent tools / MCP 集成"作为卖点之一，ArkTS 侧无现成 SDK，需自研或评估协议层兼容性。
3. **Coil / 图片加载**：常见图片加载/缓存/解码管线缺口，影响所有图片消息和附件。

这三项应进入 §17 风险表，并在 Phase 1 早期各做一个独立 spike。

### 5.3 ArkTS / ArkUI 技术风险（2026-06-17 review 补入）

本节是执行层面最危险的盲点。HarmonyOS NEXT 用 ArkTS + ArkUI，但 ArkTS **不是标准 TypeScript**，其运行时和类型系统与 Web/Kotlin 生态差异显著。下列风险必须在 Gate 0 解锁后、写任何 ArkTS 业务代码前先做最小 spike 验证，否则会在 Phase 1 中后期被迫返工。

#### R-ARK-1：ArkTS 不是 TypeScript

- ArkTS 有静态类型约束：禁用 `any`、禁用运行时改对象形状、对象字面量必须严格符合声明类型、`null`/`undefined` 区分更严格。
- **现有 `harmony/spikes/source-only/*.ts` 是按标准 TS 写的契约/参考实现，不能假设能直接当 ArkTS 编译过。** 它们的价值是**语义参考**，不是可直接移植的代码。
- 迁移策略：Phase 1 开始时先把一个 fixture（如 SSE parser）手工翻译成 ArkTS，建立"TS → ArkTS"的转换模式，再批量推进。

#### R-ARK-2：并发模型与跨线程对象传递

- ArkTS 主线程跑 UI，重活要丢到 `TaskPool` / `Worker`。
- **跨 Worker 传递的对象必须 `@Sendable`**，这会严格限制对象结构（不能持有非 Sendable 引用、不能有闭包捕获等）。
- 对 streaming accumulator 的影响：高频 chunk 合并 + 序列化/反序列化如果跨线程，accumulator 的数据结构从一开始就要按 `@Sendable` 设计，否则后期改不动。
- 迁移策略：Phase 0 补一个"accumulator 是否需要 Worker"的最小决策；若主线程能扛住 50 token/s 的 chunk 合并 + 增量 Markdown 解析，就不引入 Worker，规避 `@Sendable` 复杂度。

#### R-ARK-3：ArkUI 状态管理与深度刷新

- `@State` / `@Observed` / `@ObjectLink` 的刷新粒度和嵌套限制比 Compose 的 `mutableStateOf` 更严格。
- 深层嵌套消息树（MessageNode → UIMessage → content parts）的状态驱动刷新需要仔细设计，否则会出现"改了数据 UI 不更新"或"全量重渲染卡顿"。
- 迁移策略：UIMessage 流式更新时，优先用扁平化的不可变快照 + `@State` 整体替换，而不是深度可变绑定。这是 Android jank 优化经验的反向应用（Compose 可以细粒度订阅，ArkUI 更适合快照替换）。

#### R-ARK-4：训练语料稀少，AI 流水线产能缩水

- ArkTS/ArkUI 的公开训练语料远少于 Kotlin/Compose。AI 生成的 ArkTS 代码质量、对最新 API 的准确度都会明显下降。
- **§6 的"迁移工厂"流水线实际产能会比描述的缩水**，reviewer 反馈成本更高，需要更多人工修正。
- 迁移策略：降低对 AI 自动生成的预期，每个模块预留人工 review + 真机调试时间；优先用官方文档和 SDK sample 作为 AI 的 grounding 材料。

#### R-ARK-5：流式渲染性能未经证实

- ArkUI 的 `List` + `LazyForEach` + `Scroller` 在高频 streaming append 场景下的实际帧率没有公开基准。
- Android 版 `jank-opt/` 经过多轮优化才稳定；ArkUI 是否需要等价的 batching/throttling 策略未知。
- 迁移策略：Phase 0 Spike C（ArkUI streaming + Markdown）必须用真实 token 速率（≥ 50 token/s）压测，并在进入 Phase 2 前确定 batching 策略。

**ArkTS 技术风险 Spike 清单**（Gate 0 解锁后、Phase 1 业务代码前完成）：

1. TS → ArkTS 转换模式：拿 SSE parser fixture 做一次手工翻译，记录所有不兼容点。
2. 并发决策：benchmark 主线程 accumulator @ 50 token/s，决定是否需要 Worker。
3. 状态刷新：用扁平快照 vs 深度绑定各实现一个消息列表，对比刷新正确性和性能。
4. 流式渲染：按 §8.3 Spike C 的性能预算压测。

## 6. 迁移目标和优先级

### 6.1 P0：Chat 生死线

P0 只关注 Chat 能不能在 HarmonyOS NEXT 上活下来：

- OpenAI-compatible provider。
- SSE streaming。
- Streaming accumulator。
- Conversation/message 存储。
- Preferences 设置。
- ArkUI chat 页面。
- Markdown 基础渲染。
- 错误、取消、重试。

P0 不包含：完整工具系统、Accessibility、通知监听、terminal、ModelCouncil、全量文档解析、完整 Markdown、TTS。

### 6.2 P1：核心体验补齐

P1 补齐高频产品体验：

- Assistant 配置。
- System prompt。
- 多 provider。
- 基础 transformers。
- Reasoning 展示。
- 搜索工具。
- 文档作为 prompt。
- TTS。
- WebView 工具的鸿蒙替代。
- Rust NAPI 性能项。

### 6.3 P2：平台风险能力

P2 验证 Android 强平台绑定能力：

- Accessibility 自动化。
- 通知监听。
- 本地 terminal / proot / external CLI。
- 后台长期任务。
- 屏幕、录制、跨应用操作。
- iCloud / Android 特有集成的替代方案。

## 7. Gate 0：工具链解锁

Gate 0 是所有真实鸿蒙 spike 的前置条件。

### 7.1 必备条件

至少需要：

```text
DevEco Studio 或 Huawei Command Line Tools
hvigor 或 hvigorw
ohpm
hdc
HarmonyOS SDK
真机或模拟器 target
```

DevEco Studio GUI 不是唯一入口，Command Line Tools 也可以解锁命令行构建和部署。GUI 的价值主要在工程创建、自动签名、设备调试和 SDK 管理。

### 7.2 验证命令

在 worktree 中运行：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview
node scripts/harmony-inventory/check-harmony-gate0.mjs
```

期望输出：

```text
Gate 0 status: ready
```

如果仍是 `blocked`，读取：

```text
docs/harmony-gate0-report.md
```

### 7.3 Gate 0 完成标准

- `hvigor` 或 `hvigorw` 可执行。
- `ohpm` 可执行。
- `hdc` 可执行。
- `hdc list targets` 能看到真机或模拟器。
- 能用 DevEco 或官方 Command Line Tools 构建最小工程。
- 工程配置来自 DevEco 或可信模板，不由 AI 手写伪工程。

## 8. Phase 0：真实可行性 spike

Gate 0 ready 后，按顺序做三个 P0 spike。

### 8.1 Spike A：OpenAI-compatible SSE streaming

目标：证明 HarmonyOS NEXT 上可以稳定消费 OpenAI-compatible streaming。

验证项：

- HTTPS POST。
- headers。
- request body。
- streaming response。
- SSE `data:` 行。
- `[DONE]`。
- chunk 拆分。
- 多行 `data:`。
- provider error JSON。
- 网络中断、超时、取消。
- delta 到 UIMessage 的合并。

验收标准：

- 真机或模拟器上看到 token-by-token 输出。
- 断流后 UI 不死锁。
- `[DONE]` 后状态正确收敛。
- 合并结果与 source-only fixtures 一致。

这是 go/no-go 项。如果 HarmonyOS HTTP API 无法稳定 streaming，需要优先换底层实现，而不是继续做 UI。

### 8.2 Spike B：RDB + Preferences

目标：证明会话、消息和设置能在鸿蒙上可靠持久化。

验证项：

- RDB 建库和建表。
- 插入 conversation。
- 插入 message。
- 保存 role、content parts、时间戳和状态。
- 查询会话列表。
- 查询单个会话完整消息。
- 删除会话。
- Preferences 保存 provider config、theme 和基础设置。
- App 重启后数据仍在。

第一版最小 schema：

```text
conversations
messages
settings
```

schema 要预留分支字段：`nodeId`、`parentId`、`selectIndex`、`alternativeIndex`。

验收标准：

- 能新建会话。
- 能保存用户消息。
- 能保存 assistant streaming 完成后的消息。
- App 重启后能恢复。
- Provider base URL、API key 引用和 model id 可读写。

API key 开发态可以先跑通；正式版必须改安全存储。

### 8.3 Spike C：ArkUI + Markdown streaming render

目标：证明 ArkUI 可以承载核心聊天体验。

验证项：

- Chat list 页面。
- Message bubble。
- User / Assistant / System / Tool role 区分。
- Streaming 中持续刷新 assistant message。
- Markdown 基础渲染：paragraph、inline code、fenced code block、list、quote、link。
- 长消息滚动性能。
- Streaming 自动滚到底部。
- 用户手动上滑后不抢滚动。
- 失败消息可重试。

验收标准：

- 真机或模拟器上连续 streaming 不明显掉帧。
- Markdown 基础结构不乱。
- 1000 行以内长对话仍可滚动。
- Loading、cancel、error 状态完整。

## 9. Phase 1：正式鸿蒙工程骨架

### 9.1 工程创建

用 DevEco Studio 创建真实 HarmonyOS NEXT 工程。不要手写假的工程配置。

建议结构：

```text
harmony/
  app/
    entry/
    features/
      chat/
      settings/
      providers/
      assistants/
    core/
      model/
      storage/
      network/
      agent/
      markdown/
      security/
    shared/
      ui/
  spikes/
    p0-sse/
    p0-rdb/
    p0-arkui-markdown/
  tests/
    fixtures/
```

如果 DevEco 的模块管理或 ohpm workspace 支持不理想，第一版少拆模块。先让主链路跑起来。

### 9.2 基础设施

需要建立：

- ArkTS lint/format 规则。
- 构建命令。
- 本地运行说明。
- 真机调试说明。
- secrets 配置方式。
- fixture replay 脚本。
- 最小 smoke test。

验收标准：

- 新人按 README 能跑起来。
- 命令行能构建。
- DevEco 能调试。
- 不需要手工猜 SDK 路径。

## 10. Phase 2：Chat MVP

### 10.1 页面范围

第一版只做这些页面：

1. 会话列表。
2. 聊天页。
3. Provider 设置页。
4. 基础 Assistant 设置页。
5. Debug / logs 页面，可选。

不迁 Android 版所有页面。页面越多，迁移越慢，bug 越分散。

### 10.2 ArkTS 核心模型

先定义平台无关模型：

```text
UIMessage
MessagePart
MessageNode
Conversation
AssistantConfig
ProviderConfig
GenerationState
ToolCall
Usage
```

### 10.3 Provider 层

先做 OpenAI-compatible：

```text
baseUrl
apiKey
model
headers
temperature
maxTokens
stream
```

建议接口：

```text
ProviderClient.streamChat(request): AsyncGenerator<ChatChunk>
ProviderClient.cancel(requestId)
```

OpenAI-compatible 稳定后，再迁 Gemini、Claude、Zhipu、Ollama 等 provider。

### 10.4 Agent loop

第一版 Agent loop 保持简单：

```text
user message
→ transformer before send
→ provider streaming
→ accumulator merge chunks
→ transformer after finish
→ persist final message
```

先不做复杂 tool loop。ToolCall 类型可以保留，但执行器先禁用或 mock。

### 10.5 Streaming accumulator

职责：

- 接收 provider chunk。
- 合并 text delta。
- 合并 reasoning delta。
- 合并 tool call delta。
- 识别 finish reason。
- 生成 UI 可消费的 partial message。
- 生成最终 message。
- 处理错误和取消。

验收标准：

- source-only fixtures 可以 replay。
- 单行、多行、拆包、DONE、error 都能过。
- UI 不直接拼字符串，必须经过 accumulator。

### 10.6 Phase 2 性能预算（Gate 量化，2026-06-17 review 补入）

Phase 2 Gate 原本是"真机聊天 30 分钟不崩溃""5000 token 不卡死"等定性描述，无法客观验收。补充可量化预算，devise/真机测试时以这些数字为准；超预算的项必须记录在 `docs/harmony-capability-matrix.md` Evidence Log 并给出优化或降级结论。

| 指标 | 预算 | 测量场景 | 备注 |
|---|---|---|---|
| 流式渲染帧率 | ≥ 50 fps（P95） | 50 token/s streaming append + 增量 Markdown | 低于此则必须上 batching/throttling |
| 首帧延迟 | < 800 ms | 用户发送 → 第一个 token 到屏 | 含 SSE 首字节 + accumulator + 一次渲染 |
| 单 token 到屏延迟 | < 100 ms（P95） | accumulator 收到 chunk → UI 更新 | 不含 provider 网络往返 |
| 长列表滚动 P95 帧时间 | < 16 ms | 1000 条消息列表手动滚动 | LazyForEach + 复用 |
| 冷启动到会话列表 | < 2 s | 进程启动 → 列表可交互 | RDB 首次查询完成 |
| 取消生成响应 | < 200 ms | 用户点取消 → UI 停止追加 | 含 provider cancel + 状态收敛 |
| 内存峰值（单会话） | < 250 MB | 5000 token 长回复 + Markdown 渲染 | 监控 ArkTS heap |

测量方式优先用 DevEco Profiler / hdc 抓帧；若工具不可用，至少用打点日志记录时间戳区间。这些数字是 Phase 2 进入 Phase 3 的硬 gate，不是建议值。

### 10.7 i18n 前置约束（2026-06-17 review 补入）

i18n 原本在 Phase 5 才处理，但 `AGENTS.md` 明确要求 i18n，且权限 reason 多语言是**上架硬要求**。若 Phase 2 起硬编码中/英文字面量，到 Phase 5 抽取是已知最贵的重构之一。

**Phase 2 起的硬约束：**

1. 所有用户可见字符串（页面标题、按钮、错误提示、空状态、权限 reason）**从第一行代码起就走资源化**，使用 ArkUI 等价于 `stringResource` 的机制（`$r('app.string.xxx')` + `resources/` 目录）。
2. 代码里不得出现 `Text("发送")`、`Text("Send")` 这类硬编码字面量（调试日志、内部 key 除外）。
3. 先建 `zh-CN` 和 `en-US` 两套资源；新增功能时同步补齐两套 key。
4. 权限 reason 文案在声明权限的同一次提交里就补多语言。
5. Phase 2 的 smoke test 应包含一次"切换语言不崩、文案正确"的检查。

例外：临时 debug 页面、未定型的实验功能可临时硬编码，但必须在代码注释里标 `// i18n-todo`，且不得进入 release build。

## 11. Phase 3：功能补齐

### 11.1 Assistant 系统

优先迁移：

- assistant profile。
- system prompt。
- temperature。
- context size。
- custom headers。
- model settings。
- 每个会话绑定 assistant。

Prompt injection、lorebook、memory 类能力后置，避免拖慢 Chat MVP。

### 11.2 Message transformers

优先迁移纯逻辑 transformer：

- TemplateTransformer。
- ThinkTagTransformer。
- RegexOutputTransformer。
- DocumentAsPromptTransformer，等文档能力准备好后再做。
- OCR/image transformer 后置。

迁移方式：按输入/输出契约重写 ArkTS，不逐行翻译 Kotlin。

### 11.3 Markdown 和 code highlight

第一版只做基础 Markdown。第二阶段再做：

- code highlight。
- copy code。
- collapsed reasoning。
- tool result rendering。
- table。
- LaTeX，视产品需要决定。

### 11.4 搜索和联网工具

Exa、Tavily、Zhipu search 可以迁，但晚于 Chat MVP。

迁移前先确认：

- HarmonyOS 网络权限。
- 后台请求限制。
- API key 安全存储。
- Web content extraction 能力。

## 12. Phase 4：平台能力替代

这些能力不能假设 1:1 可迁，必须逐项验证。

### 12.1 Accessibility

Android 的 `AccessibilityService` 不能直接复用。

鸿蒙侧要验证：

- AccessibilityExtensionAbility 是否能读取目标应用 UI tree。
- 是否能执行点击、输入、滑动。
- 是否能跨应用稳定工作。
- 是否需要特殊权限或审核。
- 是否能覆盖 WebView、Canvas、不可读区域。
- 是否有后台限制。

可能结果：

| 结果 | 处理 |
|---|---|
| 能实现 | 做鸿蒙原生 phone agent |
| 只能部分实现 | 降级为半自动辅助 |
| 权限或能力不允许 | 改为桌面端、云端或手动确认方案 |

### 12.2 通知监听

要验证：

- 是否能读取其他应用通知。
- 是否需要用户授权。
- 是否允许上架。
- 后台运行限制。

如果受限，改成：

- 只读本应用通知。
- 用户手动分享内容到 AmberAgent。
- 通过系统分享入口接入。

### 12.3 本地 terminal / proot / external CLI

Android 版的 terminal、external CLI、ModelCouncil external runner 风险较高。

要验证：

- 是否允许启动本地进程。
- 是否允许长期运行 shell。
- 文件系统权限边界。
- 上架审核风险。

替代方案：

- 远程执行器。
- 局域网 agent。
- 云端 sandbox。
- 只保留轻量本地工具。

### 12.4 Rust NAPI

Rust 不是 Chat MVP 生死门。

迁移策略：

- P0 全部保留 ArkTS fallback。
- Rust 只迁纯逻辑和性能热点。
- 不把 UI、平台权限、HTTP streaming 主链路绑死到 Rust。

适合迁 Rust 的内容：

- parser。
- tokenizer。
- diff/patch。
- search index。
- crypto/hash。
- markdown/code highlight 性能热点。

不适合一开始迁：

- Android JNI 绑定。
- 文件权限相关逻辑。
- UI 状态。
- provider streaming 主链路。

## 13. Phase 5：体验打磨

### 13.1 UI

ArkUI 侧要做鸿蒙原生体验，而不是 Android Compose 的翻版。

重点：

- Navigation。
- responsive layout。
- foldable/tablet 适配。
- dark mode。
- keyboard avoidance。
- selection/copy。
- empty state。
- error state。
- settings 信息架构。

### 13.2 性能

性能基线：

```text
100 messages
1000 messages
单条 20k chars
streaming 10 token/s
streaming 50 token/s
```

重点观察：

- 长会话滚动。
- streaming 高频刷新。
- Markdown 渲染。
- 数据库查询。
- 首屏启动。
- memory leak。

### 13.3 安全

至少处理：

- API key 不进日志。
- crash log 不包含 prompt 全文，或提供开关。
- provider config 加密或安全存储。
- debug 页面不默认暴露 secrets。
- prompt injection fixtures 保留。

## 14. Phase 6：测试和发布

### 14.1 测试分层

测试分四层：

1. source-only contract tests：用现有 fixtures 验证 parser、accumulator、transformer。
2. ArkTS unit tests：验证纯逻辑。
3. integration tests：验证 provider、RDB、Preferences。
4. device smoke tests：验证真机 UI、streaming、重启恢复。

### 14.2 发布前 checklist

- DevEco release build 通过。
- 签名完成。
- 权限列表审查完成。
- API key 存储方案审查完成。
- 首次启动流程完成。
- 网络异常场景完成。
- 数据迁移策略明确。
- 隐私说明准备好。
- 不可实现的 Android 能力有产品降级说明。

## 15. 里程碑

### M0：工具链 ready

交付物：

- DevEco Studio 或 Command Line Tools 可用。
- SDK 可用。
- `hdc list targets` 能看到设备。
- `check-harmony-gate0.mjs` 输出 `ready`。

### M1：三个 P0 spike 通过

交付物：

- SSE streaming demo。
- RDB/Preferences demo。
- ArkUI Markdown streaming demo。
- 更新 capability matrix。

### M2：工程骨架

交付物：

- DevEco 生成的真实工程。
- 基础目录结构。
- README。
- 构建命令。
- smoke page。

### M3：Chat MVP

交付物：

- Provider 设置。
- 会话列表。
- 聊天页。
- 流式回复。
- 本地保存。
- App 重启恢复。

### M4：Assistant + transformer

交付物：

- Assistant 配置。
- System prompt。
- 基础 transformers。
- Reasoning 展示。
- 错误恢复。

### M5：P1 功能

交付物：

- 多 provider。
- Markdown 增强。
- 搜索工具。
- 文档 prompt。
- TTS，视平台能力决定。

### M6：平台高级能力验证

交付物：

- Accessibility 验证报告。
- 通知能力验证报告。
- 本地 CLI/terminal 验证报告。
- 降级方案。

### M7：Beta

交付物：

- release build。
- 签名。
- 崩溃日志。
- 隐私说明。
- 已知问题列表。
- 反馈入口。

## 16. 第一周执行清单

### Day 1：工具链

- 安装 DevEco Studio 或 Command Line Tools。
- 安装 HarmonyOS SDK。
- 配好 `ohpm`、`hvigor`、`hdc`。
- 接入真机或模拟器。
- 跑通 Gate 0。

### Day 2：最小工程

- 用 DevEco 创建最小 HarmonyOS NEXT 工程。
- 不手写工程配置。
- 建一个空白 Chat 页面。
- 命令行构建通过。
- 真机运行通过。

### Day 3：SSE spike

- 写 OpenAI-compatible streaming demo。
- 接入测试 provider。
- 打印 chunk。
- 处理 `[DONE]`。
- 接入 source-only SSE fixtures。

### Day 4：RDB/Preferences spike

- 建最小 schema。
- 保存 conversation、message、settings。
- 重启恢复。
- 写一组最小验证。

### Day 5：ArkUI chat spike

- Chat list。
- Message bubble。
- Streaming 更新。
- 基础 Markdown。
- 错误和取消状态。

### Day 6：整合

- provider → accumulator → UI → storage。
- 跑一轮真实对话。
- 修 streaming 和持久化边界 bug。

### Day 7：review

- 更新 capability matrix。
- 写 Phase 0 真实 spike 报告。
- 让 oracle/subagent review。
- 决定进入 MVP，还是调整技术路线。

## 17. 风险表

| 风险 | 影响 | 应对 |
|---|---|---|
| HarmonyOS streaming API 不稳定 | Chat 主链路受阻 | 先做 SSE spike，必要时换底层实现 |
| ArkUI Markdown 性能差 | 长消息体验差 | 先最小 Markdown，再局部优化 |
| RDB schema 设计过早复杂 | 迁移慢 | MVP 简化，但保留扩展字段 |
| Accessibility 权限受限 | phone agent 能力缩水 | 不阻塞 Chat，单独验证 |
| 通知监听不可用 | 自动上下文能力缩水 | 用分享入口或手动导入替代 |
| Rust NAPI 工具链复杂 | 拖慢 MVP | P0 全部 ArkTS fallback |
| DevEco 工程配置不稳定 | 构建成本高 | 只使用 DevEco 生成/验证模板 |
| API key 存储不安全 | 发布风险 | 早做安全存储方案 |
| 想 1:1 搬 Android 功能 | 工期失控 | P0/P1/P2 分层，不做全量平移 |

## 18. 当前暂停点

当前应停在：

```text
Phase 0 source-only complete
Gate 0 blocked by local toolchain/device setup
```

下一步必须先解锁工具链：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview
node scripts/harmony-inventory/check-harmony-gate0.mjs
```

看到下面结果后，再开始真实鸿蒙代码：

```text
Gate 0 status: ready
```
