package app.amber.core.ai.transformers

import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import kotlin.uuid.Uuid
import app.amber.agent.data.model.nativebridge.RegexNativeSwitch

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this

    val program = AssistantRegexProgramCache.get(assistant, scope, visual)
    if (program.isEmpty) return this

    // Phase 2 Step 3: route through RegexNativeSwitch when enabled AND no
    // rule uses JVM-only syntax. Preflight is required because the Rust
    // `regex` crate silently skips lookbehind / backref / possessive in
    // patterns (and `\$` / `$<name>` in replacements), producing different
    // output without throwing — fallback can't detect it. When the preflight
    // flags any JVM-only rule we route the whole batch to JVM to preserve
    // per-rule semantic parity. See `RegexNativeSwitch` class KDoc.
    if (RegexNativeSwitch.isEnabled() && !program.containsJvmOnlySyntax) {
        RegexNativeSwitch.applyOrNull(
            input = this,
            findPatterns = program.nativePatterns,
            replacements = program.nativeReplacements,
            jvmFallback = { program.applyJvm(this) },
        )?.let { return it }
    }

    return program.applyJvm(this)
}

private object AssistantRegexProgramCache {
    private const val MAX_ENTRIES = 128
    private val lock = Any()
    private val entries = object : LinkedHashMap<AssistantRegexProgramKey, AssistantRegexProgram>(
        MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AssistantRegexProgramKey, AssistantRegexProgram>,
        ): Boolean = size > MAX_ENTRIES
    }

    fun get(
        assistant: Assistant,
        scope: AssistantAffectScope,
        visual: Boolean,
    ): AssistantRegexProgram {
        val key = AssistantRegexProgramKey.of(assistant, scope, visual)
        synchronized(lock) {
            entries[key]?.let { return it }
        }
        val program = buildProgram(assistant, scope, visual)
        synchronized(lock) {
            entries[key]?.let { return it }
            entries[key] = program
        }
        return program
    }

    private fun buildProgram(
        assistant: Assistant,
        scope: AssistantAffectScope,
        visual: Boolean,
    ): AssistantRegexProgram {
        val applicable = assistant.regexes.filter { regex ->
            regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)
        }
        if (applicable.isEmpty()) return AssistantRegexProgram.Empty
        val compiledRules = applicable.map { rule ->
            CompiledAssistantRegex(
                regex = runCatching { Regex(rule.findRegex) }
                    .onFailure { it.printStackTrace() }
                    .getOrNull(),
                replacement = rule.replaceString,
            )
        }
        return AssistantRegexProgram(
            compiledRules = compiledRules,
            nativePatterns = Array(applicable.size) { applicable[it].findRegex },
            nativeReplacements = Array(applicable.size) { applicable[it].replaceString },
            containsJvmOnlySyntax = containsJvmOnlyRegexSyntax(applicable) ||
                compiledRules.any { it.regex == null },
        )
    }
}

private data class AssistantRegexProgramKey(
    val assistantId: Uuid,
    val scope: AssistantAffectScope,
    val visual: Boolean,
    val signature: Int,
) {
    companion object {
        fun of(
            assistant: Assistant,
            scope: AssistantAffectScope,
            visual: Boolean,
        ): AssistantRegexProgramKey {
            var signature = 1
            assistant.regexes.forEach { regex ->
                signature = 31 * signature + regex.id.hashCode()
                signature = 31 * signature + regex.enabled.hashCode()
                signature = 31 * signature + regex.findRegex.hashCode()
                signature = 31 * signature + regex.replaceString.hashCode()
                signature = 31 * signature + regex.affectingScope.hashCode()
                signature = 31 * signature + regex.visualOnly.hashCode()
            }
            return AssistantRegexProgramKey(
                assistantId = assistant.id,
                scope = scope,
                visual = visual,
                signature = signature,
            )
        }
    }
}

private data class AssistantRegexProgram(
    val compiledRules: List<CompiledAssistantRegex>,
    val nativePatterns: Array<String>,
    val nativeReplacements: Array<String>,
    val containsJvmOnlySyntax: Boolean,
) {
    val isEmpty: Boolean get() = compiledRules.isEmpty()

    fun applyJvm(input: String): String =
        compiledRules.fold(input) { acc, rule ->
            val regex = rule.regex ?: return@fold acc
            try {
                regex.replace(acc, rule.replacement)
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        }

    companion object {
        val Empty = AssistantRegexProgram(
            compiledRules = emptyList(),
            nativePatterns = emptyArray(),
            nativeReplacements = emptyArray(),
            containsJvmOnlySyntax = false,
        )
    }
}

private data class CompiledAssistantRegex(
    val regex: Regex?,
    val replacement: String,
)

/**
 * Detect any rule that uses Java `Pattern` syntax the Rust `regex` crate
 * doesn't support (and would silently skip, producing a divergent output).
 *
 * Detects, in **pattern** strings:
 * - lookbehind: `(?<=...)`, `(?<!...)`
 * - lookahead:  `(?=...)`, `(?!...)`
 * - atomic group: `(?>...)`
 * - backreference: `\1` through `\9` (only `\` followed by single digit;
 *   `\10` etc. are rare and we accept under-coverage there)
 * - possessive quantifier: `?+`, `*+`, `++`
 *
 * Detects, in **replacement** strings:
 * - literal-dollar Java syntax: `\$`
 * - named-group Java syntax: `$<name>` (Rust uses `${name}` or `$name`)
 *
 * Pure prefilter — no compilation, no DFA, no allocations beyond the
 * `Regex.containsMatchIn` internal state. Safe to run per-message.
 */
private fun containsJvmOnlyRegexSyntax(rules: List<AssistantRegex>): Boolean {
    return rules.any { rule ->
        JVM_ONLY_PATTERN_SYNTAX.containsMatchIn(rule.findRegex) ||
            JVM_ONLY_REPLACEMENT_SYNTAX.containsMatchIn(rule.replaceString)
    }
}

private val JVM_ONLY_PATTERN_SYNTAX = Regex(
    """\(\?<[=!]|\(\?[=!]|\(\?>|\\[1-9]|[?*+]\+"""
)
private val JVM_ONLY_REPLACEMENT_SYNTAX = Regex(
    """\\\$|\$<"""
)
