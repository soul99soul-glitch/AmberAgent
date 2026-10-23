package app.amber.agent

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.ui.components.ai.OperationWebPreview
import app.amber.feature.ui.components.ai.SandboxActivitySheet
import app.amber.feature.ui.components.ai.SandboxPeekBar
import app.amber.feature.ui.components.ai.WebOperationPreviewThumbnail
import app.amber.feature.ui.components.ai.WebPreviewThumbnails
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.webview.WebViewOperationStore
import java.io.File
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** Real preview components and native WebViews, isolated from conversation/provider state. */
@RunWith(AndroidJUnit4::class)
class ModularizationSandboxPreviewDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = WebViewOperationStore()
    private val fixtureKoin = koinApplication { modules(module { single { store } }) }
    private var mounted by mutableStateOf(true)
    private var expanded by mutableStateOf(false)
    private var sheetOpen by mutableStateOf(false)

    @After
    fun releaseFixture() {
        compose.runOnIdle { mounted = false; sheetOpen = false }
        compose.waitForIdle()
        fixtureKoin.close()
    }

    @Test
    fun hiddenPreviewExpandedReturnBackgroundAndDisposeUseTheSameLoad() {
        val url = localPageUrl()
        val loadId = store.open(url, toolCallId = "p3-sandbox-local-page")
        compose.setContent {
            KoinIsolatedContext(fixtureKoin) {
                FixtureTheme {
                    if (mounted) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.width(72.dp).height(96.dp).testTag("sandbox-thumb")) {
                                WebOperationPreviewThumbnail(
                                    url = url,
                                    toolCallId = "p3-sandbox-local-page",
                                    live = true,
                                    onOpen = { expanded = true },
                                )
                            }
                            if (expanded) {
                                OperationWebPreview(url, Modifier.fillMaxWidth().weight(1f).testTag("sandbox-expanded"))
                            }
                        }
                    } else {
                        Text("Sandbox preview removed", Modifier.testTag("sandbox-removed"))
                    }
                }
            }
        }
        compose.waitUntil(WAIT_MS) { store.state.value.readableText.contains(PAGE_TEXT) }
        compose.waitUntil(WAIT_MS) { store.state.value.thumbnailPath.isNotBlank() }
        assertEquals("P3 local readable page", store.state.value.title)
        val hidden = nativeWebViews().single()
        val thumbnail = File(store.state.value.thumbnailPath)
        assertTrue(thumbnail.isFile && thumbnail.length() > 0L)
        capture("01-hidden-thumbnail")

        compose.onNodeWithTag("sandbox-thumb").performTouchInput { click() }
        compose.onNodeWithTag("sandbox-expanded").assertIsDisplayed()
        compose.waitUntil(WAIT_MS) { nativeWebViews().size == 2 }
        assertEquals(loadId, store.state.value.loadId)
        assertTrue(nativeWebViews().any { it === hidden })
        capture("02-expanded-live-preview")

        compose.runOnIdle { expanded = false }
        compose.waitUntil(WAIT_MS) { nativeWebViews().size == 1 }
        assertSame(hidden, nativeWebViews().single())
        assertEquals(loadId, store.state.value.loadId)
        assertTrue(store.state.value.readableText.contains(PAGE_TEXT))
        capture("03-returned-thumbnail")

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertSame(hidden, nativeWebViews().single())
        assertEquals(loadId, store.state.value.loadId)

        compose.runOnIdle { mounted = false }
        compose.onNodeWithTag("sandbox-removed").assertIsDisplayed()
        compose.waitUntil(WAIT_MS) { nativeWebViews().isEmpty() }
        assertFalse(hidden.isAttachedToWindow)
        assertFalse(store.state.value.rendererActive)
        capture("04-preview-disposed")
    }

    @Test
    fun finishedPreviewWithASavedThumbnailReopensWithoutAWebView() {
        val url = "https://example.invalid/p3-saved-thumbnail"
        val saved = WebPreviewThumbnails.write(
            instrumentation.targetContext,
            url,
            Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.DKGRAY) },
        )
        try {
            compose.setContent {
                KoinIsolatedContext(fixtureKoin) {
                    FixtureTheme {
                        Box(Modifier.width(72.dp).height(96.dp)) {
                            WebOperationPreviewThumbnail(
                                url = url,
                                toolCallId = "p3-finished-tool",
                                live = false,
                                onOpen = {},
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Longer than the backfill delay an uncached finished preview waits before loading.
            SystemClock.sleep(2_500)
            compose.waitForIdle()
            assertTrue(nativeWebViews().isEmpty())
            assertEquals("", store.state.value.loadId)
            capture("05-saved-thumbnail-without-webview")
        } finally {
            saved.delete()
        }
    }

    @Test
    fun terminalPeekAndSheetKeepCancelAndOutputReachableAtLargeFont() {
        var cancelCount = 0
        val activity = SandboxActivityUiState(
            toolCallId = "p3-sandbox-terminal",
            toolName = "terminal_execute",
            title = "Terminal · a long operation title for compact layout",
            status = ToolActivityStatus.RUNNING,
            inputPreview = "printf 'sandbox lifecycle fixture'",
            outputTail = "sandbox lifecycle fixture output",
            runtime = "local fixture",
            canCancel = true,
            stepIndex = 12,
            stepTotal = 24,
        )
        compose.setContent {
            FixtureTheme {
                if (mounted) {
                    SandboxPeekBar(
                        activity = activity,
                        onOpen = { sheetOpen = true },
                        onCancel = { cancelCount++ },
                        onPrevious = {},
                        onNext = {},
                    )
                    if (sheetOpen) {
                        SandboxActivitySheet(
                            activity = activity,
                            onDismiss = { sheetOpen = false },
                            onCancel = { cancelCount++ },
                            onPrevious = {},
                            onNext = {},
                        )
                    }
                }
            }
        }
        val peekTitle = compose.onNodeWithText(activity.title, useUnmergedTree = true)
        peekTitle.assertIsDisplayed()
        capture("05-terminal-peek-320dp-font150")
        // The merged pill's center overlaps its Stop child; tap the actual title instead.
        peekTitle.performTouchInput { click() }
        assertTrue("The production peek callback must open the sheet", sheetOpen)
        assertEquals("Opening the title must not invoke Stop", 0, cancelCount)
        capture("06-terminal-sheet-opening-320dp-font150")
        val interrupt = instrumentation.targetContext.getString(R.string.chat_sandbox_interrupt)
        try {
            // The sheet lives in a separate animated window; composition alone is not visibility.
            compose.waitUntil(WAIT_MS) {
                val actions = compose.onAllNodesWithText(interrupt)
                actions.fetchSemanticsNodes().indices.any { actions[it].isDisplayed() }
            }
        } catch (failure: Throwable) {
            runCatching { capture("06-terminal-sheet-not-visible-320dp-font150") }
                .onFailure { println("Sandbox failure capture failed: $it") }
            throw failure
        }
        capture("07-terminal-sheet-stable-320dp-font150")
        compose.onNodeWithText(interrupt).assertIsDisplayed().performClick()
        assertEquals(1, cancelCount)
        compose.onNodeWithText(activity.outputTail).assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        assertFalse(sheetOpen)
    }

    private fun nativeWebViews(): List<WebView> {
        var views = emptyList<WebView>()
        instrumentation.runOnMainSync { views = collectWebViews(compose.activity.window.decorView) }
        return views
    }

    private fun collectWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { collectWebViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = 1.5f),
            LocalAmberTokens provides tokens,
            LocalChatTheme provides tokens.toChatTheme(),
        ) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(tokens.bg), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { content() }
                }
            }
        }
    }

    private fun localPageUrl(): String {
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>P3 local readable page</title></head>
            <body style="margin:0;padding:24px;background:#e6f2ff;color:#142a49;font-family:sans-serif">
            <h1>Local preview</h1><p>$PAGE_TEXT</p><div style="height:120px;background:#1e62a0">Capture content</div>
            <a href="https://example.invalid/p3-link">A readable fixture link</a></body></html>
        """.trimIndent()
        return "data:text/html;base64," + Base64.getEncoder().encodeToString(html.toByteArray(Charsets.UTF_8))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val directory = instrumentation.targetContext.getExternalFilesDir("modularization-phase-3-sandbox")!!
        check(directory.exists() || directory.mkdirs())
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "$name.txt").writeText(
            roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() },
        )
        println("ModularizationSandboxPreviewDeviceTest screenshot=${File(directory, "$name.png").absolutePath}")
    }

    private companion object {
        const val PAGE_TEXT = "This text came from the real local WebView document."
        const val WAIT_MS = 20_000L
    }
}
