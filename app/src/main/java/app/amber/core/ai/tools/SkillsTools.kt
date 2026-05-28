package app.amber.core.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
    settingsStore: SettingsAggregator,
    workspaceManager: WorkspaceManager,
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

        add(skillValidateTool(skillManager, workspaceManager))
        add(skillImportTool(skillManager, settingsStore, workspaceManager))
        add(skillEnableTool(settingsStore, enable = true))
        add(skillEnableTool(settingsStore, enable = false))

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
                        if (!target.exists()) {
                            val available = listSkillFilesShort(skillManager, name)
                            val hint = if (available.isNotBlank()) {
                                "This skill only ships with these files: $available. Do not retry with other paths from SKILL.md links — those reference files were not bundled. Re-read SKILL.md (omit the path argument) and follow its inline instructions directly."
                            } else {
                                "This skill ships with only SKILL.md. Re-read it (omit the path argument) and follow its inline instructions without fetching sub-files."
                            }
                            error("File '$path' not found in skill '$name'. $hint")
                        }
                        target.readText()
                    }
                    listOf(UIMessagePart.Text(wrapSkillForMobileRuntime(name, path, content)))
                }
            )
        )
    }
}

private fun wrapSkillForMobileRuntime(skillName: String, filePath: String?, body: String): String {
    val pathLabel = filePath?.takeIf { it.isNotBlank() } ?: "SKILL.md"
    return buildString {
        appendLine("[AmberAgent Mobile Runtime — applies to the skill content below]")
        appendLine("You are running inside AmberAgent on an Android phone/tablet — NOT desktop Claude Code, NOT Codex, NOT a CLI environment.")
        appendLine("These mobile constraints OVERRIDE any conflicting instruction in the skill body:")
        appendLine("- The user has no physical keyboard, no mouse, no system shell. Never write \"press ← →\", \"F for fullscreen\", \"S for speaker mode\", \"Ctrl+C to quit\", or any keyboard/mouse hint into your visible reply or into widget content.")
        appendLine("- There is no system browser to open .html / .pdf / .pptx files for preview. Anything visual must render inside the chat as a show-widget block (SVG, HTML, vchart, slides) so it appears as a card in the conversation timeline.")
        appendLine("- For multi-page presentations / decks / PPT / 幻灯片 / 演示文稿, emit a show-widget deck preview as the final visible artifact. Generic decks use renderer \"slides\" with AmberAgent Slides Spec V2; guizang-ppt-skill decks use renderer \"guizang_html\" by default. Do NOT generate a .pptx file as the only deliverable; do NOT pack a multi-page deck into a single SVG grid; do NOT turn PPT requests into MiniApps.")
        appendLine("- Do NOT recommend running npm/pip/curl/python or installing desktop tooling unless the skill explicitly invokes the in-app terminal_execute tool (Alpine sandbox).")
        appendLine("- File outputs go to /workspace via file_write; users browse them through the in-app file sheet, not through Finder/Explorer.")
        appendLine("- If the skill describes a desktop-only workflow, translate it into the mobile equivalent: replace \"open in PowerPoint\" with \"emit a show-widget deck preview\", \"open in browser\" with \"emit the appropriate show-widget renderer\", etc.")
        appendLine("- IMPORTANT about use_skill paths: many skills installed via download only ship the SKILL.md file — the references/, scripts/, assets/ subfolders mentioned in the SKILL.md links may NOT exist locally. Do NOT chain a second use_skill(path=...) call just because SKILL.md links to it; treat SKILL.md as self-contained instructions and only retry with a path if a previous call confirms that file exists.")
        if (skillName.contains("guizang-ppt", ignoreCase = true)) {
            appendLine("- guizang-ppt-skill SPECIAL MOBILE ADAPTER: the default and preferred output is a `show-widget` using `renderer:\"guizang_html\"`, not renderer:\"slides\", not widget_code HTML, not a MiniApp, and not a standalone saved HTML page. Always give the user an inline PPT preview card in the chat.")
            appendLine("- Required guizang_html skeleton: `spec.html` contains one `<div id=\"deck\">` wrapper and 6-10 `<section class=\"slide ...\" data-animate=\"...\">...</section>` pages copied from/adapted to the guizang template style. `widget_code` is only a tiny static SVG cover.")
            appendLine("- Runtime scripts must be AmberAgent bundled assets: Lucide `<script src=\"${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}\"></script>` and Motion `await import('${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}')`. Do NOT use unpkg/jsdelivr/skypack/CDN script URLs.")
            appendLine("- Keep the skill's look directly in HTML/CSS: magazine/Swiss typography, grid layout, canvas/WebGL/ASCII backgrounds, Motion data-animate recipes, lucide `<i data-lucide=\"...\">` icons, touch-friendly swipe. Do NOT add Android bridge calls, popups, downloads, file/content/intent/android_asset URLs, flat non-deck pages, or keyboard-only instructions.")
        }
        appendLine()
        appendLine("Skill: $skillName  ($pathLabel)")
        appendLine("--- skill content begins ---")
        append(body)
        if (!body.endsWith("\n")) appendLine()
        appendLine("--- skill content ends ---")
        appendLine()
        appendLine("Reminder: the mobile constraints above take priority. If the skill says \"open in a browser\" or \"add keyboard shortcuts\", you ignore that part and use the AmberAgent-native equivalent.")
    }
}

