package app.amber.ai.provider

import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P3+ 风险拦截（Surface F / TODO 断链审计 → ai-core Provider 契约）：
 *
 * `Provider.getBalance()` 的默认实现此前是 `return "TODO"`（Provider.kt:18-20）。
 * 这是一个隐藏的数据/契约陷阱：3 个 provider 里只有 OpenAIProvider override 了它，
 * Google / Claude（含 KMP 端 ClaudeKmpProvider / OpenAIKmpProvider）都走默认实现。
 * 若 iOS 侧经 :shared → Swift 调 getBalance，会把字面量 "TODO" 当作余额字符串
 * 显示给用户——逻辑不闭环（调用方拿到的是占位符而非真数据或明确错误）。
 *
 * 修复：默认实现改为抛 UnsupportedOperationException（fail-fast，明确契约）。
 * 本测试钉死：未 override getBalance 的 Provider 子类调用时必须抛异常，不得返回
 * "TODO" 或任何占位字符串。若有人把默认实现改回 return "TODO"，本测试失败。
 */
class ProviderGetBalanceDefaultContractTest {

    /**
     * 一个故意不 override getBalance 的 Provider 桩，用来触发默认实现。
     * 签名严格对齐 Provider<T> 接口（Provider.kt:15-55）。
     */
    private class NoBalanceProvider : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        // 故意不 override getBalance —— 测试默认实现
        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("not used in test")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = error("not used in test")

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("not used in test")
    }

    @Test
    fun defaultGetBalanceThrowsInsteadOfReturningTodoString() {
        val provider = NoBalanceProvider()
        val setting = ProviderSetting.OpenAI()
        val ex = runBlocking {
            // 用 runBlocking 而非 runTest：commonTest 默认只有 kotlinx-coroutines-core，
            // 不强引 coroutines-test，保持 ai-core 依赖最小化。
            assertFailsWith<UnsupportedOperationException> {
                provider.getBalance(setting)
            }
        }
        // 错误信息必须点名 getBalance，便于调用方诊断
        assertTrue(ex.message!!.contains("getBalance"), "异常信息应说明是 getBalance 未实现: ${ex.message}")
        // 关键断言：绝不能返回占位字符串 "TODO"
        // （若默认实现被改回 return "TODO"，上面的 assertFailsWith 会失败，本测试即红）
    }
}
