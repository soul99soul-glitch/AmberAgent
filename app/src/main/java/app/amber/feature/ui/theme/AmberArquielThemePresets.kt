package app.amber.feature.ui.theme

import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.ThemeDesign.Palette
import app.amber.core.settings.ThemePackDocument

/** Same ordered v1 recipes as AmberArquielThemePresets in AmberThemeDesign.swift. */
internal object AmberArquielThemePresets {
    private val linearPalette = Palette("#0C0D10", "#15171C", "#F2F3F5", "#8B91A1", "#262932")

    val all = listOf(
        preset(
            "arquiel-warm-editorial", "初版 · 暖纸赤陶", "paper", "#C96442",
            light = Palette("#FBF9F5", "#F3EFEA", "#1E1B18", "#787067", "#E6E0D6"),
            dark = Palette("#141210", "#211D19", "#F5F0E8", "#A89E92", "#38312A"),
        ),
        preset(
            "arquiel-things-polish", "Things 3 · 原生精修", "white", "#2B6CE6",
            light = Palette("#FFFFFF", "#F4F6F9", "#1D1D1F", "#5E6572", "#E3E7ED"),
            dark = Palette("#101216", "#1B1E24", "#F3F5F8", "#949BA8", "#2C313B"),
        ),
        preset(
            "arquiel-comet-indigo", "天文图录 · 琉璃绀", "pi", "#234B8C",
            light = Palette("#F7F4EC", "#EEE9DD", "#141B29", "#5A6373", "#DDD6C7"),
            dark = Palette("#0E131D", "#182030", "#F0EDE4", "#949FB2", "#2A354B"),
            brandMark = "serifWordmark",
        ),
        preset(
            "arquiel-takram-moss", "Takram · 松林苔绿", "neutral", "#1E6B4E",
            light = Palette("#F6F6F1", "#ECEEE6", "#17211B", "#55635B", "#D8DDD3"),
            dark = Palette("#0F1512", "#19221D", "#EEF2EE", "#92A198", "#2A362F"),
        ),
        preset(
            "arquiel-bookcraft-cinnabar", "吕敬人 · 故宫朱砂", "paper", "#AE3528",
            light = Palette("#F6F1E7", "#EDE6D8", "#1E1B18", "#635A50", "#DDD3C2"),
            dark = Palette("#151210", "#221D19", "#F4EFE6", "#A69B8E", "#3A312A"),
            brandMark = "serifWordmark",
            chromeTypeface = "serif",
        ),
        preset(
            "arquiel-linear-terminal", "Linear · 暗色发丝", "neutral", "#5E6AD2",
            light = linearPalette,
            dark = linearPalette,
        ),
    )

    private fun preset(
        id: String,
        name: String,
        paper: String,
        accent: String,
        light: Palette,
        dark: Palette,
        brandMark: String = "systemWordmark",
        chromeTypeface: String = "system",
    ) = ThemePackDocument(
        id = id,
        displayName = name,
        paper = paper,
        accentHex = accent,
        inkHex = "#FFFFFF",
        canvasStyle = "flat",
        brandMark = brandMark,
        shortcutIconStyle = "phosphorFill",
        chromeTypeface = chromeTypeface,
        design = ThemeDesign(light = light, dark = dark),
    )
}
