# AmberAgent vs RikkaHub 全面分析与重构规划

> 分析时间：2026-05-13
> 上游仓库：https://github.com/rikkahub/rikkahub
> Fork 仓库：https://github.com/soul99soul-glitch/AmberAgent

---

## 一、背景

AmberAgent 是从 RikkaHub fork 出来的 Android LLM 聊天客户端。两者共享代码命名空间 `me.rerere.rikkahub`，但 applicationId 为 `me.rerere.amberagent`，对外品牌名为 AmberAgent。

**关系类比：** `rikkahub` 是仓库名，`AmberAgent` 是产品品牌名，类似 `chromium` 构建出 `Google Chrome`。

---

## 二、基础 Git 数据

| 指标 | 数值 |
|------|------|
| AmberAgent 领先上游 | 140 commits |
| 上游领先 AmberAgent（未合入） | 27 commits |
| 变更文件总数 | 673 files |
| 代码新增 | +107,729 行 |
| 代码删除 | -11,040 行 |
| 净增代码量 | +96,689 行 |
| 当前 app 源码总量 | 131,084 行（567 个 .kt/.java 文件） |
| 被修改的源文件 | 396 / 567（70%） |

---

## 三、相似度分析

### 3.1 UI 相似度：~45%

**变更规模：** 128 个 UI 文件，+26,150 / -7,242 行

**新增/重写：**
- 自定义主题 `AmberagentClashTheme`
- Workspace 文件预览组件（600+ 行）
- 性能追踪 Modifier `PerfTraceModifier`
- 聊天页面、设置页面、分享处理页面、统计页面大幅重写

**保留：**
- Jetpack Compose + Material You 框架
- Navigation Compose 路由骨架
- 基础组件结构

**结论：** 框架和导航骨架相同，但视觉表现、页面布局、交互细节已大幅改动。截图能认出是同类 app，但风格明显不同。

---

### 3.2 业务逻辑相似度：~30%

**变更规模：** 247 个 data 层文件，+45,056 / -1,118 行

**核心模型（共享）：** Assistant、Conversation、MessageNode 仍与上游共享基础结构

**AmberAgent 独有新模块：**

| 模块 | 说明 |
|------|------|
| WebMount | 浏览器挂载系统，含 7 个站点适配器（B站/GitHub/Reddit/HN/掘金/飞书/知乎）+ OAuth 流程 |
| ProviderUsageClient | 455+ 行，用量追踪 |
| ModelCouncil | 多模型协作决策 |
| Agent 自动化工作流 | 含 Terminal、iCloud、Feishu Office 等 |
| Speech 模块 | 替换原 TTS，34 文件重构 |

**结论：** 核心数据模型共享，但业务逻辑体量已是上游的 3-4 倍。上游代码是地基，AmberAgent 在上面盖了整栋楼。

---

### 3.3 功能集相似度：~55%

**上游功能（全部保留）：**
- 多 Provider 聊天（OpenAI / Google / Anthropic 兼容 API）
- 多模态输入（图片、PDF、Docx）
- MCP 支持
- Markdown 渲染（代码高亮、LaTeX、Mermaid）
- 消息分支（Message Branching）
- 搜索集成（Exa、Tavily、Zhipu 等）
- Agent 自定义
- 记忆功能
- AI 翻译
- Silly Tavern 角色卡导入

**AmberAgent 独有功能：**
- WebMount（浏览器挂载 + 7 个站点适配器 + OAuth）
- 用量统计面板
- Workspace 文件管理
- Speech 语音模块（重写）
- Agent 自动化控制（Terminal、ModelCouncil、iCloud、Feishu）
- 品牌定制（去掉上游捐赠页、自定义主题）

**结论：** 上游所有功能都保留，但 AmberAgent 新增的功能体量已接近上游本身的功能总量。

---

### 3.4 代码整体相似度：~25%

- 上游原始代码量估算：131,084 - 96,689 ≈ 34,000 行
- 其中被删除 11,040 行，剩余未动的上游代码 ≈ 23,000 行
- 23,000 / 131,084 ≈ **18% 的代码是原封不动的上游代码**
- 加上被修改但仍可辨认的代码，整体相似度约 25%

---

### 3.5 综合结论

| 维度 | 相似度 | 定性 |
|------|--------|------|
| UI | **~45%** | 同框架，视觉和交互大改 |
| 业务逻辑 | **~30%** | 核心模型共享，新逻辑是上游 3-4 倍 |
| 功能集 | **~55%** | 上游功能全保留，新增功能接近等量 |
| 代码整体 | **~25%** | 当前代码库约 1/4 可追溯到上游原文 |
| **综合** | **~35%** | **深度分叉的独立产品** |

