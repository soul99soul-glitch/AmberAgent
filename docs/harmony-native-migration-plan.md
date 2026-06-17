# AmberAgent Android → 原生 HarmonyOS NEXT 迁移计划

> 分支/worktree：`harmony-preview` / `/Users/mi/Downloads/AI/AmberAgent-harmony-preview`  
> 目标路线：**原生 HarmonyOS NEXT，ArkTS + ArkUI**  
> 非目标：Web 壳复刻、Android APK 兼容、Jetpack Compose 直接复用  
> 日期：2026-06-15

> **文档分工（2026-06-17 review 后确立）**
> - 本文件是**战略概览**：回答“为什么这么做、优先级如何、风险在哪”。
> - `docs/harmony-detailed-porting-plan.md` 是**权威执行计划**：阶段编号、里程碑 ID（M0–M7）、Gate 验收标准、每周清单以此为准。
> - 本文件的“战略里程碑”标记为 `S0–S5`，避免与 detailed-plan 的执行里程碑 `M0–M7` 冲突。两者对应关系见 §1.1。
> - Phase 编号两份一致（Phase 0–4 语义相同）；本计划的 Phase 5 对应 detailed-plan 的 **Phase 5 + Phase 6**（体验打磨与测试发布拆开执行）。

## 1. 执行摘要

AmberAgent 的鸿蒙版不能按“Android 工程迁移”理解，而应按“以现有产品和核心逻辑为蓝本的原生 HarmonyOS 重写”理解。

原因很直接：HarmonyOS NEXT 不运行 Android APK，不包含 ART/Java/Kotlin/Jetpack Compose 运行环境。现有 Android 代码不能直接编译成鸿蒙原生应用。真正可复用的是：Agent 行为模型、协议设计、数据模型语义、Rust 纯逻辑模块、测试语料、产品设计和部分算法实现。

计划采用 AI-assisted 的原生重写方式：先判定平台能力边界，再搭共享核心，再打穿原生 Chat 闭环，最后迁移 P1/P2 能力。不要一开始追求 1:1 复刻 Android 版，否则会被 Accessibility、通知监听、本地 CLI/proot、后台任务等系统差异拖死。

推荐节奏（**这是范围估算，不是承诺日期**——ArkTS/ArkUI 学习曲线、三方库缺口、AI 生成质量缩水都会影响实际进度；每项标注的周数假设一名熟悉移动端但首次接触鸿蒙的工程师全职投入，见 §8.1 假设清单）：

| 战略里程碑 | 对应执行里程碑 | 时间（范围估算） | 目标 |
|---|---|---:|---|
| S0 生死判定 | M0 + M1 | 1-2 周 | 验证 Rust NAPI、Accessibility 替代、通知监听、proot/CLI、SSE 流式 |
| S1 共享核心 | M2 + M3 部分 | 4-6 周 | Rust NAPI、ArkTS Agent 内核、Provider 层、RDB/Preferences 骨架 |
| S2 可用 Chat | M3 完成 | 6-8 周 | 原生聊天闭环：多轮、流式、Markdown/高亮、会话持久化 |
| S3 核心能力面 | M4 + M5 | 6-8 周 | 工具、文件、ArkWeb、云 TTS、设置、搜索、文档解析 |
| S4 平台特性 | M6 | 6-10 周 | P2 可行项落地，不可行项降级/下线 |
| S5 收敛上架 | M7 | 3-4 周 | 性能、i18n、合规、上架材料、功能对齐表 |

AI 充分参与后，**合理的范围预期**（非承诺）：

- **6-8 周**：内部 Alpha，原生聊天可用。
- **3-4 个月**：可日常使用的 Preview。
- **6 个月左右**：功能较完整的公开测试版。
- **9-12 个月**：接近 Android 版成熟度，但部分系统级能力可能以降级形态存在。

## 2. 目标与非目标

### 2.1 目标

1. 建立原生 HarmonyOS NEXT 应用工程，采用 ArkTS/ArkUI。
2. 保留 AmberAgent 的核心体验：
   - Agent 对话
   - 多 Provider / 多模型
   - 流式输出
   - 可见工具调用过程
   - 会话树/分支
   - 深度阅读与工具卡片能力
   - 本地状态与设置
3. 复用能复用的跨平台资产：
   - Rust native crates
   - Agent 核心语义与协议
   - 数据模型设计
   - 测试语料和产品设计
