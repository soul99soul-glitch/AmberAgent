# AmberAgent 灵动岛设计文档

Last updated: 2026-08-03

## 概述

AmberAgent 的系统灵动岛基于 ActivityKit Live Activity 实现，在 Chat 生成期间向用户传达"哪个对话在做什么、做到哪一步了"。设计遵循 iOS 27 Siri 灵动岛的核心原则：**compact 回答"有东西在跑"，expanded 回答"哪个对话在跑什么"**。

### 文件结构

| 文件 | 职责 |
| ------ | ------ |
| `iosApp/ActivityWidget/AmberAgentActivityWidget.swift` | Widget Extension 全部视图（compact / expanded / minimal / 锁屏） |
| `iosApp/iosApp/AgentActivityModels.swift` | 数据模型（Attributes / Presentation / Kind / Phase / Stage / Metric / Action / LifecyclePolicy） |
| `iosApp/iosApp/AgentLiveActivityController.swift` | Live Activity 生命周期管理（start / update / end / restore / adopt） |
| `iosApp/iosApp/ChatViewModel.swift` | 入口：`startLiveActivity()` 从 Chat 发起 |
| `iosApp/iosApp/ChatGenerationCoordinator.swift` | 状态更新：流式 chunk 驱动 stage 变化 |
| `iosApp/iosApp/AgentActivity.strings` | 英文本地化 |
| `iosApp/iosApp/zh-Hans.lproj/AgentActivity.strings` | 中文本地化 |

---

## 数据模型

### AgentActivityAttributes（不可变，创建时冻结）

```swift
struct AgentActivityAttributes: ActivityAttributes {
    let runId: String              // 生成 run 唯一标识
    let conversationId: String?    // 会话 ID（用于 deep link 回 App）
    let startedAt: Date            // 开始时间（计时器用）
    let conversationTitle: String? // 会话标题（展开态主标题）
}
```

**设计决策**：`conversationTitle` 放在 attributes 而非 ContentState，因为它是创建时的快照——避免在系统共享表面频繁刷新标题文本。隐私约束：只放用户自己创建的标题，不放模型名或提示词。

### AgentActivityPresentation（可变，随 ContentState 更新）

```swift
struct AgentActivityPresentation: Codable, Hashable {
    var kind: AgentActivityKind      // 任务类型（8 种）
    var phase: AgentActivityPhase    // 生命周期相位（7 种）
    var stage: AgentActivityStage    // 细粒度阶段（17 种）
    var metric: AgentActivityMetric  // 可选度量（count / progress）
    var action: AgentActivityAction? // 可执行动作（deep link）
}
```

### 状态流转

```
发送消息 → preparing（连接中）
         → thinking（思考中）     ← 收到 reasoning delta
         → generating（回复中）   ← 收到 text delta
工具调用 → searching / readingWeb / generatingImage / runningTool 等
等待确认 → waitingForUser
终态     → completed / failed / cancelled
异常     → reconnecting → stale（超时未更新）
```

---

## 胶囊态（Compact）

胶囊态是用户最常看到的状态——灵动岛收起时的小胶囊。设计目标：**一眼确认"还在跑"+ 当前阶段**。

### 布局

```
┌─────────────────────────────────┐
│  [星核 20pt]    [状态词 ≤56pt]  │
│  compactLeading  compactTrailing │
└─────────────────────────────────┘
```

### Leading：明确的静态语义图标（`AgentActivityIslandOrb`, size=20）

- compact/minimal 不伪装成可连续播放的动画。运行中按任务类型显示单色 SF Symbol；等待、重连、过期、完成、失败和取消使用各自的终态 Symbol。
- 图标固定为灰度：运行态白 90%，非运行态白 72%。语义由图形本身承担，不在黑岛中引入品牌色或信号色。
- 这样在系统不给连续动画预算时，用户看到的是一个有意设计的静态状态图标，而不是星核动效被截停在中间的一帧。

### Trailing：状态词（`AgentActivityCompactStatus`）

| 属性 | 值 | 理由 |
| ------ | ----- | ------ |
| 字体 | `.caption2.weight(.semibold)` (11pt) | compact 区域小，11pt 是系统标准 |
| 颜色 | `.white.opacity(0.9)` | 黑底高对比 |
| 宽度 | `frame(maxWidth: 56)` | 有界，防止系统撑宽胶囊 |
| 缩放 | `minimumScaleFactor(0.72)` | 英文长词兜底（有效下限 ~8pt） |
| 行数 | `lineLimit(1)` | 单行 |

中文状态词（2-3 字，≤36pt）不触发缩放；英文（≤8 字符）基本在 56pt 内。

**文案表**（`agent.activity.compact.*`）：

