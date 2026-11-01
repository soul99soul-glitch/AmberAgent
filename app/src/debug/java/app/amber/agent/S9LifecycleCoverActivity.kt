package app.amber.agent

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/** Debug-only overlay so S9 video tests can drive a real host ON_STOP without destroying the fixture. */
class S9LifecycleCoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        instance = this
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.DKGRAY)
            },
        )
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: S9LifecycleCoverActivity? = null
            private set
    }
}