4. 建立 AI-native 迁移流水线，让页面、模型、存储、Provider、工具系统可以并行生成和验证。

### 2.2 非目标

1. 不追求 Android APK 兼容。
2. 不把 Jetpack Compose 直接跑到 HarmonyOS NEXT。
3. 不在第一阶段做 Web 壳作为主线。
4. 不承诺第一版完整复刻：
   - Accessibility 自动操作手机
   - Android 通知监听
   - proot/Alpine 本地终端
   - 本地 Claude Code / Gemini CLI / Codex CLI seat
   - 所有 Android 权限型工具

## 3. 总体架构

迁移后建议分三层：

```text
┌────────────────────────────────────────────┐
│ L3 ArkTS / ArkUI 表现层                    │
│ 页面、导航、状态管理、组件、动画、主题      │
├────────────────────────────────────────────┤
│ L2 HarmonyOS 平台适配层                    │
│ 文件、通知、后台、TTS、ArkWeb、权限、RDB    │
├────────────────────────────────────────────┤
│ L1 跨平台共享核心                          │
│ Agent 语义、Provider 协议、数据模型、Rust   │
└────────────────────────────────────────────┘
```

### 3.1 L1：跨平台共享核心

推荐策略：

- Rust native crates：尽量复用业务逻辑，替换 JNI 绑定为 NAPI 绑定。
- Agent 内核：优先用 ArkTS 重写，而不是强行复用 Kotlin。
- LLM/Search/TTS Provider：按协议重写 ArkTS client。
- 数据模型：从 Kotlin data class 转为 ArkTS interface/class，并生成序列化、mock、测试。

为什么不直接复用 Kotlin：HarmonyOS NEXT 原生应用不运行 JVM/ART；强行绕一层只会引入调试复杂度。

### 3.2 L2：平台适配层

用统一接口隔离 HarmonyOS API：

```text
PlatformFileService
PlatformNotificationService
PlatformBackgroundTaskService
PlatformTtsService
PlatformWebViewBridge
PlatformPermissionService
PlatformDatabase
PlatformPreferences
PlatformAccessibilityAdapter
```

这些接口必须由 P0 阶段冻结，后续 UI 和 Agent 层只依赖接口，不直接调用系统 API。

### 3.3 L3：ArkUI 表现层

Compose 不能直接迁移，只能翻译为 ArkUI。迁移方法：

- 一个页面一个 AI worker。
- 输入：Compose 源码、截图、设计系统、状态契约、验收标准。
- 输出：ArkTS 页面、组件、mock 数据、截图验收。
- 人工负责架构 review、交互确认、真机调样式。

## 4. 功能优先级矩阵

### 4.1 P0：不做就不是 AmberAgent

| 功能 | 迁移策略 | 验收标准 |
|---|---|---|
| HarmonyOS 原生工程骨架 | DevEco Studio + hvigor + ArkTS + ArkUI | 真机启动，基础导航可用 |
| Rust NAPI 验证 | 先选 tokenizer/highlight 之类小 crate | 真机调用成功，输出与 Android/Rust 基准一致 |
| Agent 内核 | ArkTS 重写核心状态机和 run loop | harness 可完成一次 prompt → provider → streaming → response |
| LLM Provider | OpenAI-compatible 先行，再扩 Anthropic/Gemini | API Key 配置后可流式聊天 |
| Chat UI | ArkUI 原生聊天页 | 多轮消息、流式追加、取消、错误展示 |
| Markdown / 代码高亮 | 优先复用 Rust 或 ArkTS fallback | 消息渲染稳定，无明显 jank |
| 会话持久化 | Room schema 映射 RDB | 退出重进保留会话和分支 |
| Preferences | DataStore 语义映射 Preferences | Provider、模型、主题等设置可保存 |

### 4.2 P1：核心体验

| 功能 | 迁移策略 | 备注 |
|---|---|---|
| 工具系统框架 | ArkTS 定义 Tool 接口、权限 broker、结果卡片 | 先做安全工具子集 |
| 文件/附件 | Core File Kit + picker | 避免假设任意路径可访问 |
| ArkWeb | Web 组件 + JS bridge | 用于网页预览、mini app、live HTML 卡片 |
| 搜索 SDK | ArkTS HTTP 重写 | Exa/Tavily/Zhipu 等协议可复用 |
| 文档解析 | Rust office/reader crate NAPI | 若 NAPI 未通，先降级纯文本/云解析 |
| 云 TTS | HTTP provider 重写 | 比系统 TTS 风险低 |
| 设置页 | Compose → ArkUI | Provider、模型、工具、隐私、数据管理 |
| 后台生成任务 | WorkScheduler / 受限 continuous task | 需验证系统是否允许 AI 长时生成场景 |

