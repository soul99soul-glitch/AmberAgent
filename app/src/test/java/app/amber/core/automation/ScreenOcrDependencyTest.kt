package app.amber.core.automation

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the bundled ML Kit text-recognition classes the OCR fallback resolves reflectively.
 *
 * `DefaultScreenOcrEngine` only adds screenshot OCR when `com.google.mlkit.vision.text.*`
 * exists on the runtime classpath; without the dependency the "fallback" silently degrades
 * to re-reading accessibility metadata, so low-coverage WebView/Canvas screens stay blank.
 * This guard fails the build instead of shipping that silent downgrade again.
 */
class ScreenOcrDependencyTest {

    @Test
    fun bundledTextRecognitionClassesAreOnTheRuntimeClasspath() {
        val classLoader = javaClass.classLoader
        listOf(
            "com.google.mlkit.vision.common.InputImage",
            "com.google.mlkit.vision.text.TextRecognition",
            "com.google.mlkit.vision.text.TextRecognizerOptionsInterface",
            "com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions\$Builder",
        ).forEach { className ->
            assertNotNull(
                "$className is missing; keep com.google.mlkit:text-recognition-chinese bundled " +
                    "or the on-device OCR fallback degrades to accessibility metadata only.",
                Class.forName(className, false, classLoader),
            )
        }
    }
}
