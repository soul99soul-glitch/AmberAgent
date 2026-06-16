# iOS 能力桥接 Plan — 2026-06-16

> 目的：把 iOS UI 上大量"未接线/草稿/待桥接"页面，按真实成本分档，给出"哪些能接、怎么接、接的代价"的可执行路线。
>
> 一句话结论：**没有统一的"一键接桥"。能力分布决定了三种完全不同的接法。当前"未接线"几乎全是 Android 有实现、iOS 缺实现，不是"没能力"。**

## 0. 根本原因（为什么一直没接）

iOS 只能直接调用 `Shared.framework` 里 `export()` 出来的类型。查证 `shared/build.gradle.kts:17-47`：导出的是一批 KMP 模块（`ai-core`、`core:types`、各 `feature:*:api`、`core:agent-store-room` 等），但**只有"纯数据/设置类"能力真正进了 commonMain 并导出**（`Settings`、`DEFAULT_PROVIDERS`、`TTSProviderSetting`、`SearchServiceOptions`、`ModelRegistry`、`SyncSettings`）。

而 UI 上那些 Manager/VM/Repository：
- 一部分在 Android 专属的 `app/`、`tts/` 模块里，**强依赖 Android 平台 API**（WebView/Room/GoogleDrive/`android.speech.tts`）——iOS 根本 import 不到。
- 一部分在 `feature/*` 实现模块里（如 `feature/modelcouncil`、`feature/subagent`），逻辑平台无关，**但模块没声明 iOS target、没被 export**——困在 Android 构建里。

所以"桥"分三档，难度天差地别。

---

## 1. 三档总览

| 档位 | 含义 | 代表能力 | 当前 UI 状态 | 建议 |
|---|---|---|---|---|
| **第一档·已接/近接** | 纯数据/设置类，已在 `Shared` export | Provider/Model、TTS 默认、ModelRegistry、系统 TTS | ✅ 已接 | 维持 |
| **第二档·真桥** | 纯 Kotlin 逻辑，困在 Android 模块，加 target+抽 expect-actual+export 即可 | Council、SubAgent、Memory、MCP（部分） | A 类降级 | **优先做，性价比最高** |
| **第三档·重写** | 强依赖 Android 平台 API，iOS 无等价物，需从零写原生实现 | WebMount、MiniApp、Stats、Backup、Skill 扫描、TTS 云端播放 | A/C 类降级 | 按产品优先级单独排期 |

---

## 2. 第一档：已接 / 近接（无需新工作）

| 能力 | 模块 / 符号 | iOS 接法 | 状态 |
|---|---|---|---|
| Provider 模板 / 模型能力门控 | `DEFAULT_PROVIDERS`、`ModelRegistry.MODEL_ABILITIES` | `import Shared` 直接读 | ✅ Slice 21/22/24 |
| 当前聊天配置（baseUrl/apiKey/modelId） | `SettingsStore`（本地，镜像到 `ChatViewModel`） | SwiftUI `@Bindable` | ✅ Slice 2/5 |
| TTS 默认值 | `DEFAULT_TTS_PROVIDERS` = SystemTTS | 读 KMP 默认 | ✅ Slice 3 |
| 搜索服务**选项数据** | `SearchServiceOptions` | 读展示 | ⚠️ 已展示，执行未接 |

**这批不存在"为什么没接"——它们接了。**

---

## 3. 第二档：真桥（纯 Kotlin，加 target + 抽 expect-actual + export）

> 这是**唯一能真正减少"未接线"数量、且成本可控**的档位。核心动作：把平台无关的 Kotlin 逻辑搬进 `commonMain`，抽掉 `Context`/`File`/`Log` 等少量平台依赖，加 iOS target，再 `export()` 给 `Shared`。

### 3.0 通用改造模式（所有第二档模块通用）

1. 模块 `build.gradle.kts` 的 plugin 从 `android.library` 改 `kotlin.multiplatform`，加 `iosArm64()` + `iosSimulatorArm64()`。
2. 把 `src/main` 改成 `src/commonMain`（平台无关逻辑）。
3. 抽平台依赖为 `expect/actual`：
   - `android.content.Context` → 抽成"路径提供者 / 资源提供者"接口，`actual` 由 Android/iOS 各自注入。
   - `android.util.Log` → `expect class Logger`（Android 用 `Log`，iOS 用 `os_log`/`print`）。**`core:app-infra` 抽一个 Logger 全局复用。**
   - `java.io.File` → 走 `okio.Path` 或 `expect class AppFile`。
   - Ktor engine → `expect` HTTP engine（Android OkHttp / iOS Darwin）。
4. 在 `shared/build.gradle.kts` 的 `sharedProjects` 加该模块 → `export()`。
5. 重建 `Shared.framework`，Swift 直接调。
6. iOS UI 接真实调用，从 A 类降级改成"已接"。

