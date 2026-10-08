package app.amber.feature.ui.pages.novel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.novelworkspace.NovelWorkspaceCatalog
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Users
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class CatalogEditorTarget(
    val path: String,
    val title: String,
    val renameDelete: Boolean = false,
    val plot: Boolean = false,
)

internal enum class CatalogCategory(val label: Int, val initialMaterialKind: String) {
    Characters(R.string.novel_catalog_characters, "character"),
    World(R.string.novel_material_world, "world"),
    Plot(R.string.novel_catalog_plot, "masterOutline"),
    More(R.string.novel_catalog_more, "custom"),
}

private data class CatalogRow(
    val target: CatalogEditorTarget,
    val icon: ImageVector,
    val kindLabel: Int? = null,
    val updatedAt: Instant? = null,
    val showUpdate: Boolean = false,
)

private data class CatalogSection(
    val title: Int,
    val rows: List<CatalogRow>,
    val count: Int? = null,
)

/** The directory shows author-facing categories; file paths remain available in the full text editor. */
@Composable
internal fun MarkdownWorkspaceCatalog(
    viewModel: NovelMarkdownWorkspaceViewModel,
    state: NovelMarkdownWorkspaceUiState,
    category: CatalogCategory,
    onSelectCategory: (CatalogCategory) -> Unit,
    listState: LazyListState,
) {
    var create by remember(state.branchSlug) { mutableStateOf(false) }
    var editing by remember(state.branchSlug) { mutableStateOf<CatalogEditorTarget?>(null) }
    val locked = state.ghostwriteJob?.status in setOf("running", "paused", "failed")
    if (create) MarkdownMaterialCreateSheet(
        busy = state.busy, writeLocked = locked, errorMessage = state.errorMessage,
        initialKind = category.initialMaterialKind,
        onCreate = { kind, title, body, done ->
            viewModel.createMaterial(kind, title, body) {
                onSelectCategory(when (kind) {
                    "character" -> CatalogCategory.Characters
                    "world" -> CatalogCategory.World
                    "masterOutline" -> CatalogCategory.Plot
                    else -> CatalogCategory.More
                })
                done()
            }
        },
        onDismiss = { create = false },
    )
    editing?.let { target ->
        MarkdownMaterialEditSheet(
            path = target.path, displayTitle = target.title, busy = state.busy,
            writeLocked = locked, errorMessage = state.errorMessage,
            allowRenameDelete = target.renameDelete, plot = target.plot, readRaw = viewModel::readFileRaw,
            onSave = { title, body, raw, done ->
                if (target.renameDelete) viewModel.saveMaterial(target.path, title, body, raw, done)
                else viewModel.saveOpenedFileEdit(target.path, body, raw, done)
            },
            onDelete = { raw, done -> viewModel.deleteMaterial(target.path, raw, done) },
            onDismiss = { editing = null },
        )
    }
    fun open(target: CatalogEditorTarget) { viewModel.clearError(); editing = target }
    val currentPlotTitle = stringResource(R.string.novel_catalog_current_plot)
    val sections = remember(state.catalog, state.branchSlug, category, currentPlotTitle) {
        catalogSections(state.catalog, state.branchSlug, category, currentPlotTitle)
    }
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val categoryReveal = remember { Animatable(1f) }
    var revealedCategory by remember { mutableStateOf(category) }
    val categoryShift = with(LocalDensity.current) { 4.dp.toPx() }
    LaunchedEffect(category) {
        if (revealedCategory != category) {
            revealedCategory = category
            categoryReveal.snapTo(0.35f)
            categoryReveal.animateTo(1f, tween(200))
        }
    }
    Column(Modifier.fillMaxSize().background(workspace.canvas)) {
        CatalogCategoryTabs(category, onSelect = onSelectCategory)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).navigationBarsPadding().graphicsLayer {
                alpha = categoryReveal.value
                translationY = categoryShift * (1f - categoryReveal.value)
            },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        ) {
            item(key = "catalog-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(category.label), style = type.sessionTitle, color = workspace.muted,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val canCreate = !state.busy && !state.loading && !locked
                    Row(
                        Modifier.heightIn(min = 48.dp)
                            .novelPressable(onClick = { viewModel.clearError(); create = true }, enabled = canCreate)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val actionColor = if (canCreate) LocalAmberTokens.current.accent else workspace.faint
                        Icon(Lucide.Plus, contentDescription = null, tint = actionColor, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.novel_add_material), style = type.body, color = actionColor,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            if (sections.isEmpty()) {
                item(key = "catalog-empty") {
                    Text(
                        stringResource(R.string.novel_catalog_empty_category, stringResource(category.label)),
                        style = type.secondary, color = workspace.muted,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp),
                    )
                }
            }
            sections.forEachIndexed { sectionIndex, section ->
                if (section.title != R.string.novel_catalog_current_plot &&
                    (sections.size > 1 || section.title != category.label)) {
                    item(key = "catalog-section-$sectionIndex") {
                        val label = if (section.count != null) stringResource(section.title, section.count)
                            else stringResource(section.title)
                        Text(label, style = type.secondary, color = workspace.muted,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 10.dp))
                    }
                }
                section.rows.forEachIndexed { rowIndex, entry ->
                    item(key = entry.target.path) {
                        CatalogEntryRow(
                            entry = entry,
                            first = rowIndex == 0,
                            last = rowIndex == section.rows.lastIndex,
                            onOpen = { open(entry.target) },
                        )
                    }
                }
            }
        }
    }
}

