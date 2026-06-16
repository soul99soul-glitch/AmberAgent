# iOS 全能力接线计划 — 2026-06-16

> 目标：把所有"有 UI 但能力未接线"的页面全部接通，直到没有空的 UI 界面。
> 用户自行输入的内容（Provider API Key、自定义设置等）不算在内。
>
> 当前进度：阶段 1/2/3 已完成（数据源 + 9 页只读 + 数据深化），阶段 4 Council 执行链已打通（stub）。
> 本计划覆盖剩余全部缺口。

## 当前状态（截至 HEAD 81ebbb27）

### 已完成
- ✅ 9 个设置页读到真实 KMP seed Settings（TTS/Search/Display/ModelDefaults/Memory/Council/SubAgent/Board/MiniApp）
- ✅ Provider 读+写 Keychain + 切换投影
- ✅ Council 角色预设 + Search 服务实例 + 真实数据深化
- ✅ feature:task + core/app-infra + feature/modelcouncil 三模块 KMP 化
- ✅ iOS 调通 ModelCouncilManager.start()（stub runner）

### 剩余缺口（251 处"未接线"标记，33 个文件）

---

## 按工作量分 4 个阶段执行

### 阶段 5：纯 Swift 接线（低成本，无需 KMP 改动）

这些页面所需的真实数据**已在 commonMain 且已 export**，只需 Swift 端读取即可。

| 序 | 页面 | 接什么 | 数据来源 | 工作量 |
|---|---|---|---|---|
| 5.1 | **ExecutionSettingsView** | tool loop/preview/generativeUi/retry/keepalive 从草稿改为读 `sharedSettings.agentRuntime` 真实值 | `agentRuntime.maxToolLoopSteps/operationPreviewMode/generativeUi/generationRetry/keepGenerationAlive`（已在 core/types commonMain） | 小（~5 行/字段） |
| 5.2 | **CouncilSettingsView** | 席位字段从草稿改为读 `sharedSettings.agentRuntime.modelCouncil` 真实值 | `modelCouncil.defaultSeats/maxSeats/defaultRounds` | 小 |
| 5.3 | **SeatEditorView** | 席位编辑读真实 `ModelCouncilSeat` 列表 | `sharedSettings.agentRuntime.modelCouncil.defaultSeats` | 小-中 |
| 5.4 | **BoardSettingsView** | 看板字段从草稿改为读 `sharedSettings.agentRuntime.todayBoard` | `todayBoard.enabled/enabledSources/hotList*` | 小 |
| 5.5 | **ProvidersView 草稿行** | 清除残留的"本地草稿"行（Response API/余额刷新），改为诚实降级文案 | 现有 ProviderRegistryStore | 小 |
| 5.6 | **ProviderDetailView 草稿行** | 同上，清除草稿行，标注真实状态 | 现有 | 小 |
| 5.7 | **PlaceholderViews 设置首页行** | 各行 subtitle 从"未接线"改为反映真实接入状态 | 随各子页更新 | 小 |

**验证**：每页 `git diff --check` + `xcodebuild` BUILD SUCCEEDED + subagent review（调用链闭环）。

---

### 阶段 6：纯数据下沉到 commonMain（中低成本）

这些模块的真实数据是纯 Kotlin object/常量，只需从 Android `src/main` **复制**到 commonMain（参照 Council RolePresets 和 SettingsDefaults 的模式）。

| 序 | 数据 | 当前位置 | 目标位置 | 工作量 |
|---|---|---|---|---|
| 6.1 | **SubAgentDefinitions.builtIns** | `feature/subagent/src/main`（纯 Kotlin，0 平台依赖） | `feature/subagent/api/src/commonMain` 或新 commonMain 文件 | 小（移文件 + export） |
| 6.2 | **SubAgentRoleView 接线** | 接 6.1 的真实内置角色 | 纯 Swift 读 `SubAgentDefinitions.shared.builtIns` | 小 |
| 6.3 | **ModelCouncilExternalCliToolRegistry** | 已在 commonMain（Slice 31） | iOS 展示真实可用外部工具列表 | 小 |
| 6.4 | **McpServerConfig 默认/样例** | 检查 `McpManager` 里有没有内置默认 MCP 配置是纯数据 | 如有，移 commonMain | 小-中 |

**验证**：compileKotlinJvm + compileKotlinIosSimulatorArm64 + Shared.h 确认符号 + xcodebuild。

---

### 阶段 7：中量 KMP 模块化（每个独立切片）

这些模块有少量平台依赖（Context/File/Log），参照 feature:task 的模式 KMP 化。

