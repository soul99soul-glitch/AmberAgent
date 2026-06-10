# 伴随智能一期（引擎重构）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把伴随智能从"固定轮询读 UI 树"重构为"事件驱动 + 保守/激进双模式 + 场景画像 + 只填不发闭环"的引擎（设计稿：`docs/superpowers/specs/2026-06-10-live-companion-redesign-design.md`）。

**Architecture:** 纯决策逻辑（触发/去重/冷却/退避）下沉到 `feature/live/api` 模块的 `LiveEngine`（可注入时钟、纯 JVM 单测）；场景分类 `LiveScenes` 同模块；app 模块里 `LiveModeManager` 改为事件驱动编排器，无障碍事件经 SharedFlow 进入；模型调用与 prompt 迁出到 `LiveAnalyzer`（含激进模式截图喂视觉模型）；填入动作复用已有 `AmberAccessibilityService.setFocusedText`。

**Tech Stack:** Kotlin / Compose / Koin / kotlinx.serialization / AccessibilityService（事件 + takeScreenshot API 30+）/ JUnit4。

**两处对设计稿的有意收敛**（已在计划层确认）：
1. 去重签名两种模式都用 UI 树 `stableHash`，不做截图 dHash —— 截图只在分析时才拍，若用 dHash 去重就得每个事件都截屏，费电且违背"静默"原则。UI 树 hash 已包含正文文本，足够判断"屏幕变没变"。
2. 伴随模型选择 UI 复用现成 `ModelSelector`（components/ai/ModelList.kt，支持 allowClear/emptyLabel），不新做选择器。

**构建/测试命令约定：**
- 纯逻辑测试：`./gradlew :feature:live:api:test`
- app 编译验证：`./gradlew :app:compileGraphiteKotlin`（本机已验证可用；不要用 `compileDebugKotlin`，debug 变体在本 worktree 会因 google-services 跳过逻辑多绕路）
- 装机：`./gradlew :app:installGraphite`
- ⚠️ 本 worktree 当前有 5 个未提交的 UI 改动文件（AmberTokens/ChatTheme/ChatMessageTools/ChatDrawer/ConversationList），属于另一任务，**commit 时必须只 add 本计划涉及的文件**。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt` | 改 | +`LiveAnalysisMode` 枚举；`LiveModeSetting` +`analysisMode`/`companionModelId` |
| `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveScenes.kt` | 新 | 包名→场景分类 + 场景默认动作 |
| `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveEngine.kt` | 新 | 触发/去重/冷却/退避决策状态机（纯逻辑） |
| `feature/live/api/build.gradle.kts` | 改 | + junit 测试依赖 |
| `feature/live/api/src/test/kotlin/app/amber/feature/live/LiveScenesTest.kt` | 新 | |
| `feature/live/api/src/test/kotlin/app/amber/feature/live/LiveEngineTest.kt` | 新 | |
| `app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt` | 改 | 屏幕事件 SharedFlow + `takeScreenshotBitmap()` |
| `app/src/main/java/app/amber/feature/live/LiveScreenshotter.kt` | 新 | 截屏→缩放 1280→JPEG80→file:// URI |
| `app/src/main/java/app/amber/feature/live/LiveAnalyzer.kt` | 新 | LivePrompt 迁入 + 伴随模型解析 + 视觉/降级分析 |
| `app/src/main/java/app/amber/feature/live/LiveModeManager.kt` | 重写 | 事件驱动编排 + fillCurrentDraft |
| `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt` | 改 | setAnalysisMode / setCompanionModel / fillDraft |
| `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt` | 改 | 模式切换 + 伴随模型行 + 填入按钮 |

DI 不变：`AgentInfraModule.kt:48` 的 `single { LiveModeManager(get(), get(), get(), get()) }` 构造参数不变（Analyzer/Screenshotter 由 Manager 内部 new，不进容器——它们无独立生命周期）。

---

### Task 1: api 模块设置字段 + 测试设施

**Files:**
- Modify: `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt`
- Modify: `feature/live/api/build.gradle.kts`

- [ ] **Step 1: 给 LiveModeSetting 加双模式与伴随模型字段**

`LiveModeModels.kt` 中 `LiveModeSetting` 上方加枚举，data class 加两个带默认值的字段（默认值保证老配置反序列化兼容）：

```kotlin
@Serializable
enum class LiveAnalysisMode {
    /** 保守：只读无障碍 UI 树文字 */
    CONSERVATIVE,

    /** 激进：截屏喂视觉模型，UI 树作辅助 */
    AGGRESSIVE,
}

