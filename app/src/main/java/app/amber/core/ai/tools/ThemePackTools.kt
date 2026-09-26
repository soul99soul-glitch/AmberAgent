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
import app.amber.feature.ui.theme.ThemePackTransfer
import app.amber.core.settings.ThemePackDocument
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
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
        description = "读取当前已保存主题、正在试穿的主题、已安装 id 和可编辑的完整 base 配方。修改主题前先调用；id 可选 current（优先最新试穿）或已安装/内置 id。使用 amber.theme.pack v1，与 iOS 文件互通。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("id", stringProperty("可选主题 id；默认 current，优先返回最新试穿配方。"))
            })
        },
        execute = { input ->
            val id = input.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "current"
            listOf(UIMessagePart.Text(themeStatusPayload(manager.status(), manager.recipe(id)).toString()))
        },
    ),
    Tool(
        name = TOOL_THEME_PACK_IMPORT,
        description = "生成或局部修改 Amber 自身主题。先调用 theme_pack_status。action=prepare（默认）在真实界面试穿，用户点击套用才保存，还原恢复已保存主题。修改时传 base_id=current 或已安装/内置 id，仅传要求修改的字段；省略字段保留，design 对象递归合并，数组整体替换，null 清除可选 design/配色/渐变/组件。自定义主题保留 id；内置主题首次修改生成副本，后续以 current 继续。新建不传 base_id，需要 id、display_name、paper、accent_hex、ink_hex、canvas_style、brand_mark、shortcut_icon_style、chrome_typeface。文件协议 amber.theme.pack v1 与 iOS 互通；以 status 中 Android 实际支持字段为准，不改变聊天字体或布局。确认前不能声称已保存。action=apply/discard 仍需 id 和 candidate_digest 精确绑定。仅前台用户批准可执行。",
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
                    put("base_id", stringProperty("局部修改目标：current 或已安装/内置 id。省略则新建；修改不能另起 id。"))
                    put("id", stringProperty("新建主题 id；局部修改省略；apply/discard 使用 prepare 返回的 id。"))
                    put("candidate_digest", stringProperty("prepare 返回的候选摘要；apply/discard 必填，必须与当前 try-on 完全匹配。"))
                    put("display_name", stringProperty("主题名称；新建必填，修改省略即保留。"))
                    put("paper", enumProperty("基础画布", listOf("paper", "neutral", "white", "pi", "notion")))
                    put("accent_hex", stringProperty("强调色 RGB，#RRGGBB 或 0xRRGGBB。"))
                    put("ink_hex", stringProperty("强调色上的文字色；与强调色对比至少 3:1。"))
                    put("canvas_style", enumProperty("基础纹理", listOf("flat", "dotGrid", "lineGrid", "paperGrain")))
                    put("brand_mark", enumProperty("保留以供 iOS 使用", listOf("systemWordmark", "paintAMBER", "serifWordmark")))
                    put("shortcut_icon_style", enumProperty("保留以供 iOS 使用", listOf("phosphorFill", "pixelSit", "systemOutline")))
                    put("chrome_typeface", enumProperty("界面字体，独立于聊天正文", listOf("system", "rounded", "serif", "monospace")))
                    put("canvas_scope", enumProperty("背景范围", listOf("homeOnly", "shell", "appWide")))
                    put("bubble_chrome", enumProperty("保留供 iOS 使用；Android 气泡形状使用 design.components.bubbleRadius", listOf("standard", "soft", "crisp")))
                    put("glass_chrome", enumProperty("iOS 玻璃风格", listOf("standard", "quieter", "solid")))
                    put("empty_art", enumProperty("iOS 空态装饰", listOf("none", "character")))
                    put("launch_brand", enumProperty("iOS 品牌呼应", listOf("none", "matchBrand")))
                    put("settings_chrome", buildJsonObject { put("type", "boolean") })
                    put("design", designProperty())
                    put("name", stringProperty("仅旧 Android token 包兼容字段；新主题使用 display_name，不能与新配方字段混用。"))
                    put(
                        "colors",
                        mapProperty(
                            "旧 Android token 包兼容字段；不能与 paper/design 等新配方字段混用。",
                            mapOf("baseFamily" to "WARM 或 SAGE。", "accent" to "#RRGGBB 或 #AARRGGBB。"),
                        ),
                    )
                    put(
                        "fonts",
                        mapProperty(
                            "旧 Android token 包兼容字段；新生成主题不使用此字段，以保留聊天阅读偏好。",
                            mapOf("chatFontFamily" to "default、serif 或 monospace。", "fontSizeRatio" to "0.5 到 2.0。"),
                        ),
                    )
                    put(
                        "layout",
                        mapProperty(
                            "旧 Android token 包兼容字段；新生成主题不使用此字段，以保留聊天布局。",
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
                    val args = input.jsonObject
                    val baseId = args["base_id"]?.jsonPrimitive?.contentOrNull
                    val json = when {
                        baseId != null -> ThemePackTransfer.encode(ThemePackTransfer.toolRecipe(args, manager.recipe(baseId)))
                        "name" in args || "colors" in args || "fonts" in args || "layout" in args -> encodeDirectPackage(input)
                        else -> ThemePackTransfer.encode(ThemePackTransfer.toolRecipe(args))
                    }
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

private fun themeStatusPayload(status: ThemePackageStatus, base: ThemePackDocument) = buildJsonObject {
    put("status", "ok")
    put("current", displaySettingPayload(status.current.displaySetting))
    put("base", ThemePackTransfer.toolPayload(base))
    put("format", "amber.theme.pack")
    put("version", 1)
    put("builtin_ids", buildJsonArray { add("builtin:WARM"); add("builtin:SAGE") })
    put("installed_ids", buildJsonArray { status.installed.forEach { add(it.id) } })
    put("design_schema", designProperty())
    put("preserved_only", buildJsonArray {
        add("brand_mark"); add("shortcut_icon_style"); add("glass_chrome"); add("empty_art"); add("launch_brand")
        add("canvas_scope"); add("bubble_chrome"); add("settings_chrome")
        add("design.components.brandText"); add("design.components.brandSize"); add("design.components.brandTracking")
    })
    put("android_background_scope", "已使用 amberCanvas 的页面；canvas_scope 保留用于与 iOS 交换")
    put("rules", "用 base_id 局部修改，只发送变化字段；未提供字段保留，数组整体替换。可选 design/light/dark/gradient/components/组件属性可设 null。只改组件不必重建配色；首次添加配色需五个颜色。两端使用同一文件格式，平台专属字段保留但不保证同样渲染。试穿可继续修改，套用后覆盖同一条目；还原不改已保存主题。")
    put("installed", buildJsonArray {
        status.installed.forEach { packageEntity ->
            add(buildJsonObject {
                put("id", packageEntity.id)
                put("name", packageEntity.name)
                put("imported_at_ms", packageEntity.importedAtMs)
            })
        }
    })
    put("try_on", status.tryOn?.let(::tryOnPayload) ?: JsonNull)
}

private fun tryOnPayload(tryOn: ThemePackageTryOn) = buildJsonObject {
    put("package_id", tryOn.pkg.id)
    put("name", tryOn.pkg.name)
    put("candidate_digest", tryOn.candidateDigest)
    put("unknown_tokens", buildJsonArray { tryOn.unknownTokens.forEach(::add) })
    put("candidate", displaySettingPayload(tryOn.candidate))
    put("recipe", ThemePackTransfer.toolPayload(ThemePackTransfer.fromPackage(tryOn.pkg, tryOn.candidate)))
}

private fun displaySettingPayload(displaySetting: app.amber.core.settings.DisplaySetting) = buildJsonObject {
    put("base_family", displaySetting.amberBaseFamily)
    put("accent", displaySetting.accentColor)
    put("chat_font_family", displaySetting.chatFontFamily.name.lowercase())
    put("font_size_ratio", displaySetting.fontSizeRatio)
    put("show_user_avatar", displaySetting.showUserAvatar)
    put("show_assistant_bubble", displaySetting.showAssistantBubble)
    put("recipe", ThemePackTransfer.toolPayload(ThemePackTransfer.export(displaySetting)))
}

private fun encodeDirectPackage(input: JsonElement): String {
    val objectInput = input.jsonObject
    require(objectInput.keys.all { it in setOf("action", "id", "name", "colors", "fonts", "layout") }) {
        "旧 token 包与跨平台配方不能混用；生成主题请使用 display_name 和完整配方字段"
    }
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

private fun enumProperty(description: String, values: List<String>) = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach(::add) })
}

private fun designProperty(): JsonObject {
    fun nullableObject(properties: JsonObject, description: String) = buildJsonObject {
        put("type", buildJsonArray { add("object"); add("null") })
        put("description", description)
        put("properties", properties)
        put("additionalProperties", false)
    }
    fun number(min: Double, max: Double, nullable: Boolean = true) = buildJsonObject {
        if (nullable) put("type", buildJsonArray { add("number"); add("null") }) else put("type", "number")
        put("minimum", min); put("maximum", max)
    }
    val palette = nullableObject(buildJsonObject {
        listOf("background", "surface", "foreground", "mutedForeground", "border").forEach {
            put(it, stringProperty("RGB 颜色；首次添加配色五项必填。foreground 对 background/surface >=4.5:1，mutedForeground >=3:1。"))
        }
    }, "浅/深色配色；null 恢复 paper 基础色。")
    val colorArray = buildJsonObject {
        put("type", "array"); put("items", stringProperty("RGB 颜色，需与本模式 foreground 对比 >=4.5:1。"))
        put("minItems", 2); put("maxItems", 4)
    }
    return nullableObject(buildJsonObject {
        put("light", palette); put("dark", palette)
        put("gradient", nullableObject(buildJsonObject {
            put("colors", colorArray); put("darkColors", colorArray)
            put("angle", buildJsonObject { put("type", "number"); put("description", "从水平向右顺时针旋转的角度，有限数字。") })
        }, "渐变需要完整浅深配色与两套色阶，数组整体替换；null 清除。"))
        put("patterns", buildJsonObject {
            put("type", "array"); put("maxItems", 3)
            put("description", "纹理列表整体替换，[] 清空；每层五项必填。")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("kind", enumProperty("纹理", listOf("dots", "grid", "diagonal", "crosses", "waves", "rings")))
                    put("color", stringProperty("RGB 颜色")); put("opacity", number(0.0, 0.3, false))
                    put("spacing", number(12.0, 120.0, false)); put("size", number(0.5, 8.0, false))
                })
                put("required", buildJsonArray { listOf("kind", "color", "opacity", "spacing", "size").forEach(::add) })
                put("additionalProperties", false)
            })
        })
        put("components", nullableObject(buildJsonObject {
            put("cardRadius", number(0.0, 32.0)); put("bubbleRadius", number(0.0, 28.0))
            put("controlRadius", number(0.0, 28.0)); put("borderWidth", number(0.0, 3.0))
            put("shadowOpacity", number(0.0, 0.35)); put("shadowRadius", number(0.0, 24.0))
            put("brandText", buildJsonObject { put("type", buildJsonArray { add("string"); add("null") }); put("minLength", 1); put("maxLength", 16) })
            put("brandSize", number(20.0, 40.0)); put("brandTracking", number(-2.0, 6.0))
        }, "可选组件参数；仅变更指定字段，null 恢复平台默认。距离单位 dp/pt，品牌字段保留供 iOS 使用。"))
    }, "跨平台设计配方：light/dark 可省略以继承纸色，patterns 默认为 []；对象逐字段合并，数组整体替换。")
}
