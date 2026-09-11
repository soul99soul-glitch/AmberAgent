package app.amber.feature.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import app.amber.agent.R
import app.amber.core.utils.base64Encode
import app.amber.core.utils.appLocale
import app.amber.core.utils.toCssHex
import org.json.JSONObject

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
    val modulesUnsupported = context
        .getString(R.string.html_asset_markdown_modules_unsupported)
        .toScriptSafeJsonLiteral()
    val languageTag = htmlEscape(context.appLocale().toLanguageTag())
    val title = htmlEscape(context.getString(R.string.html_asset_markdown_title))

    return htmlTemplate
        .replace("{{AMBER_HTML_MARKDOWN_LANG}}", languageTag)
        .replace("{{AMBER_HTML_MARKDOWN_TITLE}}", title)
        .replace("{{AMBER_HTML_MARKDOWN_MODULES_UNSUPPORTED_JS}}", modulesUnsupported)
        .replace("{{MARKDOWN_BASE64}}", markdown.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

private fun htmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun String.toScriptSafeJsonLiteral(): String =
    JSONObject.quote(this)
        .replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
