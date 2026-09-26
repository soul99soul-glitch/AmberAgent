package app.amber.core.ai.tools

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.PermissionDecisionAction
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.ui.theme.ThemeSettingsStore
import app.amber.feature.ui.theme.ThemePackTransfer
import app.amber.feature.ui.theme.ThemePackageApplyResult
import app.amber.feature.ui.theme.ThemePackageImportResult
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemePackToolsTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: ThemePackageManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = ThemePackageManager(
            dao = db.themePackageDao(),
            settingsStore = FakeThemeSettingsStore(Settings(displaySetting = DisplaySetting())),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `theme tools expose status and approval-gated import`() = runTest {
        val tools = createThemePackTools(manager)
        assertTrue(tools.any { it.name == TOOL_THEME_PACK_STATUS })
        val import = tools.single { it.name == TOOL_THEME_PACK_IMPORT }
        assertTrue(import.needsApproval)
        assertFalse(import.allowsAutoApproval)
        assertTrue(import.mandatoryApproval)

        val resolver = PermissionDecisionResolver()
        val decision = resolver.resolve(
            toolDef = import,
            tool = UIMessagePart.Tool(
                toolCallId = "call_theme",
                toolName = TOOL_THEME_PACK_IMPORT,
                input = "{\"action\":\"prepare\"}",
                approvalState = ToolApprovalState.Auto,
            ),
            autoApproveTools = true,
            autoApproveHighRiskTools = true,
        )
        assertEquals(PermissionDecisionAction.ASK, decision.action)
    }

    @Test
    fun `prepare is memory only and explicit apply persists`() = runTest {
        val import = createThemePackTools(manager).single { it.name == TOOL_THEME_PACK_IMPORT }
        val prepare = buildJsonObject {
            put("action", "prepare")
            put("id", "agent-theme")
            put("name", "Agent Theme")
            put("colors", buildJsonObject {
                put("baseFamily", "SAGE")
                put("accent", "#4F86D6")
            })
        }

        val preparedText = (import.execute(prepare).single() as UIMessagePart.Text).text
        assertTrue(preparedText.contains("\"status\":\"prepared\""))
        assertTrue(preparedText.contains("\"persisted\":false"))
        assertNull(db.themePackageDao().getById("agent-theme"))
        val candidateDigest = manager.tryOn.value!!.candidateDigest

        val appliedText = (import.execute(buildJsonObject {
            put("action", "apply")
            put("id", "agent-theme")
            put("candidate_digest", candidateDigest)
        }).single() as UIMessagePart.Text).text
        assertTrue(appliedText.contains("\"status\":\"applied\""))
        assertTrue(appliedText.contains("\"persisted\":true"))
        assertEquals("SAGE", db.themePackageDao().getById("agent-theme")?.let {
            JsonInstant.decodeFromString(app.amber.feature.ui.theme.ThemePackage.serializer(), it.json).colors["baseFamily"]
        })
    }

    @Test
    fun `discard clears candidate without persistence`() = runTest {
        val import = createThemePackTools(manager).single { it.name == TOOL_THEME_PACK_IMPORT }
        import.execute(buildJsonObject {
            put("id", "discard-me")
            put("name", "Discard Me")
            put("colors", buildJsonObject { put("accent", "#9277C4") })
        })
        assertTrue(manager.tryOn.value != null)
        val candidateDigest = manager.tryOn.value!!.candidateDigest

        import.execute(buildJsonObject {
            put("action", "discard")
            put("id", "discard-me")
            put("candidate_digest", candidateDigest)
        })

        assertNull(manager.tryOn.value)
        assertNull(db.themePackageDao().getById("discard-me"))
    }

    @Test
    fun `apply and discard reject missing candidate binding`() = runTest {
        val import = createThemePackTools(manager).single { it.name == TOOL_THEME_PACK_IMPORT }
        import.execute(buildJsonObject {
            put("action", "prepare")
            put("id", "bound-theme")
            put("name", "Bound Theme")
        })

        val applyText = (import.execute(buildJsonObject {
            put("action", "apply")
            put("id", "bound-theme")
        }).single() as UIMessagePart.Text).text
        val discardText = (import.execute(buildJsonObject {
            put("action", "discard")
            put("id", "bound-theme")
        }).single() as UIMessagePart.Text).text

        assertTrue(applyText.contains("\"status\":\"rejected\""))
        assertTrue(applyText.contains("binding_required"))
        assertTrue(discardText.contains("\"status\":\"rejected\""))
        assertTrue(discardText.contains("binding_required"))
        assertTrue(manager.tryOn.value != null)
    }

    @Test
    fun `portable theme continues latest draft and updates one saved recipe`() = runTest {
        val fixture = File("../test-fixtures/themes/cross-platform-v1.json").readText()
        val original = ThemePackTransfer.decode(fixture)
        val before = manager.status().current
        val imported = manager.prepareImport(fixture) as ThemePackageImportResult.Preview
        val import = createThemePackTools(manager).single { it.name == TOOL_THEME_PACK_IMPORT }
        assertEquals(original, manager.recipe())
        assertEquals(before, manager.status().current)

        import.execute(buildJsonObject {
            put("base_id", "current")
            put("design", buildJsonObject {
                put("components", buildJsonObject { put("cardRadius", 12) })
            })
        })
        import.execute(buildJsonObject {
            put("base_id", "current")
            put("design", buildJsonObject {
                put("light", buildJsonObject { put("border", "#B3A89C") })
            })
        })
        val design = requireNotNull(original.design)
        val expected = original.copy(design = design.copy(
            components = design.components!!.copy(cardRadius = 12.0),
            light = design.light!!.copy(border = "#B3A89C"),
        ))
        assertEquals(expected, manager.recipe())
        assertEquals(before, manager.status().current)
        assertTrue(manager.status().installed.isEmpty())
        assertEquals(ThemePackageApplyResult.NotPrepared, manager.applyPrepared(imported.pkg.id, imported.candidateDigest))
        val candidate = manager.tryOn.value!!
        assertEquals(ThemePackageApplyResult.Applied, manager.applyPrepared(candidate.pkg.id, candidate.candidateDigest))

        import.execute(buildJsonObject {
            put("base_id", original.id)
            put("design", buildJsonObject { put("patterns", buildJsonArray {}) })
        })
        val second = manager.tryOn.value!!
        import.execute(buildJsonObject {
            put("action", "apply"); put("id", second.pkg.id); put("candidate_digest", second.candidateDigest)
        })
        assertEquals(listOf(original.id), manager.status().installed.map { it.id })
        val finalRecipe = expected.copy(design = expected.design!!.copy(patterns = emptyList()))
        val export = ThemePackTransfer.encode(ThemePackTransfer.export(manager.status().current.displaySetting))
        assertEquals(finalRecipe, ThemePackTransfer.decode(export))
        // Exercise the persisted portable JSON path as a later library selection would.
        manager.applyBuiltin("WARM")
        assertEquals(ThemePackageApplyResult.Applied, manager.apply(original.id))
        assertEquals(finalRecipe, manager.recipe())
        File("build/reports/theme-android-export.json").apply { parentFile.mkdirs() }.writeText(export)
    }

    @Test
    fun `builtin edit creates one copy and invalid refinement retains the preview`() = runTest {
        val import = createThemePackTools(manager).single { it.name == TOOL_THEME_PACK_IMPORT }
        import.execute(buildJsonObject {
            put("base_id", "current")
            put("design", buildJsonObject { put("components", buildJsonObject { put("cardRadius", 12) }) })
        })
        val first = manager.recipe()
        assertFalse(first.id in ThemePackTransfer.builtinIds)
        assertNull(first.design?.light)
        import.execute(buildJsonObject {
            put("base_id", "current")
            put("design", buildJsonObject { put("components", buildJsonObject { put("cardRadius", JsonNull) }) })
        })
        val current = manager.tryOn.value!!
        assertEquals(first.id, current.pkg.id)
        assertNull(manager.recipe().design?.components?.cardRadius)
        val failure = runCatching {
            import.execute(buildJsonObject {
                put("base_id", "current"); put("accent_hex", "#FFFFFF"); put("ink_hex", "#FFFFFF")
            })
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(current, manager.tryOn.value)
        assertTrue(manager.discardTryOn(current.pkg.id, current.candidateDigest))
        assertTrue(manager.status().installed.isEmpty())
        assertNull(manager.status().current.displaySetting.themePack)
    }

    private class FakeThemeSettingsStore(initial: Settings) : ThemeSettingsStore {
        private val flow = MutableStateFlow(initial)
        override val settingsFlow: Flow<Settings> = flow
        override suspend fun update(settings: Settings) {
            flow.value = settings
        }
    }
}
