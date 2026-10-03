# P2 独立 UI 与调用链审查

审查 agent 只读当前冻结源码，独占本报告。范围：NovelProjectSetupPage、NovelBranchHistoryPage、NovelRecoveryPreviewPage、NovelProjectsPage、NovelBranchSheet 与 Workspace 对应 P2 路由和停止切换。不重复 P1，不修改源码。

## 源码审查结果

调用链与领域测试通过；新包实际交互发现一项 UI 状态响应 blocker（见下文），不据源码测试推定视觉验收通过。

| 流程 | 已查明实际链路 | 生命周期／数据边界 |
|---|---|---|
| 项目创建设置 | Workspace ControlOps → NovelProjectSetupPage(projectId) → readWorkspaceSnapshot → setProjectPolishPreference(expectedCas) → 更新CAS与成功提示 | 只有项目读取后才展示编辑区，保存带原读取CAS；KeyboardAvoidMode.RESIZE 在 show/hide 对称恢复。返回 Workspace 的 onPageShow 会 reload。 |
| 原种子再生成 | 项目 creationMode=quickStart 且有 seed → quickStartFromSeed(projectId,guidance,coreIdeaOverride) → novelQuickStartRequestText → 既有 quickStart/generate | 原种子永久保留，本轮覆盖字段只组成请求；生成成功开始后返回 Workspace 接管现有运行状态。空白／旧项目不会显示无原种子的再生成动作。 |
| 分支历史与管理 | Workspace → NovelBranchSheet.onOpenHistory → NovelBranchHistoryPage → snapshot+workspaceHistory → 对应 creation 操作 → reload | 活动任务时历史入口禁用；每个领域 mutation 读取最新workspace CAS并进入既有事务，当前／主线不可删除。页面 mutation busy 时返回被拦，保存停止输入后执行；隐藏时loadToken失效，返回重读。 |
| 停止任务后切换 | BranchSheet.onSelect → selectWorkspaceBranch → 显式确认 → stopAndSwitchWorkspaceBranch → await stopActiveRun → await ghostwrite/polish cancel → switchBranch → Workspace.reload | 取消确认不停止任务；取消失败不切分支、不失效原进度订阅。普通任务停止等待 finally checkpoint，切换读取停止后的新 CAS。已有requestedBranch通知目标在成功reload后清掉，不会将用户随后切换重新拉回。 |
| 损坏项目只读旧稿 | Projects 恢复预览 → openRecoveryText → NovelRecoveryPreviewPage(sourceToken) → repository.readProjectRecovery → chapter完整content | repository再次校验实际快照token；没有写当前损坏目录。正文是 Text+copyOption，无编辑和保存动作。返回后原确认恢复overlay保留，替换目录只在显式confirmRestore执行。 |

## UI 源码尺寸与边界

- Setup 的三个编辑框与正文左右 20 vp；保存与再生成动作至少 48 vp。主体 Scroll 占剩余空间，RESIZE 下可滚动，正文和长原种子按内容扩高。
- History 的项目名、分支名宽度限制为容器宽度，可换行扩高。各动作 minHeight 44 vp，四动作行文字长度为固定短标签；分支重命名及 checkpoint 分叉输入均 44 vp，字段后动作置独立行。删除当前／主线按钮不可用，并有明确文字。
- BranchSheet 名称最多两行省略，当前标记有独立位置；行 minHeight64 而非固定高度，名称和主线标签可扩高。关闭44×44、创建64×44、控制动作88×44；外层 Scroll 限90%高度，内层分支列表可滚动，父Workspace为RESIZE。
- Recovery 返回图标采用局部44×44居中Row；章节选择行minHeight44，长标题可换行，正文完整展示且页面滚动。底部加navBarHeight+28。
- Projects 创建表单主体滚动、底部取消／创建动作至少44vp；长项目名列表省略，恢复预览将说明区域限制为50%滚动高度。

上述是源码核查，实际对齐／边距／keyboard是否正确须由本轮安装截图确认。

## 独立验证

- `harmony/entry/src/test/novel_branch_switch.test.cjs`：5/5通过。测试执行提取的实际 Workspace 方法，覆盖确认／取消、普通／代笔／润色等待停止后切换、取消失败原分支和订阅保留。
- `novel_project_setup.test.ts`、`novel_history_completion.test.ts`、`novel_import_recovery.test.ts`：18/18通过。覆盖种子与偏好磁盘重载、原种子再生成现有模型检查点、活动生成拒绝项目变更、stop等待清理、连续撤回、多分支检查点准确恢复／分叉、只读恢复不改变损坏字节与失效token拒绝。

## 当前状态

## 新包视觉与 AX 审查

