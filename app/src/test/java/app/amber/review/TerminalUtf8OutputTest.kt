package app.amber.review

import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.terminal.TerminalJobLog
import app.amber.feature.terminal.TerminalOutputBuffer
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.terminal.TerminalRuntimeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation

class TerminalUtf8OutputTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val expected = "ASCII 中文🙂\nline two: café\n"

    @Test
    fun processReaderPreservesUtf8AcrossSingleByteReads() {
        val runtime = ReflectionFixture.allocate<TerminalRuntime>()
        ReflectionFixture.set(runtime, "activityStore", AgentToolActivityStore())
        val jobType = Class.forName("app.amber.feature.terminal.TerminalJob")
        val job = ReflectionFixture.allocate(jobType)
        val output = TerminalOutputBuffer(1024)
        ReflectionFixture.set(job, "id", "utf8")
        ReflectionFixture.set(job, "runtime", TerminalRuntimeKind.ANDROID_SHELL)
        ReflectionFixture.set(job, "output", output)
        ReflectionFixture.set(job, "completionNotified", AtomicBoolean(false))
        ReflectionFixture.set(job, "log", TerminalJobLog(temporaryFolder.newFile(), 4096))
        val method = TerminalRuntime::class.java.getDeclaredMethod("readProcessOutput", jobType, Process::class.java)
        method.isAccessible = true

        method.invoke(runtime, job, OutputProcess(expected))

        assertEquals(expected, output.snapshot())
    }

    @Test
    fun persistentSessionReaderPreservesUtf8AcrossSingleByteReads() = runBlocking {
        val runtime = ReflectionFixture.allocate<TerminalRuntime>()
        val sessionType = Class.forName("app.amber.feature.terminal.TerminalSession")
        val outputType = Class.forName("app.amber.feature.terminal.TerminalSessionOutput")
        val output = outputType.getDeclaredConstructor(Int::class.javaPrimitiveType).let {
            it.isAccessible = true
            it.newInstance(1024)
        }
        val session = ReflectionFixture.allocate(sessionType)
        ReflectionFixture.set(session, "process", OutputProcess(expected))
        ReflectionFixture.set(session, "output", output)
        val method = TerminalRuntime::class.java.getDeclaredMethod("readSessionOutput", sessionType, Continuation::class.java)

        ReflectionFixture.invokeSuspend(runtime, method, session)

        val actual = outputType.getDeclaredMethod("drain").let { it.isAccessible = true; it.invoke(output) }
        assertEquals(expected, actual)
    }

    /** Keeps the session alive so the test exercises reading without workspace cleanup. */
    private class OutputProcess(text: String) : Process() {
        private val bytes = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        private val input = object : InputStream() {
            override fun read(): Int = bytes.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                bytes.read(buffer, offset, minOf(length, 1))
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = true
    }
}
