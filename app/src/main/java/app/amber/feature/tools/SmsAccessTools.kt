package app.amber.feature.tools

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

internal fun createSmsListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "sms_list",
    description = "List SMS messages from inbox, sent, or all after READ_SMS is granted. Bodies are returned as short previews by default.",
    parameters = {
        obj(
            "box" to enumProp("SMS box to read.", listOf("all", "inbox", "sent")),
            "sender" to accessStringProp("Optional sender address filter."),
            "since_epoch_ms" to integerProp("Only include SMS after this Unix epoch millis."),
            "limit" to integerProp("Maximum messages. Defaults to 20."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("sms_list", "读取短信列表", "sms_read", input.safePreview()) {
            textJson {
                put("messages", querySms(context, input, previewOnly = true))
            }
        }
    }
)

internal fun createSmsReadTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "sms_read",
    description = "Read SMS content by message_id or thread_id after READ_SMS is granted.",
    parameters = {
        obj(
            "message_id" to accessStringProp("SMS message _id."),
            "thread_id" to accessStringProp("SMS thread_id."),
            "limit" to integerProp("Maximum messages when reading a thread. Defaults to 20."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("sms_read", "读取短信内容", "sms_read", input.safePreview()) {
            textJson {
                put("messages", querySms(context, input, previewOnly = false))
            }
        }
    }
)

internal fun createSmsSendTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "sms_send",
    description = "Send an SMS from this device. Requires SEND_SMS and explicit approval.",
    parameters = {
        obj(
            "phone_number" to accessStringProp("Recipient phone number."),
            "message" to accessStringProp("SMS body."),
            required = listOf("phone_number", "message")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("sms_send", "发送短信", "sms_send", input.safePreview()) {
            val phoneNumber = input.requiredString("phone_number")
            val message = input.requiredString("message")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: error("SmsManager is unavailable")
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            textJson {
                put("success", true)
                put("phone_number", maskPhone(phoneNumber))
                put("message_chars", message.length)
            }
        }
    }
)

private fun querySms(context: Context, input: JsonElement, previewOnly: Boolean) = buildJsonArray {
    val messageId = input.string("message_id")
    val threadId = input.string("thread_id")
    val sender = input.string("sender")
    val since = input.long("since_epoch_ms")
    val limit = input.limit(default = 20, max = 100)
    val box = input.string("box") ?: "all"
    val uri = when (box) {
        "inbox" -> Telephony.Sms.Inbox.CONTENT_URI
        "sent" -> Telephony.Sms.Sent.CONTENT_URI
        else -> Telephony.Sms.CONTENT_URI
    }
    val clauses = mutableListOf<String>()
    val args = mutableListOf<String>()
    messageId?.let {
        clauses += "${Telephony.Sms._ID} = ?"
        args += it
    }
    threadId?.let {
        clauses += "${Telephony.Sms.THREAD_ID} = ?"
        args += it
    }
    sender?.takeIf { it.isNotBlank() }?.let {
        clauses += "${Telephony.Sms.ADDRESS} LIKE ?"
        args += "%$it%"
    }
    since?.let {
        clauses += "${Telephony.Sms.DATE} >= ?"
        args += it.toString()
    }
    var count = 0
    context.contentResolver.query(
        uri,
        arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.BODY,
        ),
        clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
        args.takeIf { it.isNotEmpty() }?.toTypedArray(),
        "${Telephony.Sms.DATE} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
        val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
        val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        while (cursor.moveToNext() && count < limit) {
            val body = cursor.getString(bodyIndex).orEmpty()
            add(buildJsonObject {
                put("message_id", cursor.getLong(idIndex))
                put("thread_id", cursor.getLong(threadIndex))
                put("address_masked", maskPhone(cursor.getString(addressIndex).orEmpty()))
                put("date_epoch_ms", cursor.getLong(dateIndex))
                put("type", cursor.getInt(typeIndex))
                if (previewOnly) {
                    put("preview", body.take(160))
                    put("body_chars", body.length)
                } else {
                    put("body", body)
                }
            })
            count++
        }
    }
}