@Serializable
data class LiveModeSetting(
    val enabled: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshIntervalMs: Long = 1_500L,
    val stableDelayMs: Long = 1_500L,
    val minAnalysisIntervalMs: Long = 10_000L,
    val maxNodes: Int = 180,
    val voiceInputEnabled: Boolean = true,
    val analysisMode: LiveAnalysisMode = LiveAnalysisMode.CONSERVATIVE,
    /** 伴随模型 Uuid 字符串；null = 跟随当前聊天模型 */
    val companionModelId: String? = null,
)
```

- [ ] **Step 2: api 模块加 junit**

`feature/live/api/build.gradle.kts` dependencies 块改为：

```kotlin
dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :feature:live:api:compileKotlin :app:compileGraphiteKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add feature/live/api/src/main/kotlin/app/amber/feature/live/LiveModeModels.kt feature/live/api/build.gradle.kts
git commit -m "feat(live): LiveModeSetting 增加保守/激进模式与伴随模型字段"
```

---

### Task 2: LiveScenes 场景画像（TDD）

**Files:**
- Create: `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveScenes.kt`
- Test: `feature/live/api/src/test/kotlin/app/amber/feature/live/LiveScenesTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveScenesTest {
    @Test
    fun `聊天类包名归为 CHAT`() {
        assertEquals(LiveScene.CHAT, LiveScenes.classify("com.tencent.mm"))
        assertEquals(LiveScene.CHAT, LiveScenes.classify("org.telegram.messenger"))
    }

    @Test
    fun `阅读类支持前缀匹配`() {
        assertEquals(LiveScene.READING, LiveScenes.classify("com.android.chrome"))
        assertEquals(LiveScene.READING, LiveScenes.classify("com.ss.android.article.news"))
    }

    @Test
    fun `未知包名归为 OTHER`() {
        assertEquals(LiveScene.OTHER, LiveScenes.classify("com.example.unknown"))
        assertEquals(LiveScene.OTHER, LiveScenes.classify(""))
    }

    @Test
    fun `场景默认动作映射`() {
        assertEquals("写回复", LiveScenes.defaultActionLabel(LiveScene.CHAT))
        assertEquals("找重点", LiveScenes.defaultActionLabel(LiveScene.READING))
        assertNull(LiveScenes.defaultActionLabel(LiveScene.OTHER))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :feature:live:api:test --tests "app.amber.feature.live.LiveScenesTest"`
Expected: FAIL（LiveScene/LiveScenes 未定义，编译错误即视为失败）

- [ ] **Step 3: 实现 LiveScenes.kt**

```kotlin
package app.amber.feature.live

/** 伴随场景：决定该屏默认做什么（CHAT→回复草稿，READING→提炼重点，OTHER→静默待命）。 */
enum class LiveScene { CHAT, READING, OTHER }

object LiveScenes {
    // 精确匹配的聊天类 app
    private val chatPackages = setOf(
        "com.tencent.mm",                // 微信
        "com.tencent.mobileqq",          // QQ
        "com.tencent.tim",
        "org.telegram.messenger",
        "org.thunderdog.challegram",
        "com.alibaba.android.rimet",     // 钉钉
        "com.ss.android.lark",           // 飞书
        "com.whatsapp",
        "jp.naver.line.android",
        "com.discord",
    )

    // 前缀匹配的阅读/资讯类（"com.android.chrome" 与 "com.android.chrome.beta" 都算）
    private val readingPrefixes = listOf(
        "com.android.chrome",
        "org.mozilla",
        "com.microsoft.emmx",
        "com.quark",
        "com.UCMobile",
        "com.tencent.mtt",               // QQ 浏览器
        "com.zhihu.android",
        "com.ss.android.article",        // 今日头条系
        "com.tencent.news",
        "com.netease.newsreader",
        "com.sina.weibo",
        "com.xingin.xhs",                // 小红书
        "com.coolapk.market",
    )

    fun classify(packageName: String): LiveScene = when {
        packageName in chatPackages -> LiveScene.CHAT
        readingPrefixes.any { packageName == it || packageName.startsWith("$it.") } -> LiveScene.READING
        else -> LiveScene.OTHER
    }

    /** 场景默认动作标签；null = 静默，等用户主动召唤。 */
    fun defaultActionLabel(scene: LiveScene): String? = when (scene) {
        LiveScene.CHAT -> "写回复"
        LiveScene.READING -> "找重点"
        LiveScene.OTHER -> null
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :feature:live:api:test --tests "app.amber.feature.live.LiveScenesTest"`
Expected: PASS（4 tests）

- [ ] **Step 5: Commit**

```bash
git add feature/live/api/src/main/kotlin/app/amber/feature/live/LiveScenes.kt feature/live/api/src/test/kotlin/app/amber/feature/live/LiveScenesTest.kt
git commit -m "feat(live): LiveScenes 场景画像 — 包名分类 + 场景默认动作"
```

---

### Task 3: LiveEngine 决策状态机（TDD）

**Files:**
- Create: `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveEngine.kt`
- Test: `feature/live/api/src/test/kotlin/app/amber/feature/live/LiveEngineTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEngineTest {
    private fun engine() = LiveEngine(
        stableDelayMs = 1_000L,
        minAnalysisIntervalMs = 10_000L,
        backoffMs = 30_000L,
    )

    @Test
    fun `没有屏幕签名时不分析`() {
        val e = engine()
        assertEquals(LiveEngine.Decision.Wait("no_screen"), e.decide(nowMillis = 0L))
    }

    @Test
    fun `新签名要等稳定延迟`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        assertEquals(LiveEngine.Decision.Wait("settling"), e.decide(nowMillis = 500L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 1_000L))
    }

    @Test
    fun `同签名重复上报不重置稳定计时`() {
        val e = engine()
        assertTrue(e.onScreenSignature("s1", nowMillis = 0L))
        assertFalse(e.onScreenSignature("s1", nowMillis = 900L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 1_000L))
    }

    @Test
    fun `已分析过的签名跳过`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 1_000L)
        e.onAnalysisSucceeded("s1")
        assertEquals(LiveEngine.Decision.Wait("unchanged"), e.decide(nowMillis = 20_000L))
    }

    @Test
    fun `冷却期内不分析`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 1_000L)
        e.onAnalysisSucceeded("s1")
        e.onScreenSignature("s2", nowMillis = 2_000L)
        assertEquals(LiveEngine.Decision.Wait("cooldown"), e.decide(nowMillis = 5_000L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 11_000L))
    }

    @Test
    fun `force 绕过冷却与去重但不绕过退避`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 100L)
        e.onAnalysisSucceeded("s1")
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 200L, force = true))
        e.onRetryableFailure(nowMillis = 300L)
        assertEquals(LiveEngine.Decision.Wait("backoff"), e.decide(nowMillis = 400L, force = true))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 30_301L, force = true))
    }

    @Test
    fun `成功清除退避`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onRetryableFailure(nowMillis = 100L)
        e.onAnalysisSucceeded("s1")
        assertEquals(0L, e.backoffUntilMillis())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :feature:live:api:test --tests "app.amber.feature.live.LiveEngineTest"`
Expected: FAIL（LiveEngine 未定义）

- [ ] **Step 3: 实现 LiveEngine.kt**

```kotlin
package app.amber.feature.live

