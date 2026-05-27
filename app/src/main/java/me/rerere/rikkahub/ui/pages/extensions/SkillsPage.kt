package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import me.rerere.hugeicons.stroke.MoreVertical
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillScanIssue
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTextButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.WorkspaceTopBar
import me.rerere.rikkahub.ui.components.ui.workspaceBorder
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SkillsPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<SkillsVM>()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val skillIssues by vm.skillIssues.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val enabledSkillNames = settings.getCurrentAssistant().enabledSkills
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
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

            items(skillIssues, key = { it.directoryName }) { issue ->
                SkillIssueCard(issue = issue)
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
                            imageVector = HugeIcons.Puzzle,
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

            items(skills, key = { it.name }) { skill ->
                SkillCard(
                    skill = skill,
                    enabled = skill.name in enabledSkillNames,
                    onClick = { navController.navigate(Screen.SkillDetail(skill.name)) },
                    onOptimize = {
                        navigateToChatPage(
                            navigator = navController,
                            initText = buildOptimizeSkillPrompt(skill.name),
                        )
                    },
                    onDelete = { deleteTarget = skill },
                )
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            onDismiss = { showAddDialog = false },
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

    RikkaConfirmDialog(
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
private fun SkillLibraryStatusCard(
    installedCount: Int,
    enabledCount: Int,
    disabledCount: Int,
    issueCount: Int,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onOptimizeAll: () -> Unit,
) {
    val colors = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // V3 settings-skills.jsx Skill 库 card 圆角 18dp
        shape = RoundedCornerShape(18.dp),
        color = colors.paper,
        contentColor = colors.ink,
        border = workspaceBorder(),
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
                    icon = HugeIcons.Puzzle,
                    tone = WorkspaceTone.Neutral,
                    size = 34.dp,
                    iconSize = 20.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "Skill 库",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // V3 settings-skills.jsx: 三 pill 中只有"已启用"用 accentSoft + accent，
                        // 不要绿色。设计稿原注释 "single accent color, no green"
                        WorkspaceStatusPill("已安装 $installedCount")
                        WorkspaceStatusPill("已启用 $enabledCount", tone = WorkspaceTone.Accent)
                        WorkspaceStatusPill("未启用 $disabledCount")
                    }
                }
            }
            if (issueCount > 0) {
                Text(
                    text = "$issueCount 个 Skill 目录格式异常，Agent 不会加载它们。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.red,
                )
            } else {
                Text(
                    text = "Agent 会在每次运行前重新扫描已安装 Skill。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceIconButton(
                    onClick = onAdd,
                    icon = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.skills_page_add_title),
                    tone = WorkspaceTone.Neutral,
                )
                WorkspaceIconButton(
                    onClick = onImport,
                    icon = HugeIcons.Download01,
                    contentDescription = stringResource(R.string.skills_page_import_from_github),
                    tone = WorkspaceTone.Neutral,
                )
                WorkspaceIconButton(
                    onClick = onRefresh,
                    icon = HugeIcons.Refresh01,
                    contentDescription = "刷新 Skill",
                    tone = WorkspaceTone.Neutral,
                )
                if (installedCount > 0) {
                    WorkspaceTextButton(
                        text = "全量规整",
                        onClick = onOptimizeAll,
                        tone = WorkspaceTone.Accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillIssueCard(issue: SkillScanIssue) {
    val colors = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.redContainer.copy(alpha = 0.55f),
        contentColor = colors.ink,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Alert01,
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
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = "格式错误：${issue.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.red,
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillMetadata,
    enabled: Boolean,
    onClick: () -> Unit,
    onOptimize: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = workspaceColors()
    val chatTheme = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current
    // V3 settings-skills.jsx: 单 Skill 卡 18dp 圆角 + surface 底 + hair 边线
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = chatTheme.surface,
        contentColor = chatTheme.ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, chatTheme.hair),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Puzzle,
                tone = WorkspaceTone.Neutral,
                size = 34.dp,
                iconSize = 20.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = skill.description.ifBlank { "没有描述" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!skill.compatibility.isNullOrBlank()) {
                    Text(
                        text = skill.compatibility,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // V3 settings-skills.jsx: 仅 disabled 行显示"未启用"小灰胶囊；MCP skill 显示 accent "MCP" 胶囊
                if (!enabled || skill.skillDir.resolve("mcp.json").exists()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!enabled) {
                            WorkspaceStatusPill(
                                text = "未启用",
                                tone = WorkspaceTone.Neutral,
                            )
                        }
                        if (skill.skillDir.resolve("mcp.json").exists()) {
                            WorkspaceStatusPill(
                                text = "MCP",
                                tone = WorkspaceTone.Accent,
                            )
                        }
                    }
                }
            }
            // V3 settings-skills.jsx: 单行右侧仅 chevron-right；操作（规整化/删除）改用 overflow 菜单
            var showMenu by remember { mutableStateOf(false) }
            Box {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = "更多操作",
                    tint = chatTheme.inkSoft,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showMenu = true },
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("规整化为移动端") },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.MagicWand01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = chatTheme.accent,
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
                                imageVector = HugeIcons.Delete01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.red,
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
    onConfirm: (name: String, content: String) -> Unit,
) {
    var content by rememberSaveable { mutableStateOf("") }

    val name = remember(content) {
        SkillFrontmatterParser.parse(content)["name"]?.trim() ?: ""
    }
    val nameError = content.isNotBlank() && name.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skills_page_add_title)) },
        text = {
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
                        color = MaterialTheme.colorScheme.error
                    )
                    else if (name.isNotBlank()) Text(stringResource(R.string.skills_page_skill_name, name))
                    else Text(stringResource(R.string.skills_page_paste_hint))
                },
                isError = nameError,
                minLines = 8,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, content) },
                enabled = name.isNotBlank() && !nameError,
            ) {
                Text(stringResource(R.string.skills_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSkillDialog(
    onDismiss: () -> Unit,
    onConfirm: (repoUrl: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(stringResource(R.string.skills_page_import_from_github)) },
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
