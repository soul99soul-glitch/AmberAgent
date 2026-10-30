package app.amber.agent.perffixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.LAST_CONVERSATION_ID_PREF
import app.amber.agent.RouteActivity
import app.amber.agent.data.db.AppDatabase
import app.amber.core.repository.ConversationRepository
import app.amber.feature.ui.hooks.readStringPreference
import app.amber.feature.ui.utils.ChatPerfImageModelOverride
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class PerfFixtureIsolationTest {
    private fun assertUnder(label: String, candidate: File, root: File) {
        val path = candidate.canonicalFile
        val base = root.canonicalFile
        assertTrue(
            "$label: $path is not under $base",
            path == base || path.path.startsWith(base.path + File.separator),
        )
    }

    @Test
    fun fixtureApplicationUsesIndependentPackageAndStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext
        val packageName = "app.amber.agent.perffixture"
        val dataDir = File(context.applicationInfo.dataDir).canonicalFile
        assertEquals(packageName, context.packageName)
        assertEquals(PerfFixtureApplication::class.java.name, app::class.java.name)
        assertEquals(packageName, context.applicationInfo.packageName)
        assertEquals(packageName, dataDir.name)
        assertEquals(dataDir, File(app.applicationInfo.dataDir).canonicalFile)
        assertEquals(context.applicationInfo.uid, app.applicationInfo.uid)
        assertEquals(android.os.Process.myUid(), app.applicationInfo.uid)
        assertTrue("fixture UID must not be root", app.applicationInfo.uid != 0)

        // Ordinary target ContextImpl paths: these do NOT go through the
        // Application overrides. They must still be inside this package only.
        assertEquals(File(dataDir, "files").canonicalPath, context.filesDir.canonicalPath)
        assertEquals(File(dataDir, "cache").canonicalPath, context.cacheDir.canonicalPath)
        assertUnder(
            "target no-backup",
            context.noBackupFilesDir,
            dataDir,
        )
        assertUnder(
            "target database",
            context.getDatabasePath("fixture.db"),
            File(dataDir, "databases"),
        )
        val targetInternal = context.getDir("internal", 0)
        assertUnder("target getDir", targetInternal, dataDir)
        val externalRoot = context.getExternalFilesDir(null)
        assertTrue("external files dir missing", externalRoot != null)
        assertUnder(
            "target external files",
            context.getExternalFilesDir("chat-perf")!!,
            externalRoot!!,
        )

        // Application-overridden paths: the fixture root is deliberate here.
        val fixtureRoot = app.filesDir.parentFile!!.canonicalFile
        assertTrue(
            "Application fixture root must be inside the package data dir",
            fixtureRoot.path.startsWith(dataDir.path + File.separator),
        )
        assertEquals(File(fixtureRoot, "files").canonicalPath, app.filesDir.canonicalPath)
        assertEquals(File(fixtureRoot, "cache").canonicalPath, app.cacheDir.canonicalPath)
        assertEquals(
            File(fixtureRoot, "no-backup").canonicalPath,
            app.noBackupFilesDir.canonicalPath,
        )
        assertUnder(
            "application database",
            app.getDatabasePath("fixture.db"),
            File(fixtureRoot, "databases"),
        )
        // getDir is intentionally not overridden: do not claim a custom root.
        val appInternal = app.getDir("internal", 0)
        assertUnder("application getDir", appInternal, dataDir)
        assertTrue(
            "getDir must not be misreported as the custom fixture root",
            !appInternal.canonicalPath.startsWith(fixtureRoot.path + File.separator),
        )

        // WorkManager/Room production path uses the Application context.
        val database = GlobalContext.get().get<AppDatabase>()
        val openPath = database.openHelper.readableDatabase.path
        assertTrue("Room openPath missing", openPath != null)
        assertUnder("Room openPath", File(openPath!!), fixtureRoot)

        // SharedPreferences: Application and target ContextImpl are the same
        // file, with no Application-only name prefix.
        val appPrefs = app.getSharedPreferences(
            "probe", android.content.Context.MODE_PRIVATE,
        )
        val contextPrefs = context.getSharedPreferences(
            "probe", android.content.Context.MODE_PRIVATE,
        )
        assertTrue(appPrefs.edit().putString("namespace", "fixture").commit())
        assertEquals("fixture", contextPrefs.getString("namespace", null))
        val prefsFile = File(dataDir, "shared_prefs/probe.xml").canonicalFile
        assertTrue(prefsFile.absolutePath, prefsFile.isFile)
        assertUnder(
            "preferences",
            prefsFile,
            File(dataDir, "shared_prefs"),
        )

        // Production classes/DI are used; only storage + provider are replaced.
        assertTrue(ChatPerfImageModelOverride.resolve != null)
        context.packageManager.getActivityInfo(
            android.content.ComponentName(context, RouteActivity::class.java),
            0,
        )
    }

    @Test
    fun productionRepositoryReadsSeededFixtureConversation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsAggregator>()
        val seed = PerfFixtureScenarios.seed(
            context = context.applicationContext,
            repository = repository,
            settingsStore = settings,
            scenario = "S1",
        )
        try {
            val read = repository.getConversationById(seed.conversationId)
            assertEquals(seed.nodeCount, read?.messageNodes?.size)
            // Seed wrote through Application; the launcher/RouteActivity read
            // through an Activity-like ContextImpl. Both must be the same file.
            assertEquals(
                seed.conversationId.toString(),
                context.readStringPreference("perf_scenario_id_S1"),
            )
            assertEquals(
                seed.conversationId.toString(),
                context.readStringPreference(LAST_CONVERSATION_ID_PREF),
            )
        } finally {
            repository.getConversationById(seed.conversationId)?.let {
                repository.deleteConversation(it, deferCleanup = true)
            }
        }
    }

    @Test
    fun scenarioMatrixIsStableAndCoversRequiredShapes() {
        assertEquals(
            listOf("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8"),
            PerfFixtureScenarios.specs.map { it.id },
        )
        assertEquals(120, PerfFixtureScenarios.spec("S2").nodeCount)
        assertEquals(1_000, PerfFixtureScenarios.spec("S3").nodeCount)
        assertTrue(PerfFixtureScenarios.spec("S7").streaming)
        assertTrue(PerfFixtureScenarios.spec("S8").deepLink)
    }
}