> AmberAgent 已不是"改了点东西"的程度——96,689 行净增量，相当于在上游基础上重新写了 3 个上游的代码量。

---

## 四、现状技术债诊断

### 上帝类（单文件超 1000 行）

| 文件 | 行数 | 问题 |
|------|------|------|
| `ui/components/ai/ChatInput.kt` | 3,557 | UI 与逻辑严重混合 |
| `ui/pages/setting/SettingExperimentalPage.kt` | 2,483 | 设置页失控 |
| `service/ChatService.kt` | 2,116 | 上帝类，职责过多 |
| `ui/components/message/ChatMessageTools.kt` | 2,200 | 工具调用 UI 过重 |
| `data/agent/webmount/tools/WebMountPrimitiveTools.kt` | 1,541 | 工具定义堆砌 |
| `data/agent/tools/SystemAccessTools.kt` | 1,441 | 同上 |
| `data/datastore/PreferencesStore.kt` | 1,020 | 单文件管理所有偏好 |

### 其他技术债

| 问题 | 说明 |
|------|------|
| 两套 HTTP 客户端 | OkHttp + Ktor 并存，Retrofit 只用于部分 API |
| 两套 Markdown 渲染 | `Markdown.kt` + `MarkdownNew.kt` 并存 |
| `Pebble` 模板引擎 | Java 重量级库，只用于消息变量替换 |
| `tts/` 模块残留 | 已被 speech 替代但未清理 |
| `jlatexmath` 非懒加载 | 非数学消息也会初始化 |
| `jmDNS` 常驻 | 应只在 WebMount 激活时启动 |
| Firebase Analytics | 每次启动上报，未批量 |

---

## 五、重构路线规划

### Phase 0：基础设施清理

- **目标：** 消除冗余，统一技术选型，不改任何功能
- **风险：** 低
- **周期：** 1-2 周

**技术决策：**
- 淘汰：`Retrofit`（统一用 Ktor）、`Pebble`（用轻量 Kotlin 字符串替换）、`tts/` 模块
- 保留：Ktor、OkHttp engine、Room、Koin

**执行步骤：**
1. 删除 `tts/` 模块，从 `settings.gradle.kts` 和 `app/build.gradle.kts` 移除引用
2. 删除 `Retrofit` 依赖，将所有 Retrofit 调用迁移到 Ktor
3. 删除 `Pebble` 依赖，用 100 行以内的 Kotlin 实现替换变量替换逻辑
4. 将 `PreferencesStore.kt` 按领域拆分：`ChatPrefs`、`UIPrefs`、`ProviderPrefs`、`AgentPrefs`
5. 删除 `MarkdownNew.kt` 或 `Markdown.kt`，统一保留一套

**性能/电量收益：** APK 体积减小，冷启动略快，依赖树简化

**验收标准：** 所有功能正常，`./gradlew assembleDebug` 通过，依赖数量减少

---

### Phase 1：数据层重构

- **目标：** 建立清晰的 Repository → UseCase → ViewModel 分层
- **风险：** 中
- **周期：** 3-4 周

**技术决策：**
- 保留：Room、Koin、DataStore
- 引入：UseCase 层（目前 ViewModel 直接调 Repository）
- 淘汰：`ChatService` 上帝类模式

**执行步骤：**
1. 审查 `data/model/`，把 JSON 序列化注解和业务逻辑分离
2. 拆分 `ChatService.kt`（2116 行）为：
   - `ChatOrchestrator`：协调流程
   - `MessageTransformPipeline`：transformer 链
   - `StreamingHandler`：SSE 流处理
   - `ToolCallDispatcher`：工具调用分发
3. 将 WebMount、ModelCouncil、Terminal 封装为独立 `AgentCapability` 接口，通过注册机制接入
4. 每个 Repository 只依赖 DAO + 网络，不依赖其他 Repository
5. 为每个核心业务操作建立 UseCase 类

**性能/电量收益：** 减少主线程阻塞，UseCase 可精确控制协程调度器

**验收标准：** 无 Repository 互相依赖，ViewModel 不直接调用网络，所有 UseCase 有单元测试

---

### Phase 2：UI 层重构

