package app.amber.feature.ui.pages.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import com.composables.icons.lucide.EllipsisVertical
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.WandSparkles
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.RefreshCw
import app.amber.core.files.SkillFrontmatterParser
import app.amber.core.files.SkillMetadata
import app.amber.core.files.SkillScanIssue
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.ConfirmDialog
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val skillIssues by vm.skillIssues.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enabledSkillNames = settings.enabledSkills
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SkillMetadata?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.skills_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(Modifier.padding(bottom = 8.dp)) {
                    SkillLibraryStatusCard(
                        installedCount = skills.size,
                        enabledCount = skills.count { it.name in enabledSkillNames },
                        disabledCount = skills.count { it.name !in enabledSkillNames },
                        issueCount = skillIssues.size,
                        onAdd = { showAddDialog = true },
                        onImport = { showImportDialog = true },
                        onRefresh = { vm.loadSkills() },
                        onOptimizeAll = {
                            navigateToChatPage(
                                navigator = navController,
                                initText = buildOptimizeAllPrompt(skills.map { it.name }),
                            )
                        },
                    )
                }
            }

            items(skillIssues, key = { it.directoryName }) { issue ->
                Column(Modifier.padding(bottom = 8.dp)) {
                    SkillIssueCard(issue = issue)
                }
            }

            if (skills.isNotEmpty()) {
                item {
                    SectionLabel(
                        text = stringResource(R.string.skills_page_installed_count, skills.size),
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                }
            }

            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.Puzzle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.skills_page_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (skills.isNotEmpty()) {
                itemsIndexed(
                    items = skills,
                    key = { _, skill -> skill.name },
                ) { index, skill ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(LocalAmberTokens.current.surface)
                                    .padding(start = 68.dp, end = 16.dp)
                                    .heightIn(min = 0.5.dp)
                                    .background(LocalAmberTokens.current.line.copy(alpha = 0.46f)),
                            )
                        }
                        SkillCard(
                            skill = skill,
                            enabled = skill.name in enabledSkillNames,
                            groupedFirst = index == 0,
                            groupedLast = index == skills.lastIndex,
                            onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                            onOptimize = {
                                navigateToChatPage(
                                    navigator = navController,
                                    initText = buildOptimizeSkillPrompt(skill.name),
                                )
                            },
                            onToggle = {
                                vm.setSkillEnabled(skill.name, skill.name !in enabledSkillNames)
                            },
                            onDelete = { deleteTarget = skill },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
            onImportGitHub = {
                showAddDialog = false
                showImportDialog = true
            },
            onConfirm = { name, content ->
                vm.saveSkill(name, content) { success ->
                    showAddDialog = false
                    if (!success) {
                        toaster.show(context.getString(R.string.skills_page_save_failed))
                    }
                }
            },
        )
    }

    if (showImportDialog) {
        ImportSkillDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { repoUrl ->
                vm.importSkillFromGitHub(repoUrl) { success, message ->
                    showImportDialog = false
                    if (success) {
                        toaster.show(context.getString(R.string.skills_page_import_success, message))
                    } else {
                        toaster.show(context.getString(R.string.skills_page_import_failed, message))
                    }
                }
            },
        )
    }

    ConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.skills_page_delete_title),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.deleteSkill(it.name) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.skills_page_delete_message, deleteTarget?.name ?: ""))
    }
}

