package app.amber.core.di

import app.amber.core.automation.ScreenOcrEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Guards the lifecycle wiring used for the OCR engine: a Koin singleton alone does
 * not close `AutoCloseable`, so `AgentInfraModule` registers `withOptions { onClose }`.
 */
class OcrRecognizerKoinCloseLifecycleTest {

    private class FakeCloseable : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun koinOnCloseInvokesAutoCloseableClose() {
        val testModule = module {
            single { FakeCloseable() } withOptions {
                onClose { closeable -> closeable?.close() }
            }
        }
        val application = koinApplication { modules(testModule) }
        val instance = application.koin.get<FakeCloseable>()

        application.close()

        assertTrue(instance.closed)
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun agentInfraModuleRegistersOnCloseForScreenOcrEngine() {
        val factory = agentInfraModule.mappings.values.single { mapping ->
            mapping.beanDefinition.primaryType == ScreenOcrEngine::class
        }
        assertNotNull(
            "ScreenOcrEngine singleton must register Koin onClose so close() actually runs",
            factory.beanDefinition.callbacks.onClose,
        )
    }
}
