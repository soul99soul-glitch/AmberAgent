package app.amber.core.automation.display

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Non-exported virtual-display target Activity for the dual-display isolation self-test (R3 L12).
 *
 * Renders a per-run random solid color marker band for frame verification, a tap surface counting
 * injected touches on its display, and an editable Chinese-capable [EditText] for `ACTION_SET_TEXT`
 * readback verification. Immediately reports leakage if it ever appears on [Display.DEFAULT_DISPLAY].
 */
class IsolationProbeTargetActivity : ComponentActivity() {

    private lateinit var tapCounterView: TextView
    private lateinit var editableField: EditText
    private var localTapCount = 0
    private var probeToken: String = ""
    private var resumedDisplayId: Int = Display.INVALID_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        probeToken = intent.getStringExtra(IsolationProbeRunner.EXTRA_PROBE_TOKEN) ?: IsolationProbeChannel.currentToken()
        val currentDisplayId = currentDisplayId()
        if (currentDisplayId == Display.DEFAULT_DISPLAY) {
            IsolationProbeChannel.recordLeakedToDefaultDisplay(probeToken)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // Solid per-run marker band for VirtualDisplayFrameSource pixel verification.
        val markerBand = View(this).apply {
            setBackgroundColor(IsolationProbeChannel.currentMarkerRgb())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.28f,
            )
        }

        // Dedicated tap target covering the vertical midpoint where taps are injected.
        tapCounterView = TextView(this).apply {
            text = "Virtual Target display=$currentDisplayId taps=0"
            textSize = 18f
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.44f,
            )
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    localTapCount++
                    val tapDisplayId = currentDisplayId()
                    text = "Virtual Target display=$tapDisplayId taps=$localTapCount"
                    IsolationProbeChannel.recordTargetTap(probeToken, tapDisplayId)
                }
                true
            }
        }

        editableField = EditText(this).apply {
            hint = "Virtual display ACTION_SET_TEXT target"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.28f,
            )
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        IsolationProbeChannel.recordTargetText(probeToken, s?.toString().orEmpty())
                    }
                },
            )
        }

        root.addView(markerBand)
        root.addView(tapCounterView)
        root.addView(editableField)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumedDisplayId = currentDisplayId()
        IsolationProbeChannel.recordTargetResumed(probeToken, resumedDisplayId)
        editableField.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resumedDisplayId = currentDisplayId()
        IsolationProbeChannel.recordTargetResumed(probeToken, resumedDisplayId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (currentDisplayId() == Display.DEFAULT_DISPLAY) {
            IsolationProbeChannel.recordLeakedToDefaultDisplay(probeToken)
        }
    }

    override fun onDestroy() {
        IsolationProbeChannel.recordTargetDestroyed(probeToken, resumedDisplayId)
        super.onDestroy()
    }

    private fun currentDisplayId(): Int = runCatching {
        display?.displayId ?: Display.DEFAULT_DISPLAY
    }.getOrDefault(Display.DEFAULT_DISPLAY)
}
