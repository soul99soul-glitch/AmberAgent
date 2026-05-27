package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.memory.model.MemoryKind
import me.rerere.rikkahub.data.memory.model.MemoryScope
import me.rerere.rikkahub.data.model.nativebridge.RegexNativeSwitch
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val imageGenerationModelId: Uuid? = null, // 如果为null, 回退到全局 Settings.imageGenerationModelId; 仍为 null 时不启用 generate_image 工具
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val toolProfile: MainAgentToolProfile = MainAgentToolProfile.FULL,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
    val scope: MemoryScope = MemoryScope.LONG_TERM,
    val kind: MemoryKind = MemoryKind.NOTE,
    val expiresAt: Long? = null,
    val confidence: Float = 1f,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

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

/** JVM regex pipeline preserved as the sole fallback path. Identical to the
 *  fold body that was inline before Phase 2 Step 3 — invalid patterns are
 *  caught per-rule and skipped (acc passes through unchanged), so a single
 *  malformed rule doesn't break the whole chain. */
/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
    ) : PromptInjection()
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (e: Exception) {
                false
            }
        } else {
            if (caseSensitive) {
                context.contains(keyword)
            } else {
                context.contains(keyword, ignoreCase = true)
            }
        }
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
