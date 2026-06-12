# 伴随智能二期（悬浮气泡）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给伴随智能加悬浮气泡形态——静默点/分析中/有结果三态，可拖动贴边，点开精简卡片（结论/要点/草稿 + 填入/立即分析），长按退出伴随（设计稿 §5 二期，spec：`docs/superpowers/specs/2026-06-10-live-companion-redesign-design.md`）。

**Architecture:** 气泡窗口挂在 `AmberAccessibilityService` 上（`TYPE_ACCESSIBILITY_OVERLAY`），由 `LiveModeManager.runLoop` 每 tick 同步可见性（active && bubbleEnabled && 服务在 && 前台不是 Amber 自己）。`LiveBubbleWindow` 负责窗口/生命周期/拖动贴边；`LiveBubbleContent` 是纯 Compose 内容；Manager 仍是唯一编排者，不引入新 DI。

**Tech Stack:** WindowManager + TYPE_ACCESSIBILITY_OVERLAY / ComposeView（自持 LifecycleOwner+SavedStateRegistryOwner）/ 现有 LiveModeManager·LiveModeUiState。

**对设计稿的记录在案偏差：**
1. **不需要悬浮窗权限**（spec 写"需 SYSTEM_ALERT_WINDOW"）——伴随强制开无障碍服务，无障碍服务可挂 `TYPE_ACCESSIBILITY_OVERLAY`，零新增权限、服务断开时系统自动回收窗口。FloatingX（项目里给 TTS 用的）不用于气泡：其 system-scope 需要悬浮窗权限且 Compose 生命周期借宿主 Activity，后台会冻结。
2. **不做历史页**（用户 2026-06-11 决策）：现有页面保持"最后一张卡片 + 设置"，不加持久化。
3. **精简交互**（用户决策）：展开卡片只有 填入 / 立即分析 两个动作 + 长按退出；指令输入/语音/模式切换仍在页面。

**用户已确认的范围：** 不做历史；精简版气泡交互。

**构建命令约定**（同一期）：编译 `./gradlew :app:compileGraphiteKotlin`；装机 `./gradlew :app:installGraphite`；api 测试 `./gradlew :feature:live:api:test`。
⚠️ worktree 仍有 5 个未提交文件属于另一任务（AmberTokens/ChatTheme/ChatMessageTools/ChatDrawer/ConversationList）——commit 永远只 add 本计划文件。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt` | 改 | `LiveModeSetting` + `bubbleEnabled: Boolean = true` |
| `app/src/main/java/app/amber/feature/live/bubble/LiveBubbleWindow.kt` | 新 | 无障碍 overlay 窗口宿主：生命周期、add/remove、拖动、贴边、夹紧 |
| `app/src/main/java/app/amber/feature/live/bubble/LiveBubbleContent.kt` | 新 | 气泡 Compose 内容：三态点 + 展开卡片 + 动作 |
| `app/src/main/java/app/amber/feature/live/LiveModeManager.kt` | 改 | 持有 bubble，runLoop 同步可见性，stop 收回 |
| `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt` | 改 | `setBubbleEnabled` |
| `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt` | 改 | 状态面板第三个开关"气泡" |

---

### Task 1: 设置字段 + VM + 页面开关

**Files:**
- Modify: `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt`
- Modify: `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt`
- Modify: `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt`

- [ ] **Step 1:** `LiveModeSetting` 追加字段（保持默认值兼容旧配置）：

```kotlin
    /** 伴随期间显示悬浮气泡（无障碍 overlay，零额外权限） */
    val bubbleEnabled: Boolean = true,
```

- [ ] **Step 2:** VM 仿照 `setAutoRefresh` 加：

```kotlin
    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(bubbleEnabled = enabled)
                    )
                )
            }
        }
    }
```

- [ ] **Step 3:** `LiveStatusPanel` 加参数 `bubbleEnabled: Boolean, onToggleBubble: (Boolean) -> Unit`，在"激进 · 截屏"TextButton 后面同一 Row 内追加第三个：

```kotlin
                    TextButton(
                        onClick = { onToggleBubble(!bubbleEnabled) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (bubbleEnabled) "气泡：开" else "气泡：关",
                            style = LocalAmberType.current.secondary,
                        )
                    }
