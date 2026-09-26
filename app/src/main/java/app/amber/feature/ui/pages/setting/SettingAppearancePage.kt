package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.settings.DisplaySetting
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.Switch as WorkspaceSwitch
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.rememberAmoledDarkMode
import app.amber.feature.ui.hooks.rememberColorMode
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.ColorMode
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.core.utils.plus
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAppearancePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var colorMode by rememberColorMode()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val themePackageManager: ThemePackageManager = koinInject()

    fun updateDisplaySetting(transform: (DisplaySetting) -> DisplaySetting) {
        val display = vm.settings.value.displaySetting
        val updated = transform(display)
        val themeValueChanged = updated.amberBaseFamily != display.amberBaseFamily ||
            !updated.accentColor.equals(display.accentColor, ignoreCase = true)
        if (!themeValueChanged) return

        themePackageManager.tryOn.value?.let { candidate ->
            themePackageManager.discardTryOn(candidate.pkg.id, candidate.candidateDigest)
        }

        vm.updateSettings { current ->
            val currentDisplay = current.displaySetting
            val currentUpdated = transform(currentDisplay)
            val changed = currentUpdated.amberBaseFamily != currentDisplay.amberBaseFamily ||
                !currentUpdated.accentColor.equals(currentDisplay.accentColor, ignoreCase = true)
            if (!changed) {
                current
            } else {
                current.copy(
                    displaySetting = currentUpdated.copy(
                        appliedThemePackageId = null,
                        themePack = null,
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_page_appearance),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = Color.Transparent,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(
                horizontal = SettingPageHorizontalInset,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("appearanceControls") {
                SettingCardGroup(title = stringResource(R.string.setting_page_theme_setting)) {
                    item(
                        modifier = Modifier.settingSingleLine(),
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        trailingContent = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = { colorMode = it },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                            )
                        },
                    )
                    item(
                        modifier = Modifier.settingSingleLine(),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_base_family_title)) },
                        trailingContent = {
                            ThemeFamilyChoice(
                                selected = settings.displaySetting.amberBaseFamily,
                                onSelected = { family ->
                                    updateDisplaySetting { it.copy(amberBaseFamily = family) }
                                },
                            )
                        },
                    )
                    item(
                        modifier = Modifier.settingSingleLine(),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_accent_color_title)) },
                        trailingContent = {
                            AccentColorChoice(
                                selected = settings.displaySetting.accentColor,
                                onSelected = { accent ->
                                    updateDisplaySetting { it.copy(accentColor = accent) }
                                },
                            )
                        },
                    )
                    item(
                        modifier = Modifier.settingSingleLine(),
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            WorkspaceSwitch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it },
                            )
                        },
                    )
                }
            }
            item("themeLibrary") {
                ThemeLibrarySection(
                    displaySetting = settings.displaySetting,
                )
            }
        }
    }
}

@Composable
private fun AccentColorChoice(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val colors = workspaceColors()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmberAccents.forEach { accent ->
            val hex = "#%06X".format(accent.hex.toArgb() and 0xFFFFFF)
            val isSelected = selected.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onSelected(hex) }
                    .semantics { contentDescription = accent.label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accent.hex)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, colors.ink, CircleShape)
                            } else {
                                Modifier.border(1.dp, colors.hairline, CircleShape)
                            },
                        ),
                )
            }
        }
    }
}
