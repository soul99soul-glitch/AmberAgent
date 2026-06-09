package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool

fun createSettingsOpenTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "settings_open",
    description = "Open a whitelisted Android settings page, such as accessibility, notification access, app details, overlay, battery optimization, location, or default apps.",
    parameters = {
        obj(
            "target" to enumProp(
                "Settings page target.",
                listOf(
                    "app_details",
                    "accessibility",
                    "notification_access",
                    "usage_access",
                    "overlay",
                    "battery_optimization",
                    "location",
                    "default_apps",
                )
            ),
            required = listOf("target")
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.trackSystemTool("settings_open", "打开系统设置", "apps", input) {
            val intent = settingsIntent(context, input.requiredString("target"))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textJson {
                put("success", true)
                put("target", input.requiredString("target"))
            }
        }
    }
)

fun createIntentOpenTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "intent_open",
    description = "Open a whitelisted Android intent action with optional data URI. Dangerous or non-whitelisted actions are rejected.",
    parameters = {
        obj(
            "action" to enumProp("Intent action.", listOf("view", "dial", "sendto", "web_search")),
            "data_uri" to accessStringProp("Optional data URI. Only http/https/tel/mailto/smsto are allowed."),
            required = listOf("action")
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.trackSystemTool("intent_open", "打开 Intent", "apps", input.safePreview()) {
            val intent = whitelistedIntent(input.requiredString("action"), input.string("data_uri"))
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textJson {
                put("success", true)
                put("action", input.requiredString("action"))
            }
        }
    }
)

private fun settingsIntent(context: Context, target: String): Intent = when (target) {
    "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    else -> error("Unsupported settings target: $target")
}

private fun whitelistedIntent(action: String, dataUri: String?): Intent {
    val intentAction = when (action) {
        "view" -> Intent.ACTION_VIEW
        "dial" -> Intent.ACTION_DIAL
        "sendto" -> Intent.ACTION_SENDTO
        "web_search" -> Intent.ACTION_WEB_SEARCH
        else -> error("Unsupported intent action: $action")
    }
    val uri = dataUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
    if (uri != null) {
        require(uri.scheme in setOf("http", "https", "tel", "mailto", "smsto")) {
            "Unsupported data_uri scheme: ${uri.scheme}"
        }
    }
    return Intent(intentAction, uri)
}
