package app.amber.core.automation

import com.google.mlkit.common.MlKitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the production OCR contract without touching a device:
 * the compiled ML Kit artifacts must be on the app classpath, the old reflective
 * recognizer must be gone, and diagnostics must stay stable.
 */
class ScreenOcrProductionContractTest {

    private val loader get() = this::class.java.classLoader!!

    @Test
    fun mlKitTextRecognitionClassesAreCompiledIntoTheApp() {
        listOf(
            "com.google.mlkit.vision.text.TextRecognition",
            "com.google.mlkit.vision.text.TextRecognizer",
            "com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions\$Builder",
            "com.google.mlkit.vision.common.InputImage",
            "com.google.mlkit.common.MlKitException",
        ).forEach { className ->
            assertNotNull("$className missing from app classpath", Class.forName(className, false, loader))
        }
    }

    @Test
    fun reflectiveRecognizerIsGone() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("app.amber.core.automation.AndroidOnDeviceOcrRecognizer", false, loader)
        }
    }

    @Test
    fun supportedScriptsAreChineseAndLatinOnly() {
        assertEquals("mlkit_text_recognition_chinese", ScreenOcrCapabilities.RECOGNIZER_ID)
        assertTrue(ScreenOcrCapabilities.supportedScripts.containsAll(listOf("zh-Hans", "zh-Hant", "latin")))
        assertTrue(ScreenOcrCapabilities.unsupportedScripts.containsAll(listOf("ja", "ko", "ru", "devanagari")))
    }

    @Test
    fun mlKitErrorCodesMapToStableDiagnostics() {
        assertEquals("not_enough_space", mlKitFailureReason(MlKitException("x", MlKitException.NOT_ENOUGH_SPACE)))
        assertEquals("model_hash_mismatch", mlKitFailureReason(MlKitException("x", MlKitException.MODEL_HASH_MISMATCH)))
        assertEquals("model_not_found", mlKitFailureReason(MlKitException("x", MlKitException.NOT_FOUND)))
        assertEquals("recognizer_unavailable", mlKitFailureReason(MlKitException("x", MlKitException.UNAVAILABLE)))
        assertEquals("recognition_timeout", mlKitFailureReason(MlKitException("x", MlKitException.DEADLINE_EXCEEDED)))
        assertEquals("invalid_image", mlKitFailureReason(MlKitException("x", MlKitException.INVALID_ARGUMENT)))
        assertEquals("recognition_failed", mlKitFailureReason(IllegalStateException("boom")))
    }

    @Test
    fun sensitiveClassifierRedactsOtpPasswordAndCardNumbers() {
        val otp = SensitiveScreenTextClassifier.redactIfSensitive("验证码 849201")
        assertFalse(otp.contains("849201"))
        assertTrue(otp.contains("******"))

        val password = SensitiveScreenTextClassifier.redactIfSensitive("密码: hunter2")
        assertFalse(password.contains("hunter2"))

        val card = SensitiveScreenTextClassifier.redactIfSensitive("银行卡 6222021234567890")
        assertFalse(card.contains("6222021234567890"))

        assertTrue(SensitiveScreenTextClassifier.isSensitive("OTP 123456"))
        assertFalse(SensitiveScreenTextClassifier.isSensitive("确认订单"))

        // Keywords do not identify every secret. A password with no keyword still
        // needs tree password/sensitive bounds (see ScreenOcrFallbackTest).
        assertFalse(SensitiveScreenTextClassifier.isSensitive("hunter2"))
        assertTrue(SensitiveScreenTextClassifier.isStandaloneNumericSecret("8492"))
        assertTrue(SensitiveScreenTextClassifier.isStandaloneNumericSecret("38491"))
        assertEquals("******", SensitiveScreenTextClassifier.redactForDisplay("8492"))
        assertEquals("******", SensitiveScreenTextClassifier.redactForDisplay("38491"))
        assertEquals(
            SensitiveScreenTextClassifier.MASKED_SENSITIVE_TEXT,
            SensitiveScreenTextClassifier.redactForDisplay("hunter2", maskEntirely = true),
        )
        assertEquals("hunter2", SensitiveScreenTextClassifier.redactForDisplay("hunter2"))
    }
}
