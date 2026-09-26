# Session 首页光影细节精修计划（2026-09-26）

## 目标与边界

- 首页（`app/src/main/java/app/amber/feature/ui/pages/sessionhome/SessionHomePage.kt`）的**布局、配色、结构、尺寸、交互路径全部不变**，与 iOS 版保持现有一致。
- 只补"光影细节"：高光勾边、分层投影、按压光效、（可选硬件上的）HDR 按压提亮。
- 不复刻 Liquid Glass，不引入模糊玻璃材质，不改 Material/石墨设计方向。
- 精准实现：不加设置开关、不加多余抽象、不做过度兜底。只在首页落地；ds 原语保持小而可复用。
- 工作区有大量用户未提交改动：只改本计划涉及的文件，禁止 git commit / stash / reset / checkout。

## 构建与验证

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --offline :app:compileDebugKotlin
./gradlew --offline :app:installDebug   # 真机 PMA110（API 37，HDR 屏 2000nit）已连接
```

截图：`adb exec-out screencap -p > /tmp/x.png`；首页为 app 启动后的默认页（`app.amber.agent`）。
已知与本任务无关的失败单测见 memory（KoinGraphVerifyTest 等），不要归因到本次改动。

## Phase 1 — ds 原语 + 顶栏

新增 `app/src/main/java/app/amber/feature/ui/components/ds/AmberDepth.kt`：

1. `Modifier.amberRim(shape, style)`：`drawWithCache` 画
   - 高光勾边：竖向渐变描边（顶亮→底淡）。浅色约 白 60%→0%，深色约 白 12%→2%。线宽 ~0.75–1dp（按像素对齐，避免发糊）。
   - 底部暗边：描边下半段叠极淡暗色（厚度感）。
   - 表面渐变（可选参数）：1–2% 上亮下暗，叠在已有背景之上。
2. `Modifier.amberShadow(shape, style)`：双层投影（紧贴的接触阴影 + 大半径低透明环境阴影），基于已在用的 `Modifier.dropShadow(shape, Shadow)`；支持传入着色（accent 控件用 accent 色投影）。
3. `Modifier.amberPressHighlight(interactionSource, shape)`：
   - 取 `PressInteraction.Press.pressPosition` 为中心画径向提亮（`BlendMode.Plus` 或等效），同时勾边增亮；
   - 轻微弹簧缩放（~0.97）；松手光渐隐（非瞬断）；
   - 替代首页现有 ripple（包括 `ripple(bounded = false)` 越界问题）。
4. 预设 `AmberDepthStyle`：`Chip` / `Card` / `Accent` 三档，浅深色参数分别调，读取 `LocalAmberTokens`。

落地到顶栏：搜索胶囊、设置齿轮（替换 `border(1.dp, tokens.line)` 平边 + ripple），头像加外圈细高光环 + 按压光效。尺寸与位置不变。

## Phase 2 — 功能卡片 + 继续卡片 + 会话列表

- 功能入口卡片（`HomeFeatureRail`）：单层 `shadow(7.dp)` 换成双层投影，加高光勾边；卡片内五个入口 ripple 换按压光效。
- 继续卡片：图标块、「继续」按钮顶部内侧高光 + 按压光效；整行点击保持现状。
- 会话列表（`HomeSessionRow`，每行是独立 lazy item，首行上圆角、末行下圆角）：与功能卡片同一套勾边与投影，整组视觉上是一张卡片——行与行之间不能出现投影接缝、勾边断线或重复描边；勾边的侧边在行间连续，顶部高光只在首行、底部暗边只在末行。每行按压光效替代 ripple。SwipeToDismissBox 滑动时的背景层与勾边/投影不能错位。

## Phase 3 — 新对话按钮 + 全页收尾

- FAB：accent 底改上亮下暗渐变、顶部高光勾边、accent 着色双层投影、按压光效（此处光效最明显）。去掉 `ripple(bounded = false)`。
- 全页检查：首页不再残留默认 ripple；所有新效果浅色/深色两套参数到位。

## Phase 4 — HDR 按压提亮（API 34+ 且屏幕支持 HDR）

- 仅当 `Build.VERSION.SDK_INT >= 34` 且当前 Display 支持 HDR 时启用；其他情况无任何行为变化。
- 按压期间让首页所在窗口进入 HDR 色彩模式并请求适度 headroom（API 35+ `setDesiredHdrHeadroom`），按压高光用扩展范围颜色绘制，使高光真实地比 SDR 白更亮；松手后恢复 headroom，离开首页恢复原 colorMode。
- 不加设置开关；不在无 HDR 屏的设备上做任何尝试。切换不能导致可见闪屏；若真机验证发现闪屏，改为首页可见期间常驻 HDR 模式、仅动态调 headroom。
- 技术要点：
  - 能力判定：`display.isHdrSdrRatioAvailable`（API 34）或 `display.hdrCapabilities` 非空；Activity 用 `LocalActivity`/`LocalContext` 取得，首页离开（DisposableEffect onDispose）时恢复原 `window.colorMode` 与 headroom。
  - 扩展范围颜色：用 `Color(r, g, b, a, ColorSpaces.ExtendedSrgb)`（分量 > 1f）或 LinearExtendedSrgb。**必须确认 Compose 的 `Brush.radialGradient` 是否把颜色压成 Int ARGB 而截断扩展范围**；若会截断，改用 `android.graphics.RadialGradient` 的 `LongArray` 颜色构造（API 29+）包成 `ShaderBrush`。
  - 只让 FAB（Accent 档）与顶栏 Chip 的按压高光走 HDR；卡片内格子保持 SDR（大面积 HDR 提亮刺眼）。
  - 只有 amberPressHighlight 需要知道"当前是否 HDR 可用"：用一个 CompositionLocal 或参数从首页注入，避免 ds 原语直接依赖 Activity。

## Phase 5 — 真机终验

- 真机浅色 + 深色各截：首页顶部、滚动后、按压 FAB/会话行状态。
- 核对：对齐、边距、尺寸与改动前一致；勾边像素对齐无发糊；投影无接缝、无裁切（注意父级 padding/clip 对 dropShadow 的裁切）；顶部 haze 渐隐与新投影不打架。

## 每个 Phase 的流程

1. Codex（gpt-6-sol）实现线程：按本计划实现该 phase，`:app:compileDebugKotlin` 通过，装到真机截图自查。
2. Codex 独立 review 线程：逻辑闭环、调用链路完整（修饰符是否真的挂到了目标控件、interactionSource 是否与 clickable 共用）、UI 细节（错位、对齐、边距、大小、裁切、深浅色）。只报真实问题。
3. 实现线程精准修复 review 中确认的问题，不做额外扩展。
