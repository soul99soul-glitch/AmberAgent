# Plan: iOS 会话持久化（Conversation Persistence）

> 目标：把 iOS 端从「单会话内存 demo」推进到「多会话、可持久、可切换」的可用产品状态。
> 这是 iOS 版从 demo 跨进「日常可用」的**唯一阻断级缺口**。

## 现状（已勘查确认）

- `ChatViewModel.messages: [UIMessage]` 只活在内存，**App 重启即丢**。
- **没有**会话列表 / 新建 / 切换 / 历史 / 删除 UI。
- `ChatViewModel` 无 `Conversation` 概念，无 `currentConversationId`。

### 可复用的 KMP 基础（不需要重造）

| 资产 | 位置 | 可复用性 |
|---|---|---|
| `Conversation` 数据模型 | `core/types/src/commonMain/.../model/Conversation.kt` | ✅ `@Serializable`，已在 commonMain，iOS 可直接用 |
| `MessageNode` | 同上 | ✅ `@Serializable`，含 `selectIndex` 分支结构 |
| `UIMessage` + 全部 `UIMessagePart` 子类 | `ai-core/src/commonMain/.../ui/Message.kt` | ✅ 全部 `@Serializable`（text/image/tool/reasoning…） |
| `JsonInstant`（lenient Json）| `core/agent-utils/src/commonMain/.../Json.kt` | ✅ commonMain，iOS 可直接复用做 JSON 编解码 |
| `Conversation.updateCurrentMessages()` | Conversation.kt:45 | ✅ 节点合并逻辑已实现（含 identity 短路优化）|
| `InstantSerializer` | ai util | ✅ Instant 字段序列化已配好 |
| Android `ConversationRepository.saveConversation(id, conv)` 契约 | `app/src/main/.../service/ChatService.kt:1892` | 📙 参照契约，iOS 写等价实现 |

### 不复用的部分（Android-only，不照搬）

- Room / DAO / Entity / FTS / Paging —— 全部 Android-only，iOS 用文件 JSON 替代。
- `ConversationContextEngine` / `ConversationSession` —— 上下文工程，第一版不做。
- `Conversation.files` 附件清理（`ConversationAndroidExt.kt`）—— 附件是 Android 特有，第一版不管。

## 设计决策：存储层

**方案：每会话一个 JSON 文件 + 一个 index 摘要文件**（不引入 SQLite/Room KMP）。

理由：
1. `Conversation` 已是 `@Serializable`，整对象一次落盘最简单、最不易错。
2. 第一版规模（几十～几百会话）下，文件 JSON 性能完全够用；列表只读 index。
3. 不需要引入 Room KMP / SQLDelight 新依赖（本机无 Android SDK，引入风险高）。
4. 与 Android 格式**不需要二进制兼容**（两端各自序列化各自的存储），只要 iOS 内部自洽。

### 目录结构

```
Documents/
  conversations/
    index.json                          # [{id, title, assistantId, createAt, updateAt, isPinned, messageCount}] 摘要列表
    {conversationId}.json               # 完整 Conversation 对象（含 messageNodes）
```

- `index.json` 是**派生缓存**，可从 `{id}.json` 重建（写时同步更新，读列表只读它）。
- 单文件原子写（`Data.write(to: .atomic)`），避免半写损坏。

## 阶段划分

### 阶段 1：存储层（KMP expect-actual，参照 TaskFile 模板）

**目标**：纯 I/O，不碰 UI。

新增 `core/conversation-storage` KMP 模块（参照 `feature/task` 模板）：

- `commonMain/ConversationStorageInterface.kt`：纯接口
  ```kotlin
  interface ConversationStorageInterface {
      suspend fun listSummaries(): List<ConversationSummary>
      suspend fun loadConversation(id: Uuid): Conversation?
      suspend fun saveConversation(conversation: Conversation)   // upsert
      suspend fun deleteConversation(id: Uuid)
      suspend fun updateMetadata(id: Uuid, title: String?, isPinned: Boolean?)
  }
  data class ConversationSummary(
      val id: Uuid, val title: String, val assistantId: Uuid,
      val createAt: Instant, val updateAt: Instant, val isPinned: Boolean, val messageCount: Int
  )
  ```

- `commonMain/JsonConversationStorage.kt`：**纯 Kotlin 实现**（用 `JsonInstant` + 接口的抽象 File 操作）。
  - 序列化用 `JsonInstant.encodeToString(Conversation)`.
  - 文件操作走 `expect class ConversationStorageFile`（mkdirs/exists/read/write/delete/list）。

- `iosMain/ConversationStorageFile.kt`（actual）：`NSFileManager` Documents/conversations/。
- `jvmMain/ConversationStorageFile.kt`（actual）：`java.io.File`（仅为 jvm target 编译通过，iOS 是真实使用方）。

**单测**（commonTest）：用内存假 File 验证 save→load 往返、index 同步、delete 清理、metadata 更新。

**验收**：`:core:conversation-storage:compileKotlinJvm :compileKotlinIosSimulatorArm64 :allTests` 通过；`:shared:linkDebugFramework` 通过；Shared.h 含 `ConversationStorageInterface`。