| Stage | 中文 | 英文 |
| ------- | ------ | ------ |
| preparing | 连接中 | Connect |
| thinking | 思考中 | Thinking |
| searching | 检索中 | Search |
| readingSources | 阅读中 | Reading |
| readingWeb | 读网页 | Web |
| generating | 回复中 | Reply |
| generatingImage | 绘图中 | Drawing |
| organizing | 整理中 | Organize |
| readingDocument | 读文档 | Doc |
| updatingMemory | 记忆中 | Memory |
| runningTool | 处理中 | Running |

### Minimal（多活动并存时）

仅显示 17pt 星核/SF Symbol，无文字。`accessibilityLabel` 提供完整语音描述。

### Keyline

`.keylineTint(.white.opacity(0.12))` 保持细微单色轮廓，不把品牌色带进系统黑岛。

---

## 展开态（Expanded）

长按胶囊展开。设计目标：**回答"哪个对话在跑什么"**，提供 compact 无法承载的上下文信息。

### 布局

```
┌──────────────────────────────────────────────┐
│             [TrueDepth 系统区域]             │
│  [星核 40pt]  [会话标题 ..........  0:42]   │
│               [当前阶段]                     │
└──────────────────────────────────────────────┘
```

展开内容只使用一个 `.bottom` 区域，并在同一个 `HStack(alignment: .center, spacing: 12)` 中排版。星核、文字列和计时因此共享同一套纵向坐标，不再让 `leading / center / trailing` 的系统独立布局把星核留在 TrueDepth 左侧、标题推到硬件下方。TrueDepth 所占顶部带是物理保留区，应用不能在其中绘制内容；下方内容横向铺满后，该留白保持居中且不再造成左右失衡。

### 星核（size=40）

运行态按当前 stage 复用 Chat 六套星核动效；每次 stage 变化播放一轮完整循环，WidgetKit 预算内最多 2 秒，并在 resting phase 收口。Reduce Motion、Always-On 或非运行态直接显示明确的静态语义 Symbol。

### 主标题 + 副标题（`AgentActivityIslandHeadline`）

**有会话标题时**（Chat 主路径）：

| 行 | 内容 | 字体 | 颜色 |
|----|------|------|------|
| 主标题 | 会话标题（如"帮我写一首诗"） | `.subheadline.weight(.semibold)` (15pt) | 纯白 |
| 副标题 | 阶段名（如"思考中"） | `.caption2.weight(.medium)` (11pt) | 白 58% |

**无标题时**（工具活动等）：回退到阶段做主标题 + kind 做副标题。

| 属性 | 值 | 理由 |
| ------ | ----- | ------ |
| 间距 | `spacing: 2` | 主副标题紧凑但不粘连 |
| 对齐 | `.leading` | 左对齐，阅读自然 |
| 标题行 | 标题 + `Spacer(minLength: 8)` + 计时 | 同一首行基线 |
| 光学补偿 | 文字列 `offset(y: 1)` | 修正纯白重标题的视觉重心 |
| 宽度 | `frame(maxWidth: .infinity)` | 使用星核之外的全部可用宽度 |

**设计原则**（iOS 27 Siri）：展开态的价值在于提供 compact 没有的信息。compact 已经说了"在做什么"（状态词），展开态回答"是哪件事"（会话标题）。

### 计时器

计时器位于标题行尾部，使用 12pt medium、白 55% 与 `monospacedDigit()`。运行时实时推进；completed / failed / cancelled 使用终态 `updatedAt` 冻结，不在完成后继续走动。展开岛不再放进度条、度量明细或「打开对话」按钮，整岛点击继续由根级 `widgetURL` 负责。

### Deep Link

点击展开态任意区域跳转回 App：

```
amber://activity/{runId}?conversation={conversationId}&focus={task|confirmation|result}
```

`widgetURL` 绑定在 `DynamicIsland` 和锁屏卡片上。`focus` 由 `action` 决定：

- `.openTask` → 打开对话
- `.openConfirmation` → 打开确认页
- `.viewResult` → 查看结果

---

## 锁屏卡片（Lock Screen）

锁屏横幅与展开态共享信息层级，布局为水平排列：

```
┌──────────────────────────────────────────────┐
│  [星核 34pt]  [会话标题 / 阶段]    [计时器]  │
│  [进度条]  [度量明细]        [打开对话 ↗]    │
└──────────────────────────────────────────────┘
```

| 属性 | 值 | 理由 |
| ------ | ----- | ------ |
| 外边距 | `16h / 12v` | iOS 锁屏标准 |
| 行间距 | `spacing: 8` | 图标-文字-计时器 |
| 背景 | 系统默认材质 | 不覆盖 `activityBackgroundTint`，保留与壁纸融合 |
| 前景 | `.activitySystemActionForegroundColor(.white)` | 系统操作按钮白色 |

---

## 生命周期管理

### AgentLiveActivityController（单例）

