# AI 伴随（Live Companion）现状调研与全面重构蓝图（v3 · R2 复核后修订）

> **文档目的**：本文是对 AmberAgent Android 仓「AI 伴随」功能的调研与重构方案，作为 P0 实施契约。**v3 已按 GPT6 Astra R2 复核意见修正全部阻断项**（R1/R2 逐条裁决记录见 §10）。
> **版本**：v3，2026-09-15。v1 主调研；v2 并入 R1；v3 修正 R2 指出的三个 P0 契约冲突（P0 手动语义自相矛盾 / epoch 注记歧义 / 门控执行顺序）、截图与填入开放边界、Live 持久化保留关系。
> **前提约束**：团队 1–2 名 Android 主力开发；六个月交付可日常使用的公开测试版；Google Play 判断仅在计划上架时构成发布门槛。
> **调研方法**：主代理通读功能全部 11 个核心文件（约 2900 行）+ 三个并发子代理 + 主代理复核。GitHub 星数为 2026-09-15 API 实查。
> **核心原则（R1 确立）**：每个扩展阶段都必须证明比"用户手动问一次屏幕"更有净收益。六个月的交付标准是用户愿意持续使用，而不是能力矩阵多几个勾。

---

## 1. 摘要（TL;DR）

- 「AI 伴随」2026-08-23 落地后零功能演进；**伴随页面当前在应用内不可达（孤岛页面）**，功能实质断裂。
- 真正的问题不是"没接 runtime"这个形式，而是：**上下文失效无校验、产物不落库、usage 不可查询、屏幕写入无仲裁、隐私边界未定义**。
- 外部调研（证据强度已降级）："读屏→悬浮气泡→只填不发"形态本次检索未见成熟开源竞品。
- **P0 契约（R2 定稿）**：恢复入口；显式伴随会话启停；**P0 仅由用户明确操作触发分析（只做手动问屏幕），屏幕事件仅用于使旧上下文失效，不自动启动模型请求；P0 不开放截图分析；填入能力 P0 只提供用户主动复制**；上下文失效校验；最小 run 持久化 + usage 独立查询；隐私边界；调用预算。P1 起逐 App 启用填入、开放截图分析；P2 起才做与手动基线对照的有限自动建议（逐 App 由用户开启、默认关闭）。

---

## 2. 功能现状

### 2.1 用户可见能力

- **入口**：设置页有 Live 伴随开关区；伴随主页 `LiveCompanionPage` 存在但应用内不可达（见 §3.2）。
- **两种分析模式**（`LiveAnalysisMode`，`feature/live/api/.../LiveModeModels.kt:7-15`）：文字分析（只读无障碍 UI 树）/ 截图分析（截屏喂视觉模型）。〔R1 命名修正：原"保守/激进"〕
- **场景识别**（`LiveScenes.kt`）：硬编码包名清单分 CHAT/READING/OTHER；**当前默认行为：autoRefresh=true 的自动分析**（R2 裁决：P0 起收敛为仅手动触发，见 §7.2 P0-7）。
- **动作标签**：中文关键词匹配收敛为 6 个内置动作（`LiveModeManager.kt:425-436`）。
- **悬浮气泡**（`bubble/LiveBubbleWindow.kt`）：`TYPE_ACCESSIBILITY_OVERLAY` 挂无障碍服务；自持 Lifecycle owner；三态点 / 280dp 卡片 / 长按退出。
- **只填不发**（`LiveModeManager.kt:506-523`）：草稿写入目标应用输入框，失败降级写当前焦点框，再失败降级剪贴板。**风险：写入即替换，用户已有输入会被覆盖；目标应用可能自行同步草稿（如 Telegram）。**

### 2.2 技术链路

```
无障碍 screenEvents (SharedFlow, buffer=64, DROP_OLDEST)
  → screenDirty 事件驱动（LiveModeManager.kt:73-77）
      ⚠ 切屏只设 dirty 标志，不取消在飞分析、不推进 generation
  → captureLiveUiSnapshot 遍历窗口打分选择（LiveWindowCandidate）
  → 脱敏/chrome 过滤（LiveUiTreeProcessor）
      ⚠ stableHash 归一化把 \d+ 替换为 [num]（LiveUiTreeProcessor.kt:166-171），
        "Price: 99→199" 这类业务变化会被去重吞掉
  → LiveEngine 状态机（防抖→去重→冷却→退避，有测试）
  → LiveAnalyzer 裸调 ProviderCatalog.text().complete()
      （maxTokens=420, temperature=0.25, ReasoningLevel.OFF，LiveAnalyzer.kt:89-103）
  → 中文 actionLabel 输出契约 + 文本段解析（LiveAnalyzer.kt:174-239）
      ⚠ "写回复"未识别到回复段时降级把通用结论当草稿（LiveAnalyzer.kt:221-225）
  → LiveModeCard → 内存 StateFlow → 气泡/页面（不落库）
```

- 模型解析：伴随专用 `companionModelId` 优先，回退跟随聊天模型（LiveAnalyzer.kt:37-42）。**模型回退必须服从数据接收方授权，不能仅按"当前聊天模型可用"决定。**
- 截图通路：`takeScreenshotBitmap`（整屏，API 30+）→ JPEG(80) → `cache/live/live_screenshot.jpg`（LiveScreenshotter.kt:16-29）。**缺陷**：(a) 树先截、图后截，非同一次观察；(b) 缓存文件无删除机制。API 34+ `takeScreenshotOfWindow` 可按窗口截图避免 overlay 混入；安全窗口与 `accessibilityDataSensitive` 存在平台读取限制——**"读不到/部分可读"必须是正常结果**。

### 2.3 配置面（`LiveModeSetting`，9 字段）

enabled / autoRefresh / refreshIntervalMs / maxNodes（设置页已接线）；analysisMode / companionModelId / bubbleEnabled（仅伴随页，因孤岛当前不可配）；**voiceInputEnabled（死配置）**；stableDelayMs / minAnalysisIntervalMs（无 UI，不必全部暴露）。

