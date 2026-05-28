package app.amber.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSettingsRedactorTest {
    @Test
    fun masksKnownSecretFieldsWithoutMaskingMaxTokens() {
        val input = buildJsonObject {
            put("apiKey", "sk-live")
            put("maxTokens", 4096)
            put("webDavConfig", buildJsonObject {
                put("password", "webdav-password")
            })
            put("s3Config", buildJsonObject {
                put("secretAccessKey", "s3-secret")
            })
            put("mcpServers", JsonArray(listOf(buildJsonObject {
                put("commonOptions", buildJsonObject {
                    put("headers", JsonArray(listOf(buildJsonObject {
                        put("first", "Authorization")
                        put("second", "Bearer token")
                    })))
                })
            })))
        }

        val masked = maskBackupSecrets(input).jsonObject

        assertEquals(BACKUP_SECRET_MASK, masked["apiKey"]!!.jsonPrimitive.content)
        assertEquals("4096", masked["maxTokens"]!!.jsonPrimitive.content)
        assertEquals(
            BACKUP_SECRET_MASK,
            masked["webDavConfig"]!!.jsonObject["password"]!!.jsonPrimitive.content
        )
        assertEquals(
            BACKUP_SECRET_MASK,
            masked["s3Config"]!!.jsonObject["secretAccessKey"]!!.jsonPrimitive.content
        )
        val header = masked["mcpServers"]!!
            .jsonArray[0].jsonObject["commonOptions"]!!
            .jsonObject["headers"]!!
            .jsonArray[0].jsonObject
        assertEquals("Authorization", header["first"]!!.jsonPrimitive.contentOrNull)
        assertEquals(BACKUP_SECRET_MASK, header["second"]!!.jsonPrimitive.contentOrNull)
    }
}
