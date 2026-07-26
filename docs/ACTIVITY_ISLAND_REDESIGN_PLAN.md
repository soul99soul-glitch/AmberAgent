# 灵动岛重设计实施计划

状态：待实施
日期：2026-07-26
设计权威来源：`docs/ACTIVITY_ISLAND_REDESIGN.md`（视觉/动效/文案规范以该文件为准，本计划只回答怎么做、怎么验）。
工作方式：一次一个切片，红→绿优先；未经用户授权不 commit。

## 0. 实施前必须核对的事实

现有 canary（改动后必须保持或同步更新，不允许静默放宽）：

1. `IOSSettingsWiringTests.testChatTitleIslandUsesIntrinsicWidthUpToItsCollisionLimit`：idle 宽度短标题 <150pt、长标题 ≤230.5pt（UIHostingController 实测）。S1 不得改变 idle 几何。
2. `IOSSettingsWiringTests.testCustomTopBarsUseNativeSoftEdgesAndLiquidGlassControls`：岛源文件必须含 `.glassEffect(.regular, in: Capsule())` 与 `Capsule().fill(.ultraThinMaterial)`。S1 保留这两行原样。
3. `ThinkingOrbEngineTests` 8 条：引擎不改，应全程不动。

环境事实：

- 工程由 XcodeGen 生成（`iosApp/project.yml`）：新增 Swift 文件后需重跑 `xcodegen`（或确认 glob 自动收录）。
- 测试命令按 `iosApp/AGENTS.md`，只传 `-destination` 不传 `-sdk`；本机曾缺 watchOS 26.5 platform，若 scheme 构建受阻记录为环境限制而非产品失败。
- 工作区有他会话未提交改动（ish 内核、Novel）。本轮只碰下列文件清单，动手前逐文件 `git diff` 核对。

文件范围（超出需先说明）：

- 新增：`iosApp/iosApp/IslandEdgeGlowView.swift`、`iosApp/iosAppTests/ChatIslandPresentationTests.swift`
- 修改：`iosApp/iosApp/ChatActivityIslandView.swift`、`iosApp/iosApp/ChatView.swift`、`iosApp/iosAppTests/IOSSettingsWiringTests.swift`
- S3 追加：`iosApp/ActivityWidget/AmberAgentActivityWidget.swift`、activity presentation 文案所在文件
- 不动：`ThinkingOrbEngine.swift`、`ThinkingOrbView.swift`（`speed`/`paused` 参数已存在）、vendor

## 1. 新组件契约（先定 API 再动工）

### 1.1 `IslandEdgeGlowView`（新文件，UIKit CADisplayLink 路径）

```swift
struct IslandEdgeGlowView: UIViewRepresentable {
    let spec: IslandGlowSpec            // 由静态工厂构造（见下）
    var opacity: Double                 // settle/terminal 淡入淡出由外层驱动
}

struct IslandGlowSpec: Equatable {      // 纯值：stops + rotationPeriod + breathing
    static func spectral(rotationPeriod:breathing:) -> IslandGlowSpec  // AI 三态，光谱对流
    static func hue(hex:) -> IslandGlowSpec        // 工具语义色单 hue，常亮不转
    static func terminal(hex:) -> IslandGlowSpec   // 绿/红静态边光，永不旋转
}
```

实现要点（以实现为准，本节为契约意图）：

- 内部 `IslandGlowCanvasView: UIView`：`CADisplayLink` + weak proxy（仿 `OrbCanvasView`），`preferredFrameRateRange 24...30`，window/isHidden/alpha 暂停门控；`accessibilityReduceMotion` → 静态 40% 透明度 ring，displayLink 不启动。
- 绘制：预渲染一张 2x conic 渐变图（按 stops 缓存）；每帧对衰减描边阶梯（1/2.5/4/7/11pt，alpha 递减代替逐帧模糊）逐一 clip 胶囊描边 → rotate CTM → drawImage。无逐帧模糊、无逐帧堆分配。
- 时钟：`CACurrentMediaTime()`，与 `OrbCanvasView` 同源；相位偏移常量对齐 orb yaw 基准，注释写明「光源一致性」意图。
- 纯值解析抽成 `IslandGlowSpec.resolve(style:) -> (stops, blurRadii, strokeWidths)`，供单元测试不实例化 UIView。