### 4.3 P2：高风险平台能力

| 功能 | 风险 | 推荐处理 |
|---|---|---|
| Accessibility 自动操作手机 | HarmonyOS NEXT 能力不一定等价 | Phase 0 验证，无法实现则改为用户确认式操作 |
| 通知监听 / 今日看板采集 | 第三方通用通知监听可能不可用 | 改为自有数据源、手动接入、API feed |
| proot / Alpine / 本地终端 | 沙箱可能禁止执行任意 ELF/proot | 改为远程 CLI seat / 桌面 seat / 云端执行 |
| 本地 Claude Code / Gemini CLI seat | 依赖终端和本地运行环境 | 第一版不承诺，走远程 seat |
| MediaProjection / 截屏 / 悬浮窗 | 权限和系统能力不确定 | 单独 spike，不绑 P0/P1 |
| SMS / Phone / Contacts / Calendar | 权限强受限 | 逐项判定，可用则做，不可用则下线 |
| 系统 TTS | API 可用性和音频策略需验证 | 云 TTS 先行，系统 TTS 后置 |

## 5. 阶段计划

## Phase 0：可行性生死判定（1-2 周）

目标：先验证平台边界，避免大规模开发后发现关键能力不可用。

### 任务

1. 创建最小 HarmonyOS NEXT ArkTS 工程。
2. 验证 NAPI + Rust：
   - 选择最小 crate，例如 tokenizer 或 highlight。
   - 编译为 HarmonyOS 可加载模块。
   - 真机调用并与 Android/Rust 基准输出对齐。
3. 验证 SSE/流式 HTTP：
   - OpenAI-compatible streaming。
   - 取消请求。
   - 网络异常恢复。
4. 验证 RDB/Preferences：
   - 创建会话表。
   - 写入、查询、迁移版本。
5. 验证 ArkUI 流式渲染：
   - 每秒高频 token append。
   - 滚动跟随。
   - 长消息性能。
6. 验证平台高风险能力：
   - Accessibility / 无障碍扩展能力。
   - 通知监听能力。
   - 后台长时任务。
   - proot/本地 ELF 执行可行性。
   - 文件 picker 和沙箱路径。

### 产物

- `docs/harmony-capability-matrix.md`
- `harmony-spike/` 最小工程
- Rust NAPI demo
- SSE demo
- RDB demo
- 高风险能力结论：可行 / 受限 / 不可行 / 需替代

### Gate

进入 Phase 1 前必须回答：

1. Rust NAPI 是否可用？
2. ArkTS SSE 是否能支撑 Chat？
3. RDB/Preferences 是否能支撑本地状态？
4. Accessibility/通知监听/终端是否进入 P2 降级？

## Phase 1：共享核心与工程骨架（4-6 周）

目标：没有完整 UI，也能通过 harness 跑通核心 Agent 流程。

### 任务

1. 建立正式 HarmonyOS 工程结构：

```text
harmony/
  AppScope/
  entry/
  shared/
    agent-core/
    provider-sdk/
    storage/
    platform/
    ui-kit/
  native/
    rust-napi/
```

2. 冻结核心接口：
   - `AgentRunner`
   - `MessageStore`
   - `ProviderClient`
   - `ToolRegistry`
   - `PermissionBroker`
   - `AttachmentStore`
3. ArkTS 重写 Agent 内核：
   - message pipeline
   - streaming state
   - tool call state
   - cancellation
   - error model
4. ArkTS Provider SDK：
   - OpenAI-compatible P0
   - Anthropic/Gemini P1
   - custom base URL
   - headers / proxy / timeout
5. RDB schema v1：
   - assistant
   - conversation
   - message_node
   - ui_message
   - provider_config
   - attachment
6. Preferences：
   - provider settings
   - UI settings
   - model defaults
   - privacy flags
7. Rust NAPI：
   - tokenizer
   - markdown/highlight
   - document extractor（如果 Phase 0 通过）

