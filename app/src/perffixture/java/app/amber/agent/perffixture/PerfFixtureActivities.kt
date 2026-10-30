package app.amber.agent.perffixture

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.amber.agent.LAST_CONVERSATION_ID_PREF
import app.amber.agent.LAUNCH_START_MODE_PREF
import app.amber.agent.LaunchStartMode
import app.amber.agent.RouteActivity
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.hooks.readStringPreference
import app.amber.feature.ui.utils.ChatPerfCommandBridge
import app.amber.feature.ui.utils.ChatPerfTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.context.GlobalContext

private data class PrepVerification(
    val seed: SeedResult,
    val roomNodes: Int,
    val scenarioPreference: String?,
    val lastConversationPreference: String?,
) {
    val persisted: Boolean
        get() = roomNodes == seed.nodeCount &&
            scenarioPreference == seed.conversationId.toString() &&
            lastConversationPreference == seed.conversationId.toString()
}

/** Separate-process seed pass. Killed before the measured cold entry. */
class PerfFixturePrepActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent?.getStringExtra("scenario")?.takeIf { it.isNotBlank() } ?: "S1"
        val runId = intent?.getStringExtra("runId")?.takeIf { it.isNotBlank() } ?: "no-run-id"
        val verifyOnly = intent?.getBooleanExtra("verifyOnly", false) == true
        setContentView(TextView(this).apply {
            text = "Preparing $scenario"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 18f
        })
        lifecycleScope.launch {
            if (verifyOnly) {
                withContext(Dispatchers.IO) {
                    val verification = runCatching {
                        PerfFixtureScenarios.verifyPersisted(
                            applicationContext, GlobalContext.get().get<ConversationRepository>(), scenario,
                        )
                    }
                    val json = verification.getOrNull() ?: JSONObject()
                    json.put("runId", runId).put("scenario", scenario)
                        .put("status", if (verification.isSuccess) "ok" else "failed")
                        .put("restartedReadback", true).put("pid", android.os.Process.myPid())
                    verification.exceptionOrNull()?.let {
                        json.put("errorClass", it::class.java.name)
                            .put("errorMessage", it.message).put("stackTrace", it.stackTraceToString())
                    }
                    val root = getExternalFilesDir("chat-perf") ?: filesDir
                    java.io.File(root, "verified-$scenario.json").writeText(json.toString(2))
                    Log.i(FIXTURE_LOG_TAG, "verified runId=$runId scenario=$scenario " +
                        "status=${json.getString("status")} restarted=true pid=${android.os.Process.myPid()}")
                }
                finish()
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val koin = GlobalContext.get()
                    val repository = koin.get<ConversationRepository>()
                    val seed = PerfFixtureScenarios.seed(
                        context = applicationContext,
                        repository = repository,
                        settingsStore = koin.get<SettingsAggregator>(),
                        scenario = scenario,
                    )
                    // Verify through the Activity context as well: this is the
                    // same ContextImpl type the launcher/RouteActivity use.
                    val readBack = repository.getConversationById(seed.conversationId)
                    PrepVerification(
                        seed = seed,
                        roomNodes = readBack?.messageNodes?.size ?: -1,
                        scenarioPreference = this@PerfFixturePrepActivity.readStringPreference(
                            "perf_scenario_id_$scenario",
                        ),
                        lastConversationPreference = this@PerfFixturePrepActivity.readStringPreference(
                            LAST_CONVERSATION_ID_PREF,
                        ),
                    )
                }
            }
            withContext(Dispatchers.IO) {
                val json = JSONObject().apply {
                    put("schema", 1)
                    put("package", packageName)
                    put("scenario", scenario)
                    put("runId", runId)
                    put("pid", android.os.Process.myPid())
                    val expected = java.io.File(applicationContext.filesDir, "expected-$scenario.json")
                    if (expected.isFile) put("expectedRows", JSONObject(expected.readText()))
                    val verification = result.getOrNull()
                    put(
                        "status",
                        if (result.isSuccess && verification?.persisted == true) "ok" else "failed",
                    )
                    verification?.let { verified ->
                        val seed = verified.seed
                        put("conversationId", seed.conversationId.toString())
                        put("nodeCount", seed.nodeCount)
                        put("contentHash", seed.contentHash)
                        put("roomNodes", verified.roomNodes)
                        put("persisted", verified.persisted)
                        put("scenarioPreference", verified.scenarioPreference)
                        put("lastConversationPreference", verified.lastConversationPreference)
                        seed.secondaryConversationId?.let {
                            put("secondaryConversationId", it.toString())
                        }
                    }
                    result.exceptionOrNull()?.let { error ->
                        put("errorClass", error::class.java.name)
                        put("errorMessage", error.message?.take(200).orEmpty())
                        put("stackTrace", error.stackTraceToString())
                    }
                    if (result.isSuccess && verification?.persisted != true) {
                        put("errorMessage", "Room or preference readback failed")
                    }
                }
                writeFixtureMarker(applicationContext, scenario, json)
                android.util.Log.i(
                    "AmberPerfFixture",
                    "prepared runId=$runId scenario=$scenario " +
                        "status=${json.getString("status")} " +
                        "persisted=${json.optBoolean("persisted")}",
                )
            }
            finish()
        }
    }
}

