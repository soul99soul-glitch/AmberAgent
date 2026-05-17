package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.system.AgentPermissionStatus

/**
 * Factory for the `permissions_status` agent tool — exposes the per-capability
 * Android permission state to the model so it can guide the user through
 * granting missing permissions before invoking gated tools.
 *
 * Depends on [AgentPermissionBroker] (the registry of declared capabilities
 * + their runtime/special-access requirements + how to grant them).
 *
 * Extracted from `LocalTools.permissionsStatusTool` in M1.4 demo.
 */
fun createPermissionsStatusTool(permissionBroker: AgentPermissionBroker): Tool = Tool(
    name = "permissions_status",
    description = "List AmberAgent Android permission capability status and how to grant missing permissions.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("capability_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional capability id to inspect")
                })
                put("include_how_to_grant", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include user-facing grant guidance. Defaults to true.")
                })
            }
        )
    },
    execute = { input ->
        val id = input.jsonObject["capability_id"]?.jsonPrimitive?.contentOrNull
        val includeHowToGrant = input.jsonObject["include_how_to_grant"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val capabilities = permissionBroker.capabilities.filter { id == null || it.id == id }
        val allFilesCapability = permissionBroker.getCapability("manage_all_files")
        val payload = buildJsonObject {
            put("all_files_access_declared", permissionBroker.getStatus(allFilesCapability) != AgentPermissionStatus.Unsupported)
            put("all_files_access_granted", permissionBroker.getStatus(allFilesCapability) == AgentPermissionStatus.Granted)
            put("all_files_access_manage_intent_available", permissionBroker.createSpecialAccessIntent("manage_all_files") != null)
            put(
                "capabilities",
                buildJsonArray {
                    capabilities.forEach { capability ->
                        add(
                            buildJsonObject {
                                put("capability_id", capability.id)
                                put("title", capability.title)
                                put("description", capability.description)
                                put("risk", capability.risk.name.lowercase())
                                put("status", permissionBroker.getStatus(capability).name.lowercase())
                                put("runtime_permissions", buildJsonArray {
                                    permissionBroker.runtimePermissionsFor(capability).forEach { permission -> add(permission) }
                                })
                                capability.specialAccess?.let { put("special_access", it.name) }
                                put("tools", buildJsonArray { capability.toolNames.forEach { tool -> add(tool) } })
                                if (includeHowToGrant) {
                                    put("how_to_grant", "Open AmberAgent Settings > Agent 设置 > 系统权限, then grant ${capability.title}. Special access items open Android system settings.")
                                }
                            }
                        )
                    }
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
