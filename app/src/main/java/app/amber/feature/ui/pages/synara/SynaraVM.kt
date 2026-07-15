package app.amber.feature.ui.pages.synara

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SynaraUiState(
    val draft: SynaraConnection = SynaraConnection(),
    val checking: Boolean = false,
    val lastCheckMessage: String? = null,
    val lastCheckOk: Boolean? = null,
)

class SynaraVM(
    private val store: SynaraConnectionStore,
) : ViewModel() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val _ui = MutableStateFlow(SynaraUiState())
    val ui: StateFlow<SynaraUiState> = _ui.asStateFlow()

    val saved: StateFlow<SynaraConnection> = store.connectionFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SynaraConnection())

    init {
        viewModelScope.launch {
            // Seed the form once from disk; subsequent typing is local until save/test.
            val initial = store.connectionFlow.first()
            _ui.update { it.copy(draft = initial) }
        }
    }

    fun updateDraft(transform: (SynaraConnection) -> SynaraConnection) {
        _ui.update { it.copy(draft = transform(it.draft), lastCheckMessage = null, lastCheckOk = null) }
    }

    fun reportError(message: String) {
        _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = message) }
    }

    fun save(onSaved: (SynaraConnection) -> Unit = {}) {
        val draft = _ui.value.draft
        val error = draft.validationError()
        if (error != null) {
            _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = error) }
            return
        }
        viewModelScope.launch {
            store.save(draft)
            onSaved(draft)
        }
    }

    fun testConnection() {
        val draft = _ui.value.draft
        val error = draft.validationError()
        if (error != null) {
            _ui.update { it.copy(lastCheckOk = false, lastCheckMessage = error) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(checking = true, lastCheckMessage = null, lastCheckOk = null) }
            val result = withContext(Dispatchers.IO) { probeHealth(draft) }
            _ui.update {
                it.copy(
                    checking = false,
                    lastCheckOk = result.isSuccess,
                    lastCheckMessage = result.getOrElse { e -> e.message ?: "连接失败" },
                )
            }
            if (result.isSuccess) {
                store.save(draft)
            }
        }
    }

    private fun probeHealth(connection: SynaraConnection): Result<String> {
        return runCatching {
            val healthRequest = Request.Builder()
                .url(connection.healthUrl())
                .get()
                .header("Accept", "application/json")
                .build()
            val healthBody = client.newCall(healthRequest).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error("HTTP ${response.code}")
                body
            }
            val status = runCatching { JSONObject(healthBody).optString("status") }.getOrDefault("")
            if (!status.equals("ok", ignoreCase = true)) {
                error("health 未就绪（status=${status.ifBlank { "empty" }}）")
            }
            "健康检查通过（status=ok） · ${connection.httpBaseUrl()}"
        }
    }
}
