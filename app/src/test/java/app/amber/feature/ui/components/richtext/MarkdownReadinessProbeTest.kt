package app.amber.feature.ui.components.richtext

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import app.amber.feature.ui.components.message.ChatMessageVirtualItem
import app.amber.feature.ui.components.message.ChatMessageVirtualItemContent
import app.amber.feature.ui.components.message.buildChatMessageVirtualItems
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.feature.ui.utils.ChatPerfTargetKind
import org.junit.Assume.assumeTrue
import org.json.JSONObject
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownParseCache
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.utils.LocalMarkdownReadinessDispose
import app.amber.feature.ui.utils.LocalMarkdownReadinessProbe
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the real-device missing FirstContentDraw/FirstReadableDraw:
 * settled history takes MarkdownBlockLegacy's cache-hit fast path, which
 * returns before the old probe SideEffect. The probe must fire on that real
 * Compose path and be removable when the slice leaves composition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MarkdownReadinessProbeTest {
    @get:Rule val compose = createComposeRule()
    @Before fun useRealClock() { ChatPerfTrace.clockNanos = { System.nanoTime() } }
    @After fun restoreClock() { ChatPerfTrace.clockNanos = { android.os.SystemClock.elapsedRealtimeNanos() } }

    @Test
    fun offscreenAstSliceCannotReportFormattedUntilItIntersectsViewport() {
        val data = MarkdownParseCache.getOrParse("# Heading\n\nS4 parsed body")
        var offscreen by mutableStateOf(true)
        val reports = mutableListOf<Int>()
        val disposed = mutableListOf<Int>()
        val probe: (Int, Boolean) -> Unit = { token, _ -> reports += token }
        val dispose: (Int) -> Unit = { disposed += it }
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(),
                LocalMarkdownReadinessProbe provides probe,
                LocalMarkdownReadinessDispose provides dispose) {
                MaterialTheme {
                    MarkdownTopLevelBlock(data, data.tree.children.indexOfFirst { it.type == MdNodeType.Paragraph }, Modifier.offset(y = if (offscreen) 3000.dp else 0.dp))
                }
            }
        }
        compose.waitForIdle()
        assertTrue("offscreen parsed AST must not certify body readiness", reports.isEmpty())
        compose.runOnIdle { offscreen = false }
        compose.waitForIdle()
        assertTrue(reports.isNotEmpty())
        val token = reports.first()
        compose.runOnIdle { offscreen = true }
        compose.waitForIdle()
        assertTrue("leaving viewport removes readiness", token in disposed)
    }

    @Test
    fun parsedTopLevelSliceKeepsTokenOnRecomposeAndDisposesOnUnload() {
        val data = MarkdownParseCache.getOrParse("# S4 heading\n\n" + "body ".repeat(300))
        var show by mutableStateOf(true)
        var revision by mutableStateOf(0)
        val reports = mutableListOf<Pair<Int, Boolean>>()
        val disposed = mutableListOf<Int>()
        val probe: (Int, Boolean) -> Unit = { token, pending -> reports += token to pending }
        val dispose: (Int) -> Unit = { disposed += it }
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalMarkdownReadinessProbe provides probe,
                LocalMarkdownReadinessDispose provides dispose,
            ) {
                MaterialTheme {
                    Column {
                        Text("revision $revision")
                        if (show) MarkdownTopLevelBlock(data, data.tree.children.indexOfFirst { it.type == MdNodeType.Paragraph })
                    }
                }
            }
        }
        compose.waitForIdle()
        val token = reports.single().first
        assertTrue(token > 0)
        assertTrue(reports.all { !it.second })
        compose.runOnIdle { revision++ }
        compose.waitForIdle()
        assertTrue(reports.all { it.first == token })
        assertTrue(disposed.isEmpty())
        compose.runOnIdle { show = false }
        compose.waitForIdle()
        assertEquals(listOf(token), disposed)
    }

    @Test
    fun actualVirtualItemRendererReportsReadableThroughNodeProvider() {
        assumeTrue(ChatPerfTrace.enabled)
        val context = RuntimeEnvironment.getApplication()
        val runId = "s4-virtual-item-${System.nanoTime()}"
        val part = UIMessagePart.Text("# S4 heading\n\n" + "long body ".repeat(2400) + "\n\n## Tail\n\nreadable tail")
        val node = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)))
        val items = buildChatMessageVirtualItems(node, emptyList(), false, false, true)!!
        val child = items.filterIsInstance<ChatMessageVirtualItem.MarkdownChild>().first()
        val highlighter = Highlighter(context)
        ChatPerfTrace.startRun(context, "S4", runId)
        ChatPerfTrace.dataReady()
        ChatPerfTrace.initialPreparationReady()
        ChatPerfTrace.firstTimelineLayout()
        ChatPerfTrace.beginTarget(ChatPerfTargetKind.MESSAGE, node.id)
        ChatPerfTrace.onTargetVisible(ChatPerfTrace.targetGeneration(), true)
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalHighlighter provides highlighter,
                LocalToaster provides ToasterState(CoroutineScope(Dispatchers.Unconfined)),
            ) {
                MaterialTheme {
                    ChatMessageVirtualItemContent(node, child, onFork = {}, onRegenerate = {},
                        onEdit = {}, onShare = {}, onQuote = {}, onDelete = {}, onUpdate = {})
                }
            }
        }
        compose.waitForIdle()
        ChatPerfTrace.onTargetDraw(1f)
        ChatPerfTrace.interactionRequested("swipe")
        ChatPerfTrace.confirmInteraction("compose-test")
        val root = context.getExternalFilesDir("chat-perf") ?: File(context.filesDir, "chat-perf")
        val file = File(root, "runs/$runId/metrics-S4.json")
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline &&
            (!file.isFile || JSONObject(file.readText()).optString("status") != "final")) Thread.sleep(20)
        val metrics = JSONObject(file.readText())
        assertEquals("final", metrics.getString("status"))
        assertTrue(metrics.getJSONObject("phasesMs").has("FirstReadableDraw"))
    }

    @Test
    fun settledCacheHitPathReportsFormattedAndDisposesToken() {
        val content = "# Cached heading\n\nCached body for readiness."
        // Warm the exact settled-history cache path before composition.
        MarkdownParseCache.getOrParse(content)
        assertTrue(MarkdownParseCache.get(content) != null)

        var show by mutableStateOf(true)
        var token = 0
        var pending: Boolean? = null
        val disposed = mutableListOf<Int>()
        val highlighter = Highlighter(RuntimeEnvironment.getApplication())
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalHighlighter provides highlighter,
                LocalToaster provides ToasterState(CoroutineScope(Dispatchers.Unconfined)),
                LocalMarkdownReadinessProbe provides { probeToken, isPending ->
                    token = probeToken
                    pending = isPending
                },
                LocalMarkdownReadinessDispose provides { disposed += it },
            ) {
                MaterialTheme {
                    if (show) {
                        MarkdownBlock(content = content)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("cache-hit composable must allocate a positive token", token > 0)
        assertEquals("cache-hit settled content is formatted, not a placeholder", false, pending)

        compose.runOnIdle { show = false }
        compose.waitForIdle()
        assertTrue("unloaded slice must dispose its probe token", token in disposed)
    }
}