### 1.2 `IslandTitleGlint`（SwiftUI modifier，`ChatActivityIslandView.swift` 内 private）

- `LinearGradient(clear → white 0.55 → clear)` 30% 宽高光带，overlay 于标题 Text 并以同一 Text 作 mask；`.phaseAnimator` 两相位 + `.linear(duration: 2.4)`。
- 挂载条件：`isActive && orbState ∈ {waiting, thinking, generating} && !reduceMotion`；终态立即摘除，不回扫。
- 降级预案：若 Time Profiler 显示 ViewGraph 被 pin，改 30fps `TimelineView` 手动相位（实现时以 profile 证据二选一，不留双实现）。

### 1.3 `ChatIslandPresentation`（纯函数 reducer，放 `ChatActivityIslandView.swift` 同文件）

```swift
enum ChatIslandPresentation: Equatable {
    case idle(ChatActivityIslandState)                 // 标题态
    case active(ChatActivityIslandState)
    case settling(ChatActivityIslandState, until: Double)      // 0.4s：orb speed 1→0，辉光熄暗
    case terminalHold(ChatActivityIslandState, until: Double)  // 2.0s：红/绿静态边光
}

enum ChatIslandPresentationReducer {
    static func reduce(
        prev: ChatIslandPresentation, next: ChatActivityIslandState,
        now: Double, reduceMotion: Bool
    ) -> ChatIslandPresentation
}
```

规则：active→idle 且原态有 orb → settling 0.4s；工具失败/生成失败 → terminalHold 2s（红）；settle/hold 期间出现新 active → 立即切换不打断；reduceMotion → 无 settle 直落 idle；idle→active 无过渡态。ChatView 以 `@State` + `onChange(of: topIslandState)` 驱动；settle 期间 orb 冻结静帧 + 全岛 0.4s 淡出（实现时发现连续 speed 插值需要额外计时器，冻结+淡出以更小机制达到同等「安静退场」）。

### 1.4 orb 六态映射（`ChatActivityIslandView.orbState` 提取为 static pure）

```swift
static func islandOrbState(kind: Kind, systemImage: String) -> OrbState?
```

- waiting→`.listening`，thinking→`.working`，generating→`.composing`（现状保留）
- tool：`magnifyingglass`/`globe*`→`.searching`；`photo.on.rectangle`→`.shaping`；其余→`.solving`
- image→`.shaping`；title→nil

## 2. S1（壳+核）：辉光、morph、六态、settle

| # | 任务 | 验证 |
| --- | --- | --- |
| T1.1 | 红测试：`IslandGlowSpec.resolve` 三样式 stops/半径/线宽；转速表（8/14/20s）；terminal 不转 | 新 `ChatIslandPresentationTests.swift`（或同名 spec 测试），先红 |
| T1.2 | 实现 `IslandEdgeGlowView` + canvas（§1.1） | spec 测试转绿；`swiftc -typecheck` |
| T1.3 | 红测试：`islandOrbState` 六态映射 + 未知工具→`.solving` | 先红 |
| T1.4 | 接线新映射；退役 `ChatActivityIslandGlyph` 的 SF Symbol 底圈 | 转绿 |
| T1.5 | 红测试：reducer 5 条（settle 0.4 / terminalHold 2 / 新 active 打断 / reduceMotion 直落 / idle→active 无过渡） | 先红 |
| T1.6 | ChatView 接 `islandPresentation`；`topIslandState` 语义不变 | 转绿 |
| T1.7 | morph 过渡替换 flip；删除 `ChatActivityIslandHalo`、`ChatActivityIslandSoftField`、`chatIslandFlip`。删除为独立可审 diff（用户授权 commit 时单列） | 尺寸 canary 复跑 |
| T1.8 | glow 接线：underlay 于玻璃背后；AI 态 `.spectral`、工具 `.hue(tint)`、终态 `.terminal`；settle 熄暗 0.6s | 模拟器目测六态 |
| T1.9 | 核对两条 wiring canary 仍绿（glass 行保留）；`xcodegen` 重跑收录新文件 | 门禁 |