```

调用处补传 `bubbleEnabled = liveSetting.bubbleEnabled, onToggleBubble = vm::setBubbleEnabled,`。

- [ ] **Step 4:** `./gradlew :app:compileGraphiteKotlin` → BUILD SUCCESSFUL；`./gradlew :feature:live:api:test` → 11 pass

- [ ] **Step 5: Commit**

```bash
git add feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt
git commit -m "feat(live): bubbleEnabled 设置 + 页面气泡开关"
```

---

### Task 2: LiveBubbleWindow — overlay 窗口宿主

**Files:**
- Create: `app/src/main/java/app/amber/feature/live/bubble/LiveBubbleWindow.kt`

- [ ] **Step 1: 实现**（完整代码；仅主线程调用，调用方是 runLoop/Main.immediate）：

```kotlin
package app.amber.feature.live.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.amber.core.automation.AmberAccessibilityService
import kotlin.math.roundToInt

/**
 * 伴随气泡的悬浮窗宿主：TYPE_ACCESSIBILITY_OVERLAY 挂在无障碍服务上，
 * 不需要 SYSTEM_ALERT_WINDOW 权限；服务断开时系统自动移除窗口。
 * 自持 Lifecycle/SavedState owner——不能借 Activity 的（后台 STOPPED 会冻结重组）。
 * 所有方法仅主线程调用（LiveModeManager.runLoop 在 Main.immediate）。
 */
class LiveBubbleWindow {
    private var host: ComposeView? = null
    private var hostService: AmberAccessibilityService? = null
    private var owner: BubbleLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = host != null

    /** 已显示且宿主服务相同则幂等；服务实例换了（重连）则先收回再重挂。 */
    fun show(service: AmberAccessibilityService, content: @Composable () -> Unit) {
        if (host != null && hostService === service) return
        hide()
        val newOwner = BubbleLifecycleOwner().apply { create(); resume() }
        val view = ComposeView(service).apply {
            setViewTreeLifecycleOwner(newOwner)
            setViewTreeSavedStateRegistryOwner(newOwner)
            setContent(content)
        }
        val dm = service.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - (64 * dm.density).roundToInt() // 初始贴右
            y = dm.heightPixels / 3
        }
        val added = runCatching { windowManager(service).addView(view, lp) }.isSuccess
        if (!added) {
            newOwner.destroy()
            return
        }
        host = view
        hostService = service
        owner = newOwner
        params = lp
    }

    fun hide() {
        val view = host ?: return
        runCatching { hostService?.let { windowManager(it).removeViewImmediate(view) } }
        owner?.destroy()
        host = null
        hostService = null
        owner = null
        params = null
    }

    /** 拖动中：位移叠加、夹回屏内、立即应用。 */
    fun moveBy(dx: Float, dy: Float) {
        val view = host ?: return
        val lp = params ?: return
        val service = hostService ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        clamp(service, lp, view.width, view.height)
        runCatching { windowManager(service).updateViewLayout(view, lp) }
    }

    /** 松手贴边（左右取近）。 */
    fun snapToEdge() {
        val view = host ?: return
        val lp = params ?: return
        val service = hostService ?: return
        val screenW = service.resources.displayMetrics.widthPixels
        lp.x = if (lp.x + view.width / 2 < screenW / 2) 0 else (screenW - view.width).coerceAtLeast(0)
        runCatching { windowManager(service).updateViewLayout(view, lp) }
    }

    /** 内容尺寸变化（展开/收起卡片）后调用：等重新布局完把窗口夹回屏内。 */
    fun requestReclamp() {
        val view = host ?: return
        view.post {
            val lp = params ?: return@post
            val service = hostService ?: return@post
            clamp(service, lp, view.width, view.height)
            runCatching { windowManager(service).updateViewLayout(view, lp) }
        }
    }

    private fun clamp(context: Context, lp: WindowManager.LayoutParams, w: Int, h: Int) {
        val dm = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
    }

    private fun windowManager(context: Context): WindowManager =
        context.getSystemService(WindowManager::class.java)

    /** 常驻 RESUMED 的窗口生命周期——Compose 重组不受宿主 Activity 后台影响。 */
    private class BubbleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
