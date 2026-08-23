package app.amber.core.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.getCurrentAssistant
import app.amber.core.files.SkillManager
import app.amber.core.files.SkillFrontmatterParser
import app.amber.core.files.SkillMetadata
import app.amber.core.files.SkillPromotionTransaction
import app.amber.core.files.SkillReadBoundary
import app.amber.core.files.readResources
import app.amber.core.files.walkSkillFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager,
    settingsStore: SettingsAggregator,
    workspaceManager: WorkspaceManager,
    casLedger: app.amber.feature.runtime.CasLedger? = null,
    runId: String? = null,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    val installedNames = allSkills.map { it.name }.toSet()
    val missingEnabled = enabledSkills.filter { it !in installedNames }.sorted()
    val disabled = allSkills.filter { it.name !in enabledSkills }
    val promotionTransaction = SkillPromotionTransaction(skillManager, casLedger)

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
        add(skillPreviewTool(skillManager, workspaceManager, promotionTransaction))
        add(skillImportTool(skillManager, settingsStore, workspaceManager, promotionTransaction, runId))
        add(skillRollbackTool(skillManager, promotionTransaction, runId))
        add(skillEnableTool(settingsStore, enable = true))
        add(skillEnableTool(settingsStore, enable = false))

        if (available.isEmpty()) return@buildList

        add(
            Tool(
                name = "use_skill",
                description = """
                    Load and apply a skill to get specialized instructions or capabilities.
                    Call this tool when the user's request matches one of the available skills.
                    Only SKILL.md and files listed in the skill manifest's read-resources entry are readable; do not guess file paths.
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
                                    "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths listed in the read-resources entry of the SKILL.md frontmatter, or the file list returned by a failed read. Do NOT guess or infer paths."
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
                    val skillDir = skillManager.getSkillDir(name)
                        ?: error("Skill '$name' not found")
                    val skillFile = skillDir.resolve("SKILL.md")
                    if (!skillFile.exists()) error("Skill '$name' not found")
                    val content = if (path.isNullOrBlank()) {
                        val skillMd = skillFile.inputStream().use { input ->
                            input.readBytesWithinLimit(SkillReadBoundary.MAX_SKILL_FILE_BYTES, "SKILL.md").decodeToString()
                        }
                        SkillFrontmatterParser.extractBody(skillMd)
                    } else {
                        val skillMd = skillFile.inputStream().use { input ->
                            input.readBytesWithinLimit(SkillReadBoundary.MAX_SKILL_FILE_BYTES, "SKILL.md").decodeToString()
                        }
                        val readResources = SkillFrontmatterParser.readResources(SkillFrontmatterParser.parse(skillMd))
                        // P2-03: model-readable boundary — manifest allowlist +
                        // realpath containment + permanent blocklist. mcp.json,
                        // secrets and symlink escapes never reach the model.
                        when (val result = SkillReadBoundary.readForModel(skillDir, path, readResources)) {
                            is SkillReadBoundary.ReadResult.Ok -> result.content
                            is SkillReadBoundary.ReadResult.Rejected -> {
                                val readable = SkillReadBoundary.listReadableFiles(skillDir, readResources)
                                val hint = if (readable.isNotEmpty()) {
                                    "This skill exposes these readable files: ${readable.joinToString(", ")}. Do not retry with other paths."
                                } else {
                                    "This skill ships with only SKILL.md. Re-read it (omit the path argument) and follow its inline instructions without fetching sub-files."
                                }
                                error("${result.detail}. $hint")
                            }
                        }
                    }
                    listOf(UIMessagePart.Text(wrapSkillForMobileRuntime(name, path, content)))
                }
            )
        )
    }
}

private fun wrapSkillForMobileRuntime(skillName: String, filePath: String?, body: String): String {
    return buildSkillMobileRuntimePrompt(skillName, filePath, body)
}

internal fun buildSkillMobileRuntimePrompt(skillName: String, filePath: String?, body: String): String {
    val pathLabel = filePath?.takeIf { it.isNotBlank() } ?: "SKILL.md"
    return buildString {
        appendLine("[AmberAgent Mobile Runtime — applies to the skill content below]")
        appendLine("You are running inside AmberAgent on an Android phone/tablet — NOT desktop Claude Code, NOT Codex, NOT a CLI environment.")
        appendLine("These mobile constraints OVERRIDE any conflicting instruction in the skill body:")
        appendLine("- The user has no physical keyboard, no mouse, no system shell. Never write \"press ← →\", \"F for fullscreen\", \"S for speaker mode\", \"Ctrl+C to quit\", or any keyboard/mouse hint into your visible reply or into widget content.")
        appendLine("- There is no system browser to open .html / .pdf / .pptx files for preview. Visual previews should render inside the chat as show-widget blocks (SVG, HTML, vchart, slides) so they appear as cards in the conversation timeline.")
        appendLine("- For multi-page presentations / decks / PPT / 幻灯片 / 演示文稿, emit one final show-widget deck preview only after the deck HTML is complete. Use renderer \"${GuizangHtmlDeckValidator.RENDERER}\" with the full live deck HTML in spec.html. During generation, use short progress text or a tiny SVG widget_code cover/status; never emit partial spec.html. Do NOT generate a .pptx file as the only deliverable; do NOT pack a multi-page deck into a single SVG grid; do NOT turn PPT requests into MiniApps.")
        appendLine("- Do NOT recommend running npm/pip/curl/python or installing desktop tooling unless the skill explicitly invokes the in-app terminal_execute tool (Alpine sandbox).")
        appendLine("- File outputs go to /workspace via file_write; users browse them through the in-app file sheet, not through Finder/Explorer.")
        appendLine("- If the skill describes a desktop-only workflow, translate it into the mobile equivalent: replace \"open in PowerPoint\" with \"emit a show-widget deck preview\", \"open in browser\" with \"emit the appropriate show-widget renderer\", etc.")
        appendLine("- IMPORTANT about use_skill paths: many skills installed via download only ship the SKILL.md file — the references/, scripts/, assets/ subfolders mentioned in the SKILL.md links may NOT exist locally. Do NOT chain a second use_skill(path=...) call just because SKILL.md links to it; treat SKILL.md as self-contained instructions and only retry with a path if a previous call confirms that file exists.")
        if (skillName.contains("guizang-ppt", ignoreCase = true)) {
            appendLine("- guizang-ppt-skill SPECIAL MOBILE ADAPTER: the default and preferred final output is a complete `show-widget` using `renderer:\"${GuizangHtmlDeckValidator.RENDERER}\"`, not renderer:\"slides\", not widget_code HTML, not a MiniApp, and not a standalone saved HTML page. Give the user an inline PPT preview card in the chat after the HTML is complete; while generating, show only concise progress or a tiny SVG cover/status.")
            appendLine("- Required full_html skeleton: `spec.html` contains one `<div id=\"deck\">` wrapper and 6-10 `<section class=\"slide ...\" data-animate=\"...\">...</section>` pages copied from/adapted to the guizang template style. `widget_code` is only a tiny static SVG cover.")
            appendLine("- Runtime scripts must be AmberAgent bundled assets: Lucide `<script src=\"${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}\"></script>` and Motion `await import('${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}')`. Do NOT use unpkg/jsdelivr/skypack/CDN script URLs.")
            appendLine("- Keep the skill's look directly in HTML/CSS: magazine/Swiss typography, grid layout, canvas/WebGL/ASCII backgrounds, Motion data-animate recipes, lucide `<i data-lucide=\"...\">` icons, touch-friendly swipe. Do NOT add Android bridge calls, popups, downloads, file/content/intent/android_asset URLs, flat non-deck pages, or keyboard-only instructions.")
        }
        if (skillName.contains("guizang-social-card", ignoreCase = true)) {
            appendLine("- guizang-social-card-skill SPECIAL MOBILE ADAPTER: default chat preview output is one complete `show-widget` using `renderer:\"${GuizangHtmlDeckValidator.RENDERER}\"`. Do not output raw HTML as ordinary Markdown/code, a MiniApp, or a standalone saved HTML page as the only deliverable.")
            appendLine("- Required social-card full_html skeleton: `spec.html` contains one `<div id=\"deck\">` wrapper. Each card is one deck page: `<section class=\"slide social-card poster xhs\">...</section>`, `<section class=\"slide social-card poster wide\">...</section>`, or `<section class=\"slide social-card poster square\">...</section>`. A single social card is a one-slide deck; a carousel is a multi-slide deck.")
            appendLine("- `widget_code` is only a tiny static SVG cover/status thumbnail. Keep progress visible with concise text or the tiny cover; emit the full_html show-widget only after `spec.html` is complete, and never emit a partial or truncated `spec.html`.")
            appendLine("- Preview and export are separate: for normal \"show me / preview / make a card\" requests, render the inline full_html preview in chat. Only create /workspace files, PNG/JPG exports, or screenshot workflows when the user explicitly asks to export, save, share, or download image files.")
            appendLine("- Social-card HTML must stay mobile-native: inline CSS, touch-friendly sizing, no external CSS/CDN/runtime scripts, no iframe/form/nav/download/popups, no file/content/intent/android_asset URLs, and no desktop/browser/keyboard instructions.")
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
            !name.isNullOrBlank() -> collectInstalledSkillFiles(skillManager, name)
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

/**
 * P2-04 stage 1: preview a skill candidate (folder / SKILL.md / zip) without
 * touching the installed library — validation, SKILL.md diff, risk, base and
 * candidate digests and the bound approval digest. Read-only, no approval.
 */
private fun skillPreviewTool(
    skillManager: SkillManager,
    workspaceManager: WorkspaceManager,
    transaction: SkillPromotionTransaction,
) = Tool(
    name = "skill_preview",
    description = "Preview a skill candidate from /workspace (folder, SKILL.md, or zip) before importing: validation, SKILL.md diff, risk and the approval digest. Returns the digest to pass to skill_import.",
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
    execute = { input ->
        val workspacePath = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull
            ?: error("workspace_path is required")
        val files = collectWorkspaceSkillFiles(workspaceManager, workspacePath)
        val skillMd = files["SKILL.md"] ?: error("Skill package does not contain SKILL.md")
        val name = SkillFrontmatterParser.parse(skillMd)["name"]?.takeIf { it.isNotBlank() }
            ?: error("SKILL.md missing name")
        when (val preparation = transaction.prepare(name, files)) {
            is SkillPromotionTransaction.Preparation.Rejected ->
                error("Skill candidate rejected: ${preparation.errors.joinToString("; ")}")
            is SkillPromotionTransaction.Preparation.Ready -> {
                val preview = preparation.preview
                val payload = buildJsonObject {
                    put("status", "ready")
                    put("name", preview.name)
                    put("is_new", preview.isNew)
                    put("file_count", preview.fileCount)
                    put("changed_files", buildJsonArray { preview.changedFiles.forEach { add(it) } })
                    put("risk", preview.risk)
                    put("prompt_size_chars", preview.promptSizeChars)
                    put("base_digest", preview.baseDigest)
                    put("candidate_digest", preview.candidateDigest)
                    put("digest", preview.digest)
                    put("diff", preview.diff)
                    put("issues", buildJsonArray { preview.issues.forEach { add(it) } })
                    put("import_instruction", "Call skill_import with workspace_path and preview_digest=${preview.digest} after the user approves.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    }
)

/**
 * P2-04 stage 2: apply an approved skill candidate. The call carries the
 * bound digest from [skillPreviewTool]; the approval is bound to base+candidate
 * digest through the args digest, and apply re-reads both sides before the
 * atomic replace (stale → reject, never auto-overwrite). The replaced version
 * is kept for one explicit rollback; the audit goes to the approval history.
 */
private fun skillImportTool(
    skillManager: SkillManager,
    settingsStore: SettingsAggregator,
    workspaceManager: WorkspaceManager,
    transaction: SkillPromotionTransaction,
    runId: String?,
) = Tool(
    name = "skill_import",
    description = "Import a skill folder, SKILL.md file, or zip archive from /workspace after skill_preview. The preview_digest must match the current candidate. Imported skills are enabled by default.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workspace_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Workspace path to a skill folder, SKILL.md, or zip archive.")
                })
                put("preview_digest", buildJsonObject {
                    put("type", "string")
                    put("description", "The digest returned by skill_preview for this candidate. If the candidate changed since the preview, the import is rejected as stale.")
                })
            },
            required = listOf("workspace_path", "preview_digest")
        )
    },
    needsApproval = true,
    execute = { input ->
        val workspacePath = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull
            ?: error("workspace_path is required")
        val previewDigest = input.jsonObject["preview_digest"]?.jsonPrimitive?.contentOrNull
            ?: error("preview_digest is required")
        // Re-read the candidate AFTER approval (apply-time read): if the
        // workspace content changed since the preview, the digest mismatch
        // rejects the import instead of importing changed content.
        val files = collectWorkspaceSkillFiles(workspaceManager, workspacePath)
        val skillMd = files["SKILL.md"] ?: error("Skill package does not contain SKILL.md")
        val name = SkillFrontmatterParser.parse(skillMd)["name"]?.takeIf { it.isNotBlank() }
            ?: error("SKILL.md missing name")
        val sessionId = java.util.UUID.randomUUID().toString()
        when (val result = transaction.apply(name, files, sessionId, runId, previewDigest)) {
            is SkillPromotionTransaction.ApplyResult.Rejected ->
                error("Skill candidate rejected: ${result.errors.joinToString("; ")}")
            is SkillPromotionTransaction.ApplyResult.Stale ->
                error(result.reason)
            is SkillPromotionTransaction.ApplyResult.Applied -> {
                updateEnabledSkill(settingsStore, result.name, enable = true)
                val payload = buildJsonObject {
                    put("success", true)
                    put("name", result.name)
                    put("file_count", result.fileCount)
                    put("enabled", true)
                    put("contains_mcp_config", files.containsKey("mcp.json"))
                    put("digest", previewDigest)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    }
)

/**
 * P2-04 stage 3: one explicit rollback of the last promotion. Restores the
 * kept previous version and deletes it — a second rollback is impossible.
 */
private fun skillRollbackTool(
    skillManager: SkillManager,
    transaction: SkillPromotionTransaction,
    runId: String?,
) = Tool(
    name = "skill_rollback",
    description = "Roll back the most recent promotion of an installed skill to its previous version. Can be executed only once per promotion.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Skill name to roll back.")
                })
            },
            required = listOf("name")
        )
    },
    needsApproval = true,
    execute = { input ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val sessionId = java.util.UUID.randomUUID().toString()
        when (val result = transaction.rollback(name, sessionId, runId)) {
            is SkillPromotionTransaction.RollbackResult.NoPrevious -> error(result.reason)
            is SkillPromotionTransaction.RollbackResult.Stale -> error(result.reason)
            is SkillPromotionTransaction.RollbackResult.RolledBack -> {
                val payload = buildJsonObject {
                    put("success", true)
                    put("name", result.name)
                    put("rolled_back", true)
                    put("note", "The previous version was restored; a second rollback is not possible.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
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

private fun collectInstalledSkillFiles(skillManager: SkillManager, name: String): Map<String, String> =
    collectSkillFilesFromDirectory(skillManager.getSkillDir(name))

internal fun collectSkillFilesFromDirectory(dir: File?): Map<String, String> {
    if (dir == null) return emptyMap()
    val files = linkedMapOf<String, String>()
    walkSkillFiles(dir).forEach { (file, relative) ->
        val name = relative.canonicalSkillFileName()
        if (name.isBlank() || name.contains("..") || !isLikelyTextSkillFile(name)) return@forEach
        files[name] = file.inputStream().use { input ->
            input.readBytesWithinLimit(MAX_SKILL_FILE_BYTES, name).decodeToString()
        }
    }
    return files
}

internal fun unzipSkillFiles(bytes: ByteArray): Map<String, String> {
    val files = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val clean = entry.name.trim('/').substringAfter('/')
            val name = clean.ifBlank { entry.name.substringAfterLast('/') }.canonicalSkillFileName()
            if (name.isBlank() || name.contains("..") || !isLikelyTextSkillFile(name)) continue
            files[name] = zip.readBytesWithinLimit(MAX_SKILL_FILE_BYTES, entry.name).decodeToString()
        }
    }
    return files
}

private fun ZipInputStream.readBytesWithinLimit(limit: Int, entryName: String): ByteArray {
    return (this as java.io.InputStream).readBytesWithinLimit(limit, entryName)
}

private fun java.io.InputStream.readBytesWithinLimit(limit: Int, entryName: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Skill archive entry $entryName exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

private const val MAX_SKILL_FILE_BYTES = SkillReadBoundary.MAX_SKILL_FILE_BYTES

private fun isLikelyTextSkillFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "skill.md" ||
        lower == "skill.txt" ||
        lower == "mcp.json" ||
        lower.endsWith(".md") ||
        lower.endsWith(".md.txt") ||
        lower.endsWith(".json") ||
        lower.endsWith(".html") ||
        lower.endsWith(".htm") ||
        lower.endsWith(".css") ||
        lower.endsWith(".txt") ||
        lower.endsWith(".yaml") ||
        lower.endsWith(".yml") ||
        lower.endsWith(".js") ||
        lower.endsWith(".mjs") ||
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
