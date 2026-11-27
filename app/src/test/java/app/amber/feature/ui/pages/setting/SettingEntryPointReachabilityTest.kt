package app.amber.feature.ui.pages.setting

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

/**
 * Checks that a registered settings destination is also wired to an actual settings entry.
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
    fun multiDevicePageIsReachableFromGeneralSettings() {
        val settings = source("app/src/main/java/app/amber/feature/ui/pages/setting/SettingPage.kt")
        val general = settings.substringAfter("item(\"generalSettings\")", "")
            .substringBefore("item(\"agentRuntimeSettings\")", "")
        assertTrue(
            "General settings must offer an Amber Multi-device row wired to the gateway destination",
            groupedEntry("SettingExperimentalMacGateway", "setting_home_multi_device_title")
                .containsMatchIn(general),
        )
        assertTrue(
            "Clicking a grouped settings row must navigate to that row's destination",
            Regex("""SettingHomeRow\(entry,[^\n]+\)\s*\{\s*navController\.navigate\(entry\.screen\)\s*}""")
                .containsMatchIn(settings),
        )
    }

    @Test
    fun everyRegisteredExperimentalChildIsReachableFromSomeCallSite() {
        val settingsPages = File(repoRoot, "app/src/main/java/app/amber/feature/ui/pages/setting")
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }
        val registry = source("app/src/main/java/app/amber/agent/AppNavigationEntries.kt")
        // These four are the experimental children a user is meant to open directly.
        listOf(
            "SettingExperimentalMacGateway",
            "SettingExperimentalICloud",
            "SettingExperimentalSubAgent",
            "SettingExperimentalWebMount",
        ).forEach { target ->
            assertTrue("$target must be registered", registry.contains("entry<Screen.$target>"))
            assertTrue(
                "$target is registered in the nav graph but no settings entry opens it",
                settingsPages.contains("navigate(Screen.$target)") || groupedEntry(target).containsMatchIn(settingsPages),
            )
        }
    }

    @Test
    fun multiDeviceRowHasDistinctTitleAndSubtitle() {
        val strings = File(repoRoot, "app/src/main/res/values/strings_settings_home.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings)
        val xpath = XPathFactory.newInstance().newXPath()
        val title = xpath.evaluate("/resources/string[@name='setting_home_multi_device_title']", document).trim()
        val subtitle = xpath.evaluate("/resources/string[@name='setting_home_multi_device_subtitle']", document).trim()
        assertTrue("The row requires a title and a subtitle", title.isNotEmpty() && subtitle.isNotEmpty())
        assertNotEquals(title, subtitle)
    }

    private fun groupedEntry(target: String, label: String = "\\w+"): Regex =
        Regex("""SettingHomeEntry\(\s*R\.string\.$label,\s*Lucide\.\w+,\s*Screen\.$target\b""")
}