### 2.4 测试面

仅纯逻辑层 3 个测试类。**LiveModeManager（560 行编排）、LiveAnalyzer、bubble、填入链路零覆盖。** P0 验证重点（R1 指定、R2 修正验证方法）：离开页面继续运行、停止后晚到结果被拒绝、切聊天后禁止旧草稿填入、已有输入不被覆盖、终态落库、usage 可查——**每项具备最小充分验证证据；已复现缺陷在存在验证缺口时补最少回归用例**（允许复用现有测试、最小复现与直接运行检查，不强制全部 failing→passing 新测试）。

---

## 3. 停滞与断裂证据

### 3.1 git 史（共 5 条，逐 hunk 核对）

| 提交 | 日期 | 性质 |
|---|---|---|
| f79e7e1 | 2026-08-23 | **功能诞生**（约 2900 行纯新增） |
| b4ab3bc | 2026-08-27 | 纯迁移适配 |
| e9326d7 | 2026-08-30 | **最后一次行为改变**（双语化） |
| c02036b | 2026-09-08 | 纯并发防护（generation 守卫） |
| 3ff8cd4 | 2026-09-13 | 纯视觉换肤 |

### 3.2 孤岛页面（主代理复核确认）

路由定义存在（`RouteActivity.kt:674`），**全仓无任何 `navigate(Screen.LiveCompanion)` 调用点**；无 deep link；设置页只有开关无入口行。**待产品定性：有意收口还是入口遗失。**

### 3.3 生命周期倒挂

`onDispose → vm.stop()`（LiveCompanionPage.kt:99-101）+ `LiveCompanionVM.onCleared → stop()`（:122-125）：页面 VM 决定 DI 进程级单例（AgentInfraModule.kt:67）的生死。

---

## 4. 问题清单（R2 定稿；D5/D8 已按 R1 移除——非债点）

| # | 问题 | 证据 | 严重度 |
|---|---|---|---|
| D1 | 卡片/状态纯内存，进程死即丢 | LiveModeManager.kt:37-40,107-123 | P0 |
| D2 | **上下文失效无校验**：切屏不取消在飞分析、不推进 generation；旧快照结果照样回写当前卡片。Room CAS 保护单 run 状态转移，与"多个分析对同一当前卡片的竞争"是两个语义——**保留等价守卫并加领域层校验（会话/上下文版本 + 当前 run + 有效期），落地前保留现有 generation** | LiveModeManager.kt:74-76,:344-396,:378 | P0 |
| D3 | 分析不过 run 协议；接入陷阱：`runScopeFactory` 按输入类型分支（AgentRuntimeModule.kt:199），新输入漏加分支落入 `LegacyRunScope` 的 `NoOpEventWriter`（LegacyRunScope.kt:45），事件零持久化；注册 codec 不自动产生 Final 事件 | AgentRuntimeModule.kt:183-209 | P0 |
| D4 | `result.usage` 丢弃；"入 run 事件"≠"Stats 可见"——Stats 只查 `messageStatsDAO`（StatsVM.kt:65）；不得伪造聊天消息充统计 | LiveAnalyzer.kt:104 | P0 |
| D6 | **屏幕写入无仲裁**：布尔检查与写入间有竞态；锁只能协调自身；目标仅按包名找可编辑框，失败退到任意焦点；`ACTION_SET_TEXT` 替换式覆盖已有输入 | LiveModeManager.kt:506-523; AmberAccessibilityService.kt:77; ScreenAutomationTools.kt:452 | P0 |
| D7 | 场景识别硬编码包名 + 动作标签中文关键词匹配 | LiveScenes.kt; LiveModeManager.kt:425-436 | P1 |
| D9 | **隐私问题发生在云同步之前**：正则脱敏不处理截图像素；截图直接发往视觉模型且缓存无删除；run 日志/历史/记忆/聊天导出/通知每个都新增副本 | LiveScreenshotter.kt:16-29 | P0 |
| D10 | **去重吞业务变化**：stableHash 数字归一化为 [num]，价格/数量变化保持同签名 | LiveUiTreeProcessor.kt:133,166-171 | P1 |
| D11 | **树与截图非同一次观察**；整屏截图混入 overlay；平台不可读状态（安全窗口、sensitive）未入产物契约 | AmberAccessibilityService.kt:120-146 | P1 |
| D12 | **成本无准入控制**：冷却≠预算；无效页面变化反复触发；取消请求不能假定不计费 | LiveAnalyzer.kt:89-104 | P1 |
| D13 | **记忆授权 ≠ 存储复用**：屏幕内容含对抗性指令/他人陈述/模型输出；candidate 模型围绕 conversation/message，无法表达伴随卡片及第三方内容主体 | MemoryModels.kt:104 | P1 |
| D14 | 共享事件流串扰：自动化手势触发 CONTENT_CHANGED → Live 多余分析（防抖/冷却缓解，代价是多余模型调用） | LiveModeManager.kt:73-77 | P1 |

---

## 5. 外部调研（截至 2026-09-15；证据强度已分层）

> 纪律：有限检索只支持"本次未找到更强反例"；README 声明 / 可运行示例 / 独立复现 / 生产验证分列；星数为 2026-09-15 GitHub API 采集记录。

### 5.1 GUI / 屏幕理解开源框架

