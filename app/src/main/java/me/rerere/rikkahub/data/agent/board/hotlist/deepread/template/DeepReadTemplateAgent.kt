package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.agent.board.DeepReadTemplateIds
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import kotlin.uuid.Uuid

class DeepReadTemplateAgent(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val repository: DeepReadTemplateRepository,
    private val json: Json,
) {
    suspend fun generate(name: String, brief: String): Result<DeepReadTemplatePackage> {
        val settings = settingsStore.settingsFlow.value
        val model = settings.agentRuntime.todayBoard.boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
            ?: settings.resolveTaskChatModel(settings.chatModelId)
            ?: return Result.failure(IllegalStateException("请先配置聊天模型"))
        val provider = model.findProvider(settings.providers)
            ?: return Result.failure(IllegalStateException("模型 ${model.displayName} 的提供商不可用"))

        return try {
            val response = withTimeout(MODEL_TIMEOUT_MS) {
                providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system("你是移动端新闻杂志版式设计师。只输出合法 JSON，不要解释。"),
                        UIMessage.user(buildPrompt(name, brief)),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.35f,
                        maxTokens = 3600,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            val raw = response.choices.firstOrNull()?.message?.toText().orEmpty()
            val decoded = parsePackage(raw)
                ?: return Result.failure(IllegalStateException("模型没有输出可用模板 JSON"))
            val saved = repository.saveTemplate(
                decoded.copy(
                    id = DeepReadTemplateIds.custom(Uuid.random().toString()),
                    name = name.ifBlank { decoded.name },
                    createdByAi = true,
                )
            )
            Result.success(saved)
        } catch (error: TimeoutCancellationException) {
            Result.failure(IllegalStateException("模板生成超时，请稍后重试"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun parsePackage(raw: String): DeepReadTemplatePackage? {
        val cleaned = raw
            .trim()
            .removePrefix("```json")
            .removePrefix("```html")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return runCatching { json.decodeFromString<DeepReadTemplatePackage>(cleaned) }.getOrNull()
    }

    private fun buildPrompt(name: String, brief: String): String =
        """
        请生成一个 AmberAgent 深度阅读静态 HTML 模板，名称：${name.ifBlank { "自定义模板" }}。
        设计方向：${brief.ifBlank { "高端 News 杂志 App，强排版、全幅图片、留白、时间轴、引用块、扩展阅读。" }}

        输出 JSON：
        {
          "id": "draft",
          "name": "模板名",
          "description": "一句话说明",
          "html": "<!doctype html>...",
          "createdByAi": true,
          "schemaVersion": 1
        }

        硬性要求：
        - html 必须是完整 HTML 文档，内联 CSS，禁止 JavaScript、iframe、form、button、外链 CSS、@import、CSS url()。
        - 必须包含占位符 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}，且 {{timeline_html}} / {{core_points_html}} 至少包含一个。
        - 推荐支持这些占位符：{{topic_type}}、{{source_label}}、{{hero_image_url}}、{{hero_caption}}、{{timeline_html}}、{{core_points_html}}、{{analysis_html}}、{{extended_reading_html}}、{{font_css}}。
        - 如果使用 hero 图片，只能写 <img src="{{hero_image_url}}" ...>；不要写真实图片 URL。
        - 块级占位符 {{timeline_html}}、{{core_points_html}}、{{analysis_html}}、{{extended_reading_html}} 必须放在正文节点中，不能放进标签属性或 style。
        - 不要写任何固定 href/src 外链；扩展阅读链接只能来自 {{extended_reading_html}}。
        - 模板内容只负责版式，不生成新闻事实，不写示例新闻正文。
        - 移动端优先，正文 15px 左右、line-height 1.7 左右；可用斜切 Hero、红色时间轴、灰色 metadata。
        """.trimIndent()

    companion object {
        private const val MODEL_TIMEOUT_MS = 60_000L
    }
}
