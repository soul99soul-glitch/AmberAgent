package app.amber.feature.miniapp

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.VibratorManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.core.utils.ImageUtils
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiniAppAndroidDeviceCapabilitiesTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @org.junit.Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @org.junit.After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class TestActivity : Activity()

    @Test
    fun `waveform capabilities dispatch without invalid timing arrays`() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        shadowOf(vibrator).setHasVibrator(true)
        val owner = owner(context, activity)

        runBlocking {
            listOf(
                buildJsonObject { put("style", "soft") },
                buildJsonObject { put("style", "rigid") },
            ).forEach { owner.dispatch("haptics.impact", it) }
            listOf("success", "warning", "error").forEach { type ->
                owner.dispatch("haptics.notification", buildJsonObject { put("type", type) })
            }
        }

        assertTrue(shadowOf(vibrator).isVibrating())
    }

    @Test
    fun `screen brightness is returned as the contract scalar`() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val result = runBlocking { owner(context, activity).dispatch("screen.getBrightness", JsonObject(emptyMap())) }

        assertTrue(result is JsonPrimitive)
        assertTrue(result.jsonPrimitive.float in 0f..1f)
    }

    @Test
    fun `generated QR payload decodes through the existing ZXing reader`() {
        val context = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        val expected = "AmberAgent QR 验收"
        val result = runBlocking {
            owner(context, activity).dispatch(
                "qrcode.generate",
                buildJsonObject {
                    put("text", expected)
                    put("size", 256)
                },
            )
        }.jsonObject

        val dataUrl = result.getValue("dataURL").jsonPrimitive.content
        val bitmapBytes = Base64.getDecoder().decode(dataUrl.substringAfter(","))
        val bitmap = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.size)
            ?: error("generated QR data URL is not a bitmap")
        try {
            assertEquals(expected, ImageUtils.decodeQRCodeFromBitmap(bitmap))
            assertTrue(result.getValue("width").jsonPrimitive.int >= 128)
            assertTrue(result.getValue("height").jsonPrimitive.int >= 128)
        } finally {
            bitmap.recycle()
        }
    }

    private fun owner(context: Context, activity: Activity): MiniAppAndroidDeviceCapabilities =
        MiniAppAndroidDeviceCapabilities(
            context = context,
            activityProvider = { activity },
            speechEngine = MiniAppSpeechEngine(context),
            shareLauncher = {},
            openLauncher = { false },
        )
}
