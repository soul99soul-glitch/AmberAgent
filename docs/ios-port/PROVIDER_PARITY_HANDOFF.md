# iOS Provider 体系对齐 Android — 工作进度与交接

> 分支：`feat/ios-provider-parity-claude`
> 最近更新：2026-06-20
> 状态：**阶段 1-5 已完成并提交，阶段 6（收尾）未做，端到端真机验证未做（需真实 API Key）**

## 一、背景：要解决什么问题

iOS 移植的服务商(Provider)体系与 Android **架构根本没对齐**，导致一系列 UX 问题：

1. **服务商模板灰色点不动**：列表里的预设 provider（DeepSeek/Kimi/智谱…）大多是灰的，点了没反应。
2. **"当前聊天配置"显示莫名其妙的 "OpenAI-compatible"**：文案写死，和用户实际选的 provider 无关。
3. **右上角 "+" 添加 provider 残缺**：不能设模型、协议写死成只读。
4. **只支持 OpenAI 兼容协议，不支持 Anthropic(Claude)**。

**根因**：Android 的服务商体系围绕"一个持久化的 provider 列表 + 按当前模型反查 provider"建立；iOS 移植时把它拆成了 3 个孤立标量（`baseUrl` / `apiKey` / `modelId`），发消息时临时构造一个 `ProviderSetting.OpenAI`，既不查 provider 列表，也不支持多协议。

## 二、Android 正确架构（对齐目标）

```
Settings.providers: List<ProviderSetting>  ← 持久化列表，每个 provider 自带 apiKey/baseUrl/models/协议类型
                                           ← 整体 JSON 存进 DataStore（iOS 侧是 IOSSharedSettingsStore.snapshot）

发消息链路：
  当前聊天模型 chatModelId  →  findModelById  →  model.findProvider(providers)  →  按 sealed 类型分发
                                                                                      ├─ OpenAI  → OpenAIProvider
                                                                                      ├─ Claude  → ClaudeProvider
                                                                                      └─ Google  → GoogleProvider

关键点：不存在 currentProvider 字段。"当前 provider"由"当前模型"反查得到。
       API Key 存在 ProviderSetting 内部（不是独立的 Keychain 槽）。
```

## 三、关键文件地图（改动集中在这里）

### KMP / Kotlin 层
- `ai-provider-claude/`（**新建模块**）：`build.gradle.kts`、`ClaudeKmpProvider.kt`、`SseEvent.kt`
- `ai-provider-openai/src/commonMain/.../OpenAIKmpProvider.kt`：KMP 版 OpenAI 执行器（Claude 模块的样板）
- `ai/src/main/java/.../providers/ClaudeProvider.kt`：Android-only 的原版 Claude 执行器（移植来源，**不要直接动**）
- `shared/src/commonMain/kotlin/shared/IosSettingsMutations.kt`：Swift-facing 的 Settings 变更函数。**新增**了 `buildClaudeProvider` / `updateProviderApiKey` / `updateProviderChatModels` / `setChatModelId`
- `shared/build.gradle.kts`：`sharedProjects` 列表里加了 `:ai-provider-claude`
- `settings.gradle.kts`：`include(":ai-provider-claude")`
- `core/types/.../Settings.kt`：`findModelById` / `getCurrentChatModel` / `Model.findProvider(providers, checkOverwrite)` 都在这里（**已存在，KMP 可达**）

### Swift 层
- `iosApp/iosApp/ChatViewModel.swift`（**3490 行 god class**）：聊天主链路。改了 `makeProviderSetting()`（返回 `ProviderSetting?`）、新增 `dispatchStream()`（按 provider 类型分发）、`claudeProvider`、`.missingProvider` 配置 issue
- `iosApp/iosApp/ProvidersView.swift`：服务商列表 + 添加页。模板行可点进详情、真实 provider 名、协议选择器、模型字段
- `iosApp/iosApp/ProviderDetailView.swift`：详情页。Claude 预设可填 Key
- `iosApp/iosApp/ProviderKeyEditView.swift`：Key 编辑器。Key 写进真实 ProviderSetting + "设为当前"
- `iosApp/iosApp/IOSSharedSettingsStore.swift`：新增 `updateProviderApiKey` / `updateProviderChatModels` / `setCurrentChatModelId` / `addProvider` 的 real-snapshot 写回
- `iosApp/iosApp/IOSAgentToolEngine.swift`：`IOSAgentTextProvider` 协议 + `OpenAIKmpProviderAdapter` 泛化为支持 OpenAI/Claude 双协议
- `iosApp/iosApp/CouncilRunner.swift`：`IOSCouncilTextStreamer` 加 `claudeProvider` + `dispatchCouncilStream()`
- `iosApp/iosApp/AppShell.swift`：路由调用点传 `sharedSettings`