/**
 * 伴随分析决策状态机（纯逻辑，时间由调用方注入）。
 * 收敛原 LiveModeManager 散落的 pendingHash/lastAnalyzedHash/backoff 字段与
 * LiveUiTreeProcessor.shouldAnalyze 的判断：
 *   屏幕签名变化 → 稳定延迟（防抖）→ 去重 → 冷却 → 分析；失败可退避。
 */
class LiveEngine(
    private val stableDelayMs: Long,
    private val minAnalysisIntervalMs: Long,
    private val backoffMs: Long = 30_000L,
) {
    sealed interface Decision {
        data object Analyze : Decision
        data class Wait(val reason: String) : Decision
    }

    var pendingSignature: String? = null
        private set
    private var pendingChangedAtMillis = 0L
    private var lastAnalyzedSignature: String? = null
    private var lastAnalysisAtMillis = Long.MIN_VALUE / 2
    private var backoffUntilMillis = 0L

    /** 上报当前屏幕签名。返回 true = 签名相对上次上报发生了变化。 */
    fun onScreenSignature(signature: String, nowMillis: Long): Boolean {
        if (signature == pendingSignature) return false
        pendingSignature = signature
        pendingChangedAtMillis = nowMillis
        return true
    }

    fun decide(nowMillis: Long, force: Boolean = false): Decision {
        val signature = pendingSignature ?: return Decision.Wait("no_screen")
        if (nowMillis < backoffUntilMillis) return Decision.Wait("backoff")
        if (force) return Decision.Analyze
        if (signature == lastAnalyzedSignature) return Decision.Wait("unchanged")
        if (nowMillis - pendingChangedAtMillis < stableDelayMs) return Decision.Wait("settling")
        if (nowMillis - lastAnalysisAtMillis < minAnalysisIntervalMs) return Decision.Wait("cooldown")
        return Decision.Analyze
    }

    fun onAnalysisStarted(nowMillis: Long) {
        lastAnalysisAtMillis = nowMillis
    }

    fun onAnalysisSucceeded(signature: String) {
        lastAnalyzedSignature = signature
        backoffUntilMillis = 0L
    }

    fun onRetryableFailure(nowMillis: Long) {
        backoffUntilMillis = nowMillis + backoffMs
    }

    fun backoffUntilMillis(): Long = backoffUntilMillis
}
```

注意 `Decision.Wait` 是 data class，测试里直接 `assertEquals(Wait("settling"), ...)` 比较。

- [ ] **Step 4: 跑全部 api 测试确认通过**

Run: `./gradlew :feature:live:api:test`
Expected: PASS（LiveScenesTest 4 + LiveEngineTest 7）

- [ ] **Step 5: Commit**

```bash
git add feature/live/api/src/main/kotlin/app/amber/feature/live/LiveEngine.kt feature/live/api/src/test/kotlin/app/amber/feature/live/LiveEngineTest.kt
git commit -m "feat(live): LiveEngine 决策状态机 — 防抖/去重/冷却/退避，纯逻辑可单测"
```

---

### Task 4: 无障碍服务 — 屏幕事件流 + 截屏

**Files:**
- Modify: `app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt`

服务 XML（`app/src/main/res/xml/amberagent_accessibility_service.xml`）已订阅 `typeWindowStateChanged|typeWindowContentChanged`，无需改动；只需把目前丢弃的 `onAccessibilityEvent` 接出来。

- [ ] **Step 1: 加事件流**

文件顶部补 import：

```kotlin
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

