package app.amber.document.nativebridge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OfficeNativeSwitchTest {
    @Test
    fun disabledSwitchDoesNotInspectInputOrInvokeFallback() {
        val previous = OfficeNativeSwitch.config
        val invalidFile = Files.createTempFile("invalid-office", ".docx").toFile()
        var fallbackCalled = false
        try {
            OfficeNativeSwitch.config = OfficeNativeSwitch.DisabledConfig

            assertNull(
                OfficeNativeSwitch.parseDocxOrNull(File(invalidFile.parentFile, "missing.docx")) {
                    fallbackCalled = true
                    "fallback"
                },
            )
            assertNull(
                OfficeNativeSwitch.parseDocxOrNull(invalidFile) {
                    fallbackCalled = true
                    "fallback"
                },
            )
            assertFalse(fallbackCalled)
        } finally {
            OfficeNativeSwitch.config = previous
            invalidFile.delete()
        }
    }

    @Test
    fun enabledSwitchReturnsNullForInvalidInputWithoutInvokingFallback() {
        val previous = OfficeNativeSwitch.config
        val invalidFile = Files.createTempFile("invalid-office-enabled", ".docx").toFile()
        var fallbackCalled = false
        try {
            OfficeNativeSwitch.config = object : OfficeNativeSwitch.Config {
                override fun enabled(): Boolean = true
                override fun samplingRate(): Float = 0f
                override fun onLoadFailure(error: Throwable) = Unit
                override fun onNativePanic(stage: String, error: Throwable?) = Unit
                override fun onDiff(
                    stage: String,
                    equal: Boolean,
                    jvmSummary: String,
                    nativeSummary: String,
                ) = Unit
            }

            assertNull(
                OfficeNativeSwitch.parseDocxOrNull(invalidFile) {
                    fallbackCalled = true
                    "fallback"
                },
            )
            assertFalse(fallbackCalled)
        } finally {
            OfficeNativeSwitch.config = previous
            invalidFile.delete()
        }
    }
}