- **目标：** 消灭上帝组件，建立严格 MVI 单向数据流，优化 Compose 重组性能
- **风险：** 高
- **周期：** 4-6 周

**技术决策：**
- 保留：Jetpack Compose、Material3、Navigation 3
- 引入：严格 `UiState sealed class` MVI 模式、Baseline Profiles 实际生成
- 规则：单文件不超过 500 行

**执行步骤：**
1. `ChatInput.kt`（3557 行）拆分为：
   - `TextInputBar`
   - `AttachmentPicker`
   - `VoiceInputButton`
   - `ToolbarRow`
   每个 < 300 行
2. `ChatPage.kt` + `ChatList.kt` 改为严格 MVI：`ChatUiState`、`ChatIntent`、`ChatViewModel` 只暴露 `StateFlow<ChatUiState>`
3. `SettingExperimentalPage.kt`（2483 行）按功能域拆成独立子页面，每个独立路由
4. Compose 性能专项：
   - 所有 `LazyColumn` item 加 `key`
   - lambda 提升为 `remember { }` 避免重组
   - 用 `derivedStateOf` 替代直接读 StateFlow 的计算属性
   - `ChatMessage` 组件加 `@Stable` 注解
5. 生成 Baseline Profile（已有 `baseline` build type，补充实际 profile 生成脚本）

**性能/电量收益：** 滚动帧率提升，减少不必要重组，冷启动时间缩短

**验收标准：** Compose 编译器报告无不稳定类型警告，Perfetto 录制滚动无 jank，单文件无超 500 行

---

### Phase 3：模块化重构

- **目标：** 从扁平 `:app` 主模块迁移到 `:core:*` + `:feature:*` 分层架构
- **风险：** 中
- **周期：** 4-6 周

**目标模块结构：**

```
:core:data          — Room DB, DataStore, 基础 Repository
:core:ui            — 设计系统, 共享组件, 主题
:core:network       — Ktor client, 统一网络层
:feature:chat       — 聊天页面 + ViewModel + UseCase
:feature:assistant  — Assistant 管理
:feature:webmount   — WebMount 完整子系统
:feature:agent      — Agent runtime, ModelCouncil, Terminal
:feature:settings   — 所有设置页
:ai                 — 保留，已相对独立
:app                — 只剩 Application, DI 组装, MainActivity
```

**执行步骤：**
1. 先建 `:core:data`、`:core:ui`、`:core:network`
2. 逐个迁移 `:feature:*`，从依赖最少的模块开始（`:feature:settings` → `:feature:assistant` → `:feature:chat` → `:feature:agent` → `:feature:webmount`）
3. 最后瘦身 `:app`

**性能/电量收益：** 增量编译速度大幅提升，模块间依赖清晰

**验收标准：** `:app` 模块不包含任何业务逻辑，模块间无循环依赖，`./gradlew :feature:chat:test` 可独立运行

---

### Phase 4：性能与电量专项（持续）

在 Phase 1-3 过程中同步处理：

| 问题 | 解法 | 收益 |
|------|------|------|
| `jlatexmath` 非懒加载 | 检测到 `$` 符号才初始化 | 减少冷启动内存 |
| `jmDNS` 常驻 | 只在 WebMount 激活时启动/停止 | 减少后台耗电 |
| WebMount WebView 常驻 | 按需创建，空闲超时销毁 | 减少内存占用 |
| Firebase Analytics | 改为批量上报或评估必要性 | 减少网络唤醒 |
| WorkManager 任务 | 所有后台任务加 `Constraints`（网络、充电条件） | 减少耗电 |
| 字体文件 | 已配置 `noCompress`（正确），确保 mmap 路径生效 | 减少启动 CPU |

---

### 阶段依赖关系

```
Phase 0（清理）
    ↓
Phase 1（数据层）
    ↓
Phase 2（UI 层）    ←→    Phase 4（性能专项，持续）
    ↓
Phase 3（模块化）
```

Phase 0 → Phase 1 必须串行（Phase 1 依赖统一的网络层）
Phase 2 和 Phase 4 可以并行推进
Phase 3 必须在 Phase 1 和 Phase 2 完成后进行（需要职责边界已清晰）

---

## 六、消除上游痕迹策略

### 6.1 包名迁移

**建议时机：** Phase 3 模块化完成后

**原因：** 包名迁移（`me.rerere.rikkahub` → `me.rerere.amberagent`）涉及所有 567 个源文件的 import 语句、Room 数据库迁移（entity 类名变化）、DataStore key 变化、AndroidManifest、ProGuard 规则。在模块化完成前做这件事，等于在混乱中加混乱。