把第 18 行 `override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit` 替换为：

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val e = event ?: return
    when (e.eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        -> _screenEvents.tryEmit(
            ScreenEvent(
                packageName = e.packageName?.toString().orEmpty(),
                eventType = e.eventType,
                atMillis = System.currentTimeMillis(),
            )
        )
    }
}
```

companion object 内（`getActiveService()` 旁）加：

```kotlin
data class ScreenEvent(val packageName: String, val eventType: Int, val atMillis: Long)

private val _screenEvents = MutableSharedFlow<ScreenEvent>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)

/** 屏幕变化事件（窗口切换/内容变化），LiveModeManager 据此驱动分析。 */
val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()
```

（`ScreenEvent` 定义放 companion object 外、类内部即可；引用写 `AmberAccessibilityService.ScreenEvent`。）

- [ ] **Step 2: 加截屏方法**

类内（`setFocusedText` 附近）加：

```kotlin
/**
 * API 30+ 截当前屏为软件 Bitmap；低版本或失败返回 null（调用方降级保守模式）。
 */
suspend fun takeScreenshotBitmap(): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    cont.resume(null)
                }
            },
        )
    }
}
```

补 import：

```kotlin
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
```

（`TakeScreenshotCallback`/`ScreenshotResult` 是 `AccessibilityService` 嵌套类型，同类内直接可用。）

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileGraphiteKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt
git commit -m "feat(automation): 无障碍屏幕事件 SharedFlow + takeScreenshotBitmap (API 30+)"
```

---

### Task 5: LiveScreenshotter

**Files:**
- Create: `app/src/main/java/app/amber/feature/live/LiveScreenshotter.kt`

- [ ] **Step 1: 实现**

```kotlin
package app.amber.feature.live

import android.content.Context
import android.graphics.Bitmap
import app.amber.core.automation.AmberAccessibilityService
import java.io.File
import java.io.FileOutputStream

/**
 * 激进模式截屏：拍 → 缩放（长边 ≤1280）→ JPEG(80) → cache 文件。
 * 返回 file:// URI 字符串；ai 模块 FileEncoder.encodeBase64 对 file:// 有现成
 * 压缩编码支持，provider 侧无需任何改动。失败返回 null（调用方降级保守）。
 */
class LiveScreenshotter(private val context: Context) {

    suspend fun captureToFileUri(service: AmberAccessibilityService): String? {
        val raw = service.takeScreenshotBitmap() ?: return null
        val scaled = downscale(raw, MAX_LONG_EDGE)
        return runCatching {
            val dir = File(context.cacheDir, "live").apply { mkdirs() }
            val file = File(dir, "live_screenshot.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            file.toURI().toString() // -> "file:///data/user/0/.../cache/live/live_screenshot.jpg"
        }.getOrNull().also {
            if (scaled !== raw) raw.recycle()
        }
    }

    private fun downscale(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        private const val MAX_LONG_EDGE = 1280
        private const val JPEG_QUALITY = 80
    }
}
```

注意 `java.io.File.toURI()` 产出 `file:/...` 单斜杠形式——`FileEncoder` 用 `url.toUri().path` 解析，两种形式都能取到 path，无碍；如想稳妥可用 `"file://${file.absolutePath}"`，**计划采用后者**：

```kotlin
            "file://${file.absolutePath}"
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileGraphiteKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/LiveScreenshotter.kt
git commit -m "feat(live): LiveScreenshotter — 截屏缩放压缩为 file:// JPEG"
```

---

### Task 6: LiveAnalyzer — prompt/解析迁出 + 视觉与降级

**Files:**
- Create: `app/src/main/java/app/amber/feature/live/LiveAnalyzer.kt`
- Modify: `app/src/main/java/app/amber/feature/live/LiveModeManager.kt`（仅删除被迁走的 `LivePrompt` 私有 object，本任务先不动其余逻辑）

- [ ] **Step 1: 创建 LiveAnalyzer.kt**

把 `LiveModeManager.LivePrompt`（原文件 452-552 行）整体迁入并扩展。迁移部分（`user`/`parseCard`/`actionContract`/`firstNonBlankSection`/`sectionItems` 及保守 system prompt）**逐字保留**，新增视觉 system prompt、模型解析与统一入口：

