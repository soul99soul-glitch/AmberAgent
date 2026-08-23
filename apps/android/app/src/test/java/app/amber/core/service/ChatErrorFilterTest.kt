package app.amber.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * P8-07：错误按会话过滤。
 *
 * - 过滤/分区是纯函数（[errorsForConversation] / [globalErrors]），直接单测。
 * - UI 区分（全局 banner 与消息错误）用源码断言验证接入点。
 */
class ChatErrorFilterTest {

    private fun chatError(conversationId: Uuid?, message: String) = ChatError(
        title = "t-$message",
        error = IllegalStateException(message),
        conversationId = conversationId,
    )

    @Test
    fun `conversation A generation error does not surface in conversation B`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val errors = listOf(
            chatError(a, "a-error"),
            chatError(b, "b-error"),
        )

        assertEquals(listOf("a-error"), errors.errorsForConversation(a).map { it.error.message })
        assertEquals(listOf("b-error"), errors.errorsForConversation(b).map { it.error.message })
    }

    @Test
    fun `A session generating while user switches to B does not leak A error into B`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val errors = listOf(
            chatError(a, "A generation failed"),
            chatError(a, "A second error"),
        )

        // B 会话看到的错误列表为空
        assertTrue(errors.errorsForConversation(b).isEmpty())
        assertEquals(2, errors.errorsForConversation(a).size)
    }

    @Test
    fun `global provider config error is separated from per-conversation errors`() {
        val a = Uuid.random()
        val errors = listOf(
            chatError(null, "provider config broken"),
            chatError(a, "generation failed"),
        )

        val global = errors.globalErrors()
        assertEquals(listOf("provider config broken"), global.map { it.error.message })
        assertTrue(global.all { it.conversationId == null })
        assertEquals(listOf("generation failed"), errors.errorsForConversation(a).map { it.error.message })
    }

    // ---- UI 接入：全局 banner 与消息错误区分 ----

    @Test
    fun `chat vm exposes per-conversation errors and global errors separately`() {
        val vm = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatVM.kt").readText()
        val page = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt").readText()

        assertTrue(vm.contains("errorsForConversation(_conversationId)"))
        assertTrue(vm.contains("it.globalErrors()"))
        assertTrue(page.contains("globalErrors by vm.globalErrors.collectAsStateWithLifecycle()"))
    }

    @Test
    fun `global error card uses distinct styling from message error`() {
        val card = repoFile("src/main/java/app/amber/feature/ui/components/ui/ErrorCard.kt").readText()

        assertTrue(card.contains("globalErrors: List<ChatError> = emptyList()"))
        assertTrue(card.contains("isGlobal: Boolean = false"))
        assertTrue(card.contains("tertiaryContainer"))
        assertTrue(card.contains("errorContainer"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
