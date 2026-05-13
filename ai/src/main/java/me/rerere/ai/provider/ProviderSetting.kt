package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

/**
 * Auth mode for [ProviderSetting.Google]. Mirrors [OpenAIAuthMode]'s "API key vs managed
 * OAuth" split so the editor UI is symmetric across brands.
 *
 *  - API_KEY               : user pastes a Generative Language API key (free tier or
 *                            paid Vertex API key) into the existing `apiKey` field.
 *                            All existing Google-related fields stay editable —
 *                            including the existing `vertexAI` and `useServiceAccount`
 *                            sub-toggles, which currently live INSIDE API_KEY rather
 *                            than as first-class enum values. If we ever want Vertex
 *                            AI / Service Account as standalone authModes the schema
 *                            migration would have to map old `vertexAI=true` rows to
 *                            the new enum constants on read.
 *  - GEMINI_CODE_ASSIST_OAUTH : "Sign in with Google" picker route. Routes calls through
 *                            cloudcode-pa.googleapis.com (Gemini Code Assist for
 *                            Individuals free tier — same backend gemini-cli uses).
 *                            Token lives in GoogleGeminiAuthStore, baseUrl is pinned
 *                            via [fixedBaseUrl]; apiKey / vertexAI / service-account
 *                            fields are hidden by the editor and scrubbed on mode entry.
 */
@Serializable
enum class GoogleAuthMode {
    @SerialName("api_key")
    API_KEY,

    @SerialName("gemini_code_assist_oauth")
    GEMINI_CODE_ASSIST_OAUTH,
}

/**
 * Fixed base URL for OAuth-managed Google auth modes. Returns null for API_KEY (where
 * the user-editable baseUrl owns the choice). Used by the picker's Gemini OAuth quick-
 * start factory and ProviderConfigureGoogle's segmented mode switch so the same URL
 * literal isn't duplicated across both call sites.
 */
fun GoogleAuthMode.fixedBaseUrl(): String? = when (this) {
    GoogleAuthMode.API_KEY -> null
    // gemini-cli reaches its free-tier endpoint at this base; the actual paths are
    // v1internal:loadCodeAssist / v1internal:onboardUser / v1internal:streamGenerateContent
    // — the provider layer appends those when it knows it's running in OAuth mode.
    GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> "https://cloudcode-pa.googleapis.com"
}

@Serializable
enum class OpenAIAuthMode {
    @SerialName("api_key")
    API_KEY,

    @SerialName("codex_oauth")
    CODEX_OAUTH,

    @SerialName("zhipu_coding_plan")
    ZHIPU_CODING_PLAN,

    @SerialName("kimi_coding_plan")
    KIMI_CODING_PLAN,

    @SerialName("mimo_coding_plan")
    MIMO_CODING_PLAN,
}

/**
 * Brand-tag for OpenAI-compatible providers. Decides which auth modes are visible in the
 * settings UI and which base URLs the Coding Plan modes should pin. Lets us avoid showing
 * "OpenAI Codex OAuth" on a DeepSeek provider, etc.
 *
 *  - GENERIC : user-defined OpenAI-compatible (only API_KEY)
 *  - OPENAI  : OpenAI official (API_KEY + CODEX_OAUTH)
 *  - DEEPSEEK: DeepSeek (only API_KEY today; field reserved for future Coding Plan)
 *  - ZHIPU   : 智谱 GLM (API_KEY + ZHIPU_CODING_PLAN)
 *  - KIMI    : Kimi / Moonshot (API_KEY + KIMI_CODING_PLAN)
 *  - MIMO    : 小米 MiMo (API_KEY + MIMO_CODING_PLAN)
 */
@Serializable
enum class OpenAIBrand {
    @SerialName("generic")
    GENERIC,

    @SerialName("openai")
    OPENAI,

    @SerialName("deepseek")
    DEEPSEEK,

    @SerialName("zhipu")
    ZHIPU,

    @SerialName("kimi")
    KIMI,

    @SerialName("mimo")
    MIMO,
}

fun OpenAIBrand.availableAuthModes(): List<OpenAIAuthMode> = when (this) {
    OpenAIBrand.GENERIC, OpenAIBrand.DEEPSEEK -> listOf(OpenAIAuthMode.API_KEY)
    OpenAIBrand.OPENAI -> listOf(OpenAIAuthMode.API_KEY, OpenAIAuthMode.CODEX_OAUTH)
    OpenAIBrand.ZHIPU -> listOf(OpenAIAuthMode.API_KEY, OpenAIAuthMode.ZHIPU_CODING_PLAN)
    OpenAIBrand.KIMI -> listOf(OpenAIAuthMode.API_KEY, OpenAIAuthMode.KIMI_CODING_PLAN)
    OpenAIBrand.MIMO -> listOf(OpenAIAuthMode.API_KEY, OpenAIAuthMode.MIMO_CODING_PLAN)
}

/**
 * Fixed base URL for "managed" auth modes (Codex OAuth, the various Coding Plans).
 * Returns null for API_KEY (user-editable). When non-null, the UI auto-fills baseUrl on
 * mode switch and disables the baseUrl text field.
 */
fun OpenAIAuthMode.fixedBaseUrl(): String? = when (this) {
    OpenAIAuthMode.API_KEY -> null
    OpenAIAuthMode.CODEX_OAUTH -> "https://chatgpt.com/backend-api/codex"
    OpenAIAuthMode.ZHIPU_CODING_PLAN -> "https://open.bigmodel.cn/api/coding/paas/v4"
    OpenAIAuthMode.KIMI_CODING_PLAN -> "https://api.kimi.com/coding/v1"
    OpenAIAuthMode.MIMO_CODING_PLAN -> "https://token-plan-cn.xiaomimimo.com/v1"
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var authMode: OpenAIAuthMode = OpenAIAuthMode.API_KEY,
        /**
         * Brand tag — see [OpenAIBrand]. Default GENERIC matches "user added a custom OpenAI-
         * compatible provider". Bundled defaults set their own brand in DEFAULT_PROVIDERS.
         * Old persisted JSON missing this field deserializes to GENERIC; PreferencesStore's
         * load path then re-stamps the brand for matched default ids so existing OpenAI /
         * DeepSeek / Moonshot providers don't lose their brand-specific UI affordances.
         */
        var brand: OpenAIBrand = OpenAIBrand.GENERIC,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        /**
         * Selects between API_KEY (existing behaviour, all current Google fields apply)
         * and GEMINI_CODE_ASSIST_OAUTH (token-managed, baseUrl pinned to cloudcode-pa,
         * apiKey / vertexAI / service-account fields hidden). Old persisted JSON missing
         * this field deserializes to API_KEY so existing Google providers keep working.
         */
        var authMode: GoogleAuthMode = GoogleAuthMode.API_KEY,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}
