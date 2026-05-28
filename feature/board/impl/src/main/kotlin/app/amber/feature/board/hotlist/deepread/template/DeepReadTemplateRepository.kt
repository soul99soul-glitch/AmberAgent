package app.amber.feature.board.hotlist.deepread.template

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.amber.feature.board.DeepReadTemplateIds
import java.io.File

class DeepReadTemplateRepository(
    context: Context,
    private val json: Json,
) {
    private val templateDir = File(context.filesDir, "deep_read_templates")
    private val templates = MutableStateFlow<List<DeepReadTemplatePackage>>(emptyList())
    private val invalidTemplateCount = MutableStateFlow(0)

    fun observeTemplates(): StateFlow<List<DeepReadTemplatePackage>> = templates

    fun observeInvalidTemplateCount(): StateFlow<Int> = invalidTemplateCount

    suspend fun reload() {
        val loaded = loadTemplates()
        templates.value = loaded.valid
        invalidTemplateCount.value = loaded.invalidCount
    }

    suspend fun getTemplate(id: String): DeepReadTemplatePackage? =
        loadTemplates().valid.firstOrNull { it.id == id }

    suspend fun saveTemplate(template: DeepReadTemplatePackage): DeepReadTemplatePackage =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val normalized = template.copy(
                id = DeepReadTemplateIds.custom(template.id),
                name = template.name.trim().take(48).ifBlank { "自定义模板" },
                description = template.description.trim().take(160),
                html = template.html.trim(),
                createdAt = template.createdAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            validateCustomTemplate(normalized.html)
            templateDir.mkdirs()
            File(templateDir, "${normalized.id.safeFileName()}.json").writeText(json.encodeToString(normalized))
            val loaded = loadTemplates()
            templates.value = loaded.valid
            invalidTemplateCount.value = loaded.invalidCount
            normalized
        }

    suspend fun deleteTemplate(id: String) = withContext(Dispatchers.IO) {
        File(templateDir, "${id.safeFileName()}.json").delete()
        val loaded = loadTemplates()
        templates.value = loaded.valid
        invalidTemplateCount.value = loaded.invalidCount
    }

    private suspend fun loadTemplates(): LoadedTemplates = withContext(Dispatchers.IO) {
        if (!templateDir.exists()) return@withContext LoadedTemplates(emptyList(), 0)
        val files = templateDir.listFiles { file -> file.extension == "json" }.orEmpty()
        var decodeFailures = 0
        files
            .mapNotNull { file ->
                runCatching { json.decodeFromString<DeepReadTemplatePackage>(file.readText()) }
                    .onFailure { decodeFailures++ }
                    .getOrNull()
            }
            .filter { it.id.startsWith(DeepReadTemplateIds.CUSTOM_PREFIX) }
            .partition { template ->
                runCatching { validateCustomTemplate(template.html) }.isSuccess
            }
            .let { (valid, invalid) ->
                LoadedTemplates(
                    valid = valid.sortedByDescending { it.updatedAt },
                    invalidCount = invalid.size + decodeFailures,
                )
            }
    }

    companion object {
        fun validateCustomTemplate(html: String) {
            DeepReadTemplateValidator.validateOrThrow(html)
            val required = listOf("{{title}}", "{{summary}}", "{{analysis_html}}", "{{extended_reading_html}}")
            val missing = required.filterNot { it in html }
            if (missing.isNotEmpty()) {
                throw DeepReadTemplateValidationException("Template missing placeholders: ${missing.joinToString()}")
            }
            if ("{{narrative_html}}" !in html && "{{timeline_html}}" !in html && "{{core_points_html}}" !in html) {
                throw DeepReadTemplateValidationException("Template must include {{narrative_html}}, {{timeline_html}}, or {{core_points_html}}")
            }
        }
    }
}

private data class LoadedTemplates(
    val valid: List<DeepReadTemplatePackage>,
    val invalidCount: Int,
)

private fun String.safeFileName(): String =
    replace(Regex("[^A-Za-z0-9_.:-]"), "_")