@Composable
internal fun SkillLibraryStatusCard(
    installedCount: Int,
    enabledCount: Int,
    disabledCount: Int,
    issueCount: Int,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onOptimizeAll: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.surface,
        borderColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkspaceLeadingIcon(
                    icon = Lucide.Puzzle,
                    tone = WorkspaceTone.Neutral,
                    size = 34.dp,
                    iconSize = 20.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = stringResource(R.string.skills_page_library_title),
                        style = type.sessionTitle,
                        color = tokens.ink,
                    )
                    Row(
                        modifier = Modifier
                            .testTag("skills-status-pills")
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // V3 settings-skills.jsx: 三 pill 中只有"已启用"用 accentSoft + accent，
                        // 不要绿色。设计稿原注释 "single accent color, no green"
                        WorkspaceStatusPill(
                            stringResource(R.string.skills_page_installed_count, installedCount)
                        )
                        WorkspaceStatusPill(
                            stringResource(R.string.skills_page_enabled_count, enabledCount),
                            tone = WorkspaceTone.Accent,
                        )
                        WorkspaceStatusPill(
                            stringResource(R.string.skills_page_disabled_count, disabledCount)
                        )
                    }
                }
            }
            if (issueCount > 0) {
                Text(
                    text = stringResource(R.string.skills_page_issue_count, issueCount),
                    style = type.secondary,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.skills_page_scan_hint),
                    style = type.secondary,
                    color = tokens.ink2,
                )
            }
            Hairline()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkillActionButton(
                    label = stringResource(R.string.skills_page_add_title),
                    onClick = onAdd,
                    accent = true,
                    modifier = Modifier.weight(1f),
                )
                SkillActionButton(
                    label = stringResource(R.string.skills_page_github_action_short),
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                )
                SkillActionButton(
                    label = stringResource(R.string.skills_page_refresh),
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                )
                if (installedCount > 0) {
                    SkillActionButton(
                        label = stringResource(R.string.skills_page_optimize_all),
                        onClick = onOptimizeAll,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillActionButton(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(CircleShape)
                .background(if (accent) tokens.accent.copy(alpha = 0.10f) else tokens.surface2)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = type.tinyTag,
                color = tokens.ink2,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SkillIssueCard(issue: SkillScanIssue) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = Lucide.TriangleAlert,
                tone = WorkspaceTone.Danger,
                size = 28.dp,
                iconSize = 16.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = issue.directoryName,
                    style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = tokens.ink,
                )
                Text(
                    text = issue.reason,
                    style = type.secondary,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    enabled: Boolean,
    groupedFirst: Boolean,
    groupedLast: Boolean,
    onClick: () -> Unit,
    onOptimize: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val hasMcp = skill.skillDir.resolve("mcp.json").exists()
    var showMenu by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(
        topStart = if (groupedFirst) 14.dp else 0.dp,
        topEnd = if (groupedFirst) 14.dp else 0.dp,
        bottomStart = if (groupedLast) 14.dp else 0.dp,
        bottomEnd = if (groupedLast) 14.dp else 0.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(rowShape)
            .background(tokens.surface, rowShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceLeadingIcon(
            icon = Lucide.Puzzle,
            tone = WorkspaceTone.Neutral,
            size = 40.dp,
            iconSize = 20.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = skill.name,
                style = type.body.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skill.description.ifBlank { stringResource(R.string.skills_page_no_description) },
                style = type.secondary,
                color = tokens.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!skill.compatibility.isNullOrBlank()) {
                Text(
                    text = skill.compatibility,
                    style = type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = tokens.ink3,
                )
            }
            if (!enabled || hasMcp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!enabled) {
                        WorkspaceStatusPill(
                            text = stringResource(R.string.skills_page_disabled),
                            tone = WorkspaceTone.Neutral,
                        )
                    }
                    if (hasMcp) {
                        WorkspaceStatusPill(text = "MCP", tone = WorkspaceTone.Accent)
                    }
                }
            }
        }
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { showMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.EllipsisVertical,
                    contentDescription = stringResource(R.string.skills_page_more_actions),
                    tint = tokens.ink3,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (enabled) R.string.skills_page_disable else R.string.skills_page_enable))
                    },
                    onClick = {
                        showMenu = false
                        onToggle()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.skills_page_optimize)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Lucide.WandSparkles,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = tokens.accent,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onOptimize()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Lucide.Trash2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun buildOptimizeSkillPrompt(skillName: String): String = """
请规整化 skill「$skillName」让它适配 AmberAgent 移动端运行环境。

工作流程（按顺序执行）：
1. 调用 use_skill('skill-creator')，学习 AmberAgent 官方 skill 范式（文件结构、frontmatter、不写 README/CHANGELOG 等约定）
2. 调用 use_skill('$skillName')，读取当前 SKILL.md 内容（系统会自动附加移动端运行时提示）
3. 列出问题清单：
   - 桌面端假设：CLI 命令（npx/pip/curl/cd）、键盘快捷键提示（"← → 翻页"/"F 全屏"/"S 演讲者模式"等）、浏览器/桌面 app 打开预览（open xxx / 在浏览器打开 / .pptx 文件）、生成 .pptx / .html / .pdf 文件作为最终交付
   - 不存在的子文件链接：references/、scripts/、assets/ 下的 markdown link，但设备上很可能只有 SKILL.md
   - frontmatter 缺失或不规范：name、description（必须是可读的触发条件 / "when to use" 描述，不能为空、不能是 |/｜/.../TODO 占位）
   - 冗余文件提示：README / CHANGELOG / INSTALLATION_GUIDE
4. 输出规整化后的完整 SKILL.md 内容，用 ```markdown 代码块包好；frontmatter 必须包含非占位的 description，格式类似 `description: "Use when ..."` 或 `description: "用于..."`
5. 不要调用任何写文件工具，只输出建议供我审阅、由我手动复制保存

每条问题都标记为「已修复」「保留原状」「无此问题」之一，方便我对照。
""".trimIndent()

