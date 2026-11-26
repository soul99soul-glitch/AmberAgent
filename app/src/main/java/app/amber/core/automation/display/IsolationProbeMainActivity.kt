package app.amber.core.automation.display

import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Non-exported Display 0 host Activity for the dual-display isolation self-test (R3 L12).
 *
 * Keeps focus on a live Chinese EditText on Display 0 and counts any leaked MotionEvents or
 * focus disturbances while [IsolationProbeRunner] drives [IsolationProbeTargetActivity] on the
 * virtual display.
 */
class IsolationProbeMainActivity : ComponentActivity() {
    private val probeRunner: IsolationProbeRunner by inject()
    private val probeStore: VirtualDisplayProbeStore by inject()

    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var chineseFocusInput: EditText
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleView = TextView(this).apply {
            text = "Virtual Display Isolation Probe (Display 0)"
            textSize = 18f
        }
        statusView = TextView(this).apply {
            text = "Compatibility: ${probeStore.compatibility()}"
            setPadding(0, padding / 2, 0, padding / 2)
        }
        chineseFocusInput = EditText(this).apply {
            hint = "请保持主屏中文输入焦点（自测期间请勿离开本页）"
            setText("主屏中文焦点监测")
            setSelection(text.length)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    IsolationProbeChannel.recordMainFocusLost()
                }
            }
        }
        startButton = Button(this).apply {
            text = "开始双屏隔离自测"
            setOnClickListener { runProbe() }
        }
        resultView = TextView(this).apply {
            text = probeStore.lastResult()?.toString() ?: "尚未运行自测"
            setPadding(0, padding, 0, padding)
        }

        container.addView(titleView)
        container.addView(statusView)
        container.addView(chineseFocusInput)
        container.addView(startButton)
        container.addView(resultView)

        val scrollView = ScrollView(this).apply {
            addView(container)
        }
        setContentView(scrollView)
        chineseFocusInput.requestFocus()

        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            window.decorView.postDelayed({ runProbe() }, 300L)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            IsolationProbeChannel.recordMainScreenInjectedTouch()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            IsolationProbeChannel.recordMainFocusLost()
        }
    }

    private fun runProbe() {
        startButton.isEnabled = false
        chineseFocusInput.requestFocus()
        lifecycleScope.launch {
            val result = probeRunner.run(
                hostActivity = this@IsolationProbeMainActivity,
                onProgress = { msg -> runOnUiThread { statusView.text = msg } },
            )
            statusView.text = "Done • compatibility=${probeStore.compatibility()}"
            resultView.text = result.toString()
            startButton.isEnabled = true
        }
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }
}
