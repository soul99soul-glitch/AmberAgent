import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { ACCENT, BG, ERROR, INK, INK3, LINE, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { getNovelCreation } from '../di/AppContainer.ets';
import type { NovelBranch, NovelProject, NovelWorkspaceSnapshot, NovelWorkspaceStatus, WorkspaceCommit } from '@amber/deepread-domain';

interface HistoryPageParams { projectId?: string; }
type HistoryAction = 'fork' | 'restore' | 'rename' | 'main' | 'delete' | 'switch';

@Entry
@Component
struct NovelBranchHistoryPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State status: NovelWorkspaceStatus | null = null;
  @State projectName: string = '';
  @State history: WorkspaceCommit[] = [];
  @State selectedHead: string = '';
  @State forkName: string = '';
  @State renameBranchId: string = '';
  @State renameText: string = '';
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  private projectId: string = '';
  private alive: boolean = false;
  private hidden: boolean = false;
  private loadToken: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;
  private forkController: TextInputController = new TextInputController();
  private renameController: TextInputController = new TextInputController();

  aboutToAppear(): void {
    this.alive = true;
    const params: HistoryPageParams = router.getParams() as HistoryPageParams;
    this.projectId = params?.projectId ?? '';
    this.reload();
  }

  onPageShow(): void {
    this.enableKeyboardAvoidance();
    if (this.hidden) { this.hidden = false; this.reload(); }
  }

  onPageHide(): void {
    this.hidden = true;
    this.loadToken++;
    this.restoreKeyboardAvoidance();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.loadToken++;
    this.restoreKeyboardAvoidance();
  }

  onBackPress(): boolean { return this.busy; }

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  private async reload(): Promise<void> {
    const token: number = ++this.loadToken;
    this.loading = true;
    try {
      if (this.projectId.length === 0) throw new Error('未指定小说项目');
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      const history: WorkspaceCommit[] = await getNovelCreation().workspaceHistory(this.projectId);
      if (!this.alive || token !== this.loadToken) return;
      this.status = snapshot.status;
      this.projectName = snapshot.project.name;
      this.history = history;
      if (!history.some((commit: WorkspaceCommit): boolean => commit.commitId === this.selectedHead)) this.selectedHead = '';
      if (!snapshot.status.branches.some((branch: NovelBranch): boolean =>
        branch.id === this.renameBranchId && branch.lifecycle === 'active')) this.renameBranchId = '';
      this.error = '';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = String(error);
    } finally {
      if (this.alive && token === this.loadToken) this.loading = false;
    }
  }

  private available(): boolean { return !this.loading && !this.busy && this.status !== null; }

  private activeBranches(): NovelBranch[] {
    return this.status === null ? [] : this.status.branches.filter((branch: NovelBranch): boolean => branch.lifecycle === 'active');
  }

  private selectedCommit(): WorkspaceCommit | null {
    return this.history.find((commit: WorkspaceCommit): boolean => commit.commitId === this.selectedHead) ?? null;
  }

  private selectedLabel(): string {
    const commit: WorkspaceCommit | null = this.selectedCommit();
    return commit === null ? '' : `${this.commitLabel(commit)} · ${new Date(commit.createdAt).toLocaleString()}`;
  }

  private commitLabel(commit: WorkspaceCommit): string {
    let label: string = '工作区变更';
    switch (commit.mutation) {
      case 'create': label = '创建项目'; break;
      case 'manual_edit': label = '编辑章节'; break;
      case 'chapter_delete': label = '删除章节'; break;
      case 'chapter_discard': label = '废弃章节'; break;
      case 'material_edit': label = '编辑资料'; break;
      case 'material_delete': label = '删除资料'; break;
      case 'collect': label = '收录正文'; break;
      case 'generation_commit': label = '保存创作结果'; break;
      case 'polish_adopt': case 'batch_polish_commit': label = '采纳润色'; break;
      case 'version_restore': label = '恢复章节版本'; break;
      case 'discussion_archive': label = '归档讨论'; break;
      case 'create_branch': label = '创建分支'; break;
      case 'history_fork': label = '从历史创建分支'; break;
      case 'undo': label = '恢复检查点'; break;
      case 'rename': label = '修改项目名称'; break;
      case 'model_change': label = '修改模型配置'; break;
      case 'plot_sync': label = '同步剧情摘要'; break;
      case 'unresolved_resolve': label = '确认剧情影响'; break;
      case 'transcript_checkpoint': label = '保存讨论记录'; break;
      case 'branch_settings_change': label = '修改创作偏好'; break;
      case 'project_setup_change': label = '修改创建设置'; break;
      case 'branch_rename': label = '分支改名'; break;
      case 'branch_main': label = '调整主线'; break;
      case 'branch_delete': label = '删除分支'; break;
      case 'workspace_import': label = '导入作品'; break;
      case 'proposal_create': label = '创建修改建议'; break;
      case 'proposal_resolve': label = '处理修改建议'; break;
      case 'interrupt_commit': label = '保存中断草稿'; break;
    }
    return commit.changedChapterOrdinal === null ? label : `${label} · 第 ${commit.changedChapterOrdinal} 章`;
  }

  private async perform(action: HistoryAction, target: string, value: string = ''): Promise<void> {
    if (!this.available()) return;
    this.forkController.stopEditing();
    this.renameController.stopEditing();
    this.busy = true;
    this.error = '';
    this.notice = '';
    try {
      let project: NovelProject;
      const creation = getNovelCreation();
      if (action === 'fork') project = await creation.forkFromHistory(this.projectId, target, value);
      else if (action === 'restore') project = await creation.undoToCheckpoint(this.projectId, target);
      else if (action === 'rename') project = await creation.renameBranch(this.projectId, target, value);
      else if (action === 'main') project = await creation.setMainBranch(this.projectId, target);
      else if (action === 'delete') project = await creation.deleteBranch(this.projectId, target);
      else project = await creation.switchBranch(this.projectId, target);
      if (!this.alive) return;
      this.projectName = project.name;
      this.renameBranchId = '';
      if (action === 'fork' || action === 'switch') this.selectedHead = '';
      if (action === 'fork') this.forkName = '';
      await this.reload();
      if (this.alive && !this.hidden) this.notice = action === 'fork' ? '已创建并切换到新分支'
        : action === 'restore' ? '已恢复当前分支，其他分支保持原样'
        : action === 'rename' ? '分支名称已保存' : action === 'main' ? '主线已更新'
        : action === 'delete' ? '分支已删除' : '已切换分支';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }

  private confirmRestore(): void {
    if (!this.available() || this.selectedCommit() === null || this.status?.cas.head === this.selectedHead) return;
    const head: string = this.selectedHead;
    AlertDialog.show({
      title: '恢复当前分支？',
      message: `恢复到“${this.selectedLabel()}”。当前分支的正文、资料和讨论记录将使用该检查点的内容；其他分支不会改变。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '恢复', action: (): void => { this.perform('restore', head); } },
    });
  }

  private confirmBranch(branch: NovelBranch, action: HistoryAction): void {
    if (!this.available()) return;
    if (action === 'delete' && (branch.isMain || branch.id === this.status?.activeBranchId)) return;
    AlertDialog.show({
      title: action === 'delete' ? '删除分支？' : '设为主线？',
      message: action === 'delete' ? `删除“${branch.name}”。当前分支和主线保持原样。`
        : `将“${branch.name}”设为主线，原主线保留为普通分支。当前打开的分支不会切换。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: action === 'delete' ? '删除' : '设为主线', fontColor: action === 'delete' ? ERROR : ACCENT,
        action: (): void => { this.perform(action, branch.id); } },
    });
  }

  @Builder
  private Action(label: string, enabled: boolean, action: () => void, destructive: boolean = false): void {
    Text(label).fontSize(14).fontColor(destructive ? ERROR : ACCENT)
      .constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12 })
      .enabled(enabled).opacity(enabled ? 1 : 0.45).onClick(action).accessibilityText(label)
  }

  @Builder
  private BranchRow(branch: NovelBranch): void {
    Column({ space: 4 }) {
      Text(branch.name).fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
      Text(`${branch.isMain ? '主线' : '分支'}${branch.id === this.status?.activeBranchId ? ' · 当前打开' : ''}`)
        .fontSize(12).fontColor(INK3).width('100%')
      Row() {
        this.Action('切换', this.available() && branch.id !== this.status?.activeBranchId,
          (): void => { this.perform('switch', branch.id); })
        this.Action('改名', this.available(), (): void => {
          this.renameBranchId = this.renameBranchId === branch.id ? '' : branch.id;
          this.renameText = branch.name;
        })
        this.Action('设为主线', this.available() && !branch.isMain,
          (): void => { this.confirmBranch(branch, 'main'); })
        this.Action('删除', this.available() && !branch.isMain && branch.id !== this.status?.activeBranchId,
          (): void => { this.confirmBranch(branch, 'delete'); }, true)
      }.width('100%')
      if (this.renameBranchId === branch.id) {
        TextInput({ text: this.renameText, placeholder: '分支名称', controller: this.renameController })
          .width('100%').height(44).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
          .enabled(this.available()).onChange((value: string): void => { this.renameText = value; })
        Text('保存分支名称').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
          .padding({ left: 12, right: 12 }).enabled(this.available() && this.renameText.trim().length > 0)
          .opacity(this.available() && this.renameText.trim().length > 0 ? 1 : 0.45)
          .onClick((): void => { this.perform('rename', branch.id, this.renameText.trim()); })
      }
      if (branch.isMain || branch.id === this.status?.activeBranchId) {
        Text('当前分支和主线不能删除。').fontSize(12).fontColor(INK3).width('100%')
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 14, bottom: 14 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
  }

  @Builder
  private CheckpointActions(): void {
    Column({ space: 8 }) {
      Text(`已选择：${this.selectedLabel()}`).fontSize(13).fontColor(INK).width('100%')
      Text('从此处创建分支会保留当前分支，并切换到新分支。').fontSize(12).fontColor(INK3).width('100%')
      TextInput({ text: this.forkName, placeholder: '新分支名称', controller: this.forkController })
        .height(44).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
        .enabled(this.available()).onChange((value: string): void => { this.forkName = value; })
      Text('从检查点创建分支').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .padding({ left: 12, right: 12 }).enabled(this.available() && this.forkName.trim().length > 0)
        .opacity(this.available() && this.forkName.trim().length > 0 ? 1 : 0.45)
        .onClick((): void => { this.perform('fork', this.selectedHead, this.forkName.trim()); })
      this.Action('恢复到此检查点', this.available() && this.status?.cas.head !== this.selectedHead,
        (): void => { this.confirmRestore(); })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(SURFACE2).borderRadius(12)
      .margin({ bottom: 12 })
  }

  @Builder
  private HistoryRow(commit: WorkspaceCommit): void {
    Column({ space: 5 }) {
      Text(`${this.selectedHead === commit.commitId ? '✓ ' : ''}${this.commitLabel(commit)}`)
        .fontSize(14).fontColor(this.selectedHead === commit.commitId ? ACCENT : INK).width('100%')
      Text(`${new Date(commit.createdAt).toLocaleString()}${commit.commitId === this.status?.cas.head ? ' · 当前检查点' : ''}`)
        .fontSize(12).fontColor(INK3).width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start).constraintSize({ minHeight: 60 })
      .padding({ top: 12, bottom: 12, left: 12, right: 12 })
      .backgroundColor(this.selectedHead === commit.commitId ? SURFACE2 : BG).borderRadius(10)
      .enabled(this.available()).onClick((): void => { this.selectedHead = commit.commitId; })
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '历史与分支', actionText: this.loading ? '加载中' : '刷新',
            onBack: (): void => { if (!this.busy) router.back(); },
            onAction: (): void => { if (!this.busy && !this.loading) this.reload(); } })
          if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding(20) }
          if (this.notice.length > 0) { Text(this.notice).fontSize(13).fontColor(ACCENT).width('100%').padding(20) }
          Scroll() {
            Column({ space: 12 }) {
              Text(this.projectName).fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
              Text(this.status === null ? '正在读取工作区…' : `当前分支：${this.status.activeBranchName}`)
                .fontSize(13).fontColor(INK3).width('100%')
              Text(this.busy ? '正在保存工作区变更…' : '分支管理').fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
              ForEach(this.activeBranches(), (branch: NovelBranch): void => { this.BranchRow(branch); },
                (branch: NovelBranch): string => `${branch.id}:${branch.name}:${branch.isMain}:${this.renameBranchId === branch.id}`)
              Text('当前分支的历史').fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
              Text('选择一个检查点，再恢复当前分支或从该处创建新分支。').fontSize(12).fontColor(INK3).width('100%')
              if (!this.loading && this.history.length === 0) { Text('暂无可用检查点').fontSize(14).fontColor(INK3) }
              ForEach(this.history, (commit: WorkspaceCommit): void => {
                this.HistoryRow(commit)
                if (this.selectedHead === commit.commitId) { this.CheckpointActions() }
              },
                (commit: WorkspaceCommit): string => `${commit.commitId}:${this.selectedHead === commit.commitId}`)
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 18, bottom: this.navBarHeight + 24 })
          }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
