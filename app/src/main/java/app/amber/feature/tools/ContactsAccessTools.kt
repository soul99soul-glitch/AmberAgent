package app.amber.feature.tools

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

internal fun createContactsSearchTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "contacts_search",
    description = "Search Android contacts by name, phone, or email after READ_CONTACTS is granted. Phone numbers are masked by default.",
    parameters = {
        obj(
            "query" to accessStringProp("Name, phone, or email keyword. Empty lists recent contacts."),
            "limit" to integerProp("Maximum contacts to return. Defaults to 20."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("contacts_search", "搜索通讯录", "contacts_read", input) {
            textJson {
                put("contacts", queryContacts(context, input.string("query").orEmpty(), input.limit(default = 20, max = 50)))
            }
        }
    }
)

internal fun createContactsWriteTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "contacts_write",
    description = "Create a contact in Android Contacts. Requires WRITE_CONTACTS and explicit approval.",
    parameters = {
        obj(
            "name" to accessStringProp("Contact display name."),
            "phone" to accessStringProp("Optional phone number."),
            "email" to accessStringProp("Optional email address."),
            required = listOf("name")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("contacts_write", "写入联系人", "contacts_write", input.safePreview()) {
            val name = input.requiredString("name")
            val phone = input.string("phone").orEmpty()
            val email = input.string("email").orEmpty()
            if (phone.isBlank() && email.isBlank()) error("phone or email is required")
            val id = createContact(context, name = name, phone = phone, email = email)
            textJson {
                put("success", true)
                put("raw_contact_id", id)
            }
        }
    }
)

private fun queryContacts(context: Context, query: String, limit: Int) = buildJsonArray {
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

private fun createContact(context: Context, name: String, phone: String, email: String): Long {
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