**迁移成本：**
- 源文件 import：可用 IDE 全局重命名，低风险
- Room 数据库：需要写 Migration，entity 类路径变化会导致 Room 认为表结构变化
- DataStore：key 名称不变，但序列化类路径变化需要处理
- Firebase：需更新 `google-services.json` 中的包名

### 6.2 持续接收上游 bug fix

**推荐策略：** 维护一个 `upstream-sync` 分支

```bash
# 定期同步上游
git fetch origin
git checkout -b upstream-sync-YYYYMMDD
git merge origin/master --no-ff

# 只挑选安全修复
git cherry-pick <upstream-security-fix-commit>
```

**原则：**
- 上游的 UI 改动：通常不合入（你已大幅重写）
- 上游的 AI SDK 修复（`:ai` 模块）：优先合入
- 上游的安全/崩溃修复：cherry-pick 合入
- 上游的新功能：评估后决定

### 6.3 品牌残留清理

当前仍引用上游品牌的位置：

| 位置 | 内容 | 清理方式 |
|------|------|------|
| `namespace = "me.rerere.rikkahub"` | 代码命名空间 | Phase 3 后统一迁移 |
| `app/src/main/res/values/strings.xml` | 含 `rikkahub` 字符串 | 全局替换 |
| `app/src/main/res/xml/amberagent_accessibility_service.xml` | 已用 amberagent | 已处理 |
| `app/src/main/res/mipmap-*/` | 图标资源 | 确认是否已替换为 AmberAgent 图标 |
| `google-services.json` | Firebase 项目配置 | 确认是否已切换到 AmberAgent 的 Firebase 项目 |
| `README.md` | 已改为 AmberAgent | 已处理 |

---

## 七、给其他 AI 的分析 Prompt

```
# 任务：AmberAgent vs RikkaHub 全面分析与重构规划

## 仓库信息
- 上游仓库 (RikkaHub)：https://github.com/rikkahub/rikkahub
- Fork 仓库 (AmberAgent)：https://github.com/soul99soul-glitch/AmberAgent
- 关系说明：AmberAgent 是从 RikkaHub fork 出来的 Android LLM 聊天客户端，
  Fork 之后进行了大量定制开发。两者共享相同的代码命名空间（me.rerere.rikkahub），
  但 applicationId 为 me.rerere.amberagent。

## 项目技术栈
- 语言：Kotlin
- UI 框架：Jetpack Compose + Material3 + Navigation 3
- DI：Koin
- 数据库：Room + DataStore
- 网络：OkHttp + Retrofit + Ktor（多套混用，需统一）
- 模块结构：:app :ai :web :search :tts :document :highlight :common
- 核心模型：Assistant / Conversation / MessageNode / UIMessage / Message Transformer

## 任务一：相似度分析
基于 git diff origin/master..HEAD，给出以下四个维度的相似度百分比，
每个维度附带具体证据（文件路径、行数、模块名）：
1. UI 相似度（对比 app/src/main/java/.../ui/ 目录）
2. 业务逻辑相似度（对比 data/ + :ai 模块）
3. 功能集相似度（README 功能 + 实际代码）
4. 代码整体相似度（基于 numstat 数据）

输出格式：表格，含相似度百分比 + 核心证据 + 综合定性结论

## 任务二：重构路线规划
目标：逐步消除上游代码痕迹，建立独立技术架构，同时优化代码简洁性、
架构清晰度、运行性能、耗电量。

约束：
1. 不能一次性大重写，必须是可增量发版的改动
2. 保留所有现有功能（WebMount、ModelCouncil、Terminal 等必须无损迁移）
3. 优先低风险高收益
4. 关注冷启动、滚动帧率、后台任务调度、WebView 生命周期、懒加载

每个阶段输出：目标、风险等级、预估周期、技术决策（保留/引入/淘汰）、
执行步骤（文件级）、性能收益、验收标准

## 任务三：消除上游痕迹策略
1. 包名 me.rerere.rikkahub 何时改、迁移成本是什么？
2. 如何在持续接收上游 bug fix 的同时保持 fork 独立？
3. 哪些资源/字符串/图标仍引用上游品牌？如何系统性清理？

## 要求
- 必须实际读代码或 diff，不能基于文件名猜测
- 所有百分比结论必须有具体数字支撑
- 推荐方案必须可落地
- 输出语言：简体中文
```