### 3.1 模块清单与工作量（按依赖顺序）

| 序 | 模块 | 平台依赖（实测） | iOS target | 工作量 | 解锁谁 | 风险点 |
|---|---|---|---|---|---|---|
| **①** | `core:app-infra` | 仅 `Log`（1 处）+ datastore `PreferencesKey`（key 工厂本身 KMP） | ❌→✅ | **小（抽 1 个 Logger）** | Council/SubAgent 的上游 | 优先级最高，最先动 |
| **②** | `feature:task` | `Context` + `java.io.File` + `ConcurrentHashMap`；业务全是 coroutines/serialization | ❌→✅ | 小-中 | Council/SubAgent 前置 | 文件 I/O 抽 expect-actual |
| **③** | `feature:modelcouncil`（实现） | `Context` 入参 + `File`/`Instant`/`Locale`；**api 子模块已是 KMP** | ❌→✅ | 中 | Council UI | 需确认 `feature:terminal` 的 KMP 状态 |
| **④** | `feature:subagent`（实现） | `Context`+`File`+`Instant`+`ConcurrentHashMap`+**`RandomAccessFile`（transcript tail 读）** | ❌→✅ | 中 | SubAgent UI | `RandomAccessFile` 要抽 `RandomFileReader` expect-actual（iOS 用 `NSFileHandle`）；上游 agent-utils/history/runtime-api 需先 KMP |
| **⑤** | `McpManager`（现困在 `app/`） | `Log`+`toUri`易抽；但 `OkHttp` engine + `okhttp3` + `android.webkit.MimeTypeMap` 需 expect-actual + Darwin engine | ❌→✅ | 中 | MCP UI | 需先从 `app/` 拆成独立 KMP 模块 |
| **⑥** | Memory repository | Room DAO（见第三档 Room 样板，可走 commonMain） | 部分 | 中 | Memory UI | DAO 移 commonMain + iosMain Factory |

### 3.2 Room 上 iOS 的现成样板（直接抄）

`core:agent-store-room` **已经是完整 KMP 并跑在 iOS**，是本仓库的 Room 跨平台范式：
- DAO/Entity/Database 全写在 `commonMain`（用 Room KMP 的 `@Database/@Dao/@Query`）。
- iOS 入口 `src/iosMain/.../IosDatabaseFactory.kt`：`Room.databaseBuilder<…>().setDriver(BundledSQLiteDriver()).build()`，路径取 `NSDocumentDirectory`。
- **Swift 必须调 `IosDatabaseFactory.createDatabase()`**（KSP 生成的 `initialize()` 不设 driver 和路径）。
- 依赖 `androidx.sqlite.bundled`（打包 SQLite，跨平台一致）。

→ 任何带 Room 的第二/三档模块（Memory、Stats、Conversation），**直接复制这个套路**：DAO 进 commonMain + iosMain Factory + BundledSQLiteDriver。

### 3.3 第二档示范切片（建议第一个做）

**目标：打通 `ModelCouncilManager`，让 Swift 能真实调用（哪怕先只接"读取 Council 设置"）。**

步骤：
1. `feature:task` + `core:app-infra` KMP 化（前置，抽 Logger + File）。
2. 确认/处理 `feature:terminal` 的 KMP 化。
3. `feature:modelcouncil` 实现 → commonMain，`Context` 改接口注入。
4. `shared` 加 `":feature:modelcouncil"` 到 `sharedProjects` + `export()`。
5. 重建 framework，验证 `Shared.h` 出现 `ModelCouncilManager`。
6. iOS `CouncilView` 接真实读取（A 类 → 已接），截图验证。

**这是第一次把一个 A 类能力真正接通，并给 SubAgent/MCP 复制套路。**

---

## 4. 第三档：重写（强平台依赖，iOS 从零写原生实现）

> 这些不是"接桥"，是"做一个新的 iOS 功能"。每个都是独立工作量，按产品优先级单独排期。

