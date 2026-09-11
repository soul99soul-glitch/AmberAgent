package app.amber.core.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryCasDeleteResult
import app.amber.core.memory.store.MemoryCasUpdateResult
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.store.MemoryStaleException
import app.amber.core.model.AssistantMemory
import app.amber.core.utils.toLocalString
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CasAudit
import app.amber.feature.runtime.ContentDigest
import java.time.LocalDate
import java.util.Locale

data class MemoryToolWriteRequest(
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val source: String? = null,
    val sourceConversationId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val expiresAt: Long? = null,
    val confidence: Float = 1f,
    /** P2-06: run that triggered this write (pollution provenance). */
    val sourceRunId: String? = null,
    /** P2-06: write path trigger label ("tool"). */
    val sourceTrigger: String? = null,
)

internal const val DEFAULT_MEMORY_LIST_LIMIT = 50
internal const val MAX_MEMORY_LIST_LIMIT = 100
internal const val MEMORY_LIST_SUMMARY_MAX_CHARS = 1_000

/**
 * P2-06 memory write CAS (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §P2-06).
 *
 * - memory records carry a monotonic [AssistantMemory.revision]; memory_list
 *   exposes it and the model passes it back on edit/delete, so the approval
 *   (bound to the args digest) is also bound to the exact revision the user
 *   saw.
 * - apply re-reads the record: a revision mismatch rejects the stale approval
 *   with a structured `conflict` result — the diff is re-generated, never
 *   auto-overwritten.
 * - successful writes record an audit entry (old/new content digests and the
 *   triggering runId — hashes only, never the content itself) in the P2-01
 *   approval history via [onAudit].
 *
 * @param onUpdateCas CAS edit; only called with a concrete revision — a
 *   missing revision is rejected at the tool layer (revision_required) and
 *   never resolved to the latest at apply time.
 * @param onDeleteCas CAS delete with the same semantics.
 */