| 项目 | 星数(2026-09-15 采集) / 最近推送 | 核心架构 |
|---|---|---|
| [bytedance/UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) | 38,976 / 2026-09-11 | 纯视觉；已转型 Agent TARS CLI；不碰移动端 |
| [bytedance/UI-TARS](https://github.com/bytedance/UI-TARS)（模型） | 11,462 / 2026-01-27 | UI-TARS-2 报告 AndroidWorld 73.3；权重开放状态未核实 |
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) | 9,371 / 2026-09-14 | **无障碍树+截图混合**，设备端 Portal App 开 AccessibilityService，MIT。**与本仓架构最接近的开源对标** |
| [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent) | 9,199 / 2026-07-07 | GUI-Owl VLM（2B–235B）；adb/云手机部署 |
| [simular-ai/Agent-S](https://github.com/simular-ai/Agent-S) | 12,290 / 2026-09-05 | S3：截图+grounding+反思；Android 仅评测 |
| [TencentQQGYLab/AppAgent](https://github.com/TencentQQGYLab/AppAgent) | 6,881 / 2025-03（停滞） | 探索-学习两阶段 |
| [OpenAdaptAI/OpenAdapt](https://github.com/OpenAdaptAI/OpenAdapt) | 1,724 / 2026-09-12 | 演示→编译确定性程序；无移动端 |
| [xlang-ai/OpenCUA](https://github.com/xlang-ai/OpenCUA) | 839 / NeurIPS 2025 Spotlight | 22.6K 人类轨迹（含无障碍树），桌面 only |

要点（有活跃仓库与可运行示例支撑）：屏幕读取分纯视觉派与无障碍树/混合派；所有框架动作空间都是"操作"，**本次检索未找到把"读屏→悬浮 UI→只填不发"当产品形态的成熟开源项目**；2025-2026 趋势是"演示→固化技能/程序"降本。

### 5.2 系统级商用参照

- **Google**：Gemini Live 屏幕共享（用户主动开启的会话模式，[官方帮助](https://support.google.com/gemini/answer/15274899?co=GENIE.Platform%3DAndroid&hl=en)描述用户开启/停止/重新共享）。
- **Apple**：iOS 26 Siri 屏幕感知经 App Intents——让 App 声明上下文（[WWDC26 Session 343](https://developer.apple.com/videos/play/wwdc2026/343/)）。
- **Samsung**：Seamless Action；Now Brief 主动晨报。
- **中国厂商**：OPPO 一键问屏+小布记忆；荣耀 YOYO；小米超级小爱。路径差异仅媒体报道（[证券时报](https://www.stcn.com/article/detail/3646781.html)，证据强度弱）。**本项目当前采用无障碍路线**（R2 修正措辞：MediaProjection 亦存在但需用户授权共享会话，不构成排他断言）。

### 5.3 开源悬浮助手 / overlay 形态

品类碎片化（本次检索未见 500 星以上项目）：[eggbrid2/mobileClaw](https://github.com/eggbrid2/mobileClaw)（376★，README 声明层面）；[vNeeL-code/GHOST](https://github.com/vNeeL-code/GHOST)（164★，WIP——README 声明最接近"端侧伴随"形态，未见独立复现；其"摇一摇唤出"属用户触发，不能认定具备主动决策能力）。

权限路径：(a) AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY（本项目现用）；(b) SYSTEM_ALERT_WINDOW 纯悬浮 UI；(c) **MediaProjection——授权单位是一次共享会话，会话可持续提供画面**（[官方说明](https://developer.android.com/media/grow/media-projection)）；(d) adb 外挂，开发工具向。

### 5.4 主动式伴随（学界）

- [Proactive Agent（arXiv:2410.12361）](https://arxiv.org/abs/2410.12361)：ProactiveBench，F1 66.47%。
- [Perceive Before Reasoning（arXiv:2606.03236，小米 HyperAI）](https://arxiv.org/abs/2606.03236)：门控与上下文压缩分离。**限定：其 Perceptor 是训练得到的多模态模块，支持"分离"结构，不给手写非 LLM 打分器背书。**
- [Do Proactive Agents Really Need an LLM to Decide When to Wake（arXiv:2605.30152）](https://arxiv.org/abs/2605.30152)：**限定：依赖结构化事件图（TGL），不能假定三方 Android 应用拥有同等输入。**
- **可采信的共识**：门控与推理分离、误触发率需入指标、记忆参与触发决策。指标必须是组合（有效帮助/漏触发/每活跃小时误打扰/成本）——只优化误触发率会把"永远沉默"训练成最优解。

### 5.5 记忆与人格化

- [mem0ai/mem0](https://github.com/mem0ai/mem0)（65,279★）：限定：当前 v3 `add` 接口文档有 ADD-only 依据（[Add API](https://docs.mem0.ai/api-reference/memory/add-memories)），不意味着整个产品取消显式 update/delete（[Update 文档](https://docs.mem0.ai/core-concepts/memory-operations/update)），也不意味着所有 OSS 配置检索组合相同。
- [letta-ai/letta](https://github.com/letta-ai/letta)（24,736★）；[getzep/zep](https://github.com/getzep/zep)（4,914★）+ Graphiti；[SillyTavern](https://github.com/SillyTavern/SillyTavern)（33,354★）人格卡事实标准；[Open-LLM-VTuber](https://github.com/Open-LLM-VTuber/Open-LLM-VTuber)（13,756★，其长期记忆模块自述"暂时移除"）。
- 可采信模式：profile 记忆 + 情景记忆 + 离线反思整理三层结构。

### 5.6 实时语音伴随（R1 已修正事实错误）

- [pipecat-ai/pipecat](https://github.com/pipecat-ai/pipecat)（15,533★，官方 Kotlin SDK）；[livekit/agents](https://github.com/livekit/agents)（14,191★，移动端成熟）。
- [kyutai-labs/moshi](https://github.com/kyutai-labs/moshi)（11,078★）：开源全双工；**官方写 MLX 支持 iPhone 和 Mac；160ms 是理论延迟，官方另列 L4 GPU 上约 200ms 实际整体延迟**；无官方 Android。
- 端到端 omni：[GLM-4-Voice](https://github.com/zai-org/GLM-4-Voice) **官方明确 9B**（3,231★，停更于 2024-12）；[Qwen3-Omni](https://github.com/QwenLM/Qwen3-Omni)（4,009★，30B-A3B MoE）；[Step-Audio2](https://github.com/stepfun-ai/Step-Audio2)（1,518★）。
- **结论**：移动端现实方案 = 云端 e2e API 或端侧级联。端侧语音不进路线的理由是"团队当前没有已验证的 Android 部署及体验收益"（资源取舍）。

### 5.7 端侧模型（R1 已修正推导范围）

- **Gemini Nano / AICore**：三方经 [ML Kit GenAI API](https://developers.google.ml-kit/genai)；**推理仅限应用最前台、每 app 配额——这是该推理 API 的限制，不是 AccessibilityService 或其他模型后端的共同限制**。
- [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)（24,699★）：Gemma 4 + LiteRT-LM。
- 延迟数据点（社区基准，弱来源）：Gemma 3n E2B 约 20–35 tok/s 旗舰。
- 引擎活跃：llama.cpp 128,198★、MNN 16,082★、executorch 5,027★。

### 5.8 能力矩阵结论（按证据强度分列）

- **有活跃仓库与可运行示例支撑**：树/截屏读取+LLM 循环（mobilerun/AppAgent）、grounding 模型（ShowUI/UI-TARS 路线）、演示固化降本（OpenAdapt）、语音管线框架（pipecat/livekit——框架生产案例多，本组合未独立复现）、端侧 2-4B 推理（Gemma 4/MNN）、三层记忆范式（mem0/letta/zep）。
- **本次检索仅见闭源商用**：常驻静默读屏+主动唤起（系统特权）、跨会话记忆+人格+语音+屏幕四合一伴随、Android 端侧全双工语音。
- **基准缺失（非"闭源能力"）**：2-4B VLM 端侧屏幕理解的权威延迟/功耗基准公开资料缺失。

### 5.9 未核实项（不得当作事实引用）

UI-TARS-2 权重开放状态；MediaTek 38 tok/s 宣称；letta sleep-time compute 细节；中国厂商读屏路径差异（仅媒体报道）；端侧屏幕理解延迟/功耗基准；"Gemini 屏幕共享依赖系统特权绕过逐次授权"。

---

## 6. 本仓可复用底座（R2 定稿接线契约）

| 底座 | 复用度 | 关键接线点 |
|---|---|---|
| agent runtime | 高 | `LiveTurnDescriptor/LiveTurnInput/LiveTurnArtifact`（DeepReadWorker.kt:127 范式）。**陷阱（主代理已核实）：`when(input)` 必须新增 LiveTurnInput 分支（AgentRuntimeModule.kt:183-209），否则落入 NoOpEventWriter；注册 codec 不自动产生 Final 事件。验收=handler → 持久化 scope → terminal commit → 冷读取全链路** |
| 记忆系统 | 高（机制）/ 受限（授权） | 写走 `addCandidate` 审核链；读走 `MemoryRecallStore.recall`。**屏幕内容按不可信资料处理，区分观察事实/模型推断/用户确认；首版只支持用户明确选择"记住此事"；candidate 模型需扩展表达伴随卡片来源与观察时间** |
| provider 体系 | 高 | `complete`/`stream` 均有；`Image(file://)` 经 FileEncoder。**模型回退服从数据接收方授权；usage 需独立查询链路** |
| 设置体系 | 高 | `@Serializable` 默认值即迁移；`SettingsAggregator.update` mutex 原子写 |
| Room / 持久化 | 高 | **保留契约（R2 补齐三点，主代理已核实 DAO 现状）**：①**清理对象**——`AgentRuntimeDao.deleteEventsOfTypeOlderThan(type, cutoffMs)`（:53-54）按事件 type+时间删除，可限定 Live 事件、不影响其他 descriptor；**但 DAO 当前无 run 级清理**（run 记录会无限增长），P0 需新增 Live run 清理查询（照 `pruneOldSpans` :67 模式）并明确"Live 已终结 run 与关联事件"的保留关系；现有清扫先例 RunRecoveryService.kt:143-159（7 天 best-effort、带 epoch 写保护）。②**统计保留**——usage 不得只存在于会被 TTL 删除的事件里：清理前汇总或入独立统计存储；不得删除唯一用量来源后仍承诺完整统计。③**写入上限**——仅获准分析才建 run（屏幕事件不建 run）、限制并发分析数、单次持久化体积上限。P0 不分表分库；P1 卡片表服务于用户历史的独立保留/删除语义，非高频问题的通用解法。**"双写"条款放松为：允许有界的事件→历史投影，明确全文最终权威来源及过渡保留期** |
| WorkManager | 中（职责收窄） | 只调度已保存数据的整理、清理和补偿；**不承担持续伴随生命周期**。四态分离：持久化的用户开关 / 当前会话 / 服务连接 / 在飞分析。服务重连后重新确认条件、重新采集；旧 run 可显示为中断，**旧填入动作不得恢复执行**；夜间整理只处理已获授权保存的数据 |
| 通知 | 高 | chatLiveUpdate 通道现成。**持续观察存在时，会话状态与立即停止入口进 P0；锁屏不暴露第三方摘要；action 校验会话与卡片有效性** |
| 聊天会话 | 中高 | 经 ChatService append（AgentCronWorker 先例），不直写 Repository |

---

## 7. 重构蓝图（R2 定稿）

### 7.1 目标架构

**职责分层图**（模块归属，不代表执行顺序）：

```
气泡 / 伴随页 / 通知 action            （UI gate）
        ↓
LiveCompanionManager                   （域 owner：伴随会话显式启停；四态分离——
                                        持久化的用户开关 / 当前会话 / 服务连接 / 在飞分析）
        ↓
采集层（树+图同一次观察，记录来源/时间/窗口/可用性；"读不到"是正常结果）
门控层（P0=确定性检查；P2=主动策略）   模型层（stream；授权过滤后的路由）
        ↓
AgentRuntime：LiveTurnDescriptor("live_turn")
        （run 只包推理，不含采集；CAS 管 run 终态，
         领域层另管上下文版本/当前 run/有效期校验——两者并存）
        ↓
产物分发（分别授权）：气泡卡片（即时）/ agent_event 最小终态（P0）→
   轻量卡片表（P1，独立保留策略）/ "发到聊天"（用户触发）/ "记住此事"（用户明确选择）
```

**执行顺序链**（R2 修正：硬门控在采集之前；通过门控、绑定确定上下文后才产生 run）：

```
用户触发 → 目标与授权预检查（权限/隐私硬门控，不可被其他特征抵消）
        → 采集并验证观察（图树匹配/窗口身份/可用性）
        → 推理准入（上下文有效性×变化显著性×预算）
        → 创建 run → 模型执行（stream）
        → 结果有效性检查（当前 run？上下文版本？有效期内？）→ 呈现
```

**门控职责分段**：P0 门控只承担确定性检查——权限与授权（采集前）、推理准入（创建 run 前）、结果仍可呈现（写回前）。用户可打断性、近期反馈、主动建议收益判断归 P2，**主动策略不提前塞进 P0**。

### 7.2 P0 — 确立可用边界（R2 定稿；P0 仅手动问屏幕）

| # | 事项 | 验收条件 |
|---|---|---|
| P0-1 | 恢复入口 + **显式伴随会话**：入口（产品决策 Q1）+ 明确启停语义 + 运行可见性（状态指示与立即停止入口） | 用户始终知道伴随在看、能一键停 |
| P0-2 | **最小 run 持久化**：LiveTurnDescriptor/Input/Artifact + `when(input)` 分支 + codec + terminal commit；**保留现有 generation，直至"上下文版本 + 当前 run + 有效期"等价守卫上线**（D2）；写入上限（仅获准分析建 run、并发上限、单次体积上限）；清理（Live 事件 TTL + **新增 Live run 清理查询**，限定范围不影响其他 descriptor 与进行中任务） | 全链路验收：handler → 持久化 scope → terminal commit → **冷读取**；离开页面继续运行、停止后晚到结果被拒绝 |
| P0-3 | 生命周期解绑：Manager 归域 owner（service 级），页面 VM 不再决定生死 | 切页/灭屏行为明确 |
| P0-4 | **usage 独立查询链 + 统计保留**：Live usage 入 run 事件 + Stats 独立查询（不伪造聊天消息）；**清理前汇总或独立统计存储，不得删除唯一用量来源**；区分实际 usage 与估算值 | Stats 页可查伴随成本（窗口明确） |
| P0-5 | **屏幕写入仲裁（契约 P0 落地；能力 P0 只开放"用户主动复制"）**：草稿绑定来源 run/会话/窗口/可识别聊天上下文；填入时重新定位目标并核对当前文本，非空内容必须得到用户明确替换选择；自动化执行期间暂停伴随采集或拒绝填入；写入后尽可能回读验证，无法确认标未知，**不自动重试、不排队执行旧草稿**；仅完整明确的草稿可进填入流程（删除 LiveAnalyzer.kt:221-225 结论降级）；**填入能力 P1 起按白名单逐 App 启用，未验证 App 一律不开放** | 切聊天后禁止旧草稿填入；已有输入不被覆盖；**未经明确确认的覆盖**触发对应 App 填入停用（明确确认的合法替换不算事故） |
| P0-6 | **隐私与会话边界**：允许观察的 App 清单、文本上传范围、接收模型（回退服从授权）、保留期限、停止语义；**默认不持久化原始屏幕**；UI 树脱敏不覆盖截图像素的缺口明示（P0 不开放截图，截图上传面暂不产生） | 首次模型上传前有授权；本地留存有边界 |
| P0-7 | **触发语义与预算（R2 修正核心）**：**P0 仅由用户明确操作触发分析；屏幕事件仅用于使旧上下文失效，不自动启动模型请求**（现状 autoRefresh=true 的自动分析默认行为随 P0 收敛关闭）；**P0 不开放截图分析**（P1-8 图树一致性/目标窗口/不可读状态完成后开放）；会话级调用/费用预算、单次超时、达预算停止行为；推理准入先于昂贵操作 | 预算耗尽行为明确；屏幕事件零模型调用 |
| P0-8 | 去重修正（D10）：上下文身份与降噪签名分开；仅忽略明确识别的时钟等噪声，保留金额/数量/正文变化；**"价格变化"使旧观察失效，下次手动分析使用新内容**（不产生自动触发）；填入安全检查不复用有损签名 | 失效语义正确；签名不用于安全判断 |
| P0-9 | 验证（R2 修正方法）：六类生产链路行为（离开页面继续运行/停止后晚到结果被拒/切聊天后禁止旧草稿填入/已有输入不被覆盖/终态落库/usage 可查）**每项具备最小充分验证证据**；已复现缺陷在存在验证缺口时补最少回归用例 | 证据清单可审计 |

### 7.3 P1 — 聚焦任务闭环（前提：P0 边界成立）

1. **流式卡片**（complete→stream）；"首次有用结果耗时"作为体验指标。
2. **聊天回复草稿聚焦**：复制通用可用；**填入按 P0-5 契约逐 App 启用（白名单）**。
3. **"保存卡片"**：结果卡片提供明确用户动作"保存到历史"；历史用轻量卡片表（可删除/独立保留策略；允许有界的事件→历史投影，全文权威来源唯一）。
4. **"发到聊天"**：用户触发，经 ChatService append。
5. **"记住此事"**：用户明确选择才入 candidate；记录观察事实/模型推断/用户确认三元 + 来源卡片 + 观察时间。
6. **"结果通知"**（R2 改名）：仅通知手动请求的分析完成/失败；锁屏不暴露第三方摘要；action 校验会话与卡片有效性。**主动建议通知归 P2。**
7. **场景配置化**：包名表外置、用户可自定义；动作标签结构化枚举+自由文本兜底。
8. **图树同次观察（D11；截图分析开放的前置）**：采集记录来源/时间/窗口/可用性，拒绝不匹配的图树组合；API 34+ 用 takeScreenshotOfWindow；"部分可读"作为正常产物状态。**完成前不开放截图分析。**

### 7.4 P2 — 与手动基线对照的有限智能（前提：手动版已证明有用）

1. **有限自动建议**：门控三分（采集/推理/打扰，硬门控不可抵消）；**逐 App 由用户单独开启、默认关闭**；与手动版本对照实验，指标组合=有效帮助/漏触发/每活跃小时误打扰/成本；冷启动从手动触发开始。
2. **主动建议通知**（与 P1-6"结果通知"区分）：仅在自动建议已开启的 App 内触发。
3. **按实测瓶颈三选一扩展**：阅读辅助 / 用户主动保存记忆深化 / 按住说话问屏幕（前提：已有可复用语音链路，不得为此引入整套实时通信基础设施）。
4. ~~全场景主动建议、自动长期记忆、人格化、全双工语音、端侧 VLM 生产承诺~~——六个月内不做。

### 7.5 不做清单

- **不做"没有明确会话边界的全天静默采集"**；用户明确开启、可见、可立即停止的跨 App 读屏会话是核心使用场景。
- 六个月内不做端侧全双工语音（资源取舍，非"不存在开源路径"）。
- 不做无用户确认的全自动操作；动作边界最多"确认后执行"，与自动化 agent 对齐单独立项。
- 不自研记忆栈；复用存储机制不等于已解决观察归属和记忆授权。
- 上架门槛（仅计划上架时生效）：通用助手不能声明 `isAccessibilityTool` 获豁免；需显著披露、明确同意及用途申报（[Google Play Accessibility 政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)）。

---

## 8. 风险与待产品决策点

| # | 决策点 | 说明 |
|---|---|---|
| Q1 | 孤岛页面是有意收口还是入口遗失？伴随页从哪进？ | 决定 P0-1 落点 |
| Q2 | 动作边界 | 维持只读+安全填入（P0-5 契约+P1 白名单）/ 开放"确认后执行" |
| Q3 | 模型路线 | 复用聊天模型（默认）/ 伴随专用小模型——数据接收方授权约束先行（P0-6） |
| Q4 | 数据口径 | P0-6 定义：允许观察的 App、上传范围、接收模型、保留期限、停止语义 |

**效果准入原则（凌驾所有分期）**：尚未证明主动观察相较"用户主动点一次"能带来持续净收益；屏幕内容不自然代表用户意图。先选一个任务建立手动触发基线，再比较主动版本的任务完成时间、采纳后修改量、误打扰和成本。

---

## 9. 六个月路线（R1 给定，R2 对齐 §7 后的定稿）

| 时间 | 交付 | 继续投入的条件 |
|---|---|---|
| 第 1 月 | 恢复入口；显式启停的伴随会话；**仅手动问屏幕（文字分析；屏幕事件仅用于失效校验）**；上下文失效校验；最小 run 持久化 + usage 查询；隐私边界 P0-6 | 退出页面、断连、切屏、停止等路径行为明确 |
| 第 2–3 月 | 聚焦少量聊天 App 的回复草稿；**复制通用可用，填入逐 App 启用**；流式；最小历史（"保存卡片"） | 能减少完成任务的时间；**未经明确确认的覆盖即关闭对应 App 填入支持** |
| 第 4 月 | 用户主动开启的有限自动建议（逐 App、默认关），与手动版本做对照 | 有效帮助增长足以抵偿误打扰、耗电和费用 |
| 第 5–6 月 | 按实测瓶颈三选一：阅读辅助 / 用户主动保存记忆 / 按住说话 | 已有任务留存和效果成立，新增能力解决实际瓶颈 |

**熔断条款**：若第 4 月主动建议仍没有清晰增益，停止扩大主动能力，资源留给可靠的问屏幕和草稿助手。

---

## 10. 外部评审裁决记录（GPT6 Astra）

### 10.1 R1 裁决（2026-09-15）

R1 的 5×P0、7×P1、4×P2 及路线修正**全部接受（无驳回）**，主代理补两条注记（①agent_event 需 TTL；②上下文版本校验对齐 SyncRestoreWriteEpoch——注记②经 R2 指出歧义后改写，见 §10.2）。主代理裁决前已核实四个仓内指控为真：`AgentRuntimeModule.kt:199` when(input) 漏分支→`LegacyRunScope.kt:45` NoOpEventWriter；`StatsVM.kt:65` 只查 messageStatsDAO；`LiveModeManager.kt:74-76` 切屏不取消在飞分析；`LiveAnalyzer.kt:221-225` 结论降级当草稿；`LiveUiTreeProcessor.kt:166-171` [num] 归一化吞价格变化。R1 删除原 D5/D8 债点（runtime 允许调用方保留错误分类；纯逻辑模块不依赖 runtime 符合分层）。

### 10.2 R2 裁决（2026-09-15）

R2 结论「需修订后再实施」，主代理逐条裁决：**全部接受（无驳回）**，本版（v3）即为修订结果。

| R2 条目 | 裁决 | v3 落点 |
|---|---|---|
| [P0] P0"仅手动"被"默认自动分析"推翻 | **接受** | P0-7 重写（仅用户明确操作触发；屏幕事件仅失效校验）；P0-8 改"失效"语义；P1-6 改名"结果通知"、主动建议通知归 P2-2；P2-1 补"逐 App 开启、默认关"；P0 门控限定为确定性检查（§7.1） |
| [P0] SyncRestoreWriteEpoch 注记歧义 | **接受**（措辞修正：复用范式，不复用同一 epoch 实例） | §10.3 核实结论一；P0-2 保留 generation 直至等价守卫上线 |
| [P0] 架构图硬门控画在采集之后 | **接受** | §7.1 拆为职责分层图 + 执行顺序链（用户触发→预检查→采集验证→推理准入→建 run→执行→有效性检查→呈现） |
| [P1] 截图/填入开放时点不明 | **接受** | P0-5：契约 P0 落地、能力 P0 只开放复制、填入 P1 白名单逐 App；P0-7：P0 不开放截图分析（P1-8 为开放前置）；熔断措辞改"未经明确确认的覆盖" |
| [P1] TTL 不足以闭合持久化方案 | **接受** | §6 Room 行重写（清理对象/统计保留/写入上限三点）；P0-2/P0-4 同步；"双写"条款放松为有界投影+全文权威来源唯一 |
| 生态证据降级未完成 | **接受** | §5.2 改"本项目当前采用无障碍路线"；§5.8 按证据强度分列，基准缺失项移出"闭源能力" |
| 验证方法过度限定 | **接受** | §2.4/P0-9 改"最小充分验证证据+最少回归用例" |

### 10.3 R2 两项"建议实施前再核"的核实结论（主代理已闭环）

**核实一：SyncRestoreWriteEpoch 语义（SyncRestoreWriteGate.kt 全文）**——epoch 仅由 `withRestore` 在恢复开始与结束时推进（:87-100）；`isWriteAllowed` = 非恢复中且 epoch 匹配（:61-62）；范式为"后台 owner 携带 generation token，触及持久态时校验，过期即拒/取消"（:110-133，ConversationRepository.kt:436、MemoryExtractor.kt:213 等均同范式）。**结论：epoch 推进与切屏/换聊天/停止会话完全无关，不能复用其实例做 Live 上下文失效校验；可复用的是携带 token+写入时校验的范式。R2 判断正确，注记②按此改写。**

**核实二：TTL 清理能力（AgentRuntimeDao.kt + RunRecoveryService.kt）**——`deleteEventsOfTypeOlderThan(type, cutoffMs)`（:53-54）按事件 type+时间删除，**可限定 Live 事件、不影响其他 descriptor**；现有先例 RunRecoveryService.kt:143-159 对 RequestSnapshot 做 7 天 best-effort 清扫（带 epoch 写保护）。**缺口证实：DAO 无 run 级清理**（仅 :67 pruneOldSpans 删 trace_span），Live run 记录会无限增长——P0 需新增 Live run 清理查询。usage 若仅存于事件则 TTL 删除即丢，统计保留条款必需。**R2 判断正确。**

---

## 附录 A：关键文件地图

**纯逻辑层（feature/live/api）**：LiveModeModels.kt、LiveEngine.kt（有测试）、LiveScenes.kt（有测试）、LiveUiTreeProcessor.kt（D10 归一化缺陷 :166-171，有测试）

**平台实现层（app/.../feature/live）**：LiveModeManager.kt（560 行编排——D2 :74-76/:378、D6 :506-523）、LiveAnalyzer.kt（草稿降级 :221-225、usage 丢弃 :104）、LiveScreenshotter.kt（缓存无清理 :16-29）、bubble/

**UI 层**：LiveCompanionPage.kt（onDispose stop :99-101）/ LiveCompanionVM.kt（:122-125）；路由 RouteActivity.kt:674（无导航调用点）；设置 SettingAgentExecutionPage.kt:232-328

**底座**：DI AgentInfraModule.kt:67（single）；无障碍 AmberAccessibilityService.kt（与 ScreenAutomationTools.kt:452 共享实例）；runtime 组装 AgentRuntimeModule.kt（:178-182 launchContext、:183-209 runScopeFactory）、LegacyRunScope.kt:45；**同步写门 SyncRestoreWriteGate.kt（epoch 仅由 withRestore 推进 :87-100）**；事件 DAO AgentRuntimeDao.kt（:53-54 事件 TTL、:67 span 清理、无 run 级清理）；清扫先例 RunRecoveryService.kt:143-159；记忆 app/amber/core/memory/；通知 AmberAgentApp.kt:391-455（chatLiveUpdate 通道）；统计 StatsVM.kt:65

---

## 附录 B：P0 实施记录（2026-09-15，v3 定稿当日完成）

**实施方式**：主代理按 4 个 phase 顺序实施，每个 phase 完成后由独立 checker 子代理做对抗性检查（逻辑闭环/调用链路/UI 细节），发现的问题当轮修复后再进下一 phase。工作区同期存在另一并行会话的无关改动（settings lambda 重构），全程隔离未触碰。

### Phase 1 — 入口恢复 + 显式会话 + 触发语义收敛（P0-1/P0-3/P0-5/P0-7 触发面）

- 设置页 Live 伴随区新增"打开伴随"导航 item（`SettingAgentExecutionPage.kt`，6 locale 文案）——孤岛页面恢复可达。
- 生命周期解绑：删 `DisposableEffect onDispose stop`（页面）与 `VM.onCleared stop`，伴随会话仅由页面主开关/气泡长按显式启停。
- 触发收敛：删 runLoop 自动分析分支（仅 refreshNow/submitFocusInstruction 触发）；截图分析关闭（analyze 强制 CONSERVATIVE，screenshotUri=null）；设置页/伴随页移除"自动刷新/分析模式"开关。
- 填入收敛为纯复制：`fillCurrentDraft` 不再触碰目标应用输入框，只写剪贴板；文案改"复制"语义（6 locale）。
- checker 结论：通过（0 必修）；顺手修复 KDoc 失真与 4 个小语种缺失。

### Phase 2 — 上下文失效校验（D2）+ 去重修正（D10/P0-8/P0-9 部分）

- D10：`normalizeForHash` 删除全数字 `[num]` 替换，仅保留时钟噪声归一——价格/数量变化不再被吞（含测试改写+新增 `LiveUiTreeProcessorNormalizeTest`）。
- D2：`LiveModeUiState` 加 `cardSignature` 字段与 `cardStale` 派生（新增 `LiveModeUiStateStaleTest`）；分析结果回写三重校验（generation + 屏幕上下文版本 + 60s TTL），失效结果丢弃；runLoop 签名变化时 stale 提示；页面 stale 呈现（"屏幕已变化，点下方动作重新分析"）。
- checker 发现 2 必修并当轮修复：丢弃/失败分支不清 `requestedAction` 导致进度卡矛盾常驻；`live_result_stale_dropped` 文案在 TTL 超期路径失实（改中性表述，6 locale）。

### Phase 3 — Live run 最小持久化（P0-2）+ usage 独立查询链（P0-4）+ 清理

- 新建 `LiveTurnRuntime.kt`（LiveTurnInput/Artifact/`LiveEventPayload.AnalysisCompleted`/LiveTurnDescriptor("live_turn")/LiveTurnAgent）；`AgentRuntimeModule` 完成 register + codec + `runScopeFactory` 分支三处接线（规避 LegacyRunScope NoOpEventWriter 陷阱）。
- Manager 分析改走 `agentRunner.launch + observe.first{isTerminal}`，三重校验保留；取消联动（pause/stop/新分析均 `agentRunner.cancel`）。
- 持久化：`AgentRuntimeDao` 新增 `deleteTerminalRunsOfAgentOlderThan`（R2 指出的 run 级清理缺口）与 `latestEventOfType`；`start()` 时冷读取恢复最近卡片 + 30 天保留清扫。
- usage：`LiveAnalyzer.Outcome` 补 usage（原被丢弃）；新建 `LiveUsageStore`（独立 DataStore 只增计数——事件 TTL 不影响统计完整性，不伪造聊天消息）；StatsVM/StatsPage 独立展示"伴随分析（次数 · Token）"（6 locale）。
- checker 发现 1 必修并当轮修复：`activeRunId` 跨线程取消竞争（launch 后加 generation 代数兜底，封死 run 泄漏与误指两窗口，守住"停止后晚到结果被拒"）；另采纳 2 建议（restore 不覆盖首帧现场、accumulate 包 NonCancellable）。

### Phase 4 — 隐私边界（P0-6）+ 死配置清理 + UI 对齐

- 隐私三链路闭合：input 不落库（agent_run 仅 digest）、durable payload 不含原始屏幕文本、截图不产生（全链路 screenshotUri=null）+ `start()` 清理旧版本遗留的 cache/live；设置页 desc 改隐私口径（"屏幕文字仅在你手动触发分析时发送给所选模型服务商，随时可停止"，6 locale）。
- 死配置：删 `LiveModeSetting.voiceInputEnabled`（JsonInstant ignoreUnknownKeys 兼容已证）+ voice 文案 6 locale；删 `autoRefresh` 死字段 + 8 个死字符串键 × 6 locale（48 行）。
- UI 细节：气泡"立即分析"按钮补 enabled 门控（与页面 chips 一致，封连点放大）；`activeRunId` 补 `@Volatile`。
- checker 结论：通过（0 必修），P0 全部 9 项验收逐条核对成立。

### 验证证据

- 编译：`:app:compileDebugKotlin` 全绿（含 Room KSP 对新 DAO SQL 的编译期校验）。
- 测试：`:feature:live:api:test` 14/14 绿（LiveEngine 7 + LiveScenes 4 + 新增 Stale 2 + Normalize 1）；`:core:agent-store-room:testDebugUnitTest` 绿；`:core:agent-runtime-impl:test`（LaunchGate/SupersededActivation/PersistingEventWriter）绿。
- 注：app 模块测试编译同期被并行工作的半成品打断（WebMountZCodeToolsTest 未解析引用，与本功能无关）；改写的 app 侧 `LiveUiTreeProcessorTest` 经 checker 在其环境实跑 9/9 绿。
- P0-9 六类生产链路：LiveEngine 门控/cardStale/归一化/runner 取消竞争有自动化覆盖；隐私三链路、冷恢复、TTL 丢弃、usage 记账、复制收敛为代码审查证据（Android 依赖点无 JVM 可测路径）。

### 留给 P1 的接线点（已按蓝图就位，未实施）

截图分析恢复（P1-8 前置：图树同次观察；LiveScreenshotter 保留、LiveTurnInput 加 mode 字段）、填入白名单逐 App 启用（P0-5 仲裁契约落地后；FILLED 分支保留）、流式卡片（complete→stream）、"保存卡片/发到聊天/记住此事"、结果通知、场景配置化、被 superseded run 的用量记账（当前为下限口径）。

### 总检查（第二轮，2026-09-15）

四 phase 完成后另派两个 checker 做端到端总检查（逻辑闭环+调用链路 / UI 细节），发现 2 必修并当轮修复：

- **pause 不清 requestedAction**（逻辑必修）：暂停取消在飞分析后恢复，ActionProgressCard 确定性显示"已收到结果"（run 已取消，永不收到）。修复：`pause()` 清 `requestedAction`。
- **stale 时同名动作 chip 被隐藏**（UI 必修，本次改动引入）：`currentAction` 落到旧卡片 actionKey 导致 `filterNot` 藏起用户最想重跑的同名 chip，与"点下方动作重新分析"提示自相矛盾。修复：stale 时 `currentAction = pendingAction`（无 pending 传空串不排除任何 chip）。

另采纳 7 项精准修复：设置页 enabled 关闭时对齐 stop() 取消语义（取消在飞 run+requestedAction 清空）；restoreLatestCard 回填前 active 校验（防 start 后极短窗口 stop 复活卡片）；accumulate 包 runCatching（记账失败不误报分析失败）；结果卡 pill 加 `weight(1f, fill=false)` 防长 app 名挤压时间戳；ConfigCard 的 ModelSelector 改 `minimalText=true`（对齐仓内样式惯例）；EmptyResultCard/GuidanceCard padding 16→14 统一；眉题" COMPANION" ink3→ink2 对齐 SectionLabel 惯例；气泡要点 bullet ink3→accent 与页面统一、Spacer 条件化；删死变量 actionLabel。修复后 `:app:compileDebugKotlin` + `:feature:live:api:test` 全绿。

总检查确认无误要点：八条端到端链路（入口/分析/失效/停止/持久化/usage/设置/干扰面）全部走通；动态验证 54 个测试全绿（live api 14 + store-room 11 + runtime-impl 20 + app 侧 LiveUiTreeProcessorTest 9）；147 个代码引用字符串键在 default locale 全部存在。
