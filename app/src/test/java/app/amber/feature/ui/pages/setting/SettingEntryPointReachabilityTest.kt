package app.amber.feature.ui.pages.setting

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4 / A4.1 entry-point guard.
 *
 * `Screen.SettingExperimentalMacGateway` was routed and implemented but nothing navigated to it:
 * the only path went through `Screen.SettingExperimental`, a container page registered in the nav
 * graph that no call site ever opened. The whole Mac Gateway feature was therefore unreachable from
 * the product, which no unit test noticed because each page compiled and rendered on its own.
 *
 * This is a source-level check on purpose: the reachability property lives in the navigation call
 * sites, and any Compose UI test for it would be a slower duplicate of the same string search.
 */
class SettingEntryPointReachabilityTest {
    /**
     * Gradle runs unit tests with the module directory (`app/`) as the working directory, so a
     * repo-relative path has to walk up until the settings file is found.
     */
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir ?: error("repository root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    private fun source(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        check(file.exists()) { "source file not found: ${file.absolutePath}" }
        return file.readText()
    }

    @Test
    fun macGatewayPageIsReachableFromTheTopLevelSettingsPage() {
        val settings = source("app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt")
        assertTrue(
            "SettingPage must offer a route into Mac Gateway, otherwise the page is unreachable",
            settings.contains("navigate(Screen.SettingExperimentalMacGateway)"),
        )
    }

    @Test
    fun everyRegisteredExperimentalChildIsReachableFromSomeCallSite() {
        val settingsPages = File(repoRoot, "app/src/main/java/app/amber/feature/ui/pages/setting")
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }
        // These four are the experimental children a user is meant to open directly.
        listOf(
            "SettingExperimentalMacGateway",
            "SettingExperimentalICloud",
            "SettingExperimentalSubAgent",
            "SettingExperimentalWebMount",
        ).forEach { target ->
            assertTrue(
                "$target is registered in the nav graph but no page navigates to it",
                settingsPages.contains("navigate(Screen.$target)"),
            )
        }
    }

    @Test
    fun macGatewayTitleStringIsNotCollapsedToADuplicateOfItsDescription() {
        val strings = source("app/src/main/res/values/strings.xml")
        assertTrue(
            "the settings row needs its own label",
            strings.contains("""<string name="setting_page_mac_gateway">"""),
        )
        assertTrue(
            "the settings row needs its own short description",
            strings.contains("""<string name="setting_page_mac_gateway_desc">"""),
        )
    }
}
