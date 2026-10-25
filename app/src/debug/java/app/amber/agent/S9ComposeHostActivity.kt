package app.amber.agent

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Debug-only Compose host in the target package so instrumentation
 * [android.app.Instrumentation.startActivitySync] can resume it on the
 * interactive display. Used by S9 Conversation / Video device tests.
 */
class S9ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
            },
        )
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: S9ComposeHostActivity? = null
            private set

        fun clearInstance() {
            instance = null
        }
    }
}
