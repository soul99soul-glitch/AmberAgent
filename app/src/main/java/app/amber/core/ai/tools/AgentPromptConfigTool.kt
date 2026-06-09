package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.prompts.ImagePromptInjectionConfig
import app.amber.core.settings.prefs.SettingsAggregator

fun createAgentPromptConfigTool(
    settingsStore: SettingsAggregator,
    promptConfigRepository: AgentPromptConfigRepository,
): Tool = Tool(
    name = "agent_prompt_config",
    description = """
        Read or update AmberAgent's local Markdown prompt configuration.
        Use this when the user asks to tune persistent defaults for image
        generation, context compaction handoff prompts, built-in SubAgent role
        prompts, or Model Council seat prompts. The tool writes files under app-private agent_prompts/ and
        mirrors SubAgent/Council changes into Settings immediately.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { _, _ ->
        """
            AmberAgent has agent-editable local Markdown prompt configuration.
            Use `agent_prompt_config` instead of long-term memory when the user wants persistent behavior changes for:
            - image generation defaults or negative tendencies;
            - context compaction / handoff summary format;
            - built-in SubAgent role prompts (explorer, historian, oracle, designer, writer, fixer);
            - Model Council seat prompts.
            The tool writes app-private Markdown files under `agent_prompts/` and mirrors SubAgent/Council prompt edits into settings. If the user says "以后生图都...", "压缩上下文以后...", "把 designer 改成...", "这个 council 席位以后...", or asks where these prompts live, inspect or update this config.
        """.trimIndent()
    },
    needsApproval = true,
    allowsAutoApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("get")
                            add("update_image_generation")
                            add("update_context_compaction_prompt")
                            add("update_subagent_prompt")
                            add("update_council_seat_prompt")
                            add("sync_markdown_to_settings")
                        }
                    )
                })
                put("target", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional target for get: image_generation, context_compaction, subagents, model_council, or all."
                    )
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "For image_generation: whether default prompt injection is enabled.")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "New prompt content.")
                })
                put("negative_prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "For image_generation: constraints to avoid or de-emphasize.")
                })
                put("subagent_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Built-in SubAgent id, such as explorer, historian, oracle, designer, writer, or fixer.")
                })
                put("seat_id_or_role", buildJsonObject {
                    put("type", "string")
                    put("description", "Model Council seat id, role, or visible seat name.")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj.stringValue("action")
        val response = when (action) {
            "get" -> {
                val settings = settingsStore.settingsFlow.value
                promptConfigRepository.ensureMarkdownMirrors(
                    subAgent = settings.agentRuntime.subAgent,
                    council = settings.agentRuntime.modelCouncil,
                )
                val target = obj.stringValue("target").ifBlank { "all" }
                buildJsonObject {
                    put("status", "ok")
                    put("target", target)
                    put("files", buildJsonObject {
                        if (target == "all" || target == "image_generation") {
                            put("image_generation", promptConfigRepository.imagePromptFile.absolutePath)
                        }
                        if (target == "all" || target == "context_compaction") {
                            put("context_compaction", promptConfigRepository.contextCompactionPromptFile.absolutePath)
                        }
                        if (target == "all" || target == "subagents") {
                            put("subagents", promptConfigRepository.subAgentPromptFile.absolutePath)
                        }
                        if (target == "all" || target == "model_council") {
                            put("model_council", promptConfigRepository.modelCouncilPromptFile.absolutePath)
                        }
                    })
                    put("markdown", buildJsonObject {
                        if (target == "all" || target == "image_generation") {
                            put("image_generation", promptConfigRepository.imagePromptFile.readText())
                        }
                        if (target == "all" || target == "context_compaction") {
                            promptConfigRepository.readContextCompactionPrompt()
                            put("context_compaction", promptConfigRepository.contextCompactionPromptFile.readText())
                        }
                        if (target == "all" || target == "subagents") {
                            put("subagents", promptConfigRepository.subAgentPromptFile.readText())
                        }
                        if (target == "all" || target == "model_council") {
                            put("model_council", promptConfigRepository.modelCouncilPromptFile.readText())
                        }
                    })
                }
            }

            "update_image_generation" -> {
                val current = promptConfigRepository.readImageConfig()
                val next = ImagePromptInjectionConfig(
                    enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        ?: current.enabled,
                    defaultPrompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: current.defaultPrompt,
                    negativePrompt = obj["negative_prompt"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: current.negativePrompt,
                )
                val result = promptConfigRepository.writeImageConfig(next)
                buildWriteResponse(result.file.absolutePath, result.updatedId)
            }

            "update_context_compaction_prompt" -> {
                val prompt = obj.stringValue("prompt")
                require(prompt.isNotBlank()) { "prompt is required" }
                val result = promptConfigRepository.writeContextCompactionPrompt(prompt)
                buildWriteResponse(result.file.absolutePath, result.updatedId)
            }

            "update_subagent_prompt" -> {
                val subAgentId = obj.stringValue("subagent_id")
                val prompt = obj.stringValue("prompt")
                require(subAgentId.isNotBlank()) { "subagent_id is required" }
                require(prompt.isNotBlank()) { "prompt is required" }
                val settings = settingsStore.settingsFlow.value
                val (nextSubAgent, result) = promptConfigRepository.writeSubAgentPrompt(
                    setting = settings.agentRuntime.subAgent,
                    subAgentId = subAgentId,
                    prompt = prompt,
                )
                settingsStore.update(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(subAgent = nextSubAgent)
                    )
                )
                buildWriteResponse(result.file.absolutePath, result.updatedId)
            }

            "update_council_seat_prompt" -> {
                val seatKey = obj.stringValue("seat_id_or_role")
                val prompt = obj.stringValue("prompt")
                require(seatKey.isNotBlank()) { "seat_id_or_role is required" }
                require(prompt.isNotBlank()) { "prompt is required" }
                val settings = settingsStore.settingsFlow.value
                val (nextCouncil, result) = promptConfigRepository.writeModelCouncilSeatPrompt(
                    setting = settings.agentRuntime.modelCouncil,
                    seatKey = seatKey,
                    prompt = prompt,
                )
                settingsStore.update(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(modelCouncil = nextCouncil)
                    )
                )
                buildWriteResponse(result.file.absolutePath, result.updatedId)
            }

            "sync_markdown_to_settings" -> {
                val settings = settingsStore.settingsFlow.value
                val nextSubAgent = promptConfigRepository.applySubAgentMarkdownToSetting(
                    settings.agentRuntime.subAgent
                )
                val nextCouncil = promptConfigRepository.applyModelCouncilMarkdownToSetting(
                    settings.agentRuntime.modelCouncil
                )
                settingsStore.update(
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            subAgent = nextSubAgent,
                            modelCouncil = nextCouncil,
                        )
                    )
                )
                buildJsonObject {
                    put("status", "ok")
                    put("synced", true)
                    put("subagents", promptConfigRepository.subAgentPromptFile.absolutePath)
                    put("model_council", promptConfigRepository.modelCouncilPromptFile.absolutePath)
                }
            }

            else -> error("Unsupported action: $action")
        }
        listOf(UIMessagePart.Text(response.toString()))
    }
)

private fun buildWriteResponse(file: String, updatedId: String) = buildJsonObject {
    put("status", "ok")
    put("updated", updatedId)
    put("file", file)
}

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
