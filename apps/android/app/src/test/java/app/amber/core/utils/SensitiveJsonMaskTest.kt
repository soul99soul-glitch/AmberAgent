package app.amber.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * maskSensitiveJson 契约：嵌套 api_key 被掩码、普通字段不受影响、短值全掩码、
 * 键名匹配忽略大小写（camelCase / 拼接拼写如 secretKey / accessToken 也命中）、
 * 数组递归、非字符串原始值掩码为 "***"、空串与 null 原样保留。
 */
class SensitiveJsonMaskTest {

    private fun mask(input: String): String =
        JsonInstantPretty.encodeToString(maskSensitiveJson(JsonInstant.parseToJsonElement(input)))

    @Test
    fun `masks nested api_key keeping only the last four chars`() {
        val masked = mask(
            """{"outer":{"provider":{"api_key":"sk-secret-value-1234","name":"gpt"}}}"""
        )
        assertFalse(masked.contains("sk-secret-value-1234"))
        assertTrue(masked.contains("\"••••1234\""))
    }

    @Test
    fun `normal fields are left untouched`() {
        val masked = mask(
            """{"provider_id":"abc-123","model":"gpt-4o",""" +
                """"base_url":"https://api.example.com/v1","nested":{"count":2}}"""
        )
        assertTrue(masked.contains("\"abc-123\""))
        assertTrue(masked.contains("\"gpt-4o\""))
        assertTrue(masked.contains("https://api.example.com/v1"))
        assertTrue(masked.contains("\"count\": 2"))
    }

    @Test
    fun `short values are fully masked without a suffix`() {
        val masked = mask("""{"api_key":"abcd"}""")
        assertFalse(masked.contains("abcd"))
        assertEquals(1, "••••".toRegex().findAll(masked).count())
        assertFalse(masked.contains("\"••••abcd\""))
    }

    @Test
    fun `key matching ignores case and camelCase spelling`() {
        val masked = mask(
            """{"API_KEY":"v1","Api_Key":"v2","apiKey":"v3",""" +
                """"Authorization":"Bearer abc","Token":"v4","secret_key":"v5"}"""
        )
        listOf("v1", "v2", "v3", "Bearer abc", "v4", "v5").forEach { plain ->
            assertFalse(masked.contains(plain))
        }
    }

    @Test
    fun `concatenated key spellings secretKey accessKey accessToken are masked`() {
        val masked = mask(
            """{"secretKey":"sv1","accessKey":"av2","accessToken":"at3",""" +
                """"secretkey":"sv4","accesskey":"av5","accesstoken":"at6"}"""
        )
        listOf("sv1", "av2", "at3", "sv4", "av5", "at6").forEach { plain ->
            assertFalse(masked.contains(plain))
        }
    }

    @Test
    fun `masks inside arrays recursively`() {
        val masked = mask(
            """{"items":[{"api_key":"array-secret-7777"},{"token":"plain"}],"list":["a","b"]}"""
        )
        assertFalse(masked.contains("array-secret-7777"))
        assertTrue(masked.contains("\"••••7777\""))
        assertTrue(masked.contains("\"a\""))
    }

    @Test
    fun `empty and null values under sensitive keys are preserved while numbers are masked`() {
        val masked = mask("""{"api_key":"","token":12345,"password":null,"secret":true}""")
        assertTrue(masked.contains("\"api_key\": \"\""))
        assertTrue(masked.contains("\"token\": \"***\""))
        assertTrue(masked.contains("\"password\": null"))
        assertTrue(masked.contains("\"secret\": \"***\""))
    }
}
