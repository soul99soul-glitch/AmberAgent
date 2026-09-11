package app.amber.feature.board.hotlist.deepread.template

import java.util.Locale

object DeepReadTemplateDraftGuard {
    fun applySourceEdit(
        currentDraft: DeepReadTemplatePackage?,
        name: String,
        html: String,
        locale: Locale = Locale.CHINESE,
    ): DeepReadTemplateDraftEditResult =
        try {
            DeepReadTemplateRepository.validateCustomTemplate(html)
            val draft = (currentDraft ?: DeepReadTemplatePackage(
                id = "draft",
                name = name.ifBlank { locale.defaultTemplateName() },
                html = html,
                createdByAi = true,
            )).copy(
                id = "draft",
                name = name.ifBlank { currentDraft?.name.orEmpty() }.ifBlank { locale.defaultTemplateName() },
                html = html,
                createdByAi = true,
                schemaVersion = 1,
            )
            DeepReadTemplateDraftEditResult(validDraft = draft, validationError = null)
        } catch (error: DeepReadTemplateValidationException) {
            DeepReadTemplateDraftEditResult(
                validDraft = currentDraft,
                validationError = error.message ?: locale.templateValidationFailure(),
            )
        } catch (error: IllegalArgumentException) {
            DeepReadTemplateDraftEditResult(
                validDraft = currentDraft,
                validationError = error.message ?: locale.templateValidationFailure(),
            )
        }
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

private fun Locale.defaultTemplateName(): String =
    if (isChineseLocale()) "自定义模板" else "Custom template"

private fun Locale.templateValidationFailure(): String =
    if (isChineseLocale()) "模板校验失败" else "Template validation failed"

data class DeepReadTemplateDraftEditResult(
    val validDraft: DeepReadTemplatePackage?,
    val validationError: String?,
)
