package app.amber.feature.ui.pages.setting

import app.amber.ai.provider.ProviderSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.InputStream
import kotlin.uuid.Uuid

private const val MAX_IMPORT_CHARS = 1_000_000
private val importJson = Json { ignoreUnknownKeys = false }

internal fun readProviderImport(input: InputStream): List<ProviderSetting> =
    input.bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(MAX_IMPORT_CHARS + 1)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read == -1) break
            count += read
        }
        require(count <= MAX_IMPORT_CHARS)
        parseProviderImport(String(buffer, 0, count))
    }

/** JSON array / providers envelope, or the same data in fenced Markdown JSON blocks. */
internal fun parseProviderImport(text: String): List<ProviderSetting> {
    require(text.length <= MAX_IMPORT_CHARS)
    val source = text.removePrefix("\uFEFF").trim()
    val documents = if (source.startsWith("[") || source.startsWith("{")) {
        listOf(source)
    } else {
        Regex("(?ms)^```json[ \\t]*\\r?\\n(.*?)^```[ \\t]*\\r?$")
            .findAll(source).map { it.groupValues[1] }.toList()
    }
    require(documents.isNotEmpty())
    return documents.flatMap { document ->
        val root = importJson.parseToJsonElement(document)
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                require(root.keys == setOf("providers"))
                root["providers"] as? JsonArray ?: error("Expected providers array")
            }
            else -> error("Expected providers array")
        }
        entries.map { entry ->
            val obj = entry as? JsonObject ?: error("Expected provider object")
            require("name" in obj && "baseUrl" in obj)
            // Most files use OpenAI-compatible providers; other protocols opt in with type.
            val normalized = JsonObject(mapOf("type" to JsonPrimitive("openai")) + obj)
            val provider = importJson.decodeFromJsonElement<ProviderSetting>(normalized)
            require(provider.name.isNotBlank())
            val url = when (provider) {
                is ProviderSetting.OpenAI -> provider.baseUrl
                is ProviderSetting.Claude -> provider.baseUrl
                is ProviderSetting.Google -> provider.baseUrl
            }
            require(url.toHttpUrlOrNull() != null)
            require(provider.models.all { it.modelId.isNotBlank() })
            require(provider.models.all { it.contextWindowTokens == null || it.contextWindowTokens!! > 0 })
            provider.freshImportIds()
        }
    }.also { require(it.isNotEmpty()) }
}

internal fun ProviderSetting.freshImportIds(): ProviderSetting = copyProvider(
    id = Uuid.random(),
    models = models.map { model ->
        model.copy(
            id = Uuid.random(),
            displayName = model.displayName.ifBlank { model.modelId },
            providerOverwrite = model.providerOverwrite?.freshImportIds(),
        )
    },
)
