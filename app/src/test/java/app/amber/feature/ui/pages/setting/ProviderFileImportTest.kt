package app.amber.feature.ui.pages.setting

import app.amber.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderFileImportTest {
    @Test
    fun jsonBatchPreservesConfigurationWithFreshIds() {
        val oldId = "00000000-0000-0000-0000-000000000001"
        val providers = parseProviderImport("""
            {"providers":[
              {"name":"Qwen","baseUrl":"https://example.com/v1","apiKey":"test-key",
               "id":"$oldId","models":[{"id":"$oldId","modelId":"qwen-test",
                 "contextWindowTokens":1000000,"customHeaders":[{"name":"User-Agent","value":"AmberAgent"}]}]},
              {"type":"claude","name":"Claude","baseUrl":"https://example.org/v1","models":[]}
            ]}
        """.trimIndent())
        val qwen = providers[0] as ProviderSetting.OpenAI
        assertEquals("test-key", qwen.apiKey)
        assertEquals(1_000_000, qwen.models.single().contextWindowTokens)
        assertEquals("qwen-test", qwen.models.single().displayName)
        assertEquals("AmberAgent", qwen.models.single().customHeaders.single().value)
        assertNotEquals(Uuid.parse(oldId), qwen.id)
        assertNotEquals(Uuid.parse(oldId), qwen.models.single().id)
        assertEquals("Claude", (providers[1] as ProviderSetting.Claude).name)
    }

    @Test
    fun markdownReadsJsonBlocksWithoutInterpretingProse() {
        val providers = parseProviderImport("""
            # Providers
            Instructions and notes are not configuration.
            ```json
            [{"name":"First","baseUrl":"https://example.com/v1"}]
            ```
            Notes between blocks.
            ```json
            {"providers":[{"name":"Second","baseUrl":"https://example.org/v1"}]}
            ```
        """.trimIndent())
        assertEquals(listOf("First", "Second"), providers.map { it.name })
    }

    @Test
    fun invalidEntryRejectsWholeBatch() {
        assertThrows(IllegalArgumentException::class.java) {
            parseProviderImport("""[
                {"name":"Valid","baseUrl":"https://example.com/v1"},
                {"name":"Invalid","baseUrl":"not a URL"}
            ]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseProviderImport("""[{"name":"Typo","baseUrl":"https://example.com","api_key":"test-key"}]""")
        }
    }

    @Test
    fun oversizedInputIsRejectedDuringRead() {
        assertThrows(IllegalArgumentException::class.java) {
            readProviderImport(" ".repeat(1_000_001).byteInputStream())
        }
    }
}