```kotlin
package app.amber.feature.live

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import kotlin.uuid.Uuid

/**
 * 伴随分析器：模型解析（伴随模型→聊天模型回退）、保守/激进双模式消息构建、
 * 调用与卡片解析。从 LiveModeManager 拆出，Manager 只编排不碰 prompt。
 */
class LiveAnalyzer(
    private val providerManager: ProviderManager,
) {
    data class Outcome(
        val card: LiveModeCard,
        val usedVision: Boolean,
        /** 非空 = 激进模式被降级的原因（提示用） */
        val degradedReason: String?,
    )

    /** 解析伴随模型：companionModelId 优先，无效/未设则跟随聊天模型。 */
    fun resolveModel(settings: Settings): Model? {
        val uuid = settings.agentRuntime.liveMode.companionModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val byId = uuid?.let { settings.findModelById(it) }?.takeIf { it.type == ModelType.CHAT }
        return byId ?: settings.getCurrentChatModel()
    }

    /**
     * @param screenshotUri 激进模式下由调用方先截好（file:// URI）；
     *        null 表示不可用（低版本/截屏失败/保守模式），自动走纯文字。
     * @throws Throwable 网络/模型错误原样抛出，由 Manager 统一映射 LiveFailure。
     */
    suspend fun analyze(
        settings: Settings,
        model: Model,
        snapshot: LiveScreenSnapshot,
        focus: String,
        actionLabel: String,
        mode: LiveAnalysisMode,
        screenshotUri: String?,
    ): Outcome {
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("当前模型没有可用服务")
        val wantVision = mode == LiveAnalysisMode.AGGRESSIVE
        val modelSupportsVision = Modality.IMAGE in model.inputModalities
        val useVision = wantVision && modelSupportsVision && screenshotUri != null
        val degradedReason = when {
            !wantVision -> null
            !modelSupportsVision -> "伴随模型不支持图片输入，已按保守模式分析"
            screenshotUri == null -> "截屏不可用，已按保守模式分析"
            else -> null
        }

        val messages = if (useVision) {
            listOf(
                UIMessage.system(LivePrompt.visionSystem),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text(LivePrompt.user(snapshot, focus, actionLabel)),
                        UIMessagePart.Image(url = screenshotUri!!),
                    ),
                ),
            )
        } else {
            listOf(
                UIMessage.system(LivePrompt.system),
                UIMessage.user(LivePrompt.user(snapshot, focus, actionLabel)),
            )
        }

        val providerImpl = providerManager.getProviderByType(provider)
        val result = providerImpl.generateText(
            providerSetting = provider,
            messages = messages,
            params = TextGenerationParams(
                model = model,
                temperature = 0.25f,
                topP = 0.8f,
                maxTokens = 420,
                tools = emptyList(),
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val text = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        return Outcome(
            card = LivePrompt.parseCard(text, actionLabel),
            usedVision = useVision,
            degradedReason = degradedReason,
        )
    }

    internal object LivePrompt {
        // ↓↓ 原 LiveModeManager.LivePrompt 的 system / user / parseCard /
        //    actionContract / firstNonBlankSection / sectionItems 逐字迁入，不改一字 ↓↓
        // （此处省略，执行时从 LiveModeManager.kt 452-552 行剪切过来）

        /** 激进模式：截图为主信号 */
        const val visionSystem = """
你是 AmberAgent 的 Live 伴随模式。你会收到一张当前手机屏幕截图，以及辅助的无障碍文字提取。

规则：
- 以截图为主要依据；无障碍文字仅用于核对文本细节（如长串号码、链接）。
- 忽略状态栏、导航栏、输入法、悬浮窗等系统框架。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合手机侧栏阅读。
- 如果信息不足，直接说"不确定"，不要用泛泛建议填充。
"""
    }
}
```

> 执行注意：`LivePrompt.user(...)` 中 UI 树补充段在激进模式下同样发送（截 4000 字符），与保守一致——视觉模式靠 visionSystem 指明主次。`parseCard` 内部引用的 `LiveUiTreeProcessor` 来自 api 模块，import 不变。

- [ ] **Step 2: LiveModeManager 删掉私有 LivePrompt，改引用**