private fun catalogSections(
    catalog: NovelWorkspaceCatalog.NovelWorkspaceCatalogData?,
    branchSlug: String?,
    category: CatalogCategory,
    currentPlotTitle: String,
): List<CatalogSection> {
    val groups = catalog?.settingGroups.orEmpty()
    fun materialRows(directories: Set<String>): List<CatalogRow> = groups
        .filter { it.directory in directories }
        .flatMap { group -> group.entries.map { entry -> materialRow(entry, group.directory) } }
    return when (category) {
        CatalogCategory.Characters -> materialRows(setOf("characters"))
            .takeIf { it.isNotEmpty() }?.let { listOf(CatalogSection(category.label, it)) }.orEmpty()
        CatalogCategory.World -> materialRows(setOf("world"))
            .takeIf { it.isNotEmpty() }?.let { listOf(CatalogSection(category.label, it)) }.orEmpty()
        CatalogCategory.Plot -> buildList {
            if (branchSlug != null) add(CatalogSection(R.string.novel_catalog_current_plot, listOf(CatalogRow(
                CatalogEditorTarget("branches/$branchSlug/plot/current.md", currentPlotTitle, plot = true),
                Lucide.BookOpenText, kindLabel = R.string.novel_edit_current_plot,
            ))))
            val foreshadowing = catalog?.foreshadowing.orEmpty()
            val foreshadowingPaths = foreshadowing.mapTo(mutableSetOf()) { it.path }
            val outline = materialRows(setOf("outline")).filter { it.target.path !in foreshadowingPaths }
            if (outline.isNotEmpty()) add(CatalogSection(R.string.novel_material_outline, outline))
            listOf(false, true).forEach { resolved ->
                val rows = foreshadowing.filter { it.resolved == resolved }.map { entry ->
                    CatalogRow(CatalogEditorTarget(entry.path, entry.title),
                        if (resolved) Lucide.CircleCheck else Lucide.Lightbulb,
                        kindLabel = R.string.novel_foreshadowing)
                }
                if (rows.isNotEmpty()) add(CatalogSection(
                    if (resolved) R.string.novel_resolved_foreshadowing else R.string.novel_open_foreshadowing,
                    rows, count = rows.size,
                ))
            }
        }
        CatalogCategory.More -> {
            val foreshadowingPaths = catalog?.foreshadowing.orEmpty().mapTo(mutableSetOf()) { it.path }
            val materials = groups.filter { it.directory !in setOf("characters", "world", "outline") }
                .flatMap { group -> group.entries.filter { it.path !in foreshadowingPaths }
                    .map { entry -> materialRow(entry, group.directory) } }
            val materialPaths = materials.mapTo(mutableSetOf()) { it.target.path }
            val decisions = catalog?.decisions.orEmpty().filter { it.path !in materialPaths }.map { entry ->
                CatalogRow(CatalogEditorTarget(entry.path, entry.title, renameDelete = true),
                    Lucide.CircleCheck, kindLabel = R.string.novel_decision_records)
            }
            (materials + decisions).takeIf { it.isNotEmpty() }
                ?.let { listOf(CatalogSection(category.label, it)) }.orEmpty()
        }
    }
}

private fun materialRow(entry: NovelWorkspaceCatalog.NovelWorkspaceSettingEntry, directory: String): CatalogRow {
    val (icon, label) = when (directory) {
        "characters" -> Lucide.Users to null
        "world" -> Lucide.Globe to null
        "outline" -> Lucide.BookOpenText to null
        "relationships" -> Lucide.Users to R.string.novel_material_relationship
        "writing" -> Lucide.NotebookPen to R.string.novel_material_writing
        "decisions" -> Lucide.CircleCheck to R.string.novel_decision_records
        else -> Lucide.FileText to R.string.novel_material_custom
    }
    return CatalogRow(CatalogEditorTarget(entry.path, entry.title, renameDelete = true),
        icon, label, entry.updatedAt, showUpdate = true)
}

@Composable
private fun CatalogCategoryTabs(selected: CatalogCategory, onSelect: (CatalogCategory) -> Unit) {
    NovelWorkspaceOptions(
        labels = CatalogCategory.entries.map { stringResource(it.label) },
        selected = selected.ordinal,
        onSelect = { onSelect(CatalogCategory.entries[it]) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun CatalogEntryRow(entry: CatalogRow, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(
        topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(workspace.paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .novelPressable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(workspace.row),
                contentAlignment = Alignment.Center) {
                Icon(entry.icon, contentDescription = null, tint = LocalAmberTokens.current.accent,
                    modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.target.title, style = type.sessionTitle, color = workspace.ink,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val kind = entry.kindLabel?.let { stringResource(it) }
                val updated = if (entry.showUpdate) entry.updatedAt?.let {
                    stringResource(R.string.novel_project_updated_at,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(it))
                } ?: stringResource(R.string.novel_uncommitted) else null
                val subtitle = listOfNotNull(kind, updated).joinToString(" · ")
                if (subtitle.isNotEmpty()) Text(subtitle, style = type.secondary, color = workspace.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = workspace.faint,
                modifier = Modifier.size(18.dp))
        }
        if (!last) HorizontalDivider(color = workspace.hairline,
            modifier = Modifier.padding(start = 68.dp, end = 16.dp))
    }
}