private fun buildOptimizeAllPrompt(skillNames: List<String>): String {
    val list = skillNames.joinToString("\n") { "- $it" }
    return """
请逐个规整化下面这些已安装的 skill，让它们适配 AmberAgent 移动端运行环境：

$list

工作流程：
1. 先调用一次 use_skill('skill-creator')，学习 AmberAgent 官方 skill 范式
2. 对清单里每个 skill 依次处理：
   a. use_skill('<name>') 读取 SKILL.md
   b. 给出问题清单 + 规整化后的完整 SKILL.md（```markdown 代码块）；如果 description 缺失或是 |/｜/.../TODO，占位必须自动补成一句可读的触发条件描述
   c. 用「### Skill: <name>」作为该 skill 的分节标题
3. 不要调用任何写文件工具，只输出建议给我审阅
4. 如果某个 skill 已经 mobile-friendly 无需变更，直接写「无需变更」并简述理由

按清单顺序处理，处理完一个再下一个；如果回复太长可以分批，每批告诉我处理到哪个。
""".trimIndent()
}

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onImportGitHub: () -> Unit,
    onConfirm: (name: String, content: String) -> Unit,
) {
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(18.dp),
            color = tokens.raised,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line2),
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.skills_page_add_title),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    style = type.sessionTitle,
                    color = tokens.ink,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (manualMode) {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text(stringResource(R.string.skills_page_skill_content_label)) },
                            placeholder = {
                                Text(
                                    "---\nname: my-skill\ndescription: \"...\"\n---\n\n指令内容...",
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            supportingText = {
                                if (nameError) Text(
                                    stringResource(R.string.skills_page_name_error),
                                    color = MaterialTheme.colorScheme.error,
                                ) else if (name.isNotBlank()) {
                                    Text(stringResource(R.string.skills_page_skill_name, name))
                                } else {
                                    Text(stringResource(R.string.skills_page_paste_hint))
                                }
                            },
                            isError = nameError,
                            minLines = 8,
                            maxLines = 14,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = skillsFieldColors(),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.skills_page_paste_hint),
                            style = type.secondary,
                            color = tokens.ink2,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.surface, RoundedCornerShape(14.dp))
                                .border(1.dp, tokens.line, RoundedCornerShape(14.dp)),
                        ) {
                            SkillAddOptionRow(
                                icon = Lucide.Download,
                                title = stringResource(R.string.skills_page_import_from_github),
                                description = stringResource(R.string.skills_page_import_description),
                                onClick = onImportGitHub,
                            )
                            Hairline()
                            SkillAddOptionRow(
                                icon = Lucide.FileText,
                                title = stringResource(R.string.skills_page_add_title),
                                description = stringResource(R.string.skills_page_skill_content_label),
                                onClick = { manualMode = true },
                            )
                        }
                    }
                }
                Hairline()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { if (manualMode) manualMode = false else onDismiss() },
                    ) {
                        Text(if (manualMode) stringResource(R.string.back) else stringResource(R.string.cancel))
                    }
                    if (manualMode) {
                        Button(
                            onClick = { onConfirm(name, content) },
                            enabled = name.isNotBlank() && !nameError,
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.accent,
                                contentColor = tokens.accentInk,
                            ),
                        ) { Text(stringResource(R.string.skills_page_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillAddOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceLeadingIcon(
            icon = icon,
            tone = WorkspaceTone.Neutral,
            size = 32.dp,
            iconSize = 18.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = tokens.ink,
            )
            Text(
                description,
                style = type.secondary,
                color = tokens.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = tokens.ink3)
    }
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        shape = RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.skills_page_import_from_github), style = type.sessionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.skills_page_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.skills_page_repo_url_label)) },
                    placeholder = { Text("https://github.com/owner/repo", fontFamily = FontFamily.Monospace) },
                    supportingText = { Text(stringResource(R.string.skills_page_repo_url_hint)) },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = skillsFieldColors(),
                )
                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skills_page_downloading),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    loading = true
                    onConfirm(url)
                },
                enabled = url.isNotBlank() && !loading,
            ) {
                Text(stringResource(R.string.skills_page_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun skillsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = LocalAmberTokens.current.surface2,
    unfocusedContainerColor = LocalAmberTokens.current.surface2,
    focusedBorderColor = LocalAmberTokens.current.accent,
    unfocusedBorderColor = LocalAmberTokens.current.line,
    focusedLabelColor = LocalAmberTokens.current.accent,
    unfocusedLabelColor = LocalAmberTokens.current.ink3,
    focusedTextColor = LocalAmberTokens.current.ink,
    unfocusedTextColor = LocalAmberTokens.current.ink,
    cursorColor = LocalAmberTokens.current.accent,
)