`LiveModeManager.kt`：删除 452-552 行的 `private object LivePrompt`；`analyzeSnapshot` 中两处 `LivePrompt.system`/`LivePrompt.user(...)`/`LivePrompt.parseCard(...)` 暂时改为 `LiveAnalyzer.LivePrompt.system` 等（本任务保持行为不变，Task 7 再整体重写该方法）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileGraphiteKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/LiveAnalyzer.kt app/src/main/java/app/amber/feature/live/LiveModeManager.kt
git commit -m "refactor(live): LivePrompt/解析迁出为 LiveAnalyzer，新增视觉模式与伴随模型解析"
```

---

### Task 7: LiveModeManager 重写为事件驱动编排

**Files:**
- Modify: `app/src/main/java/app/amber/feature/live/LiveModeManager.kt`

公开 API 不变（VM 依赖）：`state/start/pause/resume/stop/refreshNow/submitFocusInstruction/exportCurrentCard`；新增 `fillCurrentDraft(): LiveFillResult`。DI 构造参数不变。

- [ ] **Step 1: 重写核心**

类字段与构造改为：

```kotlin
class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val appScope: AppScope,
) {
    private val _state = MutableStateFlow(LiveModeUiState())
    val state: StateFlow<LiveModeUiState> = _state.asStateFlow()

    private val analyzer = LiveAnalyzer(providerManager)
    private val screenshotter = LiveScreenshotter(context)

    private var loopJob: Job? = null
    private var eventJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisGeneration = 0L
    private var engine: LiveEngine? = null
    private var pendingSnapshot: LiveScreenSnapshot? = null
    private var focusInstruction: String = ""

    @Volatile
    private var screenDirty: Boolean = true // 启动先看一眼
```

`start()`：

```kotlin
    fun start() {
        if (loopJob?.isActive == true) {
            resume()
            return
        }
        val liveSetting = settingsStore.settingsFlow.value.agentRuntime.liveMode
        engine = LiveEngine(
            stableDelayMs = liveSetting.stableDelayMs.coerceIn(500L, 5_000L),
            minAnalysisIntervalMs = liveSetting.minAnalysisIntervalMs.coerceIn(5_000L, 30_000L),
            backoffMs = MODEL_BUSY_BACKOFF_MS,
        )
        screenDirty = true
        _state.value = LiveModeUiState(active = true, statusText = "伴随已开启，正在检查权限")
        eventJob = appScope.launch {
            AmberAccessibilityService.screenEvents.collect { event ->
                if (event.packageName != context.packageName) screenDirty = true
            }
        }
        loopJob = appScope.launch(Dispatchers.Main.immediate) { runLoop() }
    }
```

`stop()` 同步取消 `eventJob`；`pause/resume/submitFocusInstruction/exportCurrentCard` 保持原实现；`refreshNow` 原实现保留（`analyzeSnapshot(snapshot, force = true)`）。

`runLoop()` 替换原 `observeLoop()`——头部的 enabled/paused/model/service 检查段**逐字保留**（含各状态文案），下半段换成：

```kotlin
            // 事件驱动：屏幕没动（无事件）且引擎也无待办时，跳过捕获
            val engine = engine ?: return
            val tickInterval = liveSetting.refreshIntervalMs.coerceIn(1_000L, 5_000L)
            if (!screenDirty && pendingSnapshot == null) {
                delay(tickInterval)
                continue
            }

            if (screenDirty) {
                screenDirty = false
                val snapshot = service.captureLiveUiSnapshot(
                    ownPackageName = context.packageName,
                    maxNodes = liveSetting.maxNodes.coerceIn(40, 260),
                )
                if (snapshot == null) {
                    _state.update {
                        it.copy(
                            needsAccessibility = false, noModelConfigured = false,
                            analyzing = false, statusText = "未识别到另一侧内容",
                        )
                    }
                    delay(tickInterval)
                    continue
                }
                val now = System.currentTimeMillis()
                if (engine.onScreenSignature(snapshot.stableHash, now)) {
                    pendingSnapshot = snapshot
                    _state.update {
                        it.copy(
                            active = true, needsAccessibility = false, noModelConfigured = false,
                            currentPackage = snapshot.packageName,
                            currentAppLabel = snapshot.appLabel,
                            currentTitle = snapshot.title,
                            lastSnapshotHash = snapshot.stableHash,
                            statusText = "正在伴随 ${snapshot.appLabel.ifBlank { snapshot.packageName }}",
                        )
                    }
                }
            }

            // 场景静默：OTHER 且用户没给焦点指令 → 不自动分析
            val snapshot = pendingSnapshot
            if (snapshot != null && liveSetting.autoRefresh) {
                val scene = LiveScenes.classify(snapshot.packageName)
                val silent = scene == LiveScene.OTHER && focusInstruction.isBlank()
                if (silent) {
                    _state.update {
                        if (it.analyzing || it.card != null) it
                        else it.copy(statusText = "在 ${snapshot.appLabel.ifBlank { snapshot.packageName }} 待命，点击分析或下达指令")
                    }
                } else if (engine.decide(System.currentTimeMillis()) == LiveEngine.Decision.Analyze) {
                    analyzeSnapshot(snapshot, force = false)
                }
            }
            delay(tickInterval)
```

`analyzeSnapshot(snapshot, force)` 重写为基于 engine + analyzer（保留原 generation/状态机骨架与全部状态文案、`LiveFailure` 不动）：

```kotlin
    private fun analyzeSnapshot(snapshot: LiveScreenSnapshot, force: Boolean) {
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        val liveSetting = settings.agentRuntime.liveMode
        when (val d = engine.decide(now, force)) {
            is LiveEngine.Decision.Wait -> {
                if (d.reason == "backoff") {
                    _state.update {
                        it.copy(
                            statusText = "模型服务繁忙，稍后自动重试",
                            nextAnalysisAfterMillis = engine.backoffUntilMillis(),
                        )
                    }
                }
                if (!force) return
                if (d.reason == "backoff") return
            }
            LiveEngine.Decision.Analyze -> Unit
        }
        val model = analyzer.resolveModel(settings)
        if (model == null) {
            _state.update { it.copy(noModelConfigured = true, statusText = "请先配置聊天模型") }
            return
        }

        // 场景默认动作：显式指令优先，其次场景画像，最后通用屏幕分析
        val sceneDefault = LiveScenes.defaultActionLabel(LiveScenes.classify(snapshot.packageName))
        val actionLabel = if (focusInstruction.isBlank()) {
            sceneDefault ?: DEFAULT_ACTION_LABEL
        } else {
            liveActionLabel(focusInstruction)
        }

        val generation = ++analysisGeneration
        engine.onAnalysisStarted(now)
        analysisJob?.cancel()
        analysisJob = appScope.launch(Dispatchers.IO) {
            try {
                _state.update {
                    it.copy(
                        analyzing = true,
                        requestedAction = if (actionLabel == DEFAULT_ACTION_LABEL) it.requestedAction else actionLabel,
                        completedAction = "",
                        statusText = ongoingStatus(actionLabel),
                        error = null,
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                val screenshotUri = if (liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE) {
                    AmberAccessibilityService.getActiveService()
                        ?.let { svc -> screenshotter.captureToFileUri(svc) }
                } else null
                val outcome = analyzer.analyze(
                    settings = settings,
                    model = model,
                    snapshot = snapshot,
                    focus = focusInstruction,
                    actionLabel = actionLabel,
                    mode = liveSetting.analysisMode,
                    screenshotUri = screenshotUri,
                )
                withContext(Dispatchers.Main.immediate) {
                    if (generation == analysisGeneration) {
                        engine.onAnalysisSucceeded(snapshot.stableHash)
                        _state.update {
                            it.copy(
                                analyzing = false,
                                card = outcome.card,
                                currentPackage = snapshot.packageName,
                                currentAppLabel = snapshot.appLabel,
                                currentTitle = snapshot.title,
                                requestedAction = "",
                                completedAction = actionLabel,
                                statusText = outcome.degradedReason ?: doneStatus(actionLabel),
                                error = null,
                                lastUpdatedAtMillis = System.currentTimeMillis(),
                                nextAnalysisAfterMillis = 0L,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Live analysis failed", error)
                val failure = LiveFailure.from(error)
                if (failure.retryable) engine.onRetryableFailure(System.currentTimeMillis())
                _state.update {
                    it.copy(
                        analyzing = false,
                        statusText = failure.statusText,
                        error = failure.message,
                        completedAction = "",
                        nextAnalysisAfterMillis = if (failure.retryable) engine.backoffUntilMillis() else 0L,
                    )
                }
            }
        }
    }
```

新增填入动作（文件末尾、companion 前）：

```kotlin
    /** 只填不发：草稿写进对方输入框；失败降级剪贴板。 */
    fun fillCurrentDraft(): LiveFillResult {
        val card = _state.value.card ?: return LiveFillResult.NO_DRAFT
        val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: card.watching.takeIf { it.isNotBlank() }
            ?: return LiveFillResult.NO_DRAFT
        val service = AmberAccessibilityService.getActiveService()
        if (service != null && service.setFocusedText(draft)) return LiveFillResult.FILLED
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return LiveFillResult.NO_DRAFT
        clipboard.setPrimaryClip(ClipData.newPlainText("amber-live-draft", draft))
        return LiveFillResult.COPIED
    }
```

文件级枚举（类外）：

```kotlin
enum class LiveFillResult { FILLED, COPIED, NO_DRAFT }
```

新增 import：`android.content.ClipData`、`android.content.ClipboardManager`。删除不再使用的 import（`AccessibilityServiceInfo`/`AccessibilityManager` 保留——`isAmberAccessibilityServiceEnabled` 不动）。`LiveUiTreeProcessor.shouldAnalyze` 不再被调用：**保留函数本体**，在其 KDoc 加一行 `@Deprecated("由 LiveEngine 取代，仅留作参考")`（标记不删除）。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileGraphiteKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: api 回归**

Run: `./gradlew :feature:live:api:test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/amber/feature/live/LiveModeManager.kt feature/live/api/src/main/kotlin/app/amber/feature/live/LiveUiTreeProcessor.kt
git commit -m "refactor(live): LiveModeManager 事件驱动化 — LiveEngine 决策 + 场景静默 + 只填不发"
```

---

### Task 8: VM + 页面 — 模式切换 / 伴随模型 / 填入按钮

**Files:**
- Modify: `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt`
- Modify: `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt`

- [ ] **Step 1: VM 增三个方法**

仿照现有 `setAutoRefresh` 的 settings 更新写法：

```kotlin
    fun setAnalysisMode(mode: LiveAnalysisMode) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(analysisMode = mode)
                    )
                )
            }
        }
    }

    fun setCompanionModel(modelId: String?) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(companionModelId = modelId)
                    )
                )
            }
        }
    }

    fun fillDraft(): LiveFillResult = liveModeManager.fillCurrentDraft()
