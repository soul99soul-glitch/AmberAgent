package app.amber.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.android.ComposeNotIdleException
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.files.SkillManager
import app.amber.core.settings.prefs.SettingsAggregator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Real UI navigation canary for the redesign's secondary surfaces.
 *
 * Run with [AmberAgentUiSmokeTestRunner]. Every route is reached through a
 * production Compose click. Fixtures are local-only and are created only after
 * the explicit emulator guard has passed; no credentialed provider, model
 * generation, or external write is exercised. PNGs are written to the target app's external
 * files directory under `ui-redesign-navigation` for adb pull/visual review.
 *
 * This deliberately does not duplicate UiRefreshDeviceSmokeTest's
 * home/settings/display/theme/empty-chat chain.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class RedesignNavigationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
    private val originalColorMode = preferences.getString(COLOR_MODE_PREF, null)

    private val skillFixtureSeed = "redesign-smoke-skill-${System.currentTimeMillis()}"
    private val providerFixtureName = "Redesign local provider ${System.currentTimeMillis()}"
    private val providerFixtureModelId = "redesign-cached-model"

    private var fixtureSkillName: String? = null
    private var fixtureProviderId: Uuid? = null

    @get:Rule
    val compose = createEmptyComposeRule()

    init {
        // RouteActivity reads these before composing. This test must always land
        // on the real SessionHomePage, regardless of a prior test's launch mode.
        preferences.edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name)
            .putString(COLOR_MODE_PREF, COLOR_MODE_LIGHT)
            .apply()
    }

    @Test
    fun redesignSecondaryNavigationCanary() {
        assumeTrue(
            "Run with -PuiSmokeTest=true so the real AmberAgentApp is installed",
            targetContext.applicationContext is AmberAgentApp,
        )
        assumeEmulator()

        ActivityScenario.launch(RouteActivity::class.java).use {
            try {
                compose.waitForIdle()
                prepareLocalFixtures()
                runNavigationCanary()
            } catch (failure: Throwable) {
                runCatching { captureFailure("failure-${failure::class.java.simpleName}") }
                throw failure
            } finally {
                cleanupLocalFixtures()
            }
        }
    }

    @After
    fun restoreUiPreferences() {
        preferences.edit().apply {
            if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
            else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
            if (originalColorMode == null) remove(COLOR_MODE_PREF)
            else putString(COLOR_MODE_PREF, originalColorMode)
        }.apply()
    }

    private fun assumeEmulator() {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val isEmulator = fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("android sdk built for")
        assumeTrue(
            "This canary creates disposable fixtures only on an Android emulator; " +
                "fingerprint=${Build.FINGERPRINT}, model=${Build.MODEL}",
            isEmulator,
        )
    }

    private fun prepareLocalFixtures() {
        val skillManager: SkillManager = getKoin().get()
        val skill = skillManager.saveSkill(
            name = skillFixtureSeed,
            content = """
                ---
                name: $skillFixtureSeed
                description: Local navigation smoke fixture
                ---

                # Local fixture

                This file exists only to render the read-only Skill detail page.
            """.trimIndent(),
        ) ?: error("Unable to create the local Skill fixture")
        fixtureSkillName = skill.name

        // The random provider id has no token in the fresh emulator. GROK_OAUTH
        // therefore makes the model catalogue return the local "not signed in"
        // error before any HTTP request, while the cached model remains visible
        // for the real provider-detail/model-editor clicks.
        val providerId = Uuid.random()
        fixtureProviderId = providerId
        val cachedModel = Model(
            modelId = providerFixtureModelId,
            displayName = "Cached smoke model",
            id = Uuid.random(),
            type = ModelType.CHAT,
            contextWindowTokens = 32_000,
        )
        val provider = ProviderSetting.OpenAI(
            id = providerId,
            enabled = false,
            name = providerFixtureName,
            models = listOf(cachedModel),
            apiKey = "",
            baseUrl = "https://example.invalid/v1",
            authMode = OpenAIAuthMode.GROK_OAUTH,
            brand = OpenAIBrand.GENERIC,
        )
        val settingsStore: SettingsAggregator = getKoin().get()
        runBlocking {
            settingsStore.update { current ->
                current.copy(providers = current.providers + provider)
            }
        }
    }

    private fun runNavigationCanary() {
        // Mini-app group is intentionally empty/read-only: no generation or
        // app source fixture is needed to inspect the list surface.
        clickContent(R.string.session_home_feature_mini_apps)
        capturePage("mini-apps", R.string.miniapp_title)
        goBack()

        // Opening the rail creates a local host-only room and does not send a
        // message. It is the production click path for the Council Room.
        clickContent(R.string.session_home_feature_council)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(
                text(R.string.setting_model_council_title),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(
            text(R.string.council_room_members_and_synthesis),
        ).assertIsDisplayed()
        capture("council-room")
        goBack()

        // Profile is reached from the real settings toolbar avatar, whose
        // production content description is the profile title.
        clickContent(R.string.settings)
        clickProfileAvatar()
        capturePage("profile", R.string.profile_title)
        goBack()

        // About is now reachable through the real final Settings row.
        // The About page intentionally hosts a continuously blinking cursor, so
        // it never becomes globally idle. Check the rendered accessibility tree
        // instead of Compose's idle-gated semantics, then capture that frame.
        clickSettingRowWithPersistentAnimation(R.string.about_page_title)
        captureAccessibilityPage("about", R.string.about_page_title)
        goBackFromPersistentAnimation()

        clickSettingRow(R.string.setting_page_chat_storage)
        capturePage("chat-storage", R.string.setting_page_chat_storage)
        clickClickableText(R.string.setting_chat_storage_rebuild_index)
        capturePage("chat-storage-index-confirm", R.string.search_page_rebuild_index)
        clickVisibleClickableText(R.string.cancel)
        goBack()

        // Settings group: model page, provider registry/detail, then the
        // extension/skill subtree. All controls remain read-only.
        clickSettingRow(R.string.setting_page_default_model)
        capturePage("models-prompts", R.string.setting_model_page_title)
        goBack()

        clickSettingRow(R.string.setting_page_providers)
        capturePage("providers", R.string.setting_page_providers)
        if (fixtureProviderId != null) {
            clickClickableText(providerFixtureName)
            assertDynamicVisible(providerFixtureName)
            capture("provider-detail")

            clickVisibleClickableText(R.string.setting_provider_page_models)
            waitForText(providerFixtureModelId)
            capture("provider-models")
            clickClickableText(providerFixtureModelId)
            assertDynamicVisible(providerFixtureModelId)
            capture("model-editor")
            // The editor is a modal sheet; dismiss it without saving.
            pressBack()
            compose.waitForIdle()
            goBack()
        } else {
            println("RedesignNavigationSmokeTest gap=provider-detail/model-editor: provider fixture unavailable")
        }
        goBack()

        clickSettingRow(R.string.setting_page_agent_extensions)
        capturePage("agent-extensions", R.string.setting_agent_extensions_page_title)

        clickSettingRow(R.string.setting_page_agent_skills)
        capturePage("skills", R.string.skills_page_title)
        fixtureSkillName?.let { name ->
            clickClickableText(name)
            assertDynamicVisible(name)
            capture("skill-detail")
            goBack()
        }
        goBack()

        clickSettingRow(R.string.setting_page_extensions)
        capturePage("extensions", R.string.extensions_page_title)
        clickClickableText(R.string.quick_messages_page_title)
        capturePage("quick-messages", R.string.quick_messages_page_title)
        goBack()
        clickClickableText(R.string.favorite_page_title)
        capturePage("favorites", R.string.favorite_page_title)
        goBack()
        goBack()
        goBack()
        systemBackToHome()

        // Full message search is the expanded home search's real route.
        clickContent(R.string.history_page_search)
        clickContent(R.string.parity_home_search_full)
        closeSoftKeyboard()
        capturePage("search", R.string.search_page_title)
        goBack()

    }

    private fun clickContent(id: Int) {
        compose.onNodeWithContentDescription(text(id))
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun clickSettingRow(id: Int, waitForIdle: Boolean = true) {
        val value = text(id)
        val target = hasText(value) and hasClickAction()
        val matches = compose.onAllNodes(target)
        val hasVisibleMatch = matches.fetchSemanticsNodes().indices.any { index ->
            matches[index].isDisplayed()
        }
        if (!hasVisibleMatch) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(target)
        }
        compose.onNode(target)
            .performScrollTo()
            .assertIsDisplayed()
        performAccessibilityClick(value, contentDescription = false)
        if (waitForIdle) compose.waitForIdle()
    }

    private fun clickSettingRowWithPersistentAnimation(id: Int) {
        clickSettingRow(id, waitForIdle = false)
        try {
            compose.waitForIdle()
        } catch (_: ComposeNotIdleException) {
            // SettingAboutPage keeps its visible terminal cursor animating. The
            // route is verified immediately afterward through UiAutomation.
        }
    }

    private fun clickClickableText(id: Int) = clickClickableText(text(id))

    private fun clickClickableText(
        value: String,
        waitForIdle: Boolean = true,
    ) {
        val target = hasText(value) and hasClickAction()
        val matches = compose.onAllNodes(target)
        val hasVisibleMatch = matches.fetchSemanticsNodes().indices.any { index ->
            matches[index].isDisplayed()
        }
        if (!hasVisibleMatch) {
            // A Lazy item can remain composed after scrolling off screen. Move
            // the root list to the actual row before clicking it, rather than
            // treating a stale invisible semantics node as the destination.
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                .performScrollToNode(target)
        }
        compose.onNode(target)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        if (waitForIdle) compose.waitForIdle()
    }

    private fun clickVisibleClickableText(id: Int) {
        compose.onNode(hasText(text(id)) and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun clickProfileAvatar() {
        compose.onNodeWithContentDescription(text(R.string.profile_title))
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun goBack() {
        compose.onNodeWithContentDescription(text(R.string.back))
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun goBackFromPersistentAnimation() {
        performAccessibilityClick(text(R.string.back), contentDescription = true)
        waitForText(text(R.string.settings))
    }

    private fun systemBackToHome() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription(text(R.string.history_page_search))
            .assertIsDisplayed()
    }

    private fun capturePage(fileName: String, titleId: Int, waitForIdle: Boolean = true) {
        assertDynamicVisible(text(titleId))
        capture(fileName, waitForIdle)
    }

    private fun captureAccessibilityPage(fileName: String, titleId: Int) {
        assertAccessibilityTextVisible(text(titleId))
        assertAccessibilityTextVisible(text(R.string.back), contentDescription = true)
        capture(fileName, waitForIdle = false)
    }

    private fun assertAccessibilityTextVisible(value: String, contentDescription: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val visible = root?.findVisibleAccessibilityNode(value, contentDescription) != null
            if (visible) return
            SystemClock.sleep(100)
        }
        throw AssertionError(
            "Accessibility node was not visible: ${if (contentDescription) "contentDescription" else "text"}=$value",
        )
    }

    private fun performAccessibilityClick(value: String, contentDescription: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val target = instrumentation.uiAutomation.rootInActiveWindow
                ?.findVisibleAccessibilityNode(value, contentDescription)
            if (target?.performClickOrAncestor() == true) {
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Accessibility node was not clickable: contentDescription=$value")
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.findVisibleAccessibilityNode(
        value: String,
        matchesContentDescription: Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (isVisibleToUser) {
            val actual = if (matchesContentDescription) contentDescription else text
            if (actual?.toString() == value) return this
        }
        for (index in 0 until childCount) {
            getChild(index)?.findVisibleAccessibilityNode(value, matchesContentDescription)?.let { return it }
        }
        return null
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.performClickOrAncestor(): Boolean {
        var candidate: android.view.accessibility.AccessibilityNodeInfo? = this
        repeat(8) {
            val current = candidate ?: return false
            if (current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
            candidate = current.parent
        }
        return false
    }

    private fun assertDynamicVisible(value: String) {
        // Navigation can briefly keep the outgoing list's identical title in the tree.
        // Wait for a visible match rather than asserting against the first hidden node.
        val matches = compose.onAllNodesWithText(value, substring = true, useUnmergedTree = true)
        compose.waitUntil(timeoutMillis = 15_000) {
            matches.fetchSemanticsNodes().indices.any { matches[it].isDisplayed() }
        }
        val visibleIndex = matches.fetchSemanticsNodes().indices.first { matches[it].isDisplayed() }
        matches[visibleIndex].assertIsDisplayed()
    }

    private fun waitForText(value: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(value, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun capture(name: String, waitForIdle: Boolean = true) {
        if (waitForIdle) {
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            // Compose idling can settle before the next real Android frame is
            // presented while a route's initial state is being loaded.
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
        } else {
            // This is used only after a page-specific semantic assertion for an
            // intentionally animating page, where global idleness is impossible.
            SystemClock.sleep(400)
        }
        writeScreenshot(name)
    }

    private fun captureFailure(name: String) {
        // A ComposeNotIdleException cannot use capture's normal idle barrier.
        // UiAutomation still gives us the current rendered frame for diagnosis.
        writeScreenshot(name)
    }

    private fun writeScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = targetContext.getExternalFilesDir(SCREENSHOT_DIRECTORY)
            ?: error("Target app has no external files directory")
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create screenshot directory: ${directory.absolutePath}"
        }
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Unable to encode screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        assertTrue("Screenshot was not written: ${file.absolutePath}", file.length() > 0L)
        println("RedesignNavigationSmokeTest screenshot=${file.absolutePath}")
    }

    /** Only remove fixtures created above; council's empty placeholder may remain. */
    private fun cleanupLocalFixtures() {
        runCatching {
            fixtureProviderId?.let { providerId ->
                val settingsStore: SettingsAggregator = getKoin().get()
                runBlocking {
                    settingsStore.update { current ->
                        current.copy(
                            providers = current.providers.filterNot { it.id == providerId },
                        )
                    }
                }
            }
        }
        runCatching {
            fixtureSkillName?.let { name ->
                val skillManager: SkillManager = getKoin().get()
                runBlocking { skillManager.deleteSkill(name) }
            }
        }
    }

    private fun text(id: Int): String = targetContext.getString(id)

    private companion object {
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val COLOR_MODE_PREF = "colorMode"
        const val COLOR_MODE_LIGHT = "LIGHT"
        const val SCREENSHOT_DIRECTORY = "ui-redesign-navigation"
    }
}
