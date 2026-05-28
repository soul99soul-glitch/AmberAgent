package app.amber.feature.modelcouncil

internal const val EXTERNAL_CLI_DEFAULT_TOOL_ID = "gemini_cli"

internal data class ExternalCliToolSpec(
    val id: String,
    val displayName: String,
    val binary: String,
    val missingMessage: String,
    val credentialHome: String,
    val modelPlaceholder: String,
    val seatCommand: (promptFile: String, modelArg: String) -> String,
    val readinessProbeCommand: (modelArg: String) -> String,
    val loginCommand: String,
    val requiresPtyForLogin: Boolean = false,
)

internal data class ExternalCliLoginHint(
    val url: String? = null,
    val code: String? = null,
    val needsUserAction: Boolean = false,
)

internal object ExternalCliToolRegistry {
    private val safeCliModelRegex = Regex("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,119}")
    private val urlRegex = Regex("""https?://[^\s"'<>]+""")
    private val codeRegex = Regex(
        """(?i)\b(?:verification code|authorization code|user code|code)\b(?:\s+(?:is|:|=))?[\s:=]*([A-Z0-9][A-Z0-9._-]{3,})"""
    )

    val tools: List<ExternalCliToolSpec> = listOf(
        ExternalCliToolSpec(
            id = "gemini_cli",
            displayName = "Gemini CLI",
            binary = "gemini",
            missingMessage = "gemini CLI not found in this runtime. Install Gemini CLI in Termux/selected runtime or use provider model seats.",
            credentialHome = ".gemini",
            modelPlaceholder = "例如 gemini-2.5-pro",
            seatCommand = { promptFile, modelArg ->
                "gemini --skip-trust --approval-mode plan --output-format text$modelArg --prompt \"${'$'}(cat \"$promptFile\")\""
            },
            readinessProbeCommand = { modelArg ->
                "gemini --skip-trust --approval-mode plan --output-format text$modelArg --prompt 'reply ok' >/dev/null"
            },
            loginCommand = "gemini",
            requiresPtyForLogin = true,
        ),
        ExternalCliToolSpec(
            id = "antigravity_cli",
            displayName = "Antigravity CLI",
            binary = "agy",
            missingMessage = "Antigravity CLI (agy) not found in this runtime. Install Antigravity CLI in the selected runtime or choose another external CLI.",
            credentialHome = ".gemini/antigravity-cli",
            modelPlaceholder = "例如 gemini-3-pro",
            seatCommand = { promptFile, modelArg ->
                """
                if agy --help 2>&1 | grep -Eq -- '(^|[[:space:],])(-p|--prompt)([=,[:space:]]|${'$'})'; then
                  agy$modelArg --prompt "$(cat "$promptFile")"
                else
                  echo 'Antigravity CLI is installed, but this agy build does not advertise a non-interactive --prompt/-p mode. Run login/probe in an interactive terminal or wait for PTY support.' >&2
                  exit 64
                fi
                """.trimIndent()
            },
            readinessProbeCommand = { modelArg ->
                """
                if agy --help 2>&1 | grep -Eq -- '(^|[[:space:],])(-p|--prompt)([=,[:space:]]|${'$'})'; then
                  agy$modelArg --prompt 'reply ok' >/dev/null
                else
                  exit 64
                fi
                """.trimIndent()
            },
            loginCommand = "agy",
            requiresPtyForLogin = true,
        ),
        ExternalCliToolSpec(
            id = "codex_cli",
            displayName = "Codex CLI",
            binary = "codex",
            missingMessage = "codex CLI not found in this runtime. Install Codex CLI in the selected runtime or choose another external CLI.",
            credentialHome = ".codex",
            modelPlaceholder = "例如 gpt-5.2-codex",
            seatCommand = { promptFile, modelArg ->
                "codex exec --sandbox read-only$modelArg \"${'$'}(cat \"$promptFile\")\""
            },
            readinessProbeCommand = { modelArg ->
                "codex exec --sandbox read-only$modelArg 'reply ok' >/dev/null"
            },
            loginCommand = "codex login",
        ),
        ExternalCliToolSpec(
            id = "claude_code",
            displayName = "Claude Code",
            binary = "claude",
            missingMessage = "Claude Code (claude) not found in this runtime. Install Claude Code in the selected runtime or choose another external CLI.",
            credentialHome = ".claude",
            modelPlaceholder = "例如 sonnet",
            seatCommand = { promptFile, modelArg ->
                "claude -p --output-format text --permission-mode plan$modelArg \"${'$'}(cat \"$promptFile\")\""
            },
            readinessProbeCommand = { modelArg ->
                "claude -p --output-format text --permission-mode plan$modelArg 'reply ok' >/dev/null"
            },
            loginCommand = "claude setup-token",
            requiresPtyForLogin = true,
        ),
        ExternalCliToolSpec(
            id = "kimi_cli",
            displayName = "Kimi CLI",
            binary = "kimi",
            missingMessage = "Kimi CLI (kimi) not found in this runtime. Install Kimi CLI in the selected runtime or choose another external CLI.",
            credentialHome = ".kimi",
            modelPlaceholder = "例如 kimi-k2",
            seatCommand = { promptFile, modelArg ->
                "kimi --print --final-message-only --output-format text$modelArg --prompt \"${'$'}(cat \"$promptFile\")\""
            },
            readinessProbeCommand = { modelArg ->
                "kimi --print --final-message-only --output-format text$modelArg --prompt 'reply ok' >/dev/null"
            },
            loginCommand = "kimi",
            requiresPtyForLogin = true,
        ),
    )

    val supportedToolIds: List<String> = tools.map { it.id }

    fun normalizeToolId(id: String): String =
        id.ifBlank { EXTERNAL_CLI_DEFAULT_TOOL_ID }

    fun isSupported(id: String): Boolean =
        tools.any { it.id == normalizeToolId(id) }

    fun requireSpec(id: String): ExternalCliToolSpec {
        val normalized = normalizeToolId(id)
        return tools.firstOrNull { it.id == normalized }
            ?: error("Unsupported external_tool: $normalized. Supported: ${supportedToolIds.joinToString()}.")
    }

    fun displayName(id: String): String =
        requireSpec(id).displayName

    fun modelPlaceholder(id: String): String =
        requireSpec(id).modelPlaceholder

    fun safeModelArg(model: String): String {
        val value = model.takeIf { it.isNotBlank() } ?: return ""
        require(safeCliModelRegex.matches(value)) { "Unsafe external_model: $value" }
        return " --model $value"
    }

    fun detectLoginHint(output: String): ExternalCliLoginHint {
        val url = urlRegex.find(output)?.value?.trimEnd('.', ',', ';', ')', ']')
        val code = codeRegex.find(output)?.groupValues?.getOrNull(1)
            ?.trimEnd('.', ',', ';', ')', ']')
        val lower = output.lowercase()
        val needsUserAction = url != null ||
            code != null ||
            "paste code" in lower ||
            "press c" in lower ||
            "open a browser" in lower ||
            "browser" in lower && "login" in lower
        return ExternalCliLoginHint(url = url, code = code, needsUserAction = needsUserAction)
    }
}

internal fun String.shellSingleQuoted(): String =
    "'" + replace("'", "'\"'\"'") + "'"