/**
 * The fixed history-entry UI. A tap is the real EntryRequested/T0; the
 * production RouteActivity then runs ChatPage → ChatVM → Room in this same
 * process. No production package is overwritten or given fixture data.
 */
private const val LAUNCHER_RUN_ID_PREF = "perf_launcher_run_id"
private const val LAUNCHER_SCENARIO_PREF = "perf_launcher_scenario"
private const val FIXTURE_LOG_TAG = "AmberPerfFixture"

class PerfFixtureLauncherActivity : Activity() {
    private fun rememberEntryRequest(scenario: String, runId: String?) {
        if (runId.isNullOrBlank()) return
        writeFixtureString(LAUNCHER_SCENARIO_PREF, scenario)
        writeFixtureString(LAUNCHER_RUN_ID_PREF, runId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        rememberEntryRequest(
            intent.getStringExtra("scenario")?.takeIf { it.isNotBlank() } ?: "S1",
            intent.getStringExtra("runId")?.takeIf { it.isNotBlank() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent?.getStringExtra("scenario")?.takeIf { it.isNotBlank() } ?: "S1"
        val runId = intent?.getStringExtra("runId")?.takeIf { it.isNotBlank() }
        rememberEntryRequest(scenario, runId)
        val conversationId = readStringPreference(
            "perf_scenario_id_$scenario",
            defaultValue = null,
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Perf fixture\n$scenario\n${conversationId ?: "missing seed"}"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 18f
            setPadding(0, 0, 0, 32)
        })
        val open = Button(this).apply {
            text = "Open history"
            isAllCaps = false
            contentDescription = "perf_open_history"
            isEnabled = conversationId != null
            setOnClickListener {
                // The activity can be resumed by BACK without a new intent.
                // Always read the request written by the script handshake so a
                // warm run cannot reuse the cold runId captured at onCreate.
                val activeScenario = readStringPreference(LAUNCHER_SCENARIO_PREF, defaultValue = null)
                    ?.takeIf { it.isNotBlank() } ?: scenario
                val activeRunId = readStringPreference(LAUNCHER_RUN_ID_PREF, defaultValue = null)
                    ?.takeIf { it.isNotBlank() } ?: runId ?: "missing-run-id"
                val activeConversationId = readStringPreference(
                    "perf_scenario_id_$activeScenario",
                    defaultValue = null,
                ) ?: conversationId
                if (activeConversationId == null) return@setOnClickListener
                ChatPerfCommandBridge.prepareFixtureRun(activeRunId)
                ChatPerfTrace.startRun(
                    this@PerfFixtureLauncherActivity,
                    activeScenario,
                    activeRunId,
                )
                val current = ChatPerfTrace.currentRunId()
                Log.i(
                    FIXTURE_LOG_TAG,
                    "launcher-entry runId=$activeRunId scenario=$activeScenario " +
                        "t0MonoNs=${SystemClock.elapsedRealtimeNanos()} current=$current",
                )
                writeFixtureString(LAST_CONVERSATION_ID_PREF, activeConversationId)
                writeFixtureString(LAUNCH_START_MODE_PREF, LaunchStartMode.LAST_SESSION.name)
                configureStreamingGatewayIfNeeded(activeScenario)
                val target = Intent(this@PerfFixtureLauncherActivity, RouteActivity::class.java).apply {
                    if (PerfFixtureScenarios.spec(activeScenario).deepLink) {
                        putExtra("conversationId", activeConversationId)
                    }
                }
                startActivity(target)
            }
        }
        root.addView(
            open,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(root)
    }

    private fun configureStreamingGatewayIfNeeded(scenario: String) {
        if (scenario != "S7") return
        val gateway = runCatching {
            GlobalContext.get().get<PerfFixtureTextGateway>()
        }.getOrNull() ?: return
        gateway.delayMs = PerfFixtureStreamSpec.DELAY_MS
        gateway.reply = PerfFixtureStreamSpec.reply
    }
}