```

执行注意：`setContent(content)` 形参类型是 `@Composable () -> Unit`——直接传即可。若 `androidx.savedstate` 的 setter 名在当前版本不同（如 `setViewTreeSavedStateRegistryOwner` 不在该包），参照 `app/src/main/java/app/amber/feature/ui/components/ui/FloatingWindow.kt` 第 16-20 行的现成 import 路径。

- [ ] **Step 2:** `./gradlew :app:compileGraphiteKotlin` → BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/bubble/LiveBubbleWindow.kt
git commit -m "feat(live): LiveBubbleWindow — 无障碍 overlay 气泡窗口宿主（零悬浮窗权限）"
```

---

### Task 3: LiveBubbleContent — 三态点 + 精简卡片

**Files:**
- Create: `app/src/main/java/app/amber/feature/live/bubble/LiveBubbleContent.kt`

- [ ] **Step 1: 实现**：

```kotlin
package app.amber.feature.live.bubble

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * 气泡内容（精简版交互，用户 2026-06-11 决策）：
 * 收起 = 44dp 三态点（待命灰 / 分析中呼吸 / 有新结果亮强调色）；
 * 点开 = 280dp 卡片（结论 + ≤3 要点 + 草稿 + 填入/立即分析）；长按点 = 退出伴随。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveBubbleContent(
    state: LiveModeUiState,
    onFillDraft: () -> LiveFillResult,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSizeChanged: () -> Unit,
)
 {
    val tokens = LocalAmberTokens.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var lastSeenMillis by remember { mutableLongStateOf(0L) }
    val hasFreshResult = state.card != null && state.lastUpdatedAtMillis > lastSeenMillis

    if (!expanded) {
        // ── 收起态：三态点 ──
        val pulse = rememberInfiniteTransition(label = "bubblePulse")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "bubblePulseAlpha",
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tokens.surface)
                .border(1.dp, tokens.line2, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, delta ->
                            change.consume()
                            onDrag(delta.x, delta.y)
                        },
                        onDragEnd = { onDragEnd() },
                    )
                }
                .combinedClickable(
                    onClick = {
                        expanded = true
                        lastSeenMillis = state.lastUpdatedAtMillis
                        onSizeChanged()
                    },
                    onLongClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { alpha = if (state.analyzing) pulseAlpha else 1f }
                    .clip(CircleShape)
                    .background(
                        when {
                            state.analyzing -> tokens.accent
                            hasFreshResult -> tokens.accent
                            state.paused -> tokens.ink4
                            else -> tokens.ink3
                        }
                    ),
            )
        }
    } else {
        // ── 展开态：精简卡片 ──
        val card = state.card
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.surface)
                .border(1.dp, tokens.line2, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            expanded = false
                            onSizeChanged()
                        },
                        onLongClick = onStop,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.analyzing) tokens.accent else tokens.ink3),
                )
                Text(
                    text = state.statusText.ifBlank { "Amber 伴随" },
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "收起",
                    style = LocalAmberType.current.meta,
                    color = tokens.ink4,
                )
            }

            if (card == null) {
                Text(
                    text = "还没有分析结果",
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            } else {
                Text(
                    text = card.watching.ifBlank { "不确定" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.ink,
                )
                card.keyPoints.take(3).forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "·", color = tokens.ink3, fontSize = 13.sp)
                        Text(text = point, fontSize = 13.sp, color = tokens.ink2)
                    }
                }
                val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
                if (draft != null && state.completedAction == "写回复") {
                    Text(
                        text = draft,
                        fontSize = 13.sp,
                        color = tokens.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.surface2)
                            .padding(8.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val draftAvailable = card != null &&
                    (card.suggestions.firstOrNull()?.isNotBlank() == true || card.watching.isNotBlank())
                if (draftAvailable) {
                    TextButton(onClick = {
                        val message = when (onFillDraft()) {
                            LiveFillResult.FILLED -> "已填入，发送请自己按"
                            LiveFillResult.COPIED -> "没找到输入框，已复制"
                            LiveFillResult.NO_DRAFT -> "还没有可填入的草稿"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }) {
                        Text("填入", fontSize = 13.sp, color = tokens.accent)
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onRefresh) {
                    Text("立即分析", fontSize = 13.sp, color = tokens.accent)
                }
            }
        }
    }
}
```

