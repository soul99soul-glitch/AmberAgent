package app.amber.feature.board.hotlist.deepread.template

object DeepReadTemplateDraftGuard {
    fun applySourceEdit(
        currentDraft: DeepReadTemplatePackage?,
        name: String,
        html: String,
    ): DeepReadTemplateDraftEditResult =
        try {
            DeepReadTemplateRepository.validateCustomTemplate(html)
            val draft = (currentDraft ?: DeepReadTemplatePackage(
                id = "draft",
                name = name.ifBlank { "自定义模板" },
                html = html,
                createdByAi = true,
            )).copy(
                id = "draft",
                name = name.ifBlank { currentDraft?.name.orEmpty() }.ifBlank { "自定义模板" },
                html = html,
                createdByAi = true,
                schemaVersion = 1,
            )
            DeepReadTemplateDraftEditResult(validDraft = draft, validationError = null)
        } catch (error: DeepReadTemplateValidationException) {
            DeepReadTemplateDraftEditResult(
                validDraft = currentDraft,
                validationError = error.message ?: "模板校验失败",
            )
        } catch (error: IllegalArgumentException) {
            DeepReadTemplateDraftEditResult(
                validDraft = currentDraft,
                validationError = error.message ?: "模板校验失败",
            )
        }
}

data class DeepReadTemplateDraftEditResult(
    val validDraft: DeepReadTemplatePackage?,
    val validationError: String?,
)
