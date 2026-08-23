package app.amber.core.ai.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.theme.ThemePackage
import app.amber.feature.ui.theme.ThemePackageApplyResult
import app.amber.feature.ui.theme.ThemePackageImportResult
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.ui.theme.ThemePackageStatus
import app.amber.feature.ui.theme.ThemePackageTryOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val TOOL_THEME_PACK_STATUS = "theme_pack_status"
const val TOOL_THEME_PACK_IMPORT = "theme_pack_import"

/**
 * Android 主题包工具：status 只读；import 采用 prepare/apply/discard 三步语义。
 * prepare 只写 [ThemePackageManager] 的内存 try-on，只有 action=apply 才会落库和改 Settings。
 */
fun createThemePackTools(manager: ThemePackageManager): List<Tool> = listOf(
    Tool(
        name = TOOL_THEME_PACK_STATUS,
        description = "读取 Android 当前主题、已安装主题包和内存 try-on。只返回 Android 支持的 baseFamily、accent、字体比例和布局开关，不返回 iOS 专属 token。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        execute = {
            listOf(UIMessagePart.Text(themeStatusPayload(manager.status()).toString()))
        },
    ),
    Tool(
        name = TOOL_THEME_PACK_IMPORT,
        description = "导入 Android 主题包。action=prepare 只校验并生成内存预览；用户明确确认后用 action=apply 落库并应用；action=discard 丢弃候选。此工具必须在前台由用户批准，不能被后台或全局自动批准设置绕过。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("prepare")
                            add("apply")
                            add("discard")
                        })
                        put("description", "prepare（默认）/ apply / discard")
                    })
                    put("id", stringProperty("主题包 id；prepare 必填，apply/discard 必须使用 prepare 返回的 id。"))
                    put("candidate_digest", stringProperty("prepare 返回的候选摘要；apply/discard 必填，必须与当前 try-on 完全匹配。"))
                    put("name", stringProperty("主题包名称；直接字段模式下 prepare 必填。"))
                    put(
                        "colors",
                        mapProperty(
                            "Android 支持的颜色 token。",
                            mapOf("baseFamily" to "WARM 或 SAGE。", "accent" to "#RRGGBB 或 #AARRGGBB。"),
                        ),
                    )
                    put(
                        "fonts",
                        mapProperty(
                            "Android 支持的字体 token。",
                            mapOf("chatFontFamily" to "default、serif 或 monospace。", "fontSizeRatio" to "0.5 到 2.0。"),
                        ),
                    )
                    put(
                        "layout",
                        mapProperty(
                            "Android 支持的布局 token。",
                            mapOf("showUserAvatar" to "true 或 false。", "showAssistantBubble" to "true 或 false。"),
                        ),
                    )
                }
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull
                ?.lowercase() ?: "prepare"
            val payload = when (action) {
                "prepare" -> {
                    val json = encodeDirectPackage(input)
                    when (val result = manager.prepareImport(json)) {
                        is ThemePackageImportResult.Preview -> themePreviewPayload(result)
                        is ThemePackageImportResult.Rejected -> buildJsonObject {
                            put("status", "rejected")
                            put("persisted", false)
                            put("issues", buildJsonArray { result.issues.forEach(::add) })
                        }
                    }
                }

                "apply" -> applyPayload(
                    manager = manager,
                    requestedId = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
                    candidateDigest = input.jsonObject["candidate_digest"]?.jsonPrimitive?.contentOrNull,
                )

                "discard" -> {
                    val requestedId = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val candidateDigest = input.jsonObject["candidate_digest"]?.jsonPrimitive?.contentOrNull
                    if (requestedId.isNullOrBlank() || candidateDigest.isNullOrBlank()) {
                        buildJsonObject {
                            put("status", "rejected")
                            put("persisted", false)
                            put("reason", "binding_required")
                        }
                    } else {
                        val discarded = manager.discardTryOn(requestedId, candidateDigest)
                        buildJsonObject {
                            put("status", if (discarded) "discarded" else "rejected")
                            put("persisted", false)
                            if (!discarded) put("reason", "candidate_mismatch")
                        }
                    }
                }

                else -> error("action must be prepare, apply, or discard")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)