### 产物

- `harmony/` 正式工程
- `shared/agent-core` ArkTS 包
- `shared/provider-sdk` ArkTS 包
- `shared/storage` ArkTS 包
- Rust NAPI 包
- harness：命令式跑一次 Agent loop

### Gate

- harness 能完成：输入 prompt → provider streaming → 消息聚合 → RDB 保存。
- OpenAI-compatible Provider 可用。
- 核心状态机有单测。
- Rust NAPI 至少一个核心 crate 通过真机验证。

## Phase 2：原生 Chat 闭环（6-8 周）

目标：做出可日用的原生聊天 App。

### P0 页面

1. 启动页 / 初始化页
2. 会话列表
3. 聊天页
4. Provider 设置
5. 模型选择
6. 消息详情 / 工具调用详情
7. 基础设置

### 核心体验

- 多轮聊天
- SSE 流式输出
- 取消生成
- 错误重试
- Markdown 渲染
- 代码块高亮
- 会话分支
- 消息复制/分享
- Provider/API Key 本地保存
- App 重启恢复会话

### 数据迁移

Android → HarmonyOS 数据迁移分两条：

1. **导出/导入路线**：Android 版导出 AmberAgent archive，HarmonyOS 版导入。
2. **系统迁移路线**：如果 HarmonyOS NEXT 提供备份恢复入口，则通过 BackupExtensionAbility 做转换。

第一版建议先做导出/导入，不要依赖系统迁移。

### Gate

- 真机上可以日常聊天 30 分钟无崩溃。
- 5000 token 长回复不卡死。
- 100 条会话列表滚动稳定。
- 断网/Provider 错误/取消生成都有明确 UI。
- RDB 数据可备份和恢复。

## Phase 3：P1 能力面迁移（6-8 周）

目标：从“聊天 App”变成“AmberAgent 工作台”。

### 任务

1. 工具系统：
   - Tool schema
   - Tool call/result UI
   - 权限 broker
   - 安全审计日志
2. 文件与附件：
   - 图片选择
   - 文档选择
   - 文件缓存
   - 附件索引
3. 搜索：
   - Exa/Tavily/Zhipu 等 HTTP provider
   - 搜索结果卡片
4. 文档解析：
   - 先接 Rust NAPI
   - 不可用时接云端解析 fallback
5. ArkWeb：
   - 网页预览
   - HTML 卡片
   - JS bridge
6. TTS：
   - 云 TTS provider
   - 音频播放
7. 设置体系：
   - Provider
   - Model
   - Tool
   - Storage
   - Privacy
   - Debug

### Gate

- P1 功能矩阵全部可用。
- 工具调用过程可见。
- 文件附件能参与 prompt。
- 搜索结果能回填会话。
- TTS 能朗读消息。

## Phase 4：P2 平台特性与替代方案（6-10 周）

目标：处理 Android 版最平台相关的能力。

### 4.1 Accessibility / 手机自动化

如果 HarmonyOS 能力足够：

- 定义 `ScreenAutomationAdapter`
- 支持 observe / tap / swipe / input / back / verify
- 做最小自动化闭环

如果能力不足：

- 改为用户确认式操作。
- 利用 Want、分享、剪贴板、快捷服务、应用内工具。
- 自动化能力标记为“受限 beta”。

### 4.2 通知监听 / 今日看板

如果通用通知监听不可行：

- 今日看板改为数据源聚合：RSS、网页、搜索、手动收藏、API feed。
- 只处理自家通知和用户主动授权的数据。

### 4.3 本地 CLI / 终端

不建议第一版硬做 proot。

替代方案：

```text
HarmonyOS App
  ↓ WebSocket / HTTPS
Remote Seat / Desktop Seat / Cloud Seat
  ↓
Claude Code / Codex / Gemini CLI / Kimi CLI
```

鸿蒙 App 负责展示过程、发送任务、管理会话、接收结果。

### 4.4 后台任务

- 可行则实现长任务提醒和进度通知。
- 不可行则限制为前台生成、后台仅保存状态。

### Gate

- 每个 P2 功能必须有三态结论：
  - 原生可行
  - 降级替代
  - 正式下线
- 不允许“先留着以后再说”阻塞发布。

## Phase 5：收敛、性能、合规、发布（3-4 周）

### 任务