```

import 补 `app.amber.feature.live.LiveAnalysisMode` 与 `app.amber.feature.live.LiveFillResult`。

- [ ] **Step 2: LiveStatusPanel 加模式切换**

`LiveCompanionPage.kt` 的 `LiveStatusPanel`（228 行起）加参数 `aggressive: Boolean, onToggleAggressive: (Boolean) -> Unit`；在"自动分析"TextButton（267-275 行）后并排加：

```kotlin
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onToggleAutoRefresh(!autoRefresh) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (autoRefresh) "自动分析：开" else "自动分析：关",
                            style = LocalAmberType.current.secondary,
                        )
                    }
                    TextButton(
                        onClick = { onToggleAggressive(!aggressive) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = if (aggressive) "激进 · 截屏" else "保守 · 文字",
                            style = LocalAmberType.current.secondary,
                        )
                    }
                }
```

（即把原 TextButton 包进 Row 并加第二个。）调用处（172-178 行）补传：

```kotlin
                LiveStatusPanel(
                    state = state,
                    autoRefresh = liveSetting.autoRefresh,
                    aggressive = liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE,
                    onToggleAutoRefresh = vm::setAutoRefresh,
                    onToggleAggressive = { enabled ->
                        vm.setAnalysisMode(
                            if (enabled) LiveAnalysisMode.AGGRESSIVE else LiveAnalysisMode.CONSERVATIVE
                        )
                    },
                    onStart = vm::start,
                    onPause = vm::pauseOrResume,
                )
