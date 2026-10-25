package app.amber.agent

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only native UI fixture in the target package. Isolated from
 * external-account apps; launched by S9 device tests via startActivitySync.
 */
class JevScreenFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPage(0)
    }

    private fun showPage(page: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(TextView(context).apply {
                textSize = 24f
                text = when (page) {
                    0 -> "无障碍实测首页。请打开评论列表，再进入第二页。"
                    1 -> "评论列表第 1 页。评论甲：画质清晰。"
                    else -> "评论列表第 2 页。验证标记：琥珀验证完成。评论乙：声音清晰。"
                }
            })
            if (page < 2) addView(Button(context).apply {
                text = if (page == 0) "打开评论列表" else "下一页"
                setOnClickListener { showPage(page + 1) }
            })
            addView(Button(context).apply { text = "删除全部评论" })
        }
        setContentView(layout)
    }
}
