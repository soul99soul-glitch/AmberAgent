package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telephony.TelephonyManager
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

fun createDevicePhoneStateTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "device_phone_state",
    description = "Read coarse phone/SIM state after READ_PHONE_STATE or READ_PHONE_NUMBERS is granted.",
    parameters = { obj() },
    execute = { input ->
        deps.trackSystemTool("device_phone_state", "读取电话状态", "phone_state", input) {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            textJson {
                put("phone_type", telephonyManager?.phoneType ?: TelephonyManager.PHONE_TYPE_NONE)
                put("network_operator", telephonyManager?.networkOperatorName.orEmpty())
                put("sim_operator", telephonyManager?.simOperatorName.orEmpty())
                @Suppress("DEPRECATION", "MissingPermission")
                put("line1_number", telephonyManager?.line1Number?.let(::maskPhone).orEmpty())
            }
        }
    }
)

fun createCallLogListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "call_log_list",
    description = "List recent Android call log entries after READ_CALL_LOG is granted.",
    parameters = {
        obj(
            "limit" to integerProp("Maximum call log entries. Defaults to 20."),
            "since_epoch_ms" to integerProp("Only include entries after this Unix epoch millis."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("call_log_list", "读取通话记录", "call_log_read", input.safePreview()) {
            textJson {
                put("calls", queryCallLogs(context, input.limit(default = 20, max = 50), input.long("since_epoch_ms")))
            }
        }
    }
)

fun createCallPhoneTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "call_phone",
    description = "Open the dialer by default. If direct_call=true, directly starts a phone call and requires CALL_PHONE plus explicit approval.",
    parameters = {
        obj(
            "phone_number" to accessStringProp("Phone number to dial or call."),
            "direct_call" to booleanProp("Directly place the call. Defaults to false and opens the dialer instead."),
            required = listOf("phone_number")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        val direct = input.boolean("direct_call") ?: false
        val capability = if (direct) "call_phone" else "apps"
        deps.trackSystemTool("call_phone", "拨打电话", capability, input.safePreview()) {
            val phoneNumber = input.requiredString("phone_number")
            val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
            context.startActivity(Intent(action, Uri.parse("tel:$phoneNumber")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textJson {
                put("success", true)
                put("direct_call", direct)
                put("phone_number", maskPhone(phoneNumber))
            }
        }
    }
)

private fun queryCallLogs(context: Context, limit: Int, since: Long?) = buildJsonArray {
    val selection = since?.let { "${CallLog.Calls.DATE} >= ?" }
    val args = since?.let { arrayOf(it.toString()) }
    var count = 0
    context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        ),
        selection,
        args,
        "${CallLog.Calls.DATE} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
        val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
        val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        while (cursor.moveToNext() && count < limit) {
            add(buildJsonObject {
                put("call_id", cursor.getLong(idIndex))
                put("number_masked", maskPhone(cursor.getString(numberIndex).orEmpty()))
                put("name", cursor.getString(nameIndex).orEmpty())
                put("type", cursor.getInt(typeIndex))
                put("date_epoch_ms", cursor.getLong(dateIndex))
                put("duration_seconds", cursor.getLong(durationIndex))
            })
            count++
        }
    }
}
