package app.amber.feature.subagent

import app.amber.core.model.Assistant
import app.amber.core.settings.Settings

fun Assistant.toIsolatedSubAgentAssistant(definition: SubAgentDefinition) = copy(
    name = definition.name,
    systemPrompt = definition.systemPrompt,
    streamOutput = true,
    contextMessageSize = 0,
    enableMemory = false,
    useGlobalMemory = false,
    enableRecentChatsReference = false,
    presetMessages = emptyList(),
    quickMessageIds = emptySet(),
    regexes = emptyList(),
    mcpServers = emptySet(),
    localTools = emptyList(),
    modeInjectionIds = emptySet(),
    lorebookIds = emptySet(),
    enabledSkills = emptySet(),
    enableTimeReminder = false,
    messageTemplate = "{{ message }}",
    temperature = definition.temperature ?: temperature,
    reasoningLevel = definition.reasoningLevel ?: reasoningLevel,
)

fun Settings.toIsolatedSubAgentSettings(): Settings = copy(
    agentRuntime = agentRuntime.copy(
        enableCoreMemory = false,
        enableShortTermMemory = false,
        enableLongTermMemory = false,
        enableRecentChatsReference = false,
        enableTimeReminder = false,
        agentSoulMarkdown = "",
        generativeUi = agentRuntime.generativeUi.copy(enabled = false),
        speculativeToolExecution = agentRuntime.speculativeToolExecution.copy(enabled = false),
    )
)