1. 性能：
   - 冷启动
   - 流式渲染 jank
   - 长会话滚动
   - RDB 查询
   - 内存占用
2. 稳定性：
   - 崩溃收集
   - 网络异常
   - 数据损坏恢复
   - migration 回滚
3. i18n：
   - 中文/英文
   - 权限 reason 多语言
4. 隐私与合规：
   - API Key 本地加密
   - 权限用途说明
   - 数据导出/删除
   - 上架材料
5. Android 对齐核对：
   - 对齐
   - 降级
   - 删除

### Gate

- 内部 dogfood 一周。
- 关键崩溃清零。
- 权限声明合规。
- 功能对齐表签收。
- 可提交应用市场审核。

## 6. AI-assisted 开发组织方式

### 6.1 迁移流水线

先搭一个“迁移工厂”，而不是人工逐文件搬：

```text
扫描 Android 源码
  → 页面清单
  → 数据模型清单
  → Room schema 清单
  → Provider 协议清单
  → Tool 清单
  → 平台 API 替换表
  → 每个单元生成迁移 prompt
  → AI worker 并行生成
  → 编译/单测/真机验证
  → reviewer 对抗性审查
```

> **产能预期 disclaimer**：ArkTS/ArkUI 公开训练语料远少于 Kotlin/Compose，AI worker 在 ArkTS 上的生成质量、API 准确度都会明显下降。实际产能会比这张流水线看起来缩水，reviewer 反馈成本更高、真机调试轮次更多。建议每个 worker 预留人工修正 + 真机验证时间，并把官方 SDK sample / 文档作为 AI 的 grounding 材料。详见 detailed-plan §5.3 R-ARK-4。

### 6.2 Worker 划分

| Worker | 输入 | 输出 |
|---|---|---|
| UI worker | Compose 文件、截图、设计规范 | ArkUI 页面和组件 |
| Model worker | Kotlin data class、serialization | ArkTS interface/class、mock、测试 |
| Storage worker | Room Entity/DAO/Migration | RDB schema、DAO wrapper、迁移脚本 |
| Provider worker | Kotlin Provider client | ArkTS HTTP/SSE client |
| Tool worker | Tool schema/handler | ArkTS tool 实现、权限声明、结果卡片 |
| Native worker | Rust crate/JNI binding | NAPI binding、corpus 回归 |
| QA worker | 功能说明、验收标准 | 测试用例、回归 checklist |
| Reviewer worker | 产物和平台文档 | 风险、语义漂移、YAGNI 建议 |

### 6.3 人工把关点

以下不能完全交给 AI 自动决定：

1. 平台能力是否可行。
2. P2 功能是否下线。
3. 权限和隐私策略。
4. 数据迁移策略。
5. 架构边界。
6. 上架合规。

## 7. 数据迁移计划

### 7.1 迁移对象

- Assistants
- Conversations
- MessageNodes
- UIMessages
- Provider configs
- Tool settings
- Attachments metadata
- Workspace state
- Preferences

### 7.2 推荐路径

第一版采用应用内导出/导入：

```text
Android AmberAgent
  → 导出 amberagent-backup.zip/json
  → 用户传到 HarmonyOS
  → HarmonyOS AmberAgent 导入
  → RDB migration
```

优点：不依赖系统级备份恢复，不受 HarmonyOS 升级路径限制。

### 7.3 数据安全

- API Key 默认不明文导出。
- 用户可选择是否导出 Provider secrets。
- 导出包支持密码加密。
- 导入前做 schema validate。
- 导入失败可回滚。

## 8. 风险登记册

### 8.1 时间估算假设

§1 的周数是范围估算，不是承诺日期。下列假设任一不成立都会拉长周期：

1. 一名熟悉 Android/iOS 移动端、但**首次接触 ArkTS/ArkUI/DevEco** 的工程师全职投入。
2. HarmonyOS NEXT SDK 文档质量满足自举；Gate 0 在 1-2 周内解锁。
3. 三方库缺口（见 detailed-plan §5.2）不导致架构返工。
4. AI 生成代码在 ArkTS 上质量可接受（**注意：ArkTS 训练语料远少于 Kotlin/Compose，AI 流水线实际产能会比 §6 描述的缩水，reviewer 反馈成本更高**）。
5. P2 平台能力（Accessibility/通知监听/terminal）的降级方案不需要重做主链路。

