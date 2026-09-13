package app.amber.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshProfile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Emulator-only canary for the real Agent Runtime settings route.
 *
 * Run with [AmberAgentUiSmokeTestRunner] and a selected emulator, for example:
 * `./gradlew :app:connectedDebugAndroidTest -PuiSmokeTest=true
 * -Pandroid.testInstrumentationRunnerArguments.class=app.amber.agent.SandboxSettingsSmokeTest`.
 * PNGs are written below the target app external files directory in
 * `sandbox-settings-smoke` for visual QA (`01-default.png`,
 * `02-more-settings.png`, `03-advanced-expanded.png`, and
 * `04-ssh-command-expanded.png`).
 *
 * The test creates only two metadata-only SSH profiles with generated UUIDs.
 * It never reads or prints existing credentials and never probes, executes,
 * accepts a host key, installs, repairs, or deletes an item from the UI.
 */
@RunWith(AndroidJUnit4::class)
class SandboxSettingsSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
    private val originalColorMode = preferences.getString(COLOR_MODE_PREF, null)

    private val fixtureIds = mutableListOf<String>()
    private var originalDefaultProfileId: String? = null
    private var fixtureSetupSkipped = false
    private var profileSurfaceExpected = false
    private var profileMenuTargetName: String? = null

    @get:Rule
    val compose = createEmptyComposeRule()

    init {
        // RouteActivity reads these before composing. Start from the real home
        // route even when an earlier test left a different launch preference.
        preferences.edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name)
            .putString(COLOR_MODE_PREF, COLOR_MODE_LIGHT)
            .apply()
    }

    @Test
    fun sandboxSettingsNavigationAndDisclosureCanary() {
        assumeTrue(
            "Run with -PuiSmokeTest=true so the real AmberAgentApp is installed",
            targetContext.applicationContext is AmberAgentApp,
        )
        assumeEmulator()

        try {
            prepareLocalFixtures()
            ActivityScenario.launch(RouteActivity::class.java).use {
                try {
                    openSandboxPage()
                    assertDefaultSurface()
                    // The assertions above may scroll to lazy disclosures to
                    // prove their collapsed content is absent. Restore the
                    // top of the real page before saving the default frame.
                    bringTextIntoView(text(R.string.setting_sandbox_workspace_section))
                    capture("01-default")

                    // The small visible capsule must still accept taps in its
                    // surrounding touch padding, without saving a new profile.
                    val add = compose.onNode(hasText(text(R.string.setting_sandbox_ssh_add)) and hasClickAction())
                    val density = targetContext.resources.displayMetrics.density
                    val bounds = add.fetchSemanticsNode().boundsInRoot
                    val edgeY = if (bounds.height > 36f * density) 4f * density else -4f * density
                    add.performTouchInput { click(Offset(center.x, edgeY)) }
                    assertVisible(R.string.setting_sandbox_ssh_editor_new)
                    clickActionText(text(R.string.setting_sandbox_ssh_cancel))

                    if (profileSurfaceExpected) {
                        openFirstProfileMenu()
                        assertVisible(R.string.setting_sandbox_ssh_edit)
                        assertVisible(R.string.setting_sandbox_ssh_delete)
                        assertVisible(R.string.setting_sandbox_ssh_probe)
                        // Dismiss the popup without selecting a destructive or
                        // network action. The next assertion keeps the route live.
                        androidx.test.espresso.Espresso.pressBack()
                        compose.waitForIdle()
                        assertVisible(R.string.setting_sandbox_page_title)
                    } else {
                        println(
                            "SandboxSettingsSmokeTest gap=ssh-profile-fixture " +
                                "state already contained profiles with no restorable default; " +
                                "profile menu and command screenshot skipped",
                        )
                    }

                    clickActionText(text(R.string.setting_sandbox_more_settings))
                    assertVisible(R.string.setting_sandbox_more_settings)
                    assertVisible(R.string.setting_chat_storage_maintenance)
                    assertVisible(R.string.setting_provider_page_advanced_settings)
                    if (profileSurfaceExpected) {
                        assertVisible(R.string.setting_sandbox_ssh_command_title)
                    }
                    capture("02-more-settings")

                    clickDisclosure(R.string.setting_provider_page_advanced_settings)
                    assertVisible(R.string.setting_sandbox_terminal_jobs_title)
                    assertVisible(R.string.setting_sandbox_terminal_output_title)
                    capture("03-advanced-expanded")

                    if (profileSurfaceExpected) {
                        clickDisclosure(R.string.setting_sandbox_ssh_command_title)
                        assertVisible(R.string.setting_sandbox_ssh_command_label)
                        assertVisible(R.string.setting_sandbox_ssh_run)
                        assertVisible(R.string.setting_sandbox_ssh_stop)
                        capture("04-ssh-command-expanded")
                    }
                } catch (failure: Throwable) {
                    runCatching { writeScreenshot("failure-${failure::class.java.simpleName}") }
                    throw failure
                }
            }
        } finally {
            cleanupLocalFixtures()
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
            "This canary creates disposable SSH metadata only on an Android emulator; " +
                "fingerprint=${Build.FINGERPRINT}, model=${Build.MODEL}",
            isEmulator,
        )
    }

    private fun prepareLocalFixtures() {
        val store: SshProfileStore = getKoin().get()
        val before = runBlocking { store.snapshot() }
        originalDefaultProfileId = before.defaultProfileId
        profileSurfaceExpected = before.profiles.isNotEmpty()
        profileMenuTargetName = before.profiles.firstOrNull()?.name

        // SshProfileStore keeps a null default only for an empty state. If a
        // pre-existing malformed state has profiles but no default, do not
        // mutate it because its public API cannot restore that exact state.
        val defaultCanBeRestored = before.profiles.isEmpty() ||
            (before.defaultProfileId != null && before.profiles.any { it.id == before.defaultProfileId })
        if (!defaultCanBeRestored) {
            fixtureSetupSkipped = true
            return
        }

        val now = System.currentTimeMillis()
        val fixtures = listOf(
            SshProfile(
                id = UUID.randomUUID().toString(),
                name = "Mac mini",
                host = "example.invalid",
                port = 2222,
                username = "canary",
                authMethod = SshAuthMethod.PASSWORD,
                createdAtMs = now,
                updatedAtMs = now,
            ),
            SshProfile(
                id = UUID.randomUUID().toString(),
                name = "Japan VPS · 工作环境",
                host = "workspace-gateway.example.invalid",
                port = 2223,
                username = "canary",
                authMethod = SshAuthMethod.PASSWORD,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        runBlocking {
            fixtures.forEach { profile ->
                store.save(profile)
                fixtureIds += profile.id
            }
            val published = store.snapshot().profiles.map { it.id }.toSet()
            check(fixtureIds.all { it in published }) { "SSH fixture publication was not observable" }
        }
        profileSurfaceExpected = true
        profileMenuTargetName = fixtures.first().name
    }

    private fun cleanupLocalFixtures() {
        if (fixtureIds.isEmpty()) return
        val store: SshProfileStore = getKoin().get()
        var firstFailure: Throwable? = null
        runBlocking {
            fixtureIds.toList().forEach { id ->
                runCatching { store.delete(id) }.onFailure { error ->
                    if (firstFailure == null) firstFailure = error
                }
            }
            originalDefaultProfileId?.let { originalId ->
                val state = store.snapshot()
                if (state.profiles.any { it.id == originalId } && state.defaultProfileId != originalId) {
                    runCatching { store.selectDefault(originalId) }.onFailure { error ->
                        if (firstFailure == null) firstFailure = error
                    }
                }
            }
            val remaining = store.snapshot().profiles.filter { it.id in fixtureIds }
            if (remaining.isNotEmpty() && firstFailure == null) {
                firstFailure = AssertionError("SSH fixture cleanup left ${remaining.size} profile(s)")
            }
        }
        fixtureIds.clear()
        firstFailure?.let { throw it }
    }

    private fun openSandboxPage() {
        waitForVisibleContentDescription(text(R.string.settings))
        compose.onNodeWithContentDescription(text(R.string.settings))
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        clickSettingRow(R.string.setting_page_agent_execution)
        waitForVisibleText(text(R.string.setting_page_agent_sandbox))
        clickSettingRow(R.string.setting_page_agent_sandbox)
        waitForVisibleText(text(R.string.setting_sandbox_page_title))
    }

    private fun assertDefaultSurface() {
        assertVisible(R.string.setting_sandbox_page_title)
        assertVisible(R.string.setting_sandbox_workspace_section)
        assertVisible(R.string.setting_sandbox_terminal_runtime_title)
        assertVisible(R.string.setting_sandbox_ssh_section)
        assertVisible(R.string.setting_sandbox_more_settings)

        // The three detail sections live in a modal sheet and must not be
        // composed on the compact page.
        assertNoComposedText(R.string.setting_chat_storage_maintenance)
        assertNoComposedText(R.string.setting_provider_page_advanced_settings)
        assertNoComposedText(R.string.setting_sandbox_ssh_command_title)
        assertNoComposedText(R.string.setting_sandbox_terminal_jobs_title)
        assertNoComposedText(R.string.setting_sandbox_terminal_output_title)
        assertNoComposedText(R.string.setting_sandbox_runtime_install_repair)
        if (profileSurfaceExpected) {
            assertNoComposedText(R.string.setting_sandbox_ssh_command_label)
            assertNoComposedText(R.string.setting_sandbox_ssh_run)
            assertNoComposedText(R.string.setting_sandbox_ssh_stop)
        }

        // Exercise the real compact-card menu after the default screenshot is
        // captured. This leaves every menu action read-only for the canary.
        println(
            "SandboxSettingsSmokeTest defaultSurface fixtureSetupSkipped=$fixtureSetupSkipped " +
                "sshProfiles=$profileSurfaceExpected",
        )
    }

    private fun openFirstProfileMenu() {
        val moreActions = text(R.string.skills_page_more_actions)
        val profileName = profileMenuTargetName
            ?: error("No SSH profile was available for the more-actions canary")
        val profileTitle = hasText(profileName, substring = false)
        val titleNodes = compose.onAllNodes(profileTitle, useUnmergedTree = true)
        if (titleNodes.fetchSemanticsNodes().indices.none { titleNodes[it].isDisplayed() }) {
            compose.onNode(hasScrollAction()).performScrollToNode(profileTitle)
        }
        val profileRow = hasClickAction() and (
            hasText(profileName, substring = false) or hasAnyDescendant(profileTitle)
        )
        val target = hasContentDescription(moreActions, substring = false) and
            hasAnyAncestor(profileRow)
        val candidates = compose.onAllNodes(target)
        check(candidates.fetchSemanticsNodes().isNotEmpty()) {
            "SSH profile more-actions button was not reachable for $profileName"
        }
        candidates
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun clickSettingRow(id: Int) = clickActionText(text(id))

    private fun clickDisclosure(id: Int) = clickActionText(text(id))

    private fun clickActionText(value: String) {
        val target = hasText(value, substring = false) and hasClickAction()
        val matches = compose.onAllNodes(target)
        if (matches.fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(target)
        } else if (matches.fetchSemanticsNodes().indices.none { matches[it].isDisplayed() }) {
            matches.onFirst().performScrollTo()
        }
        compose.onNode(target)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun assertVisible(id: Int) = assertVisibleText(text(id))

    private fun assertVisibleText(value: String) {
        bringTextIntoView(value)
        waitForComposedText(value)
        waitForVisibleText(value)
        val matches = compose.onAllNodesWithText(value, substring = false, useUnmergedTree = true)
        val visible = matches.fetchSemanticsNodes().indices.firstOrNull { matches[it].isDisplayed() }
        check(visible != null) { "Expected visible text: $value" }
        matches[visible].assertIsDisplayed()
    }

    private fun assertNoComposedText(id: Int) {
        val value = text(id)
        val nodes = compose.onAllNodesWithText(
            value,
            substring = false,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Expected collapsed content not to be composed: $value", nodes.isEmpty())
    }

    private fun waitForComposedText(value: String) {
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            compose.onAllNodesWithText(value, substring = false, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun bringTextIntoView(value: String) {
        val target = hasText(value, substring = false)
        val matches = compose.onAllNodes(target)
        if (matches.fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(target)
        } else if (matches.fetchSemanticsNodes().indices.none { matches[it].isDisplayed() }) {
            matches.onFirst().performScrollTo()
        }
        compose.waitForIdle()
    }

    private fun waitForVisibleText(value: String) {
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            val matches = compose.onAllNodesWithText(value, substring = false, useUnmergedTree = true)
            matches.fetchSemanticsNodes().indices.any { matches[it].isDisplayed() }
        }
    }

    private fun waitForVisibleContentDescription(value: String) {
        compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MS) {
            val matches = compose.onAllNodesWithContentDescription(value, useUnmergedTree = true)
            matches.fetchSemanticsNodes().indices.any { matches[it].isDisplayed() }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
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
        println("SandboxSettingsSmokeTest screenshot=${file.absolutePath}")
    }

    private fun text(id: Int): String = targetContext.getString(id)

    private companion object {
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val COLOR_MODE_PREF = "colorMode"
        const val COLOR_MODE_LIGHT = "LIGHT"
        const val SCREENSHOT_DIRECTORY = "sandbox-settings-smoke"
        const val WAIT_TIMEOUT_MS = 15_000L
    }
}
