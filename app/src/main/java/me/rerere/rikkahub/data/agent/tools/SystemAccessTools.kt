package me.rerere.rikkahub.data.agent.tools

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.system.AmberNotificationListenerService
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlin.coroutines.resume

class SystemAccessTools(
    private val context: Context,
    private val permissionBroker: AgentPermissionBroker,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        contactsSearchTool,
        contactsWriteTool,
        smsListTool,
        smsReadTool,
        smsSendTool,
        devicePhoneStateTool,
        callLogListTool,
        callPhoneTool,
        calendarListTool,
        calendarCreateTool,
        mediaSearchTool,
        locationCurrentTool,
        audioRecordOnceTool,
        notificationListTool,
        usageStatsListTool,
        appsListTool,
        appOpenTool,
    )

    private val contactsSearchTool = Tool(
        name = "contacts_search",
        description = "Search Android contacts by name, phone, or email after READ_CONTACTS is granted. Phone numbers are masked by default.",
        parameters = {
            obj(
                "query" to stringProp("Name, phone, or email keyword. Empty lists recent contacts."),
                "limit" to integerProp("Maximum contacts to return. Defaults to 20."),
            )
        },
        execute = { input ->
            trackSystemTool("contacts_search", "搜索通讯录", "contacts_read", input) {
                textJson {
                    put("contacts", queryContacts(input.string("query").orEmpty(), input.limit(default = 20, max = 50)))
                }
            }
        }
    )

    private val contactsWriteTool = Tool(
        name = "contacts_write",
        description = "Create a contact in Android Contacts. Requires WRITE_CONTACTS and explicit approval.",
        parameters = {
            obj(
                "name" to stringProp("Contact display name."),
                "phone" to stringProp("Optional phone number."),
                "email" to stringProp("Optional email address."),
                required = listOf("name")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackSystemTool("contacts_write", "写入联系人", "contacts_write", input.safePreview()) {
                val name = input.requiredString("name")
                val phone = input.string("phone").orEmpty()
                val email = input.string("email").orEmpty()
                if (phone.isBlank() && email.isBlank()) error("phone or email is required")
                val id = createContact(name = name, phone = phone, email = email)
                textJson {
                    put("success", true)
                    put("raw_contact_id", id)
                }
            }
        }
    )

    private val smsListTool = Tool(
        name = "sms_list",
        description = "List SMS messages from inbox, sent, or all after READ_SMS is granted. Bodies are returned as short previews by default.",
        parameters = {
            obj(
                "box" to enumProp("SMS box to read.", listOf("all", "inbox", "sent")),
                "sender" to stringProp("Optional sender address filter."),
                "since_epoch_ms" to integerProp("Only include SMS after this Unix epoch millis."),
                "limit" to integerProp("Maximum messages. Defaults to 20."),
            )
        },
        execute = { input ->
            trackSystemTool("sms_list", "读取短信列表", "sms_read", input.safePreview()) {
                textJson {
                    put("messages", querySms(input, previewOnly = true))
                }
            }
        }
    )

    private val smsReadTool = Tool(
        name = "sms_read",
        description = "Read SMS content by message_id or thread_id after READ_SMS is granted.",
        parameters = {
            obj(
                "message_id" to stringProp("SMS message _id."),
                "thread_id" to stringProp("SMS thread_id."),
                "limit" to integerProp("Maximum messages when reading a thread. Defaults to 20."),
            )
        },
        execute = { input ->
            trackSystemTool("sms_read", "读取短信内容", "sms_read", input.safePreview()) {
                textJson {
                    put("messages", querySms(input, previewOnly = false))
                }
            }
        }
    )

    private val smsSendTool = Tool(
        name = "sms_send",
        description = "Send an SMS from this device. Requires SEND_SMS and explicit approval.",
        parameters = {
            obj(
                "phone_number" to stringProp("Recipient phone number."),
                "message" to stringProp("SMS body."),
                required = listOf("phone_number", "message")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackSystemTool("sms_send", "发送短信", "sms_send", input.safePreview()) {
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

    private val devicePhoneStateTool = Tool(
        name = "device_phone_state",
        description = "Read coarse phone/SIM state after READ_PHONE_STATE or READ_PHONE_NUMBERS is granted.",
        parameters = { obj() },
        execute = { input ->
            trackSystemTool("device_phone_state", "读取电话状态", "phone_state", input) {
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

    private val callLogListTool = Tool(
        name = "call_log_list",
        description = "List recent Android call log entries after READ_CALL_LOG is granted.",
        parameters = {
            obj(
                "limit" to integerProp("Maximum call log entries. Defaults to 20."),
                "since_epoch_ms" to integerProp("Only include entries after this Unix epoch millis."),
            )
        },
        execute = { input ->
            trackSystemTool("call_log_list", "读取通话记录", "call_log_read", input.safePreview()) {
                textJson {
                    put("calls", queryCallLogs(input.limit(default = 20, max = 50), input.long("since_epoch_ms")))
                }
            }
        }
    )

    private val callPhoneTool = Tool(
        name = "call_phone",
        description = "Open the dialer by default. If direct_call=true, directly starts a phone call and requires CALL_PHONE plus explicit approval.",
        parameters = {
            obj(
                "phone_number" to stringProp("Phone number to dial or call."),
                "direct_call" to booleanProp("Directly place the call. Defaults to false and opens the dialer instead."),
                required = listOf("phone_number")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val direct = input.boolean("direct_call") ?: false
            val capability = if (direct) "call_phone" else "apps"
            trackSystemTool("call_phone", "拨打电话", capability, input.safePreview()) {
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

    private val calendarListTool = Tool(
        name = "calendar_list",
        description = "List Android calendar events after READ_CALENDAR is granted.",
        parameters = {
            obj(
                "from_epoch_ms" to integerProp("Start Unix epoch millis. Defaults to now."),
                "to_epoch_ms" to integerProp("End Unix epoch millis. Defaults to 7 days from start."),
                "limit" to integerProp("Maximum events. Defaults to 30."),
            )
        },
        execute = { input ->
            trackSystemTool("calendar_list", "读取日历事件", "calendar_read", input.safePreview()) {
                textJson {
                    put("events", queryCalendarEvents(input))
                }
            }
        }
    )

    private val calendarCreateTool = Tool(
        name = "calendar_create",
        description = "Create an Android calendar event. Requires WRITE_CALENDAR and explicit approval.",
        parameters = {
            obj(
                "title" to stringProp("Event title."),
                "start_time" to stringProp("ISO-8601 start time, for example 2026-05-03T10:00:00+08:00."),
                "end_time" to stringProp("ISO-8601 end time."),
                "start_epoch_ms" to integerProp("Start Unix epoch millis. Used if start_time is absent."),
                "end_epoch_ms" to integerProp("End Unix epoch millis. Used if end_time is absent."),
                "description" to stringProp("Optional event description."),
                "location" to stringProp("Optional event location."),
                required = listOf("title")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackSystemTool("calendar_create", "创建日历事件", "calendar_write", input.safePreview()) {
                val eventId = createCalendarEvent(input)
                textJson {
                    put("success", true)
                    put("event_id", eventId)
                }
            }
        }
    )

    private val mediaSearchTool = Tool(
        name = "media_search",
        description = "Search Android MediaStore images, videos, or audio after the matching media permission is granted.",
        parameters = {
            obj(
                "type" to enumProp("Media type.", listOf("images", "video", "audio", "all")),
                "query" to stringProp("Optional file name filter."),
                "limit" to integerProp("Maximum media entries. Defaults to 30."),
            )
        },
        execute = { input ->
            val type = input.string("type") ?: "all"
            val capabilities = when (type) {
                "images" -> listOf("media_images")
                "video" -> listOf("media_video")
                "audio" -> listOf("media_audio")
                else -> listOf("media_images", "media_video", "media_audio")
            }
            capabilities.drop(1).forEach { capability ->
                permissionBroker.ensureGranted(
                    capabilityId = capability,
                    toolName = "media_search",
                    reason = "搜索媒体库",
                )
            }
            trackSystemTool("media_search", "搜索媒体库", capabilities.first(), input.safePreview()) {
                textJson {
                    put("media", queryMedia(type = type, query = input.string("query").orEmpty(), limit = input.limit(default = 30, max = 100)))
                }
            }
        }
    )

    private val locationCurrentTool = Tool(
        name = "location_current",
        description = "Return the latest available device location from LocationManager after location permission is granted.",
        parameters = { obj() },
        execute = { input ->
            trackSystemTool("location_current", "读取当前位置", "location_current", input) {
                val location = currentOrLatestLocation()
                textJson {
                    if (location == null) {
                        put("available", false)
                        put("reason", "No recent location is available. Enable location providers or open a maps app once, then retry.")
                    } else {
                        put("available", true)
                        put("provider", location.provider.orEmpty())
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy_meters", location.accuracy.toDouble())
                        put("time_epoch_ms", location.time)
                    }
                }
            }
        }
    )

    private val audioRecordOnceTool = Tool(
        name = "audio_record_once",
        description = "Record a short microphone clip to app-private storage. Requires RECORD_AUDIO and explicit approval.",
        parameters = {
            obj(
                "duration_ms" to integerProp("Recording duration in milliseconds. Defaults to 5000, max 30000."),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackSystemTool("audio_record_once", "录制音频", "audio_record", input.safePreview()) {
                val file = recordAudioOnce(input.limit("duration_ms", default = 5_000, max = 30_000).toLong())
                textJson {
                    put("artifact_type", "audio")
                    put("path", file.absolutePath)
                    put("size_bytes", file.length())
                }
            }
        }
    )

    private val notificationListTool = Tool(
        name = "notification_list",
        description = "List active notification summaries after Notification Access is enabled.",
        parameters = {
            obj(
                "limit" to integerProp("Maximum notifications. Defaults to 30."),
            )
        },
        execute = { input ->
            trackSystemTool("notification_list", "读取通知", "notification_access", input) {
                textJson {
                    put("notifications", queryNotifications(input.limit(default = 30, max = 80)))
                }
            }
        }
    )

    private val usageStatsListTool = Tool(
        name = "usage_stats_list",
        description = "List recent app usage stats after Usage Access is enabled.",
        parameters = {
            obj(
                "since_epoch_ms" to integerProp("Start Unix epoch millis. Defaults to 24 hours ago."),
                "limit" to integerProp("Maximum apps. Defaults to 30."),
            )
        },
        execute = { input ->
            trackSystemTool("usage_stats_list", "读取应用使用情况", "usage_access", input.safePreview()) {
                textJson {
                    put("usage_stats", queryUsageStats(input.long("since_epoch_ms"), input.limit(default = 30, max = 100)))
                }
            }
        }
    )

    private val appsListTool = Tool(
        name = "apps_list",
        description = "List launchable apps visible to AmberAgent without requesting QUERY_ALL_PACKAGES.",
        parameters = {
            obj(
                "query" to stringProp("Optional app label or package filter."),
                "limit" to integerProp("Maximum apps. Defaults to 80."),
            )
        },
        execute = { input ->
            trackSystemTool("apps_list", "列出应用", "apps", input.safePreview()) {
                textJson {
                    put("apps", queryLaunchableApps(input.string("query").orEmpty(), input.limit(default = 80, max = 200)))
                }
            }
        }
    )

    private val appOpenTool = Tool(
        name = "app_open",
        description = "Open an installed launchable app by package name.",
        parameters = {
            obj(
                "package_name" to stringProp("Android package name to launch."),
                required = listOf("package_name")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackSystemTool("app_open", "打开应用", "apps", input) {
                val packageName = input.requiredString("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("No launch intent for package: $packageName")
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                textJson {
                    put("success", true)
                    put("package_name", packageName)
                }
            }
        }
    )

    private suspend fun trackSystemTool(
        toolName: String,
        title: String,
        capabilityId: String,
        input: JsonElement,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        permissionBroker.ensureGranted(
            capabilityId = capabilityId,
            toolName = toolName,
            reason = title,
        )
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.toString(),
            runtime = "Android system access",
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun queryContacts(query: String, limit: Int) = buildJsonArray {
        val seen = mutableSetOf<Long>()
        val phoneSelection = if (query.isBlank()) null else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        }
        val phoneArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        var count = 0
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            phoneSelection,
            phoneArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && count < limit) {
                val contactId = cursor.getLong(idIndex)
                if (!seen.add(contactId)) continue
                val name = cursor.getString(nameIndex).orEmpty()
                val phone = cursor.getString(phoneIndex).orEmpty()
                add(buildJsonObject {
                    put("contact_id", contactId)
                    put("name", name)
                    put("phone_masked", maskPhone(phone))
                })
                count++
            }
        }

        if (count < limit) {
            val emailSelection = if (query.isBlank()) null else {
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?"
            }
            val emailArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                emailSelection,
                emailArgs,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
                val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext() && count < limit) {
                    val contactId = cursor.getLong(idIndex)
                    if (!seen.add(contactId)) continue
                    val name = cursor.getString(nameIndex).orEmpty()
                    val email = cursor.getString(emailIndex).orEmpty()
                    add(buildJsonObject {
                        put("contact_id", contactId)
                        put("name", name)
                        put("email_masked", maskEmail(email))
                    })
                    count++
                }
            }
        }
    }

    private fun createContact(name: String, phone: String, email: String): Long {
        val ops = arrayListOf<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        if (phone.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        if (email.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                .build()
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawContactUri = results.firstOrNull()?.uri ?: error("Contact insert did not return a raw contact URI")
        return ContentUris.parseId(rawContactUri)
    }

    private fun querySms(input: JsonElement, previewOnly: Boolean) = buildJsonArray {
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

    private fun queryCallLogs(limit: Int, since: Long?) = buildJsonArray {
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

    private fun queryCalendarEvents(input: JsonElement) = buildJsonArray {
        val now = System.currentTimeMillis()
        val from = input.long("from_epoch_ms") ?: now
        val to = input.long("to_epoch_ms") ?: (from + 7L * 24L * 60L * 60L * 1000L)
        val limit = input.limit(default = 30, max = 100)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        var count = 0
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext() && count < limit) {
                add(buildJsonObject {
                    put("event_id", cursor.getLong(idIndex))
                    put("title", cursor.getString(titleIndex).orEmpty())
                    put("begin_epoch_ms", cursor.getLong(beginIndex))
                    put("end_epoch_ms", cursor.getLong(endIndex))
                    put("location", cursor.getString(locationIndex).orEmpty())
                    put("calendar", cursor.getString(calendarIndex).orEmpty())
                })
                count++
            }
        }
    }

    private fun createCalendarEvent(input: JsonElement): Long {
        val calendarId = firstWritableCalendarId()
        val start = input.timeMillis("start_time", "start_epoch_ms")
        val end = input.timeMillis("end_time", "end_epoch_ms")
        require(end > start) { "end_time must be after start_time" }
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, input.requiredString("title"))
            put(CalendarContract.Events.DESCRIPTION, input.string("description").orEmpty())
            put(CalendarContract.Events.EVENT_LOCATION, input.string("location").orEmpty())
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Failed to create calendar event")
        return ContentUris.parseId(uri)
    }

    private fun firstWritableCalendarId(): Long {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        error("No writable calendar found")
    }

    private fun queryMedia(type: String, query: String, limit: Int) = buildJsonArray {
        val targets = when (type) {
            "images" -> listOf("image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            "video" -> listOf("video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            "audio" -> listOf("audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            else -> listOf(
                "image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            )
        }
        var count = 0
        for ((kind, uri) in targets) {
            if (count >= limit) break
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                query.takeIf { it.isNotBlank() }?.let { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" },
                query.takeIf { it.isNotBlank() }?.let { arrayOf("%$it%") },
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idIndex)
                    add(buildJsonObject {
                        put("type", kind)
                        put("uri", ContentUris.withAppendedId(uri, id).toString())
                        put("name", cursor.getString(nameIndex).orEmpty())
                        put("mime_type", cursor.getString(mimeIndex).orEmpty())
                        put("size_bytes", cursor.getLong(sizeIndex))
                        put("date_modified_seconds", cursor.getLong(modifiedIndex))
                    })
                    count++
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentOrLatestLocation(): Location? {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        latestLocation(locationManager)
            ?.takeIf { System.currentTimeMillis() - it.time < 5L * 60L * 1000L }
            ?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in locationManager.getProviders(true)) {
                val current = withTimeoutOrNull(5_000L) {
                    suspendCancellableCoroutine { continuation ->
                        val cancellationSignal = CancellationSignal()
                        continuation.invokeOnCancellation { cancellationSignal.cancel() }
                        locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            context.mainExecutor
                        ) { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                    }
                }
                if (current != null) return current
            }
        }

        return latestLocation(locationManager)
    }

    @SuppressLint("MissingPermission")
    private fun latestLocation(locationManager: LocationManager): Location? {
        return locationManager.getProviders(true)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
    }

    private suspend fun recordAudioOnce(durationMillis: Long): File {
        val outputDir = File(context.filesDir, "agent-artifacts/audio").apply { mkdirs() }
        val output = File(outputDir, "recording-${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(durationMillis)
            recorder.stop()
        } finally {
            recorder.release()
        }
        return output
    }

    private fun queryNotifications(limit: Int) = buildJsonArray {
        AmberNotificationListenerService.getActiveNotificationsSnapshot()
            .take(limit)
            .forEach { sbn ->
                val extras = sbn.notification.extras
                add(buildJsonObject {
                    put("package_name", sbn.packageName)
                    put("posted_at_epoch_ms", sbn.postTime)
                    put("title", extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty())
                    put("text", extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty().take(240))
                })
            }
    }

    private fun queryUsageStats(since: Long?, limit: Int) = buildJsonArray {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            ?: error("UsageStatsManager is unavailable")
        val end = System.currentTimeMillis()
        val start = since ?: (end - 24L * 60L * 60L * 1000L)
        usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .filter { it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .take(limit)
            .forEach { usage ->
                add(buildJsonObject {
                    put("package_name", usage.packageName)
                    put("label", appLabel(usage.packageName))
                    put("last_time_used_epoch_ms", usage.lastTimeUsed)
                    put("total_time_foreground_ms", usage.totalTimeInForeground)
                })
            }
    }

    private fun queryLaunchableApps(query: String, limit: Int) = buildJsonArray {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launchIntent, 0)
            .map { resolve ->
                val packageName = resolve.activityInfo.packageName
                val label = resolve.loadLabel(pm).toString()
                packageName to label
            }
            .filter { (packageName, label) ->
                query.isBlank() ||
                    packageName.contains(query, ignoreCase = true) ||
                    label.contains(query, ignoreCase = true)
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .take(limit)
            .forEach { (packageName, label) ->
                add(buildJsonObject {
                    put("package_name", packageName)
                    put("label", label)
                })
            }
    }

    private fun appLabel(packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        }.getOrDefault("")
    }

    private fun obj(vararg properties: Pair<String, JsonElement>, required: List<String>? = null) =
        InputSchema.Obj(
            properties = buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
            required = required
        )

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun enumProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach(::add) })
    }

    private fun JsonElement.limit(name: String = "limit", default: Int, max: Int): Int =
        min(int(name) ?: default, max).coerceAtLeast(1)

    private fun JsonElement.timeMillis(isoName: String, epochName: String): Long {
        string(isoName)?.takeIf { it.isNotBlank() }?.let { return Instant.parse(it).toEpochMilli() }
        return long(epochName) ?: error("$isoName or $epochName is required")
    }

    private fun JsonElement.safePreview(): JsonElement = buildJsonObject {
        jsonObject.forEach { (key, value) ->
            when (key) {
                "message", "body", "description" -> put("${key}_chars", value.toString().length)
                "phone_number", "phone", "sender" -> put("${key}_masked", maskPhone(value.toString().trim('"')))
                "email" -> put("email_masked", maskEmail(value.toString().trim('"')))
                else -> put(key, value)
            }
        }
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun maskPhone(value: String): String {
        val digits = value.filter { it.isDigit() }
        if (digits.length <= 4) return value.take(2) + "***"
        val prefix = digits.take(3)
        val suffix = digits.takeLast(4)
        return "$prefix****$suffix"
    }

    private fun maskEmail(value: String): String {
        val parts = value.split("@", limit = 2)
        if (parts.size != 2) return value.take(2) + "***"
        val name = parts[0]
        val domain = parts[1]
        val maskedName = when {
            name.isEmpty() -> "***"
            name.length == 1 -> "${name.first()}***"
            else -> "${name.first()}***${name.last()}"
        }
        return "$maskedName@$domain"
    }
}