---

### 阶段 2：iOS Store + 接入 ChatViewModel

**目标**：让 `ChatViewModel` 拥有 `Conversation` 生命周期。

- 新增 `IOSConversationStore.swift`（`@Observable`）：
  - 持有 `currentConversation: Conversation?`、`summaries: [ConversationSummary]`。
  - `newConversation()`：生成新 `Conversation`（assistantId = 默认），存盘，设为 current。
  - `selectConversation(id:)`：loadConversation → 设为 current。
  - `saveCurrent()`：把 `ChatViewModel.messages` 回填进 `currentConversation.updateCurrentMessages()` → 存盘 → 刷新 index。
  - `deleteConversation(id:)`：存档删除 + 刷新 summaries。
  - `renameConversation(id:title:)` / `togglePin(id:)`。

- 改 `ChatViewModel`：
  - 增加 `var currentConversationId: Uuid?`。
  - `sendMessage()` 成功后 / `onComplete` 后调 `store.saveCurrent()`（节流：流式结束后存一次，不在每个 chunk 存）。
  - `loadConversation(into:)`：把 `Conversation.currentMessages` 灌进 `messages`。
  - **App 启动**：`AppShell.init` → 若有历史则选最近一条；否则 `newConversation()`。

**验收**：发一条消息 → 杀进程 → 重启 → 历史仍在；切换会话后 messages 正确切换。

---

### 阶段 3：会话列表 UI（侧边栏）

**目标**：用户能看到/选/删/建会话。

- 新增 `ConversationListView.swift`：
  - LazyVStack 列表：标题、updateAt、messageCount、置顶标记。
  - 顶部「+ 新建会话」按钮 → `store.newConversation()`。
  - 左滑：置顶 / 重命名 / 删除（带二次确认）。
  - 搜索框（本地过滤 summaries.title）。
  - 当前会话高亮。

- 接入 `AppShell` / `ChatView`：
  - ChatView 顶栏加「会话列表」入口（menu 或 sheet 或抽屉，参照 iOS 习惯）。
  - 选中会话 → `store.selectConversation` → ChatViewModel reload。

**验收**：UI 截图确认列表/新建/切换/删除/置顶/重命名全可用。

---

### 阶段 4（可选，第一版可不做）：健壮性

- 流式结束后自动生成标题（取首条 user message 前 N 字，或调一次小模型总结——后者需 Provider，第一版用前者）。
- 写盘失败降级（磁盘满）：提示但不丢内存会话。
- index.json 损坏恢复：扫 `{id}.json` 重建 index。
- 迁移：旧版无 conversations/ 目录时静默初始化空 index。

## 明确不做（第一版范围外）

- ❌ 消息分支（regenerate / 切换 selectIndex）的 UI 编辑——数据结构已支持，但 UI 编辑是另一个工程。
- ❌ 全文搜索（Android 有 FTS）——第一版只按标题过滤。
- ❌ 附件文件清理——iOS 第一版不管附件落盘。
- ❌ 上下文工程（ConversationContextEngine）。
- ❌ 分页加载（消息很多时窗口化）——第一版整会话加载，量大了再说。
- ❌ 与 Android 的存储互导/互通。

## 风险与对策

| 风险 | 对策 |
|---|---|
| `UIMessagePart` 新增子类导致旧 JSON 反序列化失败 | `JsonInstant` 已设 `ignoreUnknownKeys=true`；新增 part 类型向前兼容 |
| 流式中频繁写盘卡顿 | 只在 `onComplete`（流结束）和切换会话时存，不在 chunk 存 |
| 大会话（千条消息）整文件读写慢 | 第一版可接受；超 500 条时再做节点级分文件（参照 Android MessageNodeEntity）|
| assistantId 归属（iOS 当前无多 Assistant）| 第一版全用 `DEFAULT_ASSISTANT_ID`，listSummaries 不按 assistant 过滤 |

## 验证要求（每阶段通用）

- KMP 改动：`compileKotlinJvm` + `compileKotlinIosSimulatorArm64` 双端编译；`:shared:linkDebugFramework`；Shared.h grep 新符号。
- iOS：`xcodegen generate`（新文件）→ `xcodebuild build` SUCCEEDED → `ios_build_and_run` 启动验证 + 截图。
- 每个切片 subagent review：(a) 调用链闭环 (b) 链路断裂风险 (c) 造假数据/假执行 (d) Android 无回归。
- P0/P1 必须修完再 commit。

## 提交要求

- 每阶段单独 commit，message 写清楚接了什么。
- 只 stage 本切片文件（绝不 stage .xcodeproj/build 产物）。
- 更新 `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`。

## 工作量预估

| 阶段 | 预估 |
|---|---|
| 阶段 1 存储层 KMP | 1-1.5 天 |
| 阶段 2 Store + ChatViewModel 接入 | 1-1.5 天 |
| 阶段 3 会话列表 UI | 1-2 天 |
| 阶段 4 健壮性（可选）| 0.5-1 天 |
| **合计（1-3 必做）** | **3-5 天** |

补完阶段 1-3 后，iOS 版即从「demo」跨进「可日常使用的多会话产品」。