S1 门禁：`ThinkingOrbEngineTests` + 新测试 + `IOSSettingsWiringTests` + 强制 `ChatStreamReplayTests`，iPhone 17 Pro / iOS 26.5 模拟器全绿；`git diff --check`。

S1 验收对照设计 §8：第 1 条（几何连续 morph）、第 3 条（测试绿）。

## 3. S2（文）：glint 与文案表

| # | 任务 | 验证 |
| --- | --- | --- |
| T2.1 | `compactIslandText` 词边界截断（标点/空格优先，不切半词）——纯函数红→绿 | 定点测试 |
| T2.2 | 文案表落地：等待连接 detail = `viewModel.currentModel?.displayName`（已确认存在，取不到则 nil）；识别图片 detail = 「第 n 张，共 m 张」（沿附件管线找计数，取不到则 nil，不造数） | 定点测试 |
| T2.3 | `IslandTitleGlint` 实现 + 接线（§1.2 挂载条件） | 模拟器目测 + profile 证据 |
| T2.4 | 工具失败 detail = 「未完成」，走 terminalHold 红色 2s | reducer 测试补一条 |

S2 门禁：同 S1 + glint 相关定点。验收对照 §8 第 2、5 条。

## 4. S3（岛）：Live Activity 对齐

| # | 任务 | 验证 |
| --- | --- | --- |
| T3.1 | Live Activity center/bottom 文案与 in-app 表对齐（`AgentActivityPresentation` copy 逐条核对） | Widget 编译 + 预览 |
| T3.2 | compact glyph orb 静帧：引擎离线渲染 3 帧/态并缓存，`TimelineView` ≈1.5s 步进轮换；真机验证更新频率与电量，不达标退静态 glyph + keylineTint（在汇报中如实记录取舍） | 真机 Live Activity |
| T3.3 | 可选：`.awaitingUser` 态（审批/ask_user 时 orb `paused` + amber 静态边光；`.large` 主角时刻仅此一处）。依赖 `ChatGenerationCoordinator` pending 信号接入 `topIslandState`，独立切片，不阻塞 S3.1/3.2 | 定点 + 真机 |

验收对照 §8 第 4 条（真机留证）。

## 5. 验证矩阵

| 层 | 内容 |
| --- | --- |
| 模拟器门禁 | 每切片：新定点测试 + `IOSSettingsWiringTests` + 强制 `ChatStreamReplayTests`；S1 末加跑 `ChatSwiftUIStreamReplayTests`、`ChatMessageProjectionTests` |
| 命令模板 | `xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -parallel-testing-enabled NO -only-testing:<Suite> test` |
| 性能 | 岛 body 求值不随 token 增长（`animationKey` 等值门控已存在，改动不破坏）；orb+glow 双 displayLink 并存时 Time Profiler 采样一次留证；超标则合并为单 canvas 双图层（降级方案） |
| 真机（S3 末） | 六态切换、连续 3 分钟流式、Reduce Motion、深色模式、Live Activity；装机前按 `iosApp/AGENTS.md` 汇报变量/预期改善/预期不变/残余 |

## 6. 风险与回退

- **glint pin ViewGraph** → 降级 30fps TimelineView（§1.2）。
- **双 CADisplayLink CPU** → 合并 orb+glow 为单 canvas 两图层。
- **conic 预渲染图与 tint 不一致** → hue 样式 stops 由 `IslandGlowSpec` 从 tint 派生，测试锁定。
- **Widget 帧轮换被系统限频/耗电** → 退静态 glyph，设计语言仍由 keylineTint 与文案对齐承担。
- **删除件（halo/SoftField/flip/glyph）** → 各自独立 diff，任一可单独回退，不与新行为混合。

## 7. 完成定义（DoD）

1. 设计 §8 五条验收逐条过。
2. 三条既有 canary 与新定点全绿；强制 replay 门禁全绿；`git diff --check` 干净。
3. 真机与外部证据缺口如实写入 `docs/PROJECT_STATE.md`（原地更新，不新建 handoff）。
4. 汇报保持简短：改了什么、行为变化、验证结果、残余风险。