```

- [ ] **Step 3: 伴随模型选择行**

`LiveStatusPanel(...)` 调用之后、error 块之前插入（settings 来自页面已有 `vm.settings` collect；如页面尚无 `val settings by vm.settings.collectAsStateWithLifecycle()` 则补上）：

```kotlin
                AmberCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "伴随模型",
                            style = LocalAmberType.current.secondary,
                            color = LocalAmberTokens.current.ink2,
                            modifier = Modifier.weight(1f),
                        )
                        ModelSelector(
                            modelId = liveSetting.companionModelId
                                ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            emptyLabel = "跟随聊天模型",
                            onClear = { vm.setCompanionModel(null) },
                            onSelect = { model -> vm.setCompanionModel(model.id.toString()) },
                        )
                    }
                }
```

import 补：`app.amber.feature.ui.components.ai.ModelSelector`、`app.amber.ai.provider.ModelType`、`app.amber.feature.live.LiveAnalysisMode`、`kotlin.uuid.Uuid`、`app.amber.feature.ui.components.ds.AmberCard`（若已 import 则跳过）。

- [ ] **Step 4: 写回复卡片加"填入"按钮**

`LiveCard` 加参数 `onFillDraft: (() -> Unit)? = null`；"写回复"分支（412-415 行）改为：

```kotlin
                "写回复" -> {
                    LiveSection(title = "回复草稿", content = card.suggestions.firstOrNull() ?: card.watching, prominent = true)
                    LiveSection(title = "语气", items = card.keyPoints)
                    if (onFillDraft != null) {
                        FilledTonalButton(
                            onClick = onFillDraft,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("填入对方输入框")
                        }
                    }
                }
```

调用处（209-216 行）补传：

```kotlin
                        onFillDraft = {
                            when (vm.fillDraft()) {
                                LiveFillResult.FILLED -> Toast.makeText(context, "已填入，发送请自己按", Toast.LENGTH_SHORT).show()
                                LiveFillResult.COPIED -> Toast.makeText(context, "没找到输入框，草稿已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                LiveFillResult.NO_DRAFT -> Toast.makeText(context, "还没有可填入的草稿", Toast.LENGTH_SHORT).show()
                            }
                        },
```

import 补 `app.amber.feature.live.LiveFillResult`。

- [ ] **Step 5: 编译 + 装机**

Run: `./gradlew :app:installGraphite`
Expected: BUILD SUCCESSFUL + installed

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionVM.kt app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt
git commit -m "feat(live): 页面接入双模式切换/伴随模型选择/回复草稿一键填入"
```

---

### Task 9: 端到端验证

**Files:** 无新改动（验证回合）

- [ ] **Step 1: 全量单测**

Run: `./gradlew :feature:live:api:test :app:compileGraphiteKotlin`
Expected: 全绿

- [ ] **Step 2: 装机手动清单**（需要用户手机解锁配合）

1. 开伴随 → 切到微信任意聊天 → 应自动出"回复草稿"卡片（场景画像生效）
2. 点"填入对方输入框" → 草稿出现在微信输入框，未发送（只填不发）
3. 切到一个陌生 app（如计算器）→ 状态显示"待命"，不自动分析（场景静默）
4. 屏幕不动放置 30s → 无新分析发起（事件驱动 + 去重；可观察状态文案不刷）
5. 切"激进 · 截屏"+ 选一个视觉模型 → 刷新 → 卡片质量包含视觉细节；
   换纯文字模型 → 状态提示"已按保守模式分析"（降级路径）
6. 暂停/继续/停止/语音指令回归正常

- [ ] **Step 3: 验证结论回报用户**，不自动 push。

---

## Self-Review 记录

- **Spec 覆盖**：双模式 ✓(T1/T6/T7/T8)；事件驱动+去重+冷却 ✓(T3/T4/T7)；场景画像 ✓(T2/T7)；只填不发 ✓(T7/T8)；伴随模型 ✓(T6/T8)；API30 降级 ✓(T4 返回 null→T6 degradedReason)；模型不支持视觉降级 ✓(T6)；三拆分（引擎/分析器/动作）✓(T3/T6/T7)。dHash 收敛偏差已在头部声明。
- **占位符**：T6 的 LivePrompt 迁移段标注"逐字迁入"并给出源行号（452-552），属明确指令而非 TBD。
- **类型一致性**：`LiveEngine.Decision.Wait(reason)` 测试与实现一致；`LiveFillResult` 三态在 VM/Page/Manager 一致；`companionModelId: String?` 全链路字符串，仅 UI/解析处 `Uuid.parse`。
