package app.amber.ai.provider.openai

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIUtf8LineReaderTest {
    @Test
    fun preservesAChineseCharacterSplitAcrossNetworkBuffers() = runBlocking {
        val expected = "data: 汉"
        val bytes = "$expected\n".encodeToByteArray()
        val characterStart = "data: ".encodeToByteArray().size

        val decodedBySplit = (1..2).map { bytesFromCharacterInFirstBuffer ->
            val splitAt = characterStart + bytesFromCharacterInFirstBuffer
            val channel = ByteChannel(autoFlush = true)
            val lines = mutableListOf<String>()
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                channel.collectUtf8Lines(lines::add)
            }

            channel.writeFully(bytes, startIndex = 0, endIndex = splitAt)
            withTimeout(1_000) {
                while (channel.availableForRead != 0) yield()
            }
            channel.writeFully(bytes, startIndex = splitAt, endIndex = bytes.size)
            channel.close()
            reader.await()

            lines.single()
        }

        val oneByteAtATimeChannel = ByteChannel(autoFlush = true)
        val oneByteAtATimeLines = mutableListOf<String>()
        val oneByteAtATimeReader = async(start = CoroutineStart.UNDISPATCHED) {
            oneByteAtATimeChannel.collectUtf8Lines(oneByteAtATimeLines::add)
        }
        var chunkStart = 0
        for (chunkEnd in characterStart + 1..characterStart + 2) {
            oneByteAtATimeChannel.writeFully(bytes, startIndex = chunkStart, endIndex = chunkEnd)
            withTimeout(1_000) {
                while (oneByteAtATimeChannel.availableForRead != 0) yield()
            }
            chunkStart = chunkEnd
        }
        oneByteAtATimeChannel.writeFully(bytes, startIndex = chunkStart, endIndex = bytes.size)
        oneByteAtATimeChannel.close()
        oneByteAtATimeReader.await()

        assertEquals(listOf(expected, expected, expected), decodedBySplit + oneByteAtATimeLines.single())
    }

    @Test
    fun preservesCrLfCrAndFinalUnterminatedLineBehavior() = runBlocking {
        val lines = mutableListOf<String>()

        ByteReadChannel("one\r\ntwo\n\nthree\rfour").collectUtf8Lines(lines::add)

        assertEquals(listOf("one", "two", "", "three", "four"), lines)
    }
}