- [ ] **Step 2:** `./gradlew :app:compileGraphiteKotlin` → BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/bubble/LiveBubbleContent.kt
git commit -m "feat(live): LiveBubbleContent — 三态气泡点 + 精简展开卡片"
```

---

### Task 4: Manager 接线 — 可见性同步 + 收回

**Files:**
- Modify: `app/src/main/java/app/amber/feature/live/LiveModeManager.kt`

- [ ] **Step 1:** 字段区加 `private val bubble = app.amber.feature.live.bubble.LiveBubbleWindow()`（或 import）。

- [ ] **Step 2:** 新增同步方法（companion 前）：

```kotlin
    /** 每个 runLoop tick 调一次：根据状态决定气泡显隐。仅主线程。 */
    private fun syncBubble(liveSetting: LiveModeSetting) {
        val service = AmberAccessibilityService.getActiveService()
        val shouldShow = liveSetting.enabled &&
            liveSetting.bubbleEnabled &&
            _state.value.active &&
            service != null &&
            service.activePackageName() != context.packageName
        if (!shouldShow) {
            bubble.hide()
            return
        }
        bubble.show(service!!) {
            app.amber.feature.ui.theme.AmberAgentTheme {
                val uiState = state.collectAsState().value
                app.amber.feature.live.bubble.LiveBubbleContent(
                    state = uiState,
                    onFillDraft = ::fillCurrentDraft,
                    onRefresh = ::refreshNow,
                    onStop = ::stop,
                    onDrag = bubble::moveBy,
                    onDragEnd = bubble::snapToEdge,
                    onSizeChanged = bubble::requestReclamp,
                )
            }
        }
    }
```

（`collectAsState` 需 `import androidx.compose.runtime.collectAsState`；imports 整理成常规形式，上面的全限定名仅为说明。）

- [ ] **Step 3:** `runLoop` 的 `while (true)` 每轮**读完 `liveSetting` 之后、所有分支判断之前**插入 `syncBubble(liveSetting)`（这样 enabled=false / paused / 无模型 / 无服务的分支也会先把气泡按状态收掉或保留）。`stop()` 里加 `bubble.hide()`。

- [ ] **Step 4:** `./gradlew :app:compileGraphiteKotlin` → BUILD SUCCESSFUL；`./gradlew :feature:live:api:test` → 11 pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/LiveModeManager.kt
git commit -m "feat(live): runLoop 同步悬浮气泡显隐 — 在别的 app 前台时展示，回 Amber 隐藏"
```

---

### Task 5: 端到端验证

- [ ] **Step 1:** `./gradlew :feature:live:api:test :app:compileGraphiteKotlin` 全绿
- [ ] **Step 2:** `./gradlew :app:installGraphite` 装机
- [ ] **Step 3: 手动清单**（需用户配合）：
  1. 开伴随 → 回桌面/进微信 → 右侧出现气泡点；回 Amber → 气泡消失
  2. 微信里收到分析 → 点亮强调色；点开 → 卡片显示结论/要点/草稿
  3. 卡片"填入" → 草稿进微信输入框；"立即分析" → 重新分析
  4. 拖动气泡 → 跟手，松手贴边
  5. 长按气泡 → 伴随退出、气泡消失
  6. 页面"气泡：关" → 气泡立刻消失（下一 tick 内）
- [ ] **Step 4:** 最终整体代码审查（跨提交集成视角）后回报用户，不 push。

---

## Self-Review 记录

- **Spec 覆盖**：三态点 ✓(T3)；点开卡片 ✓(T3)；可拖动贴边 ✓(T2/T3)；权限 ✓（偏差 1：a11y overlay 免权限）；页面降级 ✓（偏差 2：保持现状不做历史）。
- **占位符**：无 TBD；T2 给出 savedstate import 兜底参照（FloatingWindow.kt:16-20）。
- **类型一致性**：`LiveFillResult` 三态与一期一致；`onSizeChanged`/`requestReclamp`、`onDrag(dx,dy)`/`moveBy(dx,dy)`、`onDragEnd`/`snapToEdge` 一一对应；`syncBubble(liveSetting: LiveModeSetting)` 与 runLoop 现有局部变量同型。
- **生命周期**：show 幂等 + 服务实例变更重挂；stop()/服务断开双保险收回；BubbleLifecycleOwner 常驻 RESUMED 不随 Activity 后台冻结。