## 四、已完成阶段（1-5，已提交）

| Commit | 阶段 | 内容 | 验证 |
|---|---|---|---|
| `778ec222d` | **1. Claude 执行器** | 新建 `:ai-provider-claude` KMP 模块，完整移植 ClaudeProvider（thinking/tools/prompt caching/SSE），接入 framework | `./gradlew` 编译通过 + 4 单测全过，`ClaudeKmpProvider` 进 ObjC 头文件 |
| `bbc37d661` | **3. 列表 UI** | 模板行可点进详情页（不再死胡同）；"当前聊天配置"显示真实 provider 名；Gemini 仍灰但文案清楚 | 编译通过 |
| `0dcbd97d4` | **4. 编辑器** | 填 Key / 设模型 / 选协议(OpenAI/Anthropic)；Key 写进真实 ProviderSetting | 编译通过，19 个 provider 测试通过 |
| `8073bce1f` | **2. 聊天链路** | `makeProviderSetting()` → 按当前模型反查 provider；`dispatchStream()` 分发 OpenAI/Claude；~40 个调用点泛化 `ProviderSetting.OpenAI`→`ProviderSetting` | 编译通过，8 个 provider 测试通过 |
| `4877f2d49` + `66538bb7d` | **5. 子代理/理事会** | `IOSAgentToolEngine` / `IOSCouncilTextStreamer` / `SubAgentRunner` / `IOSBoardPersistence` 全部支持 Claude | 编译通过 |

## 五、未完成 / 待做（交接给 Codex）

### 阶段 6：收尾（小，优先做）
1. **Gemini 诚实灰显**：Gemini 行保持灰色，文案从"待桥接"改成清楚说明（如"Gemini 执行器待移植，暂不可用于聊天"）。位置 `ProvidersView.swift` 的 `statusDescriptor`。
2. **清理死代码**：`ProviderProtocolOption.google`/`.custom` 在 `save()` 的拦截保留但 UI 不暴露；`responseAPI`/`customPath`/`balanceRefresh` 死分支确认无害或删除。`makeLegacyOpenAIProviderSetting()` 在 ChatViewModel 里如果确认无引用可删。
3. **更新 parity 文档**：`docs/ios-port/` 下的 gap-audit 文档，记录本次对齐完成项 + Gemini 仍缺。

### 端到端验证（需真实 API Key，重要）
- 在模拟器里配一个 OpenAI 兼容 provider → 发消息验证流式正常、不回归。
- 配一个 Claude provider（`https://api.anthropic.com/v1` + key + `claude-sonnet-4-5`）→ 发消息验证 `/messages` SSE 正常、thinking、工具调用。
- 验证服务商列表点行进详情、填 Key、设为当前、添加 provider 能填模型。
- **注意**：我（上一个会话）没法做这个，因为需要真实 API Key。

### 已知问题（非本次工作引入，但需知晓）
1. **`IOSCouncilRunnerMechanicsTests` 2 个测试失败**：是**预先存在的**，由工作区里一个还没改完的 `CouncilRunner.swift` 席位流程编辑导致（运行 7 个席位而非 4 个，状态显示 failed 而非 completed）。已验证：把 provider 对齐所有改动撤掉后这 2 个测试**照样失败**。不是本次工作的问题。
2. **`ChatViewModel.swift` 是 3490 行的 god class**：混了 8 个职责（流式/provider解析/7种消息注入/6种工具执行器/消息分支/审批状态机/MiniApp/UI状态）。本次工作又往里加了 `dispatchStream`/`claudeProvider` 等。**拆分是独立的架构债工程**，建议单独立项，不在 provider 对齐范围内。

## 六、构建/测试命令

```bash
# KMP 编译 + Claude 模块单测
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :ai-provider-claude:jvmTest          # 4 个测试
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64  # 验证 framework 含 ClaudeKmpProvider

# iOS 构建（注意：必须用 arm64 模拟器，不是 generic/x86_64）
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -configuration Debug build

# iOS 测试
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -configuration Debug test \
  -only-testing:iosAppTests/ProviderRegistryStoreTests \
  -only-testing:iosAppTests/IOSSharedSettingsStoreProvidersWriteBackTests

# 检查 ClaudeKmpProvider 是否进了 ObjC 头文件
grep "ClaudeKmpProvider" shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h
```

## 七、本次工作**未涉及**的范围（显式排除）
- Gemini 执行器 KMP 模块（显式排除，灰显处理）
- OAuth 类型 provider（Codex OAuth / Gemini Code Assist OAuth）
- KeyRoulette 多 key 轮换
- Provider 余额查询（balanceOption UI）
- Service Account / Vertex AI
- `ChatViewModel.swift` 的拆分重构（god class，建议独立立项）