已用 `view_image` 查看本轮 `device/p2/p2-project-setup.jpeg`、`p2-preference-saved.jpeg`、`p2-setup-keyboard.jpeg`、`p2-history-page.jpeg`、`p2-checkpoint-actions.jpeg`、`p2-after-fork-action.jpeg`，并解析对应 AX JSON。

- 项目设置输入、保存动作和成功提示左右20vp一致，保存后的 `P2 project polish preference` 可见；键盘状态下编辑框及保存动作在IME以上，未见挤压遮挡。
- 历史页面四个分支操作的 AX 热区均高154px（当前density3.5，即44vp），动作在同一行，没有超出右边界。检查点卡片的说明、输入、两个动作沿卡片内边距排列；长输入名横向滚动显示末尾，符合TextInput行为，未判断为裁切bug。
- **P2-UI01，P1级功能阻断**：非空新分支输入后，“从检查点创建分支”仍disabled，分叉无法执行。`p2-after-fork-action.json` 中 TextInput 为 `P2 History Fork with a long readable branch name`，fork动作 `enabled=false`、`opacity=0.450000`，同卡恢复动作 `enabled=true`，排除整体 `available()` 为false。截图没有切换新分支，不能称为分叉成功。
- 对应源码为 `NovelBranchHistoryPage.ets` 原200–204的 `@Builder Action(label, enabled:boolean, ...)`，原247调用用primitive值传入依赖forkName的谓词。相同pattern原229的重命名保存依赖renameText；BranchRow ForEach key仅id/name/isMain/是否打开重命名，不包含renameText，应在同一局部修复中验证非空→空→非空的保存状态响应。

已将具体证据、同pattern范围传给主 agent，修复由主 agent 分配worker；本审查未修改源码。恢复旧稿截图还未提供。

## 当前状态

## 局部修复后的实际复验

主 agent 安装局部修复后的新包，审查 agent 独立读取当前源码、AX JSON并逐图 `view_image`：

- 当前 fork 与 rename 保存动作均在实际 builder UI 表达式直接消费对应 `@State` 字段，只局部展开这两个动作，没有修改通用Action或其他页面。
- `p2-fork-enabled-fixed.json/jpeg`：输入非空名称后“从检查点创建分支” `enabled=true`、`opacity=1`；`p2-fork-created.jpeg` 出现“已创建并切换到新分支”，当前分支为 `P2-History-Branch-Long-Name-Review`，历史出现“从历史创建分支”。
- `p2-rename-enabled-fixed.json/jpeg`：“保存分支名称” `enabled=true`、`opacity=1`。`p2-rename-persisted.jpeg` 显示“分支名称已保存”，当前分支与行标题均为新长名，两行换行后没有挤压操作按钮或越过卡片右边界。
- 因此 **P2-UI01 closed**；同pattern rename范围也已实际保存验证，未增加泛化框架或兜底。

## A15 阅读器历史版本分叉视觉复查

已独立读取 `p2-main-version-preview`、`p2-reader-fork-confirm`、`p2-reader-fork-workspace`、`p2-reader-fork-old-text` 的图像及AX：

- 历史版本预览显示旧正文 `Alpha test. Alpha chapter content.`；确认时underlay AX仍有当前正文 `Beta test. Alpha chapter content.`，清楚区分当前与历史版本。
- 非空新分支名称后fork动作AX `enabled=true`。确认弹窗说明使用历史关联检查点的完整正文、资料、讨论和分支偏好，当前分支保持；正文与两个动作没有溢出或重叠。
- 确认后Workspace显示 `P2-Reader-Version-Fork`，进入其第1章显示Alpha旧正文，完成预览→确认→新分支Workspace→旧正文链；预览表单、底部双按钮边距和字号可读。
- **A15视觉gate closed**。当前分支保存与完整检查点内容的领域断言仍由既有测试支撑，截图不扩大证明多章、所有字号或所有设备尺寸。
- 额外独立运行 `novel_version_fork.test.cjs`，并与branch_switch合跑11/11通过：实际阅读器方法覆盖准确检查点/CAS、无关联不猜测、迟到查询失效、确认范围、执行前重验、stale CAS保留预览并展示错误。连同此前18项领域测试，本审查针对性验证共29项通过。

## Recovery证据边界与最终状态

损坏项目Recovery页本轮没有在设备触发：主 agent 报告当前hdc shell uid2000不能写应用沙盒，设备没有可用root smode；未改安全配置、未增加调试路由。该页仍有源码只读布局核查，以及实际repository测试验证完整旧稿读取不写损坏目录、sourceToken过期拒绝。**没有Recovery截图目测证据**，不写成设备验收通过；这属于证据边界，当前没有确认的Recovery实现bug。

P2确认finding全部closed；实际可触发的Setup、History、长名改名、阅读器版本分叉视觉及链路复验通过。领域和调用顺序测试共29项通过，可推进下一phase；Recovery保留准确的设备未触发说明。
