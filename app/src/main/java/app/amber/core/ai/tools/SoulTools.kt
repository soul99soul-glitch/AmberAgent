package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.prompts.AgentSoulStore
import app.amber.feature.prompts.SoulImportTransaction
import app.amber.feature.workspace.WorkspaceManager

const val AGENT_SOUL_GET_TOOL_NAME = "agent_soul_get"
const val AGENT_SOUL_UPDATE_TOOL_NAME = "agent_soul_update"

/**
 * 常驻直出工具 `agent_soul_get` 与 `agent_soul_update`（C-SOUL-V1 协议）。
 *
 * 注册在主对话 Always-Loaded / Core Tools 中，供模型在用户要求更换人设、角色身份、
 * 性格、说话口吻或称呼时直接读取或持久更新「形象人设」（自动保存最近 8 条历史快照并异步同步云端）。
 */
fun createAgentSoulTools(
    store: AgentSoulStore,
): List<Tool> = listOf(
    Tool(
        name = AGENT_SOUL_GET_TOOL_NAME,
        description = "Read the current agent persona (形象人设 / Soul): display_name, one-line tagline, soul_markdown, revision, is_default, and recent history snapshots. Read-only and approval-free.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList(),
            )
        },
        needsApproval = false,
        allowsAutoApproval = true,
        execute = {
            val state = store.state.value
            val payload = buildJsonObject {
                put("ok", true)
                put("status", "ok")
                put("revision", state.revision)
                put("updatedAtMs", state.updatedAtMs)
                put("displayName", state.displayName)
                put("display_name", state.displayName)
                put("tagline", state.tagline)
                put("soulMarkdown", state.soulMarkdown)
                put("soul_markdown", state.soulMarkdown)
                put("isDefault", state.isDefault)
                put("is_default", state.isDefault)
                put("source", state.source)
                put("snapshotCount", state.history.size)
                put("snapshotDigest", store.snapshotDigest(state))
                put("history", buildJsonArray {
                    state.history.forEach { snap ->
                        add(buildJsonObject {
                            put("id", snap.id)
                            put("displayName", snap.displayName)
                            put("tagline", snap.tagline)
                            put("soulMarkdown", snap.soulMarkdown)
                            put("updatedAtMs", snap.updatedAtMs)
                            put("source", snap.source)
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = AGENT_SOUL_UPDATE_TOOL_NAME,
        description = "Create, update, or reset the agent's persistent persona (形象人设 / Soul). Call this directly whenever the user asks to change your persona, character identity, personality, speaking tone, or display name. Underlying honest execution rules remain separate; never refuse a persona change by claiming SOUL.md cannot be edited. Previous versions are snapshotted automatically (up to 8) for instant rollback.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("display_name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional display name / how the user calls you (e.g. 赵匡胤, 赵大哥). Shown in header and chat.")
                    })
                    put("tagline", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional one-line persona signature / vibe summary.")
                    })
                    put("soul_markdown", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional full persona markdown: character identity, personality, speaking tone, background, and how you interact with the user.")
                    })
                    put("reset_to_default", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Optional. Set to true to reset the persona back to the default Amber identity.")
                    })
                },
                required = emptyList(),
            )
        },
        needsApproval = false,
        allowsAutoApproval = true,
        execute = { input ->
            val obj = runCatching { input.jsonObject }.getOrNull()
            val resetToDefault = obj?.get("reset_to_default")?.jsonPrimitive?.let {
                it.booleanOrNull ?: it.contentOrNull?.equals("true", ignoreCase = true)
            } ?: obj?.get("resetToDefault")?.jsonPrimitive?.let {
                it.booleanOrNull ?: it.contentOrNull?.equals("true", ignoreCase = true)
            } ?: false

            val displayName = (obj?.get("display_name") ?: obj?.get("displayName"))
                ?.jsonPrimitive?.contentOrNull
            val tagline = obj?.get("tagline")?.jsonPrimitive?.contentOrNull
            val soulMarkdown = (
                obj?.get("soul_markdown")
                    ?: obj?.get("soulMarkdown")
                    ?: obj?.get("soul_prompt")
                    ?: obj?.get("soulPrompt")
                )?.jsonPrimitive?.contentOrNull

            if (
                !resetToDefault &&
                displayName.isNullOrBlank() &&
                tagline == null &&
                soulMarkdown.isNullOrBlank()
            ) {
                val errorPayload = buildJsonObject {
                    put("status", "error")
                    put("error_code", "empty_update")
                    put("message", "Provide at least one of display_name, tagline, soul_markdown, or set reset_to_default=true.")
                }
                return@Tool listOf(UIMessagePart.Text(errorPayload.toString()))
            }

            val changed = store.update(
                displayName = displayName,
                tagline = tagline,
                soulMarkdown = soulMarkdown,
                resetToDefault = resetToDefault,
                source = AgentSoulStore.SOURCE_ANDROID_CHAT,
            )
            val updated = store.state.value
            val payload = buildJsonObject {
                put("ok", true)
                put("status", "ok")
                put("changed", changed)
                put("revision", updated.revision)
                put("updatedAtMs", updated.updatedAtMs)
                put("displayName", updated.displayName)
                put("display_name", updated.displayName)
                put("tagline", updated.tagline)
                put("soulMarkdown", updated.soulMarkdown)
                put("soul_markdown", updated.soulMarkdown)
                put("isDefault", updated.isDefault)
                put("is_default", updated.isDefault)
                put("source", updated.source)
                put("snapshotCount", updated.history.size)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)

/**
 * P2-07 agent-driven soul (agentSoulMarkdown) update tools
 * (docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md
 * §P2-07).
 *
 * - `soul_preview`: read-only; prepares a candidate from workspace/SOUL.md
 *   (or an explicit workspace file), returning diff / source / impact scope /
 *   base+candidate digests and the bound approval digest.
 * - `soul_import`: needs approval; the call carries the bound digest and
 *   apply re-reads both sides (CAS). The replaced soul is kept for one
 *   rollback; the audit goes to the approval history.
 * - `soul_rollback`: one explicit rollback of the last import.
 *
 * The existing manual editing path (SettingAgentMemoryPage) is untouched —
 * the tools never bypass the manual-edit permission, and a soul update never
 * touches skills, MCP configs or recipes.
 */
fun createSoulTools(
    workspaceManager: WorkspaceManager,
    transaction: SoulImportTransaction,
    runId: String? = null,
): List<Tool> = listOf(
    Tool(
        name = "soul_preview",
        description = "Preview an agent soul (app-level behavior guide injected into every conversation) candidate from /workspace/SOUL.md or another workspace file: diff vs the current soul, impact scope and the approval digest. Read-only — nothing is changed.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("workspace_path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Workspace path of the soul candidate. Defaults to SOUL.md at the workspace root."
                        )
                    })
                }
            )
        },
        execute = { input ->
            val path = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull
                ?: SoulImportTransaction.DEFAULT_SOUL_FILE
            val markdown = workspaceManager.readTextCapped(
                path,
                SoulImportTransaction.MAX_SOUL_CHARS + 1,
            ).content
            when (val preparation = transaction.prepare(path, markdown)) {
                is SoulImportTransaction.Preparation.Rejected ->
                    error("Soul candidate rejected: ${preparation.errors.joinToString("; ")}")
                is SoulImportTransaction.Preparation.Ready -> {
                    val preview = preparation.preview
                    val payload = buildJsonObject {
                        put("status", "ready")
                        put("source", preview.source)
                        put("char_count", preview.charCount)
                        put("risk", preview.risk)
                        put("impact_scope", preview.impactScope)
                        put("base_digest", preview.baseDigest)
                        put("candidate_digest", preview.candidateDigest)
                        put("digest", preview.digest)
                        put("diff", preview.diff)
                        put(
                            "import_instruction",
                            "Call soul_import with workspace_path and preview_digest=${preview.digest} after the user approves."
                        )
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            }
        }
    ),
    Tool(
        name = "soul_import",
        description = "Apply a soul candidate from workspace/SOUL.md (or another workspace file) after soul_preview. The preview_digest must match the current candidate; the update is rejected as stale if the soul or the candidate changed in the meantime. The previous soul is kept for one rollback.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("workspace_path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Workspace path of the soul candidate. Defaults to SOUL.md at the workspace root."
                        )
                    })
                    put("preview_digest", buildJsonObject {
                        put("type", "string")
                        put("description", "The digest returned by soul_preview for this candidate.")
                    })
                },
                required = listOf("preview_digest")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val path = input.jsonObject["workspace_path"]?.jsonPrimitive?.contentOrNull
                ?: SoulImportTransaction.DEFAULT_SOUL_FILE
            val previewDigest = input.jsonObject["preview_digest"]?.jsonPrimitive?.contentOrNull
                ?: error("preview_digest is required")
            // Apply-time re-read of the candidate (after approval): any change
            // since the preview fails the CAS instead of importing new content.
            val markdown = workspaceManager.readTextCapped(
                path,
                SoulImportTransaction.MAX_SOUL_CHARS + 1,
            ).content
            val sessionId = java.util.UUID.randomUUID().toString()
            when (val result = transaction.apply(sessionId, runId, previewDigest, markdown)) {
                is SoulImportTransaction.ApplyResult.Rejected ->
                    error("Soul candidate rejected: ${result.errors.joinToString("; ")}")
                is SoulImportTransaction.ApplyResult.Stale -> error(result.reason)
                is SoulImportTransaction.ApplyResult.Applied -> {
                    val payload = buildJsonObject {
                        put("success", true)
                        put("char_count", result.charCount)
                        put("note", "The soul is now injected into every conversation; the previous version is kept for one rollback (soul_rollback).")
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            }
        }
    ),
    Tool(
        name = "soul_rollback",
        description = "Roll back the most recent soul import to the previous soul version. Can be executed only once per import.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = {
            val sessionId = java.util.UUID.randomUUID().toString()
            when (val result = transaction.rollback(sessionId, runId)) {
                is SoulImportTransaction.RollbackResult.NoPrevious -> error(result.reason)
                is SoulImportTransaction.RollbackResult.Stale -> error(result.reason)
                is SoulImportTransaction.RollbackResult.RolledBack -> {
                    val payload = buildJsonObject {
                        put("success", true)
                        put("rolled_back", true)
                        put("char_count", result.charCount)
                        put("note", "The previous soul was restored; a second rollback is not possible.")
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            }
        }
    ),
)
