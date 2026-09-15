package app.amber.feature.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import app.amber.core.settings.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}

/**
 * 聊天正文字号缩放系数（设置页「字体大小」滑杆值，0.5..2.0）。由聊天消息渲染链路
 * provide，供气泡内不继承 LocalTextStyle 的绝对字号（Markdown 标题、代码块、思考链）
 * 等比缩放；默认 1f，非聊天表面保持各自平台的默认字号。
 */
val LocalChatFontScale = staticCompositionLocalOf { 1f }
