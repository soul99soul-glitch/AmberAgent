# Prompt: 继续完成 iOS Provider 体系对齐工作

## 背景

你将接手一个 iOS 移植项目（仓库根目录有 `iosApp/` 和 KMP 模块），继续完成"服务商(Provider)体系对齐 Android + 补齐 Anthropic(Claude)"的工作。

上一个会话已经完成了 **阶段 1-5**（全部提交在 `feat/ios-provider-parity-claude` 分支），还剩 **阶段 6 收尾** 和 **端到端验证**。

**开始前，请务必先读这份交接文档**：`docs/ios-port/PROVIDER_PARITY_HANDOFF.md` —— 它包含完整的背景、架构说明、文件地图、已完成的阶段、命令、已知问题。下面的 prompt 基于它。

---

## 你的任务

### 任务 1：端到端验证（最高优先级，需要真实 API Key）

阶段 1-5 都编译通过、单测通过，但**从未在模拟器里真正发过消息**验证 Claude/OpenAI 链路。请你做这个验证：

1. 先切换到工作分支：
   ```bash
   git checkout feat/ios-provider-parity-claude
   ```
2. 构建并装进模拟器（**必须用 arm64 模拟器**，见交接文档的命令；`generic/platform=iOS Simulator` 会因缺 x86_64 framework 切片而链接失败）。
3. **OpenAI 兼容回归验证**：配一个 OpenAI 兼容 provider（比如 DeepSeek：`https://api.deepseek.com/v1` + 你的 key + `deepseek-chat` 模型），发一条消息，确认流式输出正常、没有回归。
4. **Claude 验证（核心）**：用 "+" 添加一个 Anthropic provider（协议选 Anthropic，base `https://api.anthropic.com/v1`，key 填真实的 sk-ant-...，模型 `claude-sonnet-4-5`），发消息验证：
   - 走的是 `/messages` SSE 端点（不是 `/chat/completions`）
   - 流式输出正常
   - 如果该模型支持 thinking，reasoning 部分能正确解析
   - 工具调用（如 web search）能触发并执行
5. 验证服务商 UI 流程：列表点模板行→进详情→填 Key→设为当前；"当前聊天配置"显示真实 provider 名（不是 "OpenAI-compatible"）。

**如果发现问题，先修 bug 再继续。** 把发现的问题和修复都记录下来。如果遇到 Claude 执行器的运行时错误（比如 SSE 解析、JSON 格式、thinking 字段），问题很可能在 `ai-provider-claude/src/commonMain/kotlin/app/amber/ai/provider/claude/ClaudeKmpProvider.kt`，对照 Android 原版 `ai/src/main/java/app/amber/ai/provider/providers/ClaudeProvider.kt` 排查。

### 任务 2：阶段 6 收尾（验证通过后做）

1. **Gemini 诚实灰显**：`iosApp/iosApp/ProvidersView.swift` 的 `statusDescriptor` 里，`.googleProviderPreset` 的文案从"预置 · 待桥接"改成更清楚的说明，如"预置 · Gemini 执行器待移植"。Gemini 行保持 disabled。
2. **清理死代码**：
   - `ProvidersView.swift` 里 `ProviderProtocolOption.google`/`.custom` 在 `ProviderAddView.save()` 的拦截逻辑——确认 UI 不暴露这些 case 后，可以保留拦截作为防御，或清理。
   - `ChatViewModel.swift` 里 `makeLegacyOpenAIProviderSetting()` 如果确认无引用，删除。
   - `ProviderAddView` 里移除的旧 state（`path`/`responseAPI`/`balanceRefresh`）确认没有残留引用。
3. **更新 parity 文档**：找到 `docs/ios-port/` 下的 gap-audit 文档（类似 `IOS_ANDROID_PARITY_GAP_AUDIT_*.md`），记录：provider 列表/UI/编辑器/Claude 执行器 已对齐；Gemini 执行器 / OAuth provider / KeyRoulette / 余额查询 仍缺。

### 任务 3（可选，如果时间允许）：评估 ChatViewModel 拆分

`iosApp/iosApp/ChatViewModel.swift` 是 3490 行的 god class，混了 8 个职责。交接文档"已知问题"里有职责盘点。**这只是评估**，不要急着拆——先给一个拆分方案（按职责切哪些文件、各自边界、风险点），让用户确认后再动手。

---

## 重要约束

1. **Java 环境**：构建前必须 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`，否则 Gradle daemon 起不来。
2. **iOS 模拟器架构**：构建用 `platform=iOS Simulator,name=iPhone 17`（arm64）。不要用 `generic/platform=iOS Simulator`（会链接 x86_64 失败）。启动的模拟器可能是 iPhone 17（不是 Pro）。
3. **不要碰预先存在的失败**：`IOSCouncilRunnerMechanicsTests` 的 2 个失败是仓库里一个 in-flight 的 `CouncilRunner.swift` 席位流程编辑导致的，**不是本次 provider 工作的问题**，已验证撤掉所有 provider 改动照样失败。除非用户明确要求，别去修它。
4. **不要碰未提交的非 provider 文件**：工作区里有些 `M` 状态文件（`CouncilChatRuntimeView.swift`、`IOSLocalToolExecutor.swift`、`IOSPermissionModels.swift` 等）是更早会话的 in-flight 编辑，跟本次工作无关，别提交它们。
5. **提交规范**：每个逻辑单元一个 commit，commit message 用 `feat(ios): ...` / `fix(ios): ...` 前缀，保持和现有 history 一致。继续在 `feat/ios-provider-parity-claude` 分支上提交。
6. **API Key 安全**：如果要做端到端验证需要用到 API Key，**不要把任何真实 key 写进代码或提交**。只在模拟器 UI 里临时填写，或用环境变量。

---

## 第一步建议

先读 `docs/ios-port/PROVIDER_PARITY_HANDOFF.md` 全文，然后跑一遍构建确认环境正常，再开始任务 1 的端到端验证。遇到任何和交接文档描述不符的情况，先报告给用户，不要自行假设。