fun buildMemoryTools(
    json: Json,
    onList: suspend (String) -> List<AssistantMemory>,
    onCreation: suspend (MemoryToolWriteRequest) -> AssistantMemory,
    onUpdateCas: suspend (id: Int, content: String, expectedRevision: Long?) -> MemoryCasUpdateResult,
    onDeleteCas: suspend (id: Int, expectedRevision: Long?) -> MemoryCasDeleteResult,
    onAudit: suspend (ApprovalHistoryEntry) -> Unit = {},
    runIdProvider: () -> String? = { null },
    locale: Locale = Locale.getDefault(),
): List<Tool> = listOf(
    Tool(
        name = "memory_list",
        description = "List AmberAgent memory entries by type: core, short_term, long_term, or all. Each entry includes its revision — pass the revision back on edit/delete so the write is bound to the version you saw.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                                add("all")
                            }
                        )
                        put("description", "Memory type to list. Defaults to all.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_MEMORY_LIST_LIMIT)
                        put("default", DEFAULT_MEMORY_LIST_LIMIT)
                        put("description", "Maximum records to return. Defaults to $DEFAULT_MEMORY_LIST_LIMIT.")
                    })
                }
            )
        },
        execute = { input ->
            val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "all"
            require(type in setOf("core", "short_term", "long_term", "all")) {
                "type must be core, short_term, long_term, or all"
            }
            val limit = input.jsonObject["limit"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(1, MAX_MEMORY_LIST_LIMIT)
                ?: DEFAULT_MEMORY_LIST_LIMIT
            val entries = if (type == "all") {
                listOf("core", "short_term", "long_term").flatMap { scope -> onList(scope).map { scope to it } }
            } else {
                onList(type).map { type to it }
            }
            val visibleEntries = entries.take(limit)
            val payload = buildJsonObject {
                put("type", type)
                put("count", visibleEntries.size)
                put("total_count", entries.size)
                put("limit", limit)
                put("truncated", entries.size > visibleEntries.size)
                put("memories", buildJsonArray {
                    visibleEntries.forEach { (scope, memory) ->
                        add(memory.toJson(scope))
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_write",
        description = "Create a new AmberAgent memory entry. Core and long-term memory should be stable and important; short-term memory is for current project/task continuity.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                            }
                        )
                        put("description", "Memory type. Defaults to long_term.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Memory content.")
                    })
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional source note.")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                MemoryKind.entries.forEach { add(it.wireName) }
                            }
                        )
                        put("description", "Structured memory kind. Defaults to note.")
                    })
                    put("expiresAt", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional expiration time in epoch milliseconds.")
                    })
                },
                required = listOf("content")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val type = input.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "long_term"
            require(type in setOf("core", "short_term", "long_term")) {
                "type must be core, short_term, or long_term"
            }
            val content = input.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
            val source = input.jsonObject["source"]?.jsonPrimitive?.contentOrNull
            val kind = input.jsonObject["kind"]?.jsonPrimitive?.contentOrNull?.let(MemoryKind::fromWireName)
                ?: MemoryKind.NOTE
            val expiresAt = input.jsonObject["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val created = onCreation(
                MemoryToolWriteRequest(
                    scope = MemoryScope.fromWireName(type),
                    kind = kind,
                    content = content,
                    source = source,
                    expiresAt = expiresAt,
                    sourceRunId = runIdProvider(),
                    sourceTrigger = MemoryRepository.TRIGGER_TOOL,
                )
            )
            auditMemoryCreate(onAudit, runIdProvider, created)
            listOf(
                UIMessagePart.Text(
                    json.encodeToJsonElement(AssistantMemory.serializer(), created).toString()
                )
            )
        }
    ),
    Tool(
        name = "memory_delete",
        description = "Delete an AmberAgent memory entry by id. This is high risk and always requires explicit approval. Pass the revision from memory_list so the delete is bound to the version you saw.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Memory id to delete.")
                    })
                    put("revision", buildJsonObject {
                        put("type", "integer")
                        put("description", "Required revision from memory_list: the delete is bound to the version you saw. A missing revision is rejected (revision_required); if the record changed since you listed it, the delete is rejected with a conflict.")
                    })
                },
                required = listOf("id")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val id = input.jsonObject["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
            val revision = input.jsonObject["revision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val payload: JsonElement = if (revision == null) {
                missingRevisionPayload(id)
            } else {
                try {
                    val result = onDeleteCas(id, revision)
                    auditCasWrite(onAudit, runIdProvider, "memory_delete", id, result.oldDigest, null)
                    buildJsonObject {
                        put("success", true)
                        put("id", result.memoryId)
                        put("deleted", true)
                    }
                } catch (e: MemoryStaleException) {
                    conflictPayload(id, e)
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores layered information across AmberAgent conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            Use `scope` for create:
            - `core`: durable identity, behavior rules, or explicit facts the user wants injected everywhere.
            - `short_term`: concise summaries of the active project or recent conversations.
            - `long_term`: stable preferences, recurring interests, plans, and factual context.
            Use `kind` for create: `user`, `feedback`, `project`, `reference`, `routine`, or `note`.
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content` + `revision` (from memory_list)
            - Outdated/irrelevant record: `delete` + `id` + `revision` (from memory_list)
            Always pass the `revision` returned by memory_list on edit/delete: the write is rejected with a conflict if the record changed in the meantime (re-run memory_list to get the fresh revision).
            Memories will automatically appear in later conversations when the corresponding memory module is enabled.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true, locale)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","scope":"long_term","content":"User prefers brief replies and is more active on weekends."}
            {"action":"create","scope":"short_term","content":"Current thread is about building AmberAgent Android agent features."}
            {"action":"edit","id":12,"revision":3,"content":"User's preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7,"revision":1}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("revision", buildJsonObject {
                        put("type", "integer")
                        put("description", "The revision of the memory record from memory_list (required for edit/delete; a mismatch rejects the write with a conflict)")
                    })
                    put("scope", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("core")
                                add("short_term")
                                add("long_term")
                            }
                        )
                        put("description", "The memory scope for create. Defaults to long_term.")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                MemoryKind.entries.forEach { add(it.wireName) }
                            }
                        )
                        put("description", "The memory kind for create. Defaults to note.")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                    put("sourceConversationId", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional source conversation id.")
                    })
                    put("sourceMessageIds", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional source message ids.")
                        put("items", buildJsonObject {
                            put("type", "string")
                        })
                    })
                    put("expiresAt", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional expiration time in epoch milliseconds.")
                    })
                    put("confidence", buildJsonObject {
                        put("type", "number")
                        put("description", "Confidence from 0 to 1. Defaults to 1.")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload: JsonElement = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val scope = params["scope"]?.jsonPrimitive?.contentOrNull ?: "long_term"
                    require(scope in setOf("core", "short_term", "long_term")) {
                        "scope must be one of [core, short_term, long_term]"
                    }
                    val kind = params["kind"]?.jsonPrimitive?.contentOrNull?.let(MemoryKind::fromWireName)
                        ?: MemoryKind.NOTE
                    val sourceConversationId = params["sourceConversationId"]?.jsonPrimitive?.contentOrNull
                    val sourceMessageIds = params["sourceMessageIds"]?.jsonArray
                        ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull }
                        .orEmpty()
                    val expiresAt = params["expiresAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    val confidence = params["confidence"]?.jsonPrimitive?.floatOrNull ?: 1f
                    val created = onCreation(
                        MemoryToolWriteRequest(
                            scope = MemoryScope.fromWireName(scope),
                            kind = kind,
                            content = content,
                            sourceConversationId = sourceConversationId,
                            sourceMessageIds = sourceMessageIds,
                            expiresAt = expiresAt,
                            confidence = confidence,
                            sourceRunId = runIdProvider(),
                            sourceTrigger = MemoryRepository.TRIGGER_TOOL,
                        )
                    )
                    auditMemoryCreate(onAudit, runIdProvider, created)
                    json.encodeToJsonElement(AssistantMemory.serializer(), created)
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val revision = params["revision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (revision == null) {
                        missingRevisionPayload(id)
                    } else {
                        try {
                            val result = onUpdateCas(id, content, revision)
                            auditCasWrite(onAudit, runIdProvider, "memory_edit", id, result.oldDigest, result.newDigest)
                            json.encodeToJsonElement(AssistantMemory.serializer(), result.memory)
                        } catch (e: MemoryStaleException) {
                            conflictPayload(id, e)
                        }
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val revision = params["revision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (revision == null) {
                        missingRevisionPayload(id)
                    } else {
                        try {
                            val result = onDeleteCas(id, revision)
                            auditCasWrite(onAudit, runIdProvider, "memory_delete", id, result.oldDigest, null)
                            buildJsonObject {
                                put("success", true)
                                put("id", result.memoryId)
                                put("deleted", true)
                            }
                        } catch (e: MemoryStaleException) {
                            conflictPayload(id, e)
                        }
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * P2-06 audit: records old/new content digests and the triggering runId in
 * the approval history — hashes only, never the memory content itself.
 */
private suspend fun auditMemoryCreate(
    onAudit: suspend (ApprovalHistoryEntry) -> Unit,
    runIdProvider: () -> String?,
    memory: AssistantMemory,
) = auditCasWrite(
    onAudit = onAudit,
    runIdProvider = runIdProvider,
    toolName = "memory_create",
    memoryId = memory.id,
    oldDigest = null,
    newDigest = ContentDigest.sha256(memory.content),
)

private suspend fun auditCasWrite(
    onAudit: suspend (ApprovalHistoryEntry) -> Unit,
    runIdProvider: () -> String?,
    toolName: String,
    memoryId: Int,
    oldDigest: String?,
    newDigest: String?,
) {
    runCatching {
        onAudit(
            CasAudit.outcome(
                capability = null,
                toolName = toolName,
                sessionId = "memory-$memoryId-${System.currentTimeMillis()}",
                runId = runIdProvider(),
                digest = ContentDigest.bind(oldDigest.orEmpty(), newDigest.orEmpty()),
                source = "tool",
                outcome = "applied",
                oldDigest = oldDigest,
                newDigest = newDigest,
            )
        )
    }
}

/** Structured conflict result — the stale approval must not overwrite. */
private fun conflictPayload(id: Int, e: MemoryStaleException) = buildJsonObject {
    put("status", "conflict")
    put("memory_id", id)
    put("expected_revision", e.expectedRevision)
    put("actual_revision", e.actualRevision)
    put(
        "message",
        "The memory record changed (revision ${e.expectedRevision} -> ${e.actualRevision}) after the approval; " +
            "the write was NOT applied. Re-run memory_list and retry with the current revision — the diff is regenerated, never auto-overwritten."
    )
}

/**
 * Structured rejection when edit/delete omit the revision — the write must be
 * bound to the exact version the user saw, so a missing revision never falls
 * back to the latest one (that would silently defeat the CAS).
 */
private fun missingRevisionPayload(id: Int) = buildJsonObject {
    put("status", "revision_required")
    put("memory_id", id)
    put(
        "message",
        "The revision is required for memory edit/delete so the write is bound to the version you saw. " +
            "Run memory_list and pass back the revision of this record."
    )
}

private fun AssistantMemory.toJson(scope: String) = buildJsonObject {
    val summary = content.take(MEMORY_LIST_SUMMARY_MAX_CHARS)
    put("id", id)
    put("type", this@toJson.scope.wireName.ifBlank { scope })
    put("scope", this@toJson.scope.wireName)
    put("kind", kind.wireName)
    put("content", summary)
    put("content_truncated", content.length > summary.length)
    expiresAt?.let { put("expiresAt", it) }
    put("confidence", confidence)
    put("pinned", pinned)
    put("archived", archived)
    put("revision", revision)
    sourceRunId?.let { put("source_run_id", it) }
    sourceTrigger?.let { put("source_trigger", it) }
}