| ID | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | Accessibility 无对等能力 | 手机自动化能力缺失 | Phase 0 判定；改用户确认式操作 |
| R2 | 通知监听不可用 | 今日看板采集能力缩水 | 改数据源聚合，不绑主线 |
| R3 | proot/本地 CLI 不可用 | 本地 CLI seat 缺失 | 改远程/桌面/cloud seat |
| R4 | Rust NAPI 打包复杂 | 文档/高亮/tokenizer 复用受阻 | 先验证最小 crate，失败则 ArkTS fallback |
| R5 | ArkTS SSE 不稳定 | 流式聊天体验受损 | Phase 0 早验，必要时改原生 socket/bridge |
| R6 | ArkUI 流式渲染 jank | 聊天核心体验差 | Phase 2 设性能预算，复用 Android jank 优化经验 |
| R7 | Room → RDB 数据迁移错误 | 用户数据损坏 | 双向备份、schema validate、导入回滚 |
| R8 | 1:1 迁移范围失控 | 项目延期 | P0/P1/P2 强裁剪，P2 不阻塞发布 |
| R9 | DevEco/SDK 版本变化 | 编译和 API 适配反复 | 锁 SDK 版本，建立兼容层 |
| R10 | AI 生成代码质量不稳 | 隐性 bug | 编译、单测、真机、reviewer worker 多重 gate |

## 9. 验收标准总表

| 阶段 | Gate |
|---|---|
| Phase 0 | 可行性矩阵完成；Rust NAPI/SSE/RDB/ArkUI 流式至少有 demo 结论 |
| Phase 1 | harness 跑通一次完整 Agent loop；核心单测通过 |
| Phase 2 | 真机可日常聊天；流式稳定；RDB 持久化；基础设置可用 |
| Phase 3 | P1 功能全绿；工具/文件/搜索/TTS/ArkWeb 可用 |
| Phase 4 | P2 功能三态收敛：原生/降级/下线 |
| Phase 5 | 性能、稳定性、i18n、隐私合规、上架材料完成 |

## 10. 立即下一步

建议马上做四件事：

1. 在 `harmony-preview` 分支创建 `harmony/` 工程骨架。
2. 新建 `docs/harmony-capability-matrix.md`，列出所有平台能力待验证项。
3. 做 Phase 0 spike：
   - Rust NAPI tokenizer
   - OpenAI-compatible SSE
   - RDB conversation table
   - ArkUI streaming list
4. 建立迁移清单生成脚本：
   - 页面清单
   - 数据模型清单
   - Room schema 清单
   - Tool 清单
   - Android API 使用清单

## 11. 推荐目录结构

```text
AmberAgent-harmony-preview/
  docs/
    harmony-native-migration-plan.md
    harmony-capability-matrix.md
    harmony-android-feature-parity.md
    harmony-ai-migration-prompts.md
  harmony/
    AppScope/
    entry/
    shared/
      agent-core/
      provider-sdk/
      storage/
      platform/
      ui-kit/
      tool-runtime/
    native/
      rust-napi/
    tests/
      corpus/
      fixtures/
  scripts/
    harmony-inventory/
      scan-pages.mjs
      scan-room-schema.mjs
      scan-tools.mjs
      scan-android-apis.mjs
```

## 12. 最终判断

原生鸿蒙版 AmberAgent 可以做，但要按 AI-native 重写工程来组织，而不是按传统手工迁移。

主线判断：

- **能快速做出来的**：原生 Chat、Provider、流式、会话、工具展示、文件、搜索、云 TTS、文档解析。
- **需要早判定的**：Rust NAPI、ArkTS SSE、RDB 数据迁移、ArkUI 流式性能。
- **不能硬承诺的**：Accessibility 自动操作手机、通知监听、本地 CLI/proot、系统级权限工具。

建议目标（**范围预期，非承诺**）：

```text
2 周：完成 Phase 0 生死判定
6-8 周：原生 Chat Alpha
3-4 个月：Harmony Preview 可日用
6 个月：公开测试版
9-12 个月：接近 Android 版成熟度
```

这些区间在 ArkTS 学习曲线、三方库缺口、AI 生成质量未经验证前都应视为乐观端；详见 detailed-plan §5.2（依赖 availability）与 §5.3（ArkTS 技术风险）。

这条路线既相信 AI 的生成能力，也尊重 HarmonyOS NEXT 的平台边界。
