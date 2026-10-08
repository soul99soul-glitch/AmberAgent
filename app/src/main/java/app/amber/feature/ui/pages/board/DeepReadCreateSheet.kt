package app.amber.feature.ui.pages.board

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.DeepReadSeedInput
import app.amber.feature.board.hotlist.MAX_CUSTOM_SEED_SOURCES
import app.amber.feature.board.hotlist.MIN_CUSTOM_SEED_TEXT_CHARS
import app.amber.feature.board.hotlist.deepread.DeepReadSourceImporter
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.feature.ui.components.ui.FlatTextField
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import java.net.URI

private enum class DeepReadSeedMode { TEXT, LINK, FILE }

/**
 * Free-form deep-read creation sheet (iOS DeepRead "Create" parity): a topic
 * title plus up to [MAX_CUSTOM_SEED_SOURCES] user-provided seeds — pasted text,
 * web links, or imported files — persisted into the hot-topic cache so the
 * regular deep-read pipeline picks them up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeepReadCreateSheet(
    onDismiss: () -> Unit,
    onStart: (title: String, seeds: List<DeepReadSeedInput>, templateId: String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore: SettingsAggregator = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val templateRepository: DeepReadTemplateRepository = koinInject()
    val customTemplates by templateRepository.observeTemplates().collectAsStateWithLifecycle()

    val textSeedLabel = stringResource(R.string.deep_read_create_add_text)
    val linkSeedLabel = stringResource(R.string.deep_read_create_add_link)
    val fileSeedLabel = stringResource(R.string.deep_read_create_add_file)
    val truncatedLabel = stringResource(R.string.deep_read_create_truncated)
    val importFailedLabel = stringResource(R.string.deep_read_create_import_failed)

    var title by remember { mutableStateOf("") }
    var seeds by remember { mutableStateOf(listOf<DeepReadSeedInput>()) }
    var seedMode by remember { mutableStateOf<DeepReadSeedMode?>(null) }
    var inputValue by remember { mutableStateOf("") }
    var importingFiles by remember { mutableIntStateOf(0) }
    // Per-task override (iOS selectedTemplateId): follows the board default until
    // the user picks something else in this sheet; not written back to settings.
    var templateOverride by remember { mutableStateOf<String?>(null) }
    var templateMenuOpen by remember { mutableStateOf(false) }
    val templateId = templateOverride
        ?: DeepReadTemplateIds.normalize(settings.agentRuntime.todayBoard.deepReadTemplateId)

    val seedsFull = seeds.size >= MAX_CUSTOM_SEED_SOURCES

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        uris.take(MAX_CUSTOM_SEED_SOURCES).forEach { uri ->
            importingFiles += 1
            scope.launch {
                when (val result = DeepReadSourceImporter.extract(context, uri)) {
                    is DeepReadSourceImporter.Result.Success -> {
                        val imported = result.source
                        val label = if (imported.truncated) {
                            "${imported.fileName} · $truncatedLabel"
                        } else {
                            imported.fileName
                        }
                        seeds = seeds + DeepReadSeedInput(
                            title = label,
                            providerName = fileSeedLabel,
                            content = imported.text,
                        )
                    }
                    is DeepReadSourceImporter.Result.Failure ->
                        Toast.makeText(context, importFailedLabel, Toast.LENGTH_SHORT).show()
                }
                importingFiles -= 1
            }
        }
    }

    fun addSeed() {
        when (seedMode) {
            DeepReadSeedMode.TEXT -> {
                val body = inputValue.trim()
                if (body.length < MIN_CUSTOM_SEED_TEXT_CHARS) return
                val preview = body.lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(40)
                    ?: textSeedLabel
                seeds = seeds + DeepReadSeedInput(
                    title = preview,
                    providerName = textSeedLabel,
                    content = body,
                )
            }
            DeepReadSeedMode.LINK -> {
                val url = inputValue.trim()
                if (!url.isHttpOrHttpsUrl()) return
                seeds = seeds + DeepReadSeedInput(
                    title = url,
                    providerName = linkSeedLabel,
                    url = url,
                )
            }
            else -> return
        }
        inputValue = ""
        seedMode = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = tokens.raised,
        contentColor = tokens.ink,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.line2),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.deep_read_create_title),
                style = LocalAmberType.current.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontWeight = FontWeight.SemiBold,
                ),
            )

            FlatTextField(
                value = title,
                onValueChange = { title = it },
                label = stringResource(R.string.deep_read_create_topic_label),
                singleLine = true,
                // iOS Composer sets the topic field in serif title3.
                fontFamily = deepReadEditorialSerif,
            )

            Text(
                stringResource(R.string.deep_read_create_sources_label, MAX_CUSTOM_SEED_SOURCES),
                style = LocalAmberType.current.meta,
                color = workspaceColors().muted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeedModeChip(
                    label = textSeedLabel,
                    icon = Lucide.FileText,
                    selected = seedMode == DeepReadSeedMode.TEXT,
                    enabled = !seedsFull,
                ) {
                    seedMode = if (seedMode == DeepReadSeedMode.TEXT) null else DeepReadSeedMode.TEXT
                    inputValue = ""
                }
                SeedModeChip(
                    label = linkSeedLabel,
                    icon = Lucide.Link2,
                    selected = seedMode == DeepReadSeedMode.LINK,
                    enabled = !seedsFull,
                ) {
                    seedMode = if (seedMode == DeepReadSeedMode.LINK) null else DeepReadSeedMode.LINK
                    inputValue = ""
                }
                SeedModeChip(
                    label = fileSeedLabel,
                    icon = Lucide.Paperclip,
                    selected = importingFiles > 0,
                    enabled = !seedsFull && importingFiles == 0,
                ) {
                    filePicker.launch(arrayOf("*/*"))
                }
            }

            when (seedMode) {
                DeepReadSeedMode.TEXT -> {
                    FlatTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = textSeedLabel,
                        singleLine = false,
                        minLines = 4,
                        supportingText = stringResource(
                            R.string.deep_read_create_text_hint,
                            MIN_CUSTOM_SEED_TEXT_CHARS,
                        ) + " · " + stringResource(
                            R.string.deep_read_create_chars,
                            inputValue.trim().length,
                        ),
                    )
                    AddSeedButton(
                        enabled = inputValue.trim().length >= MIN_CUSTOM_SEED_TEXT_CHARS,
                        onClick = ::addSeed,
                    )
                }
                DeepReadSeedMode.LINK -> {
                    FlatTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = stringResource(R.string.deep_read_create_link_label),
                        singleLine = true,
                    )
                    AddSeedButton(
                        enabled = inputValue.trim().isHttpOrHttpsUrl(),
                        onClick = ::addSeed,
                    )
                }
                else -> Unit
            }

            seeds.forEachIndexed { index, seed ->
                SeedRow(
                    seed = seed,
                    onRemove = { seeds = seeds.toMutableList().also { it.removeAt(index) } },
                )
            }

            // Generation template (iOS BoardView "生成模板" menu picker).
            val templateDisplayName = DeepReadTemplateCatalog.name(
                templateId,
                customTemplates.firstOrNull { it.id == templateId }?.name,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, RoundedCornerShape(10.dp))
                    .clickable { templateMenuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        DeepReadTemplateCatalog.icon(templateId),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = tokens.accent,
                    )
                    Text(
                        stringResource(R.string.deep_read_template_label),
                        style = LocalAmberType.current.meta,
                        color = tokens.ink3,
                    )
                    Text(
                        templateDisplayName,
                        modifier = Modifier.weight(1f),
                        style = LocalAmberType.current.body,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                    Icon(
                        Lucide.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = tokens.ink3,
                    )
                }
                DropdownMenu(
                    expanded = templateMenuOpen,
                    onDismissRequest = { templateMenuOpen = false },
                ) {
                    DeepReadTemplateCatalog.options.forEach { optionId ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        DeepReadTemplateCatalog.icon(optionId),
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = tokens.ink3,
                                    )
                                    Text(
                                        DeepReadTemplateCatalog.name(optionId),
                                        style = LocalAmberType.current.body,
                                    )
                                }
                            },
                            trailingIcon = if (optionId == templateId) {
                                {
                                    Icon(
                                        Lucide.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                templateOverride = optionId
                                templateMenuOpen = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            val startEnabled = title.isNotBlank() && importingFiles == 0
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (startEnabled) tokens.accent else tokens.surface2
                    )
                    .clickable(enabled = startEnabled) {
                        onStart(title.trim(), seeds, templateId)
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (startEnabled) tokens.accentInk else tokens.ink3,
                    )
                    Text(
                        stringResource(R.string.deep_read_create_start),
                        style = LocalAmberType.current.body,
                        color = if (startEnabled) tokens.accentInk else tokens.ink3,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SeedModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val contentColor = when {
        !enabled -> tokens.ink3
        selected -> tokens.accent
        else -> tokens.ink
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.accent.copy(alpha = 0.12f) else tokens.surface2)
            .border(
                1.dp,
                if (selected) tokens.accent.copy(alpha = 0.35f) else tokens.line,
                RoundedCornerShape(10.dp),
            )
            .heightIn(min = 40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = contentColor)
        Text(label, style = LocalAmberType.current.meta, color = contentColor)
    }
}

@Composable
private fun AddSeedButton(enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) tokens.surface2 else tokens.surface)
            .border(1.dp, if (enabled) tokens.line else tokens.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.deep_read_create_add),
            style = LocalAmberType.current.body,
            color = if (enabled) tokens.ink else tokens.ink3,
        )
    }
}

@Composable
private fun SeedRow(seed: DeepReadSeedInput, onRemove: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (seed.url != null) Lucide.Link2 else Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = tokens.ink3,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    seed.title,
                    style = LocalAmberType.current.body,
                    maxLines = 1,
                )
                Text(
                    seed.providerName,
                    style = LocalAmberType.current.meta,
                    color = tokens.ink3,
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.delete),
                    modifier = Modifier.size(16.dp),
                    tint = tokens.ink3,
                )
            }
        }
    }
}

private fun String.isHttpOrHttpsUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
}