private fun listSkillFilesShort(skillManager: SkillManager, name: String, max: Int = 20): String {
    val dir = skillManager.getSkillDir(name) ?: return ""
    if (!dir.exists() || !dir.isDirectory) return ""
    return dir.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(dir).invariantSeparatorsPath }
        .take(max)
        .joinToString(", ")
}

private fun skillValidateTool(skillManager: SkillManager, workspaceManager: WorkspaceManager) = Tool(
    name = "skill_validate",
    description = "Validate an installed skill by name or a /workspace skill folder/zip before import.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Installed skill name.")
                })
                put("workspace_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace skill folder, SKILL.md, or zip archive.")
                })
            }
        )
    },
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        val workspacePath = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull
        val files = when {
            !name.isNullOrBlank() -> mapOf("SKILL.md" to (skillManager.readSkillContent(name) ?: ""))
            !workspacePath.isNullOrBlank() -> collectWorkspaceSkillFiles(workspaceManager, workspacePath)
            else -> error("name or workspace_path is required")
        }
        val skillMd = files["SKILL.md"].orEmpty()
        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val issues = buildList {
            if (skillMd.isBlank()) add("缺少 SKILL.md")
            if (frontmatter["name"].isNullOrBlank()) add("SKILL.md 缺少 name")
            if (frontmatter["description"].isNullOrBlank()) add("SKILL.md 缺少 description")
        }
        val payload = buildJsonObject {
            put("valid", issues.isEmpty())
            put("name", frontmatter["name"].orEmpty())
            put("description", frontmatter["description"].orEmpty())
            put("file_count", files.size)
            put("contains_mcp_config", files.containsKey("mcp.json"))
            put("issues", buildJsonArray { issues.forEach { add(it) } })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun skillImportTool(
    skillManager: SkillManager,
    settingsStore: SettingsAggregator,
    workspaceManager: WorkspaceManager,
) = Tool(
    name = "skill_import",
    description = "Import a skill folder, SKILL.md file, or zip archive from /workspace. Imported skills are enabled by default.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workspace_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace path to a skill folder, SKILL.md, or zip archive.")
                })
            },
            required = listOf("workspace_path")
        )
    },
    needsApproval = true,
    execute = { input ->
        val workspacePath = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull ?: error("workspace_path is required")
        val files = collectWorkspaceSkillFiles(workspaceManager, workspacePath)
        val skillMd = files["SKILL.md"] ?: error("Skill package does not contain SKILL.md")
        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: error("SKILL.md missing name")
        require(!frontmatter["description"].isNullOrBlank()) { "SKILL.md missing description" }
        val saved = skillManager.saveSkillFilesAtomically(name, files)
        require(saved) { "Failed to save skill files" }
        updateEnabledSkill(settingsStore, name, enable = true)
        val payload = buildJsonObject {
            put("success", true)
            put("name", name)
            put("file_count", files.size)
            put("enabled", true)
            put("contains_mcp_config", files.containsKey("mcp.json"))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

private fun skillEnableTool(settingsStore: SettingsAggregator, enable: Boolean) = Tool(
    name = if (enable) "skill_enable" else "skill_disable",
    description = if (enable) {
        "Enable an installed skill for the default AmberAgent."
    } else {
        "Disable an installed skill for the default AmberAgent."
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill name.")
                })
            },
            required = listOf("name")
        )
    },
    needsApproval = true,
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        updateEnabledSkill(settingsStore, name, enable)
        val payload = buildJsonObject {
            put("success", true)
            put("name", name)
            put("enabled", enable)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

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

private suspend fun updateEnabledSkill(settingsStore: SettingsAggregator, name: String, enable: Boolean) {
    settingsStore.update { settings ->
        val currentId = settings.getCurrentAssistant().id
        settings.copy(
            assistants = settings.assistants.map { assistant ->
                if (assistant.id != currentId) return@map assistant
                val next = if (enable) assistant.enabledSkills + name else assistant.enabledSkills - name
                assistant.copy(enabledSkills = next)
            }
        )
    }
}

private suspend fun collectWorkspaceSkillFiles(workspaceManager: WorkspaceManager, workspacePath: String): Map<String, String> {
    val normalized = workspacePath.trim().removePrefix("/workspace/").trim('/')
    require(normalized.isNotBlank()) { "workspace_path must not be empty" }
    val bytes = runCatching { workspaceManager.readBytes(normalized) }.getOrNull()
    if (bytes != null) {
        if (normalized.endsWith(".zip", ignoreCase = true)) {
            return unzipSkillFiles(bytes)
        }
        val relativeName = normalized.substringAfterLast('/').ifBlank { "SKILL.md" }.canonicalSkillFileName()
        return mapOf(relativeName to bytes.decodeToString())
    }

    val files = linkedMapOf<String, String>()
    suspend fun walk(dir: String, root: String) {
        workspaceManager.list(dir).forEach { entry ->
            val relative = entry.path.removePrefix(root).trim('/')
            if (entry.directory) {
                walk(entry.path, root)
            } else if (isLikelyTextSkillFile(entry.name)) {
                files[relative.ifBlank { entry.name }.canonicalSkillFileName()] =
                    workspaceManager.readBytes(entry.path).decodeToString()
            }
        }
    }
    walk(normalized, normalized)
    return files
}

private fun unzipSkillFiles(bytes: ByteArray): Map<String, String> {
    val files = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val clean = entry.name.trim('/').substringAfter('/')
            val name = clean.ifBlank { entry.name.substringAfterLast('/') }.canonicalSkillFileName()
            if (name.isBlank() || name.contains("..") || !isLikelyTextSkillFile(name)) continue
            files[name] = zip.readBytes().decodeToString()
        }
    }
    return files
}

private fun isLikelyTextSkillFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "skill.md" ||
        lower == "skill.txt" ||
        lower == "mcp.json" ||
        lower.endsWith(".md") ||
        lower.endsWith(".md.txt") ||
        lower.endsWith(".json") ||
        lower.endsWith(".txt") ||
        lower.endsWith(".yaml") ||
        lower.endsWith(".yml") ||
        lower.endsWith(".js") ||
        lower.endsWith(".ts") ||
        lower.endsWith(".py") ||
        lower.endsWith(".sh")
}

private fun String.canonicalSkillFileName(): String {
    val normalized = trim('/').replace('\\', '/')
    val lower = normalized.lowercase()
    return when {
        lower == "skill.txt" || lower.endsWith("/skill.txt") ->
            normalized.substringBeforeLast('/', missingDelimiterValue = "")
                .let { parent -> if (parent.isBlank()) "SKILL.md" else "$parent/SKILL.md" }

        lower == "skill.md.txt" || lower.endsWith("/skill.md.txt") ->
            normalized.dropLast(4)

        lower.endsWith(".md.txt") ->
            normalized.dropLast(4)

        else -> normalized
    }
}