| 序 | 模块 | 平台依赖 | KMP 化方式 | 解锁的 UI | 工作量 |
|---|---|---|---|---|---|
| 7.1 | **MemoryRepository** | 0 个平台依赖（已确认） | 直接移 commonMain + export | MemoryEditView（记忆读写） | 小-中 |
| 7.2 | **SkillManager** | 3 个（Context/File/Log） | 抽 expect TaskFile（复用 feature:task 的）+ Logger | SkillsView/SkillDetailView（扫描/读取 SKILL.md） | 中 |
| 7.3 | **McpManager** | 3 个（Log/toUri/MimeTypeMap） | 抽 Logger + Ktor engine expect-actual + 去 WebView 依赖 | McpServersView（配置加载/import 解析） | 中 |
| 7.4 | **ConversationRepository** | 查精确依赖 | 文件计数/删除（复用 TaskFile） | ConversationStorageView | 中 |
| 7.5 | **StatsVM** | 5 个（ViewModel/Room DAO/Context） | DAO 走 Room KMP 样板 + Swift VM 层 | AccountView 统计 | 中-大 |

**每个模块的通用 KMP 化步骤**（参照 feature:task Slice 29）：
1. build.gradle.kts：android.library → kotlin.multiplatform + jvm()/iosArm64()/iosSimulatorArm64()
2. 源码：src/main → commonMain（纯逻辑）+ jvmMain/iosMain（平台 actual）
3. 抽 expect-actual：Context→String 路径、File→TaskFile、Log→logE
4. shared/build.gradle.kts 加 export
5. 改 Android DI（构造参数变了）
6. 双端编译验证 + Shared.h 符号 + xcodebuild + subagent review

---

### 阶段 8：iOS 原生重写（大工程，按产品优先级单独排期）

这些模块强依赖 Android 平台 API（WebView/ExoPlayer/GoogleDrive），iOS 需从零写原生实现。

| 序 | 模块 | 锁定的 Android API | iOS 等价物 | 工作量 |
|---|---|---|---|---|
| 8.1 | **TTS 播放** | ExoPlayer + android.speech.tts | AVAudioPlayer + AVSpeechSynthesizer + Darwin Ktor engine | 大 |
| 8.2 | **WebMount** | Android WebView + OAuth + Cookie + WebViewPool | WKWebView + ASWebAuthenticationSession + HTTPCookieStorage | 大 |
| 8.3 | **MiniApp 运行** | WebView + Room + bridge.js + 系统桥 | WKWebView + SQLite + bridge 注入 + 权限 | 大 |
| 8.4 | **Sync/Backup** | BackupVM + Google Drive SDK + 加密 | ASWebAuthenticationSession + Google Drive API + CryptoKit | 大 |
| 8.5 | **Council 真实推理** | ModelCouncilManager stub → 真实 ProviderManager | :ai ProviderManager KMP 化 或 iOS 端 OpenAIKmpProvider 注入 | 大 |
| 8.6 | **Chat token 估算** | （Android 也无） | iOS 端实现 tokenizer | 大（双方都缺） |

**每个 iOS 重写的通用步骤**：
1. 研究对应 Android 实现，提取核心协议/接口
2. 在 commonMain 定义接口（参照 ModelCouncilTextRunner 模式）
3. iOS iosMain 写 actual 实现（用 iOS 原生 API）
4. Swift UI 接线
5. 双端编译 + 运行 + subagent review

---

## 执行策略

1. **严格按阶段顺序**：阶段 5（纯 Swift）→ 阶段 6（纯数据下沉）→ 阶段 7（中量 KMP）→ 阶段 8（iOS 重写）
2. **每阶段内按序号顺序**，逐个切片
3. **每个切片独立提交**，commit message 写清楚接了什么
4. **每个切片完成后 subagent review**，重点查调用链闭环/断裂/造假/Android 回归
5. **P0/P1 必须修完再进下一切片**
6. **同一问题连续失败两次后暂停问用户**
7. **更新审计文档** IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md

## 验证标准（每个切片通用）

- `git diff --check`
- `compileKotlinJvm` + `compileKotlinIosSimulatorArm64`（涉及 KMP 改动时）
- `shared:linkDebugFrameworkIosSimulatorArm64`（涉及 export 时）
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` 启动验证（能跑就跑）
- subagent review（调用链闭环/断裂/造假/Android 回归）

## 环境事实

- Xcode 26.5，xcode-select 已切完整 Xcode
- XcodeGen 生成工程，新增 .swift 后需 `xcodegen generate`
- Shared.framework 需 `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`（DEVELOPER_DIR 指向完整 Xcode）
- iOS 26.5 模拟器运行时已安装
- 本机无 Android SDK（用 compileKotlinJvm 验证 Android target；Android app 完整编译跳过）
- idb 未装，只做 build/run/screenshot
- Clock 用 kotlin.time.Clock（不是 kotlinx.datetime.Clock）
- KMP 模板参照 core/agent-store-room（jvm()/iosArm64()/iosSimulatorArm64()，无 android{} 块）
- expect-actual 参照 feature/task/src/TaskFile
- 不要 git reset/force push/删分支
- 不要 stage .xcodeproj/构建产物/无关脏文件

## 不算在内（用户自行输入）

- Provider API Key 编辑（Slice 24 已完成）
- 自定义设置值输入
- 用户自己创建的 Skill/MCP/Provider/Memory 条目