| 能力 | 锁定的 Android 平台 API | iOS 等价物（需自研） | 工作量 |
|---|---|---|---|
| **WebMount** | Android `WebView` + OAuth + Cookie + `WebViewPool` | `WKWebView` + `ASWebAuthenticationSession` + `HTTPCookieStorage` | 大 |
| **MiniApp** | Android `WebView` + Room + `miniapp_bridge.js` + 系统桥 | `WKWebView` + SQLite + bridge 注入 + 权限 | 大 |
| **Stats（统计）** | `StatsVM` + Room DAO（但 DAO 可走第二档样板） | Swift VM + 共享 DAO（可半救） | 中 |
| **Backup/Sync** | `BackupVM` + Google Drive SDK + 加密 | iOS `ASWebAuthenticationSession` + Google Drive API + `CryptoKit` | 大 |
| **Skill 扫描** | `SkillManager`(`Context`+`java.io.File` 扫描) | iOS `FileManager` 扫描（相对可救，接近第二档） | 中 |
| **TTS 云端播放** | `androidx.media3`(ExoPlayer) + `android.speech.tts` | `AVAudioPlayer` + `AVSpeechSynthesizer`；HTTP 换 Darwin engine | 大（最大） |
| **Conversation 存储/删除** | `FilesManager` + `ConversationRepository` delete | iOS 文件统计 + 安全删除事务 | 中 |
| **Chat token 估算** | （Android 也无对应） | iOS 端实现 tokenizer | 大，且双方都缺 |

**判断依据（实测 import）**：`tts/` 模块重度依赖 `android.speech.tts.TextToSpeech`、`androidx.media3.*`(ExoPlayer)、`android.util.Base64`、`org.json`——这些在 iOS 全无等价物，必须用 AVFoundation 重写。`WebMountManager` 依赖 Android `WebView` 全栈。`MiniAppRepository` 依赖 `androidx.room.withTransaction` + WebView。

### 4.1 第三档里"相对可救"的子集

- **Skill 扫描**：本质是 `FileManager` 扫描 + 解析，接近第二档，可优先尝试。
- **Stats 计数**：DAO 走 Room KMP 样板能复用，只缺 Swift VM 层，半重写。
- **Memory 持久层**：同上，DAO 可进 commonMain。

---

## 5. 执行顺序建议

```
第一档（已完成）
   ↓
第二档·前置：①core:app-infra → ②feature:task   （抽 Logger/File，解锁下游）
   ↓
第二档·示范：③feature:modelcouncil 实现 KMP 化 + export → Council UI 接通（第一个真桥）
   ↓
第二档·复制：④feature:subagent → ⑤McpManager → ⑥Memory repository
   ↓
第三档·按产品优先级：Skill扫描(可救) → Stats(半救) → 其余(重写，单独排期)
```

**每个第二档切片的交付标准**：模块 KMP 化 → framework export 验证（`Shared.h` 出现符号）→ iOS UI 接真实调用（A 类降级改"已接"）→ build/run/截图 → 更新审计文档。

---

## 6. 风险与护栏

- **不破坏 Android**：`commonMain` 改造必须保证 Android 构建仍通过（CI 绿）。
- **Context 不能直接传**：iOS 无 `android.content.Context`，所有 Context 入参必须改成接口注入，Android 端 `actual` 注入真实 Context。
- **Room driver 初始化顺序**：iOS 必须在 app 启动早期调 `IosDatabaseFactory.createDatabase()`，否则 DAO 调用崩。
- **Ktor engine**：`OkHttp` 是 JVM-only，iOS 必须换 `Darwin` engine，HTTP 客户端要走 expect-actual。
- **不造假**：第二档每条线在"Swift 真能调到真实 Manager"之前，UI 维持 A 类降级，不得用假数据/假桥。

---

## 7. 附：能力位置速查（实测）

| 能力符号 | 定义位置 | 平台 | iOS 可达性 |
|---|---|---|---|
| `Settings`/`DEFAULT_PROVIDERS`/`ModelRegistry` | `core/types` 等（commonMain，已 export） | KMP | ✅ 直接 |
| `TTSProviderSetting`/`DEFAULT_TTS_PROVIDERS` | commonMain（已 export） | KMP | ✅ 数据直接；执行(TTSManager)❌ |
| `ModelCouncilManager` | `feature/modelcouncil/src/main`（Android-only） | Android | ⚠️ 第二档③ |
| `SubAgentManager` | `feature/subagent/src/main`（Android-only） | Android | ⚠️ 第二档④ |
| `McpManager` | `app/src/main/.../mcp/` | Android | ⚠️ 第二档⑤ |
| `MemoryRepository` | `app/src/main/.../repository/`（Room） | Android | ⚠️ 第二档⑥ |
| `SkillManager` | `app/src/main/.../files/` | Android | 🔴 第三档（可救） |
| `TTSManager` | `tts/src/main/.../provider/` | Android(JVM) | 🔴 第三档（最大） |
| `WebMountManager` | `app/src/main/.../webmount/` | Android(WebView) | 🔴 第三档 |
| `MiniAppRepository` | `app/src/main/.../miniapp/` | Android(WebView+Room) | 🔴 第三档 |
| `StatsVM`/`BackupVM`/`BoardViewModel` | `app/.../pages/` | Android ViewModel | 🔴 第三档 |
| `core:agent-store-room` | commonMain + iosMain（已 KMP） | KMP | ✅ Room 上 iOS 样板 |
