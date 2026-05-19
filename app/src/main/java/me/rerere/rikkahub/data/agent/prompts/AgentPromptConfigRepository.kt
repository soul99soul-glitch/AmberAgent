package me.rerere.rikkahub.data.agent.prompts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRuntimeSetting
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilSeat
import me.rerere.rikkahub.data.agent.subagent.SubAgentDefinitions
import me.rerere.rikkahub.data.agent.subagent.SubAgentOverride
import me.rerere.rikkahub.data.agent.subagent.SubAgentRuntimeSetting
import me.rerere.rikkahub.data.agent.subagent.applyOverride
import java.io.File

const val DEFAULT_IMAGE_PROMPT_INJECTION: String =
    "Prefer clean composition, clear focal subject, natural lighting, coherent anatomy, restrained details, and a polished final image. Keep the scene readable at mobile size."

const val DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION: String =
    "Overcrowded micro-details, muddy textures, distorted hands or faces, unreadable text, duplicated limbs, heavy artifacts, random decorative clutter, and low-contrast composition."

val DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT: String = """
# Context Compaction Handoff

Write a continuation handoff for another model that will resume the same conversation.

Return valid JSON only. The JSON must include:
- `schema_version`: 2
- `timeline_summary`: 4-5 human-readable sentences in the user's language, written for the chat timeline
- `handoff_markdown`: dense Markdown for the next model, with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files
- `covered_compact_ids`: the compact ids from the provided previous handoffs that this handoff carries forward
- `source_message_ids`: exactly the source ids provided for this compact pass
- `created_at`: the unix epoch millis provided by the app

The timeline summary is for humans. The handoff Markdown is for the model. Preserve concrete names, files, commands, errors, user preferences, approvals, rejected approaches, and unresolved decisions. Do not include raw tool logs unless they are needed to continue safely.
""".trimIndent()

data class ImagePromptInjectionConfig(
    val enabled: Boolean = true,
    val defaultPrompt: String = DEFAULT_IMAGE_PROMPT_INJECTION,
    val negativePrompt: String = DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION,
)

data class PromptConfigWriteResult(
    val file: File,
    val updatedId: String,
)

class AgentPromptConfigRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val writeMutex = Mutex()

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    val imagePromptFile: File
        get() = File(directory, IMAGE_PROMPT_FILE)

    val subAgentPromptFile: File
        get() = File(directory, SUB_AGENT_PROMPT_FILE)

    val modelCouncilPromptFile: File
        get() = File(directory, MODEL_COUNCIL_PROMPT_FILE)

    val contextCompactionPromptFile: File
        get() = File(directory, CONTEXT_COMPACTION_PROMPT_FILE)

    suspend fun effectiveImagePrompt(userPrompt: String): String = withContext(Dispatchers.IO) {
        applyImagePrompt(userPrompt, readImageConfigBlocking())
    }

    suspend fun readImageConfig(): ImagePromptInjectionConfig = withContext(Dispatchers.IO) {
        readImageConfigBlocking()
    }

    suspend fun writeImageConfig(config: ImagePromptInjectionConfig): PromptConfigWriteResult =
        withContext(Dispatchers.IO) {
            val next = config.copy(
                defaultPrompt = config.defaultPrompt.validPrompt(MAX_IMAGE_PROMPT_CHARS, "image default prompt"),
                negativePrompt = config.negativePrompt.validPrompt(
                    MAX_IMAGE_NEGATIVE_PROMPT_CHARS,
                    "image negative prompt",
                ),
            )
            writeMutex.withLock {
                imagePromptFile.writeTextAtomically(renderImageConfig(next))
            }
            PromptConfigWriteResult(imagePromptFile, "image_generation")
        }

    suspend fun writeSubAgentPrompt(
        setting: SubAgentRuntimeSetting,
        subAgentId: String,
        prompt: String,
    ): Pair<SubAgentRuntimeSetting, PromptConfigWriteResult> = withContext(Dispatchers.IO) {
        val builtIn = SubAgentDefinitions.find(subAgentId)
            ?: error("Unknown built-in subagent id: $subAgentId")
        val trimmed = prompt.validPrompt(MAX_SUB_AGENT_PROMPT_CHARS, "subagent prompt")
        val current = setting.overrides[builtIn.id] ?: SubAgentOverride()
        val promptOverride = trimmed
            .takeIf { it.isNotBlank() && it != builtIn.systemPrompt.trim() }
        val nextOverride = current.copy(systemPrompt = promptOverride)
        val nextOverrides = if (nextOverride == SubAgentOverride()) {
            setting.overrides - builtIn.id
        } else {
            setting.overrides + (builtIn.id to nextOverride)
        }
        val nextSetting = setting.copy(overrides = nextOverrides)
        writeMutex.withLock {
            writeSubAgentMarkdownBlocking(nextSetting)
        }
        nextSetting to PromptConfigWriteResult(subAgentPromptFile, builtIn.id)
    }

    suspend fun writeSubAgentMarkdown(setting: SubAgentRuntimeSetting): File = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            writeSubAgentMarkdownBlocking(setting)
        }
    }

    suspend fun applySubAgentMarkdownToSetting(setting: SubAgentRuntimeSetting): SubAgentRuntimeSetting =
        withContext(Dispatchers.IO) {
            if (!subAgentPromptFile.exists()) return@withContext setting
            val prompts = parseIdPromptSections(subAgentPromptFile.readText())
            prompts.entries.fold(setting) { current, entry ->
                val builtIn = SubAgentDefinitions.find(entry.key) ?: return@fold current
                val prompt = entry.value.validPrompt(MAX_SUB_AGENT_PROMPT_CHARS, "subagent prompt")
                val cur = current.overrides[builtIn.id] ?: SubAgentOverride()
                val promptOverride = prompt
                    .takeIf { it.isNotBlank() && it != builtIn.systemPrompt.trim() }
                val nextOverride = cur.copy(systemPrompt = promptOverride)
                val nextOverrides = if (nextOverride == SubAgentOverride()) {
                    current.overrides - builtIn.id
                } else {
                    current.overrides + (builtIn.id to nextOverride)
                }
                current.copy(overrides = nextOverrides)
            }
        }

    suspend fun writeModelCouncilSeatPrompt(
        setting: ModelCouncilRuntimeSetting,
        seatKey: String,
        prompt: String,
    ): Pair<ModelCouncilRuntimeSetting, PromptConfigWriteResult> = withContext(Dispatchers.IO) {
        val normalizedKey = seatKey.trim()
        val seat = setting.defaultSeats.firstOrNull { it.matches(normalizedKey) }
            ?: error("Unknown model council seat: $seatKey")
        val trimmed = prompt.validPrompt(MAX_COUNCIL_PROMPT_CHARS, "model council prompt")
        val nextSetting = setting.copy(
            defaultSeats = setting.defaultSeats.map { current ->
                if (current.seatId == seat.seatId) {
                    current.copy(systemPrompt = trimmed)
                } else {
                    current
                }
            }
        )
        writeMutex.withLock {
            writeModelCouncilMarkdownBlocking(nextSetting)
        }
        nextSetting to PromptConfigWriteResult(modelCouncilPromptFile, seat.seatId)
    }

    suspend fun writeModelCouncilMarkdown(setting: ModelCouncilRuntimeSetting): File =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                writeModelCouncilMarkdownBlocking(setting)
            }
        }

    suspend fun readContextCompactionPrompt(): String = withContext(Dispatchers.IO) {
        readContextCompactionPromptBlocking()
    }

    suspend fun writeContextCompactionPrompt(prompt: String): PromptConfigWriteResult =
        withContext(Dispatchers.IO) {
            val trimmed = prompt.validPrompt(MAX_CONTEXT_COMPACTION_PROMPT_CHARS, "context compaction prompt")
            writeMutex.withLock {
                contextCompactionPromptFile.writeTextAtomically(renderContextCompactionPrompt(trimmed))
            }
            PromptConfigWriteResult(contextCompactionPromptFile, "context_compaction")
        }

    suspend fun applyModelCouncilMarkdownToSetting(setting: ModelCouncilRuntimeSetting): ModelCouncilRuntimeSetting =
        withContext(Dispatchers.IO) {
            if (!modelCouncilPromptFile.exists()) return@withContext setting
            val prompts = parseIdPromptSections(modelCouncilPromptFile.readText())
            setting.copy(
                defaultSeats = setting.defaultSeats.map { seat ->
                    val prompt = prompts.entries.firstOrNull { seat.matches(it.key) }?.value
                    if (prompt.isNullOrBlank()) {
                        seat
                    } else {
                        seat.copy(
                            systemPrompt = prompt.validPrompt(MAX_COUNCIL_PROMPT_CHARS, "model council prompt")
                        )
                    }
                }
            )
        }

    suspend fun ensureMarkdownMirrors(
        subAgent: SubAgentRuntimeSetting,
        council: ModelCouncilRuntimeSetting,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (!subAgentPromptFile.exists()) writeSubAgentMarkdownBlocking(subAgent)
            if (!modelCouncilPromptFile.exists()) writeModelCouncilMarkdownBlocking(council)
            if (!imagePromptFile.exists()) {
                imagePromptFile.writeTextAtomically(renderImageConfig(ImagePromptInjectionConfig()))
            }
            if (!contextCompactionPromptFile.exists()) {
                contextCompactionPromptFile.writeTextAtomically(
                    renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT)
                )
            }
        }
    }

    private fun readImageConfigBlocking(): ImagePromptInjectionConfig {
        if (!imagePromptFile.exists()) {
            imagePromptFile.writeTextAtomically(renderImageConfig(ImagePromptInjectionConfig()))
        }
        return parseImageConfig(imagePromptFile.readText())
    }

    private fun writeSubAgentMarkdownBlocking(setting: SubAgentRuntimeSetting): File {
        subAgentPromptFile.writeTextAtomically(renderSubAgentPrompts(setting))
        return subAgentPromptFile
    }

    private fun writeModelCouncilMarkdownBlocking(setting: ModelCouncilRuntimeSetting): File {
        modelCouncilPromptFile.writeTextAtomically(renderModelCouncilPrompts(setting))
        return modelCouncilPromptFile
    }

    private fun readContextCompactionPromptBlocking(): String {
        if (!contextCompactionPromptFile.exists()) {
            contextCompactionPromptFile.writeTextAtomically(
                renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT)
            )
        }
        return extractFencedSection(contextCompactionPromptFile.readText(), "Prompt")
            .ifBlank { DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT }
    }

    private fun applyImagePrompt(userPrompt: String, config: ImagePromptInjectionConfig): String {
        val defaultPrompt = config.defaultPrompt.trim()
        val negativePrompt = config.negativePrompt.trim()
        if (!config.enabled || (defaultPrompt.isBlank() && negativePrompt.isBlank())) {
            return userPrompt
        }
        return buildString {
            append(userPrompt.trim())
            append("\n\nImage generation defaults to apply unless the user explicitly conflicts:\n")
            if (defaultPrompt.isNotBlank()) append(defaultPrompt)
            if (negativePrompt.isNotBlank()) {
                append("\n\nAvoid / de-emphasize:\n")
                append(negativePrompt)
            }
        }
    }

    private fun parseImageConfig(markdown: String): ImagePromptInjectionConfig {
        val enabled = findMetadataValue(markdown, "enabled")?.equals("false", ignoreCase = true) != true
        return ImagePromptInjectionConfig(
            enabled = enabled,
            defaultPrompt = extractFencedSection(markdown, "Default prompt")
                .ifBlank { DEFAULT_IMAGE_PROMPT_INJECTION },
            negativePrompt = extractFencedSection(markdown, "Negative prompt")
                .ifBlank { DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION },
        )
    }

    private fun renderImageConfig(config: ImagePromptInjectionConfig): String = """
        # Image Generation Prompt Injection

        enabled: ${config.enabled}

        Agent-editable defaults appended to every image-generation request. Keep this file concise:
        it is injected into the provider prompt, not shown as a UI preset.

        ## Default prompt

        ```text
        ${config.defaultPrompt.trim().safeFence()}
        ```

        ## Negative prompt

        ```text
        ${config.negativePrompt.trim().safeFence()}
        ```
    """.trimIndent() + "\n"

    private fun renderSubAgentPrompts(setting: SubAgentRuntimeSetting): String = buildString {
        appendLine("# SubAgent Prompt Overrides")
        appendLine()
        appendLine("Edit built-in role prompts here. AmberAgent settings and agent tools mirror these entries.")
        appendLine("Use the built-in id as the level-2 heading.")
        appendLine()
        SubAgentDefinitions.builtIns.forEach { def ->
            val effective = def.applyOverride(setting.overrides[def.id])
            appendLine("## ${def.id}")
            appendLine()
            appendLine("name: ${def.name}")
            appendLine()
            appendLine("```text")
            appendLine(effective.systemPrompt.trim().safeFence())
            appendLine("```")
            appendLine()
        }
    }

    private fun renderModelCouncilPrompts(setting: ModelCouncilRuntimeSetting): String = buildString {
        appendLine("# Model Council Seat Prompts")
        appendLine()
        appendLine("Edit persistent council seat prompts here. Use the seat id heading for stable sync.")
        appendLine()
        setting.defaultSeats.forEach { seat ->
            appendLine("## ${seat.seatId}")
            appendLine()
            appendLine("name: ${seat.name}")
            appendLine("role: ${seat.role}")
            appendLine()
            appendLine("```text")
            appendLine(seat.systemPrompt.trim().safeFence())
            appendLine("```")
            appendLine()
        }
    }

    private fun renderContextCompactionPrompt(prompt: String): String = """
        # Context Compaction Handoff Prompt

        Agent-editable instructions appended to every conversation compaction request.
        Keep the JSON contract intact unless the app is updated to parse a new schema.

        ## Prompt

        ```text
        ${prompt.trim().safeFence()}
        ```
    """.trimIndent() + "\n"

    private fun extractFencedSection(markdown: String, heading: String): String {
        val section = extractSection(markdown, heading)
        return FENCE_REGEX.find(section)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun extractSection(markdown: String, heading: String): String {
        return splitSecondLevelSections(markdown)
            .firstOrNull { it.heading.equals(heading, ignoreCase = true) }
            ?.body
            .orEmpty()
    }

    private fun parseIdPromptSections(markdown: String): Map<String, String> {
        return splitSecondLevelSections(markdown)
            .mapNotNull { match ->
                val id = match.heading.trim()
                val prompt = FENCE_REGEX.find(match.body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                id.takeIf { it.isNotBlank() && prompt.isNotBlank() }?.let { it to prompt }
            }
            .toMap()
    }

    private fun splitSecondLevelSections(markdown: String): List<MarkdownSection> {
        val sections = mutableListOf<MarkdownSection>()
        var inFence = false
        var heading: String? = null
        val body = StringBuilder()

        fun flush() {
            val currentHeading = heading ?: return
            sections.add(MarkdownSection(currentHeading, body.toString()))
            body.clear()
        }

        markdown.lineSequence().forEach { line ->
            val trimmedStart = line.trimStart()
            val isFence = trimmedStart.startsWith("```")
            if (!inFence && trimmedStart.startsWith("## ") && !trimmedStart.startsWith("###")) {
                flush()
                heading = trimmedStart.removePrefix("## ").trim()
                return@forEach
            }
            if (heading != null) {
                body.appendLine(line)
            }
            if (isFence) {
                inFence = !inFence
            }
        }
        flush()
        return sections
    }

    private fun findMetadataValue(markdown: String, key: String): String? {
        var inFence = false
        markdown.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                return@forEach
            }
            if (!inFence && trimmed.startsWith("$key:", ignoreCase = true)) {
                return trimmed.substringAfter(':').trim()
            }
        }
        return null
    }

    private fun ModelCouncilSeat.matches(key: String): Boolean {
        return seatId.equals(key, ignoreCase = true) ||
            role.equals(key, ignoreCase = true) ||
            name.equals(key, ignoreCase = true)
    }

    private fun String.safeFence(): String = replace("```", "` ` `")

    private fun String.validPrompt(maxChars: Int, label: String): String {
        val trimmed = trim()
        require(trimmed.length <= maxChars) {
            "$label is too long: ${trimmed.length} chars, max $maxChars"
        }
        return trimmed
    }

    private fun File.writeTextAtomically(text: String) {
        require(text.length <= MAX_MARKDOWN_FILE_CHARS) {
            "Prompt config file is too large: ${text.length} chars, max $MAX_MARKDOWN_FILE_CHARS"
        }
        parentFile?.mkdirs()
        val temp = File(parentFile, "$name.tmp")
        temp.writeText(text)
        if (!temp.renameTo(this)) {
            temp.copyTo(this, overwrite = true)
            temp.delete()
        }
    }

    private data class MarkdownSection(
        val heading: String,
        val body: String,
    )

    companion object {
        private const val DIRECTORY_NAME = "agent_prompts"
        private const val IMAGE_PROMPT_FILE = "image-generation.md"
        private const val SUB_AGENT_PROMPT_FILE = "subagents.md"
        private const val MODEL_COUNCIL_PROMPT_FILE = "model-council.md"
        private const val CONTEXT_COMPACTION_PROMPT_FILE = "context-compaction-handoff.md"

        private val FENCE_REGEX = Regex("(?s)```(?:text|prompt|markdown)?\\s*\\n(.*?)\\n```")
        private const val MAX_IMAGE_PROMPT_CHARS = 4_000
        private const val MAX_IMAGE_NEGATIVE_PROMPT_CHARS = 4_000
        private const val MAX_SUB_AGENT_PROMPT_CHARS = 8_000
        private const val MAX_COUNCIL_PROMPT_CHARS = 2_000
        private const val MAX_CONTEXT_COMPACTION_PROMPT_CHARS = 8_000
        private const val MAX_MARKDOWN_FILE_CHARS = 80_000
    }
}
