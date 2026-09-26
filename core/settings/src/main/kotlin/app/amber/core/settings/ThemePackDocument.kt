package app.amber.core.settings

import kotlinx.serialization.Serializable

/** Portable amber.theme.pack v1 recipe, shared on the wire with iOS. */
@Serializable
data class ThemePackDocument(
    val format: String = "amber.theme.pack",
    val version: Int = 1,
    val id: String,
    val displayName: String,
    val paper: String,
    val accentHex: String,
    val inkHex: String,
    val canvasStyle: String,
    val brandMark: String,
    val shortcutIconStyle: String,
    val chromeTypeface: String,
    val canvasScope: String? = null,
    val bubbleChrome: String? = null,
    val glassChrome: String? = null,
    val emptyArt: String? = null,
    val settingsChrome: Boolean? = null,
    val launchBrand: String? = null,
    val assetMode: String? = null,
    val immersivePolicy: String? = null,
    val design: ThemeDesign? = null,
)
