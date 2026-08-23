package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCouncilManagerTest {

    @Test
    fun `legacy synthesis uses configured host reasoning level`() {
        assertEquals(
            ReasoningLevel.HIGH,
            ModelCouncilRuntimeSetting(hostReasoningLevel = ReasoningLevel.HIGH)
                .legacySynthesisReasoningLevel(),
        )
    }

    @Test
    fun `legacy synthesis keeps off as the unset default`() {
        assertEquals(
            ReasoningLevel.OFF,
            ModelCouncilRuntimeSetting().legacySynthesisReasoningLevel(),
        )
    }
}