```
start(runId, conversationId, conversationTitle, presentation)
  → reconcileExistingActivities（去重）
  → requestActivity（Activity.request, pushType: nil）

update(runId, presentation, force, minimumInterval: 1.5s)
  → 节流：同 presentation 在 1.5s 内不重复更新

end(runId, presentation, dismissalDelay)
  → preservingKind（终态保留之前的 kind）
  → activity.end（立即离开灵动岛，锁屏按 delay 残留）

stopCurrent（取消所有活动）
restoreExistingActivity（App 启动时恢复）
adoptExistingActivity（后台 handoff 接管）
```

### 状态更新链路

```
ChatGenerationCoordinator
  ├─ start → bindings.startLiveActivity → ChatViewModel.startLiveActivity
  │           → AgentLiveActivityController.start
  ├─ 系统进度卡取消/终止 → onSystemTaskExpiration → cancel(runId)
  ├─ UIKit 短保活先到期 → onExpire → 后台交接
  ├─ 流式 chunk → AgentActivityResponseStagePolicy.updatedStage
  │               → liveActivityController.update（reasoning→thinking, text→generating）
  ├─ 工具调用 → .runningTool(toolName) → update
  ├─ 等待确认 → .waitingForUser() → update
  ├─ 重连     → .reconnecting() → update
  └─ 终态     → .completed() / .failed() / .cancelled() → end
```

### conversationTitle 传入规则

只在 `conversationId == currentConversationId` 时传入当前会话标题。审批恢复/后台 handoff 时用户可能已切会话，此时传 `nil`，展开态回退到阶段做主标题。

### 锁屏残留策略（`lockScreenDismissalDelay`）

| 相位 | 残留时间 | 理由 |
| ------ | ---------- | ------ |
| completed | 20s | 短暂确认 |
| failed / stale | 60s | 用户可能需要看到失败 |
| cancelled | 6s | 用户主动操作，快速消失 |
| running / reconnecting / waitingForUser | 0 | 不应在终态前消失 |

### Stale 策略

| 相位 | staleDate | 理由 |
| ------ | ----------- | ------ |
| running | now + 180s | 3 分钟无更新大概率断连 |
| reconnecting | now + 60s | 1 分钟重连不成功标过期 |
| 终态 | nil | 永不过期 |

---

## 颜色体系

| 用途 | 颜色 | 值 |
| ------ | ------ | ----- |
| keyline | 白 12% | `.white.opacity(0.12)` |
| compact/minimal glyph | 白 90% / 72% | 运行 / 非运行 |
| 主文字 | 纯白 | `.white` |
| 次要文字 | 白 58% | `.white.opacity(0.58)` |
| compact 状态词 | 白 90% | `.white.opacity(0.9)` |
| trailing 事实 | 白 88% | `.white.opacity(0.88)` |

所有文字在黑底上对比度 ≥ 6.9:1（AA 达标）。

---

## 排版层级总表

| 区域 | 字体 | 大小 | 字重 | 用途 |
| ------ | ------ | ------ | ------ | ------ |
| 展开主标题 | `.system` | 15pt | semibold | 会话标题 |
| 展开副标题 | `.system` | 11pt | medium | 阶段名 |
| 展开计时 | `.system` | 12pt | medium | 实时/冻结用时 |
| compact trailing | `.caption2` | 11pt | semibold | 短状态词 |
| 锁屏 | 同展开 center | — | — | 一致 |

---

## 隐私约束

- **不放模型名**：`AgentActivityPresentation.generatingResponse(modelName:)` 接收但故意不存储模型名（有测试断言 `XCTAssertFalse(String(describing:).contains("private-model-name"))`）
- **不放提示词**：用户输入内容不进入 Live Activity
- **会话标题**：仅用户自己创建的标题，创建时冻结
- **锁屏可见**：所有 Live Activity 内容在锁屏上可见，因此只放非敏感信息

---

## 已知限制

1. **pushType: nil**：纯本地更新，App 被杀后 Live Activity 变 stale 然后消失，无服务端推送兜底
2. **conversationTitle 不可更新**：生成过程中改标题，灵动岛仍显示旧标题
3. **metric 无生产写入点**：`AgentActivityMetric` 的 count/progress 目前只有测试/预览在喂，Chat 主路径不产生 metric
4. **系统后台任务活动并存**：Chat 当前同时申请自定义 ActivityKit 活动与 `BGContinuedProcessingTask`，后者会由系统生成独立的进度活动。系统卡被取消/终止时现已按 owned `runId` 停止 Chat，不再转后台重启；但两套系统表面是否合并仍涉及后台执行能力取舍，不能用 Widget 布局补偿代替架构决策。
5. **切会话交接的系统请求重叠**：前台 `.keepalive.<runId>` 与后台 `.chat.<runId>` 可能在交接窗口同时存在。当前终态最终都会释放，但系统卡合并与配额观感仍需真机 activity ID/录屏取证后再决定是否收敛为单一 owner。
