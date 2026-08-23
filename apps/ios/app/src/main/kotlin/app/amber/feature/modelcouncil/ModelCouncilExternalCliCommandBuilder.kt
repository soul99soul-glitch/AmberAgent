object ModelCouncilExternalCliCommandBuilder {
    private const val OUTPUT_BEGIN = "__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_BEGIN__"
    private const val OUTPUT_END = "__AMBERAGENT_MODEL_COUNCIL_CLI_OUTPUT_END__"

    enum class Marker { NONE, BEGIN, END }

    fun build(
        seat: ModelCouncilSeat,
        prompt: String,
        timeoutMs: Long,
        externalCliHomeRoot: String,
        runtime: TerminalRuntimeKind = TerminalRuntimeKind.BUILTIN_ALPINE,
    ): String {
        require(seat.runnerType == ModelCouncilSeatRunner.EXTERNAL_CLI) {
            "External CLI command requires runner_type=external_cli."
        }
        require(externalCliHomeRoot.isNotBlank()) { "externalCliHomeRoot is required." }
        val spec = ExternalCliToolRegistry.requireSpec(seat.externalTool)
        val boundedPrompt = prompt.take(MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS)
        val timeoutSeconds = (timeoutMs / 1_000L).coerceAtLeast(1L).coerceAtMost(24 * 60 * 60)
        val promptDelimiter = "AMBERAGENT_COUNCIL_PROMPT_${Uuid.random().toString().replace("-", "")}"
        val modelArg = ExternalCliToolRegistry.safeModelArg(seat.externalModel)
        val homeRoot = "${externalCliHomeRoot.trimEnd('/')}/${spec.id}"
        val seatCommand = spec.seatCommand("\${prompt_file}", modelArg)
        val readinessProbeCommand = spec.readinessProbeCommand(modelArg)
        return buildString {
            appendLine("set -u")
            appendLine("if ! command -v ${spec.binary.shellSingleQuoted()} >/dev/null 2>&1; then")
            appendLine("  echo ${spec.missingMessage.shellSingleQuoted()} >&2")
            appendLine("  exit 127")
            appendLine("fi")
            appendLine("workspace_root=\"${'$'}PWD\"")
            appendLine("tmp_root=\"${'$'}workspace_root/.amberagent-council-cli-${'$'}$\"")
            if (runtime == TerminalRuntimeKind.TERMUX_EXTERNAL) {
                appendLine("home_root=\"${'$'}HOME/.amberagent/external-cli-home/${spec.id}\"")
            } else {
                appendLine("home_root=${homeRoot.shellSingleQuoted()}")
            }
            appendLine("if ! mkdir -p \"${'$'}tmp_root/home\" \"${'$'}tmp_root/work\" \"${'$'}home_root/${spec.credentialHome}\"; then")
            appendLine("  echo 'External CLI credential home is not writable in this runtime. Choose builtin_alpine/android_shell or finish login in an accessible runtime.' >&2")
            appendLine("  exit 125")
            appendLine("fi")
            appendLine("cli_pid=\"\"")
            appendLine("probe_pid=\"\"")
            appendLine("stop_process_tree() {")
            appendLine("  target_pid=\"${'$'}1\"")
            appendLine("  if [ -n \"${'$'}target_pid\" ] && kill -0 \"${'$'}target_pid\" >/dev/null 2>&1; then")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -TERM -P \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -TERM \"-${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -TERM \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    sleep 2")
            appendLine("    if command -v pkill >/dev/null 2>&1; then")
            appendLine("      pkill -KILL -P \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    fi")
            appendLine("    kill -KILL \"-${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("    kill -KILL \"${'$'}target_pid\" >/dev/null 2>&1 || true")
            appendLine("  fi")
            appendLine("}")
            appendLine("stop_cli_tree() { stop_process_tree \"${'$'}cli_pid\"; }")
            appendLine("stop_probe_tree() { stop_process_tree \"${'$'}probe_pid\"; }")
            appendLine("cleanup() { stop_probe_tree; stop_cli_tree; rm -rf \"${'$'}tmp_root\"; }")
            appendLine("trap cleanup EXIT INT TERM")
            appendLine("export HOME=\"${'$'}home_root\"")
            appendLine("export CLAUDE_CONFIG_DIR=\"${'$'}home_root/.claude\"")
            appendLine("export KIMI_HOME=\"${'$'}home_root/.kimi\"")
            appendLine("export GEMINI_CLI_HOME=\"${'$'}home_root/.gemini\"")
            appendLine("export NO_COLOR=1")
            appendLine("probe_log=\"${'$'}tmp_root/probe.log\"")
            appendLine("probe_status=0")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -lc ${readinessProbeCommand.shellSingleQuoted()} >\"${'$'}probe_log\" 2>&1 &")
            appendLine("else")
            appendLine("  sh -lc ${readinessProbeCommand.shellSingleQuoted()} >\"${'$'}probe_log\" 2>&1 &")
            appendLine("fi")
            appendLine("probe_pid=${'$'}!")
            appendLine("(")
            appendLine("  sleep $MODEL_COUNCIL_EXTERNAL_CLI_PROBE_TIMEOUT_SECONDS")
            appendLine("  if kill -0 \"${'$'}probe_pid\" >/dev/null 2>&1; then")
            appendLine("    echo 'External CLI readiness probe timed out for ${spec.displayName}; stopping ${spec.binary}.' >&2")
            appendLine("    stop_probe_tree")
            appendLine("  fi")
            appendLine(") &")
            appendLine("probe_watchdog_pid=${'$'}!")
            appendLine("wait \"${'$'}probe_pid\" || probe_status=${'$'}?")
            appendLine("kill \"${'$'}probe_watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("wait \"${'$'}probe_watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("if [ \"${'$'}probe_status\" -ne 0 ]; then")
            appendLine("  echo 'External CLI ${spec.displayName} is not ready. Run ${spec.loginCommand} in the selected runtime, complete login, then retry. Council runs never start login.' >&2")
            appendLine("  exit 65")
            appendLine("fi")
            appendLine("prompt_file=\"${'$'}tmp_root/prompt.txt\"")
            appendLine("cat > \"${'$'}prompt_file\" <<'$promptDelimiter'")
            appendLine(boundedPrompt)
            appendLine(promptDelimiter)
            appendLine("export prompt_file")
            appendLine("cd \"${'$'}tmp_root/work\"")
            appendLine("printf '%s\\n' '$OUTPUT_BEGIN'")
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -lc ${seatCommand.shellSingleQuoted()} &")
            appendLine("else")
            appendLine("  sh -lc ${seatCommand.shellSingleQuoted()} &")
            appendLine("fi")
            appendLine("cli_pid=${'$'}!")
            appendLine("(")
            appendLine("  sleep $timeoutSeconds")
            appendLine("  if kill -0 \"${'$'}cli_pid\" >/dev/null 2>&1; then")
            appendLine("    echo 'External CLI seat timed out after ${timeoutSeconds}s; stopping ${spec.binary}.' >&2")
            appendLine("    stop_cli_tree")
            appendLine("  fi")
            appendLine(") &")
            appendLine("watchdog_pid=${'$'}!")
            appendLine("wait \"${'$'}cli_pid\"")
            appendLine("status=${'$'}?")
            appendLine("kill \"${'$'}watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("wait \"${'$'}watchdog_pid\" >/dev/null 2>&1 || true")
            appendLine("printf '\\n%s\\n' '$OUTPUT_END'")
            appendLine("exit \"${'$'}status\"")
        }
    }

    fun extractLiveLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed == OUTPUT_BEGIN || trimmed == OUTPUT_END) return null
        if (line.contains("Preparing /workspace mirror") || line.contains("Starting ")) return null
        return line.takeIf { it.isNotBlank() }
    }

    fun marker(line: String): Marker = when (line.trim()) {
        OUTPUT_BEGIN -> Marker.BEGIN
        OUTPUT_END -> Marker.END
        else -> Marker.NONE
    }

    fun extractFinalOutput(output: String): String {
        val start = output.indexOf(OUTPUT_BEGIN)
        if (start < 0) return ""
        val bodyStart = start + OUTPUT_BEGIN.length
        val end = output.indexOf(OUTPUT_END, startIndex = bodyStart)
        return if (end >= 0) {
            output.substring(bodyStart, end)
        } else {
            output.substring(bodyStart)
        }.trim()
    }

}

private const val MODEL_COUNCIL_EXTERNAL_CLI_PROMPT_CHARS = 24_000
private const val MODEL_COUNCIL_EXTERNAL_CLI_PROBE_TIMEOUT_SECONDS = 15
internal const val MODEL_COUNCIL_EXTERNAL_CLI_WAIT_GRACE_MS = 1_000L