private suspend fun applyPayload(
    manager: ThemePackageManager,
    requestedId: String?,
    candidateDigest: String?,
) = if (requestedId.isNullOrBlank() || candidateDigest.isNullOrBlank()) {
    buildJsonObject {
        put("status", "rejected")
        put("persisted", false)
        put("reason", "binding_required")
    }
} else {
    buildJsonObject {
        val result = manager.applyPrepared(requestedId, candidateDigest)
        when (result) {
            ThemePackageApplyResult.Applied,
            ThemePackageApplyResult.AlreadyApplied,
            -> {
                put("status", "applied")
                put("persisted", true)
            }

            ThemePackageApplyResult.NotPrepared -> {
                put("status", "not_prepared")
                put("persisted", false)
            }

            ThemePackageApplyResult.Reverted -> {
                put("status", "reverted")
                put("persisted", false)
            }

            ThemePackageApplyResult.NotFound,
            ThemePackageApplyResult.Corrupt,
            -> {
                put("status", "rejected")
                put("persisted", false)
                put("reason", result.javaClass.simpleName)
            }
        }
    }
}

private fun themePreviewPayload(result: ThemePackageImportResult.Preview) = buildJsonObject {
    put("status", "prepared")
    put("persisted", false)
    put("package_id", result.pkg.id)
    put("name", result.pkg.name)
    put("unknown_tokens", buildJsonArray { result.unknownTokens.forEach(::add) })
    put("candidate", displaySettingPayload(result.candidate))
    put("candidate_digest", result.candidateDigest)
    put("apply_instruction", "After the user confirms this preview, call theme_pack_import with action=apply, id=${result.pkg.id}, and candidate_digest=${result.candidateDigest}.")
}

private fun themeStatusPayload(status: ThemePackageStatus) = buildJsonObject {
    put("status", "ok")
    put("current", displaySettingPayload(status.current.displaySetting))
    put("installed", buildJsonArray {
        status.installed.forEach { packageEntity ->
            add(buildJsonObject {
                put("id", packageEntity.id)
                put("name", packageEntity.name)
                put("imported_at_ms", packageEntity.importedAtMs)
            })
        }
    })
    status.tryOn?.let { put("try_on", tryOnPayload(it)) }
}

private fun tryOnPayload(tryOn: ThemePackageTryOn) = buildJsonObject {
    put("package_id", tryOn.pkg.id)
    put("name", tryOn.pkg.name)
    put("candidate_digest", tryOn.candidateDigest)
    put("unknown_tokens", buildJsonArray { tryOn.unknownTokens.forEach(::add) })
    put("candidate", displaySettingPayload(tryOn.candidate))
}

private fun displaySettingPayload(displaySetting: app.amber.core.settings.DisplaySetting) = buildJsonObject {
    put("base_family", displaySetting.amberBaseFamily)
    put("accent", displaySetting.accentColor)
    put("chat_font_family", displaySetting.chatFontFamily.name.lowercase())
    put("font_size_ratio", displaySetting.fontSizeRatio)
    put("show_user_avatar", displaySetting.showUserAvatar)
    put("show_assistant_bubble", displaySetting.showAssistantBubble)
}

private fun encodeDirectPackage(input: JsonElement): String {
    val objectInput = input.jsonObject
    val id = objectInput["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("id is required for prepare")
    val name = objectInput["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: error("name is required for prepare")
    val pkg = ThemePackage(
        id = id,
        name = name,
        colors = stringMap(objectInput["colors"]),
        fonts = stringMap(objectInput["fonts"]),
        layout = stringMap(objectInput["layout"]),
    )
    return JsonInstant.encodeToString(ThemePackage.serializer(), pkg)
}

private fun stringMap(element: JsonElement?): Map<String, String> =
    element?.jsonObject?.mapValues { (_, value) -> value.jsonPrimitive.content } ?: emptyMap()

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun mapProperty(description: String, properties: Map<String, String>) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        properties.forEach { (name, propertyDescription) ->
            put(name, stringProperty(propertyDescription))
        }
    })
    put("additionalProperties", false)
    put("description", description)
}
