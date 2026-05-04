package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    val installedNames = allSkills.map { it.name }.toSet()
    val missingEnabled = enabledSkills.filter { it !in installedNames }.sorted()
    val disabled = allSkills.filter { it.name !in enabledSkills }

    return buildList {
        add(
            Tool(
                name = "skills_list",
                description = """
                    List AmberAgent skills and their load status. Use this first when you are unsure which skills are installed, enabled, disabled, or missing.
                """.trimIndent().replace("\n", " "),
                systemPrompt = { _, _ ->
                    buildString {
                        appendLine("**Skill library status**")
                        appendLine("Installed skills: ${allSkills.size}. Enabled skills: ${available.size}.")
                        appendLine("If you are unsure which skill is available, call `skills_list` before choosing `use_skill`.")
                        if (available.isNotEmpty()) {
                            appendLine("<available_skills>")
                            available.forEach { skill ->
                                appendLine("  <skill>")
                                appendLine("    <name>${skill.name}</name>")
                                appendLine("    <description>${skill.description}</description>")
                                appendLine("  </skill>")
                            }
                            appendLine("</available_skills>")
                        }
                        if (disabled.isNotEmpty()) {
                            appendLine("Some installed skills are disabled. `use_skill` can only load enabled skills.")
                        }
                        if (missingEnabled.isNotEmpty()) {
                            appendLine("Some configured enabled skills are missing from disk. Call `skills_list` for details.")
                        }
                    }
                },
                parameters = {
                    InputSchema.Obj(properties = buildJsonObject {})
                },
                execute = {
                    val payload = buildJsonObject {
                        put("installed_count", allSkills.size)
                        put("enabled_count", available.size)
                        put("configured_enabled_count", enabledSkills.size)
                        put("available_skills", buildSkillArray(available))
                        put("disabled_installed_skills", buildSkillArray(disabled))
                        put("missing_enabled_skills", buildJsonArray {
                            missingEnabled.forEach { name -> add(name) }
                        })
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            )
        )

        if (available.isEmpty()) return@buildList

        add(
            Tool(
                name = "use_skill",
                description = """
                    Load and apply a skill to get specialized instructions or capabilities.
                    Call this tool when the user's request matches one of the available skills.
                """.trimIndent(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "The name of the skill to use")
                            })
                            put("path", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                                )
                            })
                        },
                        required = listOf("name")
                    )
                },
                execute = {
                    val name = it.jsonObject["name"]?.jsonPrimitive?.content
                        ?: error("name is required")
                    if (name !in enabledSkills) {
                        error("Skill '$name' is not enabled. Call skills_list to see installed and enabled skills.")
                    }
                    val path = it.jsonObject["path"]?.jsonPrimitive?.content
                    val content = if (path.isNullOrBlank()) {
                        skillManager.readSkillBody(name)
                            ?: error("Skill '$name' not found")
                    } else {
                        val target = skillManager.resolveSkillFile(name, path)
                            ?: error("Path '$path' is outside the skill directory")
                        require(target.exists()) { "File '$path' not found in skill '$name'" }
                        target.readText()
                    }
                    listOf(UIMessagePart.Text(content))
                }
            )
        )
    }
}

private fun buildSkillArray(skills: List<SkillMetadata>) = buildJsonArray {
    skills.forEach { skill ->
        add(
            buildJsonObject {
                put("name", skill.name)
                put("description", skill.description)
                skill.compatibility?.takeIf { it.isNotBlank() }?.let { compatibility ->
                    put("compatibility", compatibility)
                }
                put("allowed_tools", buildJsonArray {
                    skill.allowedTools.forEach { tool -> add(tool) }
                })
                put("contains_mcp_config", skill.skillDir.resolve("mcp.json").exists())
            }
        )
    }
}
