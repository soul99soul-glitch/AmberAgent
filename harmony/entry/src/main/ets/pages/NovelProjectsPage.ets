import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
import { novelStorySeedText } from '@amber/deepread-domain';
import type { NovelStorySeed } from '@amber/deepread-domain';
// NovelProjectsPage — 小说项目库(iOS NovelProjectListView 结构:右上导入 +
// 底部悬浮新建胶囊 + 创建 sheet(空白/快速开始)+ 空态双按钮)
// 行操作收进长按菜单(对齐会话首页交互);恢复删除项目为列表尾注链接。
import { ACCENT, ACCENT_INK, ACCENT_LIGHT, BG, INK, INK2, INK3, INK4, ERROR, MOTION_ENTER, MOTION_OVERLAY, MOTION_STAGGER, OVERLAY, SHADOW_OVERLAY, SURFACE, SURFACE2, TITLE, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, BtnFill, BtnGhost, Seg, IconSeat, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { StudioHero } from '../components/studio/StudioHero.ets';
import { StudioButton } from '../components/studio/StudioButton.ets';
import { router, promptAction, KeyboardAvoidMode, curves } from '@kit.ArkUI';
import { getNovelCreation, getNovelWorkspaceService, loadNovelModelOptions } from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelProject, NovelProjectFailure, NovelProjectRecoveryPreview } from '@amber/deepread-domain';
import type { NovelWorkspaceImportPreview } from '../platform_impl/NovelWorkspaceService.ets';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';

interface RowMenuResult {
  index: number;
}

const relativeTime = (ms: number): string => {
  const diff: number = Math.max(0, Date.now() - ms);
  if (diff < 60 * 1000) return '刚刚';
  if (diff < 3600 * 1000) return `${Math.floor(diff / 60000)} 分钟前`;
  if (diff < 86400 * 1000) return `${Math.floor(diff / 3600000)} 小时前`;
  if (diff < 30 * 86400 * 1000) return `${Math.floor(diff / 86400000)} 天前`;
  return `${Math.floor(diff / (30 * 86400 * 1000))} 个月前`;
};

@Entry
@Component
struct NovelProjectsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State projects: NovelProject[] = [];
  @State projectFailures: NovelProjectFailure[] = [];
  @State importPreview: NovelWorkspaceImportPreview | null = null;
  @State recoveryPreview: NovelProjectRecoveryPreview | null = null;
  @State loading: boolean = true;
  @State modelSetupRequired: boolean = false;
  @State renameDialogOpen: boolean = false;
  @State renameId: string = '';
  @State nameInput: string = '';
  @State confirmDeleteId: string = '';
  @State restoring: boolean = false;
  @State importing: boolean = false;
  @State errorMsg: string = '';
  @State pressedId: string = '';
  @State designVisible: boolean = true;
  // 创建 sheet(iOS NovelProjectCreateSheet):空白项目 / 快速开始(题材+核心想法)
  @State createSheetOpen: boolean = false;
  @State createMode: number = 0;
  @State createName: string = '';
  @State quickGenre: string = '';
  @State quickIdea: string = '';
  @State quickWorld: string = '';
  @State quickCharacters: string = '';
  @State quickDirection: string = '';
  @State creating: boolean = false;
  private pageAlive: boolean = false;
  private loadToken: number = 0;
  // F41:仅首次加载展示整页 loading;后续刷新保留既有列表(不卸载已读内容)
  private loadedOnce: boolean = false;
  // aboutToAppear 与首次 onPageShow 都会触发,首屏只加载一次
  private firstShowDone: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;
  private createSession: number = 0;
  private createExitPromptOpen: boolean = false;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.loadProjects().catch((e: Error): void => {
      if (this.pageAlive) this.errorMsg = String(e);
    });
  }

  onPageShow(): void {
    this.designVisible = true;
    if (this.createSheetOpen) this.enableKeyboardAvoidance();
    if (getProductKind() === 'novel') markWantRoutingReady(this.getUIContext().getRouter());
    if (this.firstShowDone) {
      this.loadProjects().catch((e: Error): void => {
        if (this.pageAlive) this.errorMsg = String(e);
      });
    } else {
      this.firstShowDone = true;
    }
  }

  onPageHide(): void {
    this.designVisible = false;
    this.pressedId = '';
    this.restoreKeyboardAvoidance();
  }

  aboutToDisappear(): void {
    this.designVisible = false;
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.loadToken++;
  }

  onBackPress(): boolean {
    if (this.importPreview !== null || this.recoveryPreview !== null) {
      if (!this.importing && !this.restoring) this.dismissRestorePreview();
      return true;
    }
    if (this.createSheetOpen) {
      this.requestDismissCreateSheet();
      return true;
    }
    if (this.renameDialogOpen) {
      this.renameDialogOpen = false;
      this.errorMsg = '';
      return true;
    }
    if (this.confirmDeleteId.length > 0) {
      this.confirmDeleteId = '';
      return true;
    }
    return false;
  }

  // 创建 sheet 有底部输入框:页面级键盘 RESIZE 避让(灵魂/提示词编辑器同款)
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

  async loadProjects(): Promise<void> {
    const token: number = ++this.loadToken;
    // F41:仅首次进入显示 loading;刷新时保留 projects,避免列表闪烁/滚动位置丢失
    const firstLoad: boolean = !this.loadedOnce;
    if (this.pageAlive && firstLoad) this.loading = true;
    try {
      const inventory = await getNovelCreation().listProjectInventory();
      const projects: NovelProject[] = inventory.projects;
      if (this.pageAlive && token === this.loadToken) {
        // 首次填充走 animateTo,让行交错入场 transition 生效
        if (firstLoad && projects.length > 0) {
          animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
            this.projects = projects;
          });
        } else {
          this.projects = projects;
        }
        this.projectFailures = inventory.failures;
        this.loadedOnce = true;
        this.errorMsg = '';
      }
      // A broken service configuration must not hide healthy local manuscripts.
      if (getProductKind() === 'novel') {
        const modelOptions: NovelModelOption[] = await loadNovelModelOptions();
        if (this.pageAlive && token === this.loadToken) {
          this.modelSetupRequired = !modelOptions.some((option: NovelModelOption): boolean => option.available);
        }
      }

    } finally {
      if (this.pageAlive && token === this.loadToken) this.loading = false;
    }
  }

  openCreateSheet(): void {
    if (this.createSheetOpen || this.creating || this.importing || this.restoring ||
      this.importPreview !== null || this.recoveryPreview !== null) return;
    this.createSession++;
    this.createExitPromptOpen = false;
    this.createMode = 0;
    this.createName = '';
    this.quickGenre = '';
    this.quickIdea = '';
    this.quickWorld = '';
    this.quickCharacters = '';
    this.quickDirection = '';
    this.errorMsg = '';
    this.enableKeyboardAvoidance();
    this.createSheetOpen = true;
  }

  private dismissCreateSheet(): void {
    this.createSheetOpen = false;
    this.createExitPromptOpen = false;
    this.errorMsg = '';
    this.restoreKeyboardAvoidance();
  }

  private hasCreateDraft(): boolean {
    return this.createName.trim().length > 0 || this.quickGenre.trim().length > 0 ||
      this.quickIdea.trim().length > 0 || this.quickWorld.trim().length > 0 ||
      this.quickCharacters.trim().length > 0 || this.quickDirection.trim().length > 0;
  }

  private isCreateSessionActive(session: number): boolean {
    return this.pageAlive && this.designVisible && this.createSheetOpen && session === this.createSession;
  }

  private requestDismissCreateSheet(): void {
    if (!this.isCreateSessionActive(this.createSession) || this.creating || this.createExitPromptOpen) return;
    if (!this.hasCreateDraft()) {
      this.dismissCreateSheet();
      return;
    }
    const session: number = this.createSession;
    this.createExitPromptOpen = true;
    AlertDialog.show({ title: '放弃新建小说？', message: '已填写的项目名称和故事种子会丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {
        if (this.isCreateSessionActive(session)) this.createExitPromptOpen = false;
      } },
      secondaryButton: { value: '放弃更改', fontColor: ERROR, action: (): void => {
        if (this.isCreateSessionActive(session) && !this.creating) this.dismissCreateSheet();
      } },
      cancel: (): void => {
        if (this.isCreateSessionActive(session)) this.createExitPromptOpen = false;
      },
    });
  }

  openRename(p: NovelProject): void {
    if (this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null) return;
    this.renameId = p.id;
    this.nameInput = p.name;
    this.errorMsg = '';
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.renameDialogOpen = true;
    });
  }

  async submitCreate(): Promise<void> {
    const name: string = this.createName.trim();
    if (name.length === 0) {
      this.errorMsg = '请输入名称';
      return;
    }
    if (this.creating) return;
    this.creating = true;
    try {
      const story: NovelStorySeed = {
        genre: this.quickGenre, coreIdea: this.quickIdea, world: this.quickWorld,
        characters: this.quickCharacters, direction: this.quickDirection,
      };
      const seed: string = this.createMode === 1 ? novelStorySeedText(story) : '';
      if (this.createMode === 1 && seed.length === 0) throw new Error('请至少填写一项故事种子');
      const created: NovelProject = await getNovelCreation().create(name, this.createMode === 1 ? story : undefined);
      this.dismissCreateSheet();
      this.openWorkspace(created.id, seed);
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.creating = false;
    }
  }

  async submitRename(): Promise<void> {
    const name: string = this.nameInput.trim();
    if (name.length === 0) {
      this.errorMsg = '请输入名称';
      return;
    }
    try {
      await getNovelCreation().rename(this.renameId, name);
      animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => { this.renameDialogOpen = false; });
      this.errorMsg = '';
      await this.loadProjects();
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  async confirmDelete(): Promise<void> {
    if (this.confirmDeleteId.length === 0) return;
    const targetId: string = this.confirmDeleteId;
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => { this.confirmDeleteId = ''; });
    await getNovelCreation().delete(targetId);
    await this.loadProjects();
  }

  // N3:恢复最近删除的项目(repository 软删恢复)
  async restoreDeleted(): Promise<void> {
    if (this.restoring || this.importing || this.importPreview !== null || this.recoveryPreview !== null) return;
    this.restoring = true;
    try {
      const restored: NovelProject | null = await getNovelCreation().restorePreviousProject();
      if (restored === null) {
        this.errorMsg = '没有可恢复的删除项目';
      } else {
        this.errorMsg = '';
        await this.loadProjects();
      }
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.restoring = false;
    }
  }

  private readSelectedBytes(uri: string): Uint8Array {
    const maxBytes: number = 16 * 1024 * 1024;
    const f: fileIo.File = fileIo.openSync(uri);
    try {
      const stat: fileIo.Stat = fileIo.statSync(f.fd);
      if (stat.size > maxBytes) throw new Error('文件过大');
      const buf: ArrayBuffer = new ArrayBuffer(stat.size);
      fileIo.readSync(f.fd, buf);
      return new Uint8Array(buf);
    } finally {
      fileIo.closeSync(f);
    }
  }

  async importWorkspace(): Promise<void> {
    if (this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null ||
      this.createSheetOpen || this.renameDialogOpen || this.confirmDeleteId.length > 0) return;
    this.errorMsg = '';
    this.importing = true;
    try {
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['小说工作区或完整备份 ZIP|.zip'];
      const context: common.UIAbilityContext =
        this.getUIContext().getHostContext() as common.UIAbilityContext;
      const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
      const uris: string[] = await documentPicker.select(options);
      if (uris.length === 0) return;
      const preview: NovelWorkspaceImportPreview = await getNovelWorkspaceService().inspectImport(
        this.readSelectedBytes(uris[0]));
      if (!this.pageAlive) return;
      this.errorMsg = '';
      this.importPreview = preview;
    } catch (e) {
      if (this.pageAlive) this.errorMsg = `导入失败：${String(e)}`;
    } finally {
      if (this.pageAlive) this.importing = false;
    }
  }

  private dismissRestorePreview(): void {
    this.importPreview = null;
    this.recoveryPreview = null;
    this.errorMsg = '';
  }

  private async inspectRecovery(failure: NovelProjectFailure): Promise<void> {
    if (this.restoring || this.importing || this.recoveryPreview !== null || this.importPreview !== null) return;
    this.restoring = true;
    try {
      const preview: NovelProjectRecoveryPreview | null =
        await getNovelCreation().inspectProjectRecovery(failure.id);
      if (!this.pageAlive) return;
      if (preview === null) this.errorMsg = `「${failure.name}」没有可用的恢复快照，请导入完整备份。`;
      else {
        this.errorMsg = '';
        this.recoveryPreview = preview;
      }
    } catch (e) {
      if (this.pageAlive) this.errorMsg = `检查恢复来源失败：${String(e)}`;
    } finally {
      if (this.pageAlive) this.restoring = false;
    }
  }

  private async confirmRestorePreview(resolution: 'reject' | 'replace' | 'keepBoth' = 'reject'): Promise<void> {
    if (this.importing || this.restoring) return;
    const imported: NovelWorkspaceImportPreview | null = this.importPreview;
    const recovered: NovelProjectRecoveryPreview | null = this.recoveryPreview;
    if (imported === null && recovered === null) return;
    this.restoring = true;
    try {
      const project: NovelProject = imported !== null
        ? await getNovelWorkspaceService().confirmImport(imported, resolution)
        : await getNovelCreation().restoreProjectRecovery(recovered as NovelProjectRecoveryPreview);
      if (!this.pageAlive) return;
      this.dismissRestorePreview();
      await this.loadProjects();
      if (this.pageAlive) this.openWorkspace(project.id, '');
    } catch (e) {
      if (this.pageAlive) this.errorMsg = `恢复失败：${String(e)}`;
    } finally {
      if (this.pageAlive) this.restoring = false;
    }
  }

  private importHasCollision(): boolean {
    return this.importPreview !== null && this.importPreview.collision;
  }

  private openRecoveryText(): void {
    if (this.recoveryPreview === null || this.restoring || this.importing) return;
    const preview: NovelProjectRecoveryPreview = this.recoveryPreview;
    router.pushUrl({ url: 'pages/NovelRecoveryPreviewPage', params: {
      projectId: preview.projectId, title: preview.title, source: preview.source, sourceToken: preview.sourceToken,
    } });
  }

  private restorePreviewTitle(): string {
    if (this.importPreview !== null) return this.importPreview.kind === 'native' ? '恢复完整备份' : '导入公开工作区';
    return '恢复损坏的项目';
  }

  private restorePreviewDetails(): string {
    if (this.importPreview !== null) return this.importPreview.description;
    if (this.recoveryPreview !== null) {
      const source: string = this.recoveryPreview.source === 'trash' ? '删除前的完整项目' : '当前分支的提交快照';
      return `项目：${this.recoveryPreview.title}\n恢复来源：${source}\n损坏的原目录会保留，确认后才替换。`;
    }
    return '';
  }

  openWorkspace(projectId: string, quickStartSeed: string): void {
    const params: Record<string, string> = { 'projectId': projectId };
    if (quickStartSeed.length > 0) params['quickStartSeed'] = quickStartSeed;
    router.pushUrl({ url: 'pages/NovelWorkspacePage', params: params });
  }

  // 行操作收进长按菜单(对齐会话首页交互),列表不再常驻两个盒按钮
  private showRowMenu(p: NovelProject): void {
    if (this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '重命名', color: INK },
        { text: '删除', color: ERROR },
      ],
    }).then((result: RowMenuResult): void => {
      if (result.index === 0) this.openRename(p);
      else if (result.index === 1) {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.confirmDeleteId = p.id;
        });
      }
    }).catch((): void => {});
  }

  private projectStats(): string {
    let chapters: number = 0;
    for (const project of this.projects) chapters += project.chapters.length;
    return `${this.projects.length} 个故事 · ${chapters} 章`;
  }

  private openStandaloneSettings(): void {
    router.pushUrl({ url: 'pages/NovelStandaloneSettingsPage' });
  }

  @Builder
  StandaloneTitleBar(): void {
    Stack({ alignContent: Alignment.Center }) {
      Text('小说创作').fontSize(18).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .textAlign(TextAlign.Center).width('100%').padding({ left: 96, right: 96 })
      Row() {
        Blank()
        Row() {
          Icon({ name: 'arrow_down_to_line', iconSize: 21, color: INK })
        }.width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .enabled(!this.importing && !this.restoring && this.importPreview === null && this.recoveryPreview === null)
          .accessibilityText('导入小说工作区或完整备份')
          .onClick((): void => { this.importWorkspace(); })
        Row() {
          Icon({ name: 'gear', iconSize: 21, color: INK })
        }.width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .accessibilityText('设置').onClick((): void => { this.openStandaloneSettings(); })
      }.width('100%')
    }.width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight, bottom: 8 })
      .backgroundColor(BG)
  }

  @Builder
  ModelSetupPrompt(): void {
    Row({ space: 12 }) {
      Icon({ name: 'key', iconSize: 20, color: ACCENT })
      Column({ space: 2 }) {
        Text('先配置写作模型').fontSize(14).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('添加 API 服务后才能开始生成。').fontSize(12).fontColor(INK3)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Icon({ name: 'chevron_right', iconSize: 14, color: INK3 })
    }.width('100%').constraintSize({ minHeight: 56 }).padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).borderRadius(14).accessibilityText('先配置写作模型，添加 API 服务后才能开始生成')
      .onClick((): void => { this.openStandaloneSettings(); })
  }

  @Builder
  StudioEmptyLibrary(): void {
    Scroll() {
      Column({ space: 24 }) {
        StudioHero({ kind: 'novel', title: '故事从一句话开始', caption: '把灵感留在纸上',
          detail: '设定、章节与创作记录，随故事一起保存。', compact: false, active: this.designVisible })
        Column({ space: 12 }) {
          StudioButton({ label: '新建项目', iconName: 'plus', primary: true,
            disabled: this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null,
            onTap: (): void => { this.openCreateSheet(); } })
          StudioButton({ label: this.importing ? '导入中…' : '导入项目', iconName: 'arrow_down_to_line', primary: false,
            disabled: this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null,
            onTap: (): void => { this.importWorkspace(); } })
        }.width('100%').constraintSize({ maxWidth: 360 })
        this.RestoreFooter()
      }.width('100%').alignItems(HorizontalAlign.Center)
        .padding({ left: 20, right: 20, top: 24,
          bottom: this.modelSetupRequired ? 100 + Math.max(this.navBarHeight, 24) : 24 + this.navBarHeight })
    }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
  }

  @Builder
  projectRow(project: NovelProject, isLast: boolean, idx: number): void {
    Column() {
      Row() {
        IconSeat({ iconName: 'book' })
        Column() {
          Text(project.name)
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(`${relativeTime(project.updatedAt)} · ${project.chapters.length} 章`)
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .margin({ top: 4 })
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })
        Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressedId === project.id ? SURFACE2 : Color.Transparent)
      .scale({ x: getProductKind() === 'novel' && this.pressedId === project.id ? 0.985 : 1,
        y: getProductKind() === 'novel' && this.pressedId === project.id ? 0.985 : 1 })
      .opacity(getProductKind() === 'novel' && this.pressedId === project.id ? 0.9 : 1)
      .translate({ y: getProductKind() === 'novel' && this.pressedId === project.id ? 1 : 0 })
      .onTouch((e: TouchEvent): void => {
        if (e.type !== TouchType.Down && e.type !== TouchType.Up && e.type !== TouchType.Cancel) return;
        if (getProductKind() === 'novel') {
          animateTo({ duration: 240, curve: curves.springCurve(0, 1, 220, 26) }, (): void => {
            this.pressedId = e.type === TouchType.Down ? project.id : '';
          });
        } else {
          this.pressedId = e.type === TouchType.Down ? project.id : '';
        }
      })
      .enabled(!this.importing && !this.restoring)
      .accessibilityText(`${project.name}，${project.chapters.length} 章，点击继续创作，长按管理项目`)
      .onClick((): void => { this.openWorkspace(project.id, ''); })
      .gesture(
        LongPressGesture()
          .onAction((): void => { this.showRowMenu(project); })
      )
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
    .transition(TransitionEffect.OPACITY
      .combine(TransitionEffect.translate({ y: 14 }))
      .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut,
        delay: Math.min(idx * MOTION_STAGGER, 240) }))
  }

  // 项目行组(CardGroup 内容必须 @Builder:尾闭包后链 .margin 会被当新语句)
  @Builder
  ProjectRows(): void {
    ForEach(this.projects, (p: NovelProject, idx: number): void => {
      this.projectRow(p, idx === this.projects.length - 1, idx)
    }, (p: NovelProject): string => p.id)
  }

  @Builder
  FailedProjectRows(): void {
    ForEach(this.projectFailures, (failure: NovelProjectFailure): void => {
      Column({ space: 8 }) {
        Text(failure.name).fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
        Text(`读取失败：${failure.error}`).fontSize(13).fontColor(ERROR).width('100%')
        Row({ space: 12 }) {
          Text('重试读取').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
            .enabled(!this.restoring && !this.importing)
            .onClick((): void => { this.loadProjects().catch((e: Error): void => { this.errorMsg = String(e); }); })
          Text(this.restoring ? '检查中…' : '检查恢复来源').fontSize(14).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).enabled(!this.restoring && !this.importing)
            .onClick((): void => { this.inspectRecovery(failure); })
        }.width('100%')
      }.width('100%').alignItems(HorizontalAlign.Start).padding(16)
    }, (failure: NovelProjectFailure): string => failure.id)
  }

  @Builder
  RestorePreviewSheet(): void {
    Column() {
      Blank().onClick((): void => { if (!this.restoring && !this.importing) this.dismissRestorePreview(); })
      Column({ space: 12 }) {
        Text(this.restorePreviewTitle()).fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK).width('100%')
        Scroll() {
          Column({ space: 12 }) {
            Text(this.restorePreviewDetails()).fontSize(14).lineHeight(22).fontColor(INK2).width('100%')
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(13).fontColor(ERROR).width('100%')
            }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.width('100%').constraintSize({ maxHeight: '50%' }).scrollBar(BarState.Off)
        if (this.recoveryPreview !== null) {
          Text('只读查看旧稿').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
            .enabled(!this.restoring && !this.importing).onClick((): void => { this.openRecoveryText(); })
        }
        if (this.importHasCollision()) {
          Text('已存在同一项目。选择保留两份，或明确替换现有项目。').fontSize(13).fontColor(INK3).width('100%')
          Row({ space: 12 }) {
            Text('保留两份').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
              .constraintSize({ minHeight: 44 }).enabled(!this.restoring && !this.importing)
              .onClick((): void => { this.confirmRestorePreview('keepBoth'); })
            Text('替换现有项目').fontSize(14).fontColor(ERROR).layoutWeight(1).textAlign(TextAlign.Center)
              .constraintSize({ minHeight: 44 }).enabled(!this.restoring && !this.importing)
              .onClick((): void => { this.confirmRestorePreview('replace'); })
          }.width('100%')
        }
        Row({ space: 12 }) {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .constraintSize({ minHeight: 44 }).enabled(!this.restoring && !this.importing)
            .onClick((): void => { this.dismissRestorePreview(); })
          if (!this.importHasCollision()) {
            Text(this.restoring ? '恢复中…' : '确认').fontSize(14).fontColor(ACCENT).layoutWeight(1)
              .textAlign(TextAlign.Center).constraintSize({ minHeight: 44 }).enabled(!this.restoring && !this.importing)
              .onClick((): void => { this.confirmRestorePreview(); })
          }
        }.width('100%')
      }.width('100%').padding({ left: 20, right: 20, top: 20, bottom: 20 + this.navBarHeight })
        .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .alignItems(HorizontalAlign.Start).onClick((): void => {})
    }.width('100%').height('100%').backgroundColor(OVERLAY).justifyContent(FlexAlign.End)
  }

  // 列表尾注:恢复删除的项目(鸿蒙独有救命入口,iOS 无对等行)
  @Builder
  RestoreFooter(): void {
    Text(this.restoring ? '正在恢复…' : '恢复删除的项目')
      .fontSize(13)
      .fontFamily(FONT_SANS_MEDIUM)
      .fontColor(this.restoring ? INK4 : ACCENT)
      .constraintSize({ minHeight: 44 })
      .enabled(!this.restoring)
      .onClick((): void => { this.restoreDeleted(); })
      .margin({ top: 4 })
  }

  // 底部悬浮新建胶囊(iOS 列表页底中大按钮)
  @Builder
  Fab(): void {
    Column() {
      Blank()
      if (getProductKind() === 'novel' && (this.projects.length > 0 || this.projectFailures.length > 0)) {
        Column() {
          StudioButton({ label: '新建项目', iconName: 'plus', primary: true,
            disabled: this.importing || this.restoring || this.importPreview !== null || this.recoveryPreview !== null,
            onTap: (): void => { this.openCreateSheet(); } })
            .accessibilityText('新建小说项目')
        }.width('80%').constraintSize({ maxWidth: 240 })
          .margin({ bottom: this.modelSetupRequired ? 12 : 24 + Math.max(this.navBarHeight, 24) })
          .transition(TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ y: 10 }))
            .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      } else if (getProductKind() !== 'novel') {
        Row() {
          Icon({ name: 'plus', iconSize: 18, color: ACCENT_INK })
          Text('新建项目')
            .fontSize(15).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(ACCENT_INK).margin({ left: 6 })
        }
        .alignItems(VerticalAlign.Center)
        .constraintSize({ minHeight: 50 })
        .padding({ left: 24, right: 24 })
        .backgroundColor(ACCENT)
        .borderRadius(25)
        .shadow({ radius: 16, color: SHADOW_OVERLAY, offsetY: 6 })
        .margin({ bottom: 24 + Math.max(this.navBarHeight, 24) })
        .enabled(!this.importing && !this.restoring && this.importPreview === null && this.recoveryPreview === null)
        .onClick((): void => { this.openCreateSheet(); })
        .accessibilityText('新建小说项目')
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 10 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      }
      if (getProductKind() === 'novel' && this.modelSetupRequired) {
        Column() { this.ModelSetupPrompt() }.width('100%').constraintSize({ maxWidth: 480 })
          .padding({ left: 16, right: 16 }).margin({ bottom: 24 + Math.max(this.navBarHeight, 24) })
      }
    }
    .width('100%').height('100%')
    .alignItems(HorizontalAlign.Center)
    .hitTestBehavior(HitTestMode.Transparent)
  }

  // 创建 sheet:空白项目 / 快速开始(题材+核心想法;AI 只生成建议,确认后才写入设定)
  @Builder
  CreateSheet(): void {
    Column() {
      Blank().onClick((): void => { this.requestDismissCreateSheet(); })
      Column() {
        Text('新建小说')
          .fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          .width('100%').margin({ bottom: 12 })
        Seg({ options: ['空白项目', '快速开始'], selected: this.createMode,
          onSelect: (idx: number): void => {
            animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.createMode = idx;
            });
          } })
        Scroll() {
          Column({ space: 12 }) {
            TextInput({ text: this.createName, placeholder: '小说名称，例如：长夜来信' })
              .height(46).fontSize(TEXT_STYLES.body.size).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .margin({ top: 12 })
              .onChange((v: string) => { this.createName = v; })
            if (this.createMode === 1) {
              Column({ space: 12 }) {
                TextInput({ text: this.quickGenre, placeholder: '题材，例如：近未来悬疑' })
                  .height(46).fontSize(TEXT_STYLES.body.size).fontFamily(TEXT_STYLES.body.family)
                  .fontColor(INK).placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
                  .onChange((v: string) => { this.quickGenre = v; })
                TextArea({ text: this.quickIdea, placeholder: '核心想法：一句话讲清这个故事最想说的事…' })
                  .height(120).fontSize(14).fontColor(INK).placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10).padding(12)
                  .onChange((v: string) => { this.quickIdea = v; })
                TextArea({ text: this.quickWorld, placeholder: '世界背景：故事发生的时代、地点和规则' })
                  .height(88).fontSize(14).fontColor(INK).placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10).padding(12)
                  .onChange((value: string): void => { this.quickWorld = value; })
                TextArea({ text: this.quickCharacters, placeholder: '主要人物：身份、目标和人物关系' })
                  .height(88).fontSize(14).fontColor(INK).placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10).padding(12)
                  .onChange((value: string): void => { this.quickCharacters = value; })
                TextArea({ text: this.quickDirection, placeholder: '故事方向：冲突、走向与期望结局' })
                  .height(88).fontSize(14).fontColor(INK).placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10).padding(12)
                  .onChange((value: string): void => { this.quickDirection = value; })
                Text('你填写的种子会保存为项目资料。AI 生成的建议仍须确认后写入设定。')
                  .fontSize(12).fontColor(INK3).width('100%')
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            }
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(13).fontColor(ERROR).width('100%')
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start).clip(true)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.creating)
            .onClick((): void => { this.requestDismissCreateSheet(); })
          Text(this.creating ? '创建中…' : '创建')
            .fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.creating)
            .onClick((): void => { this.submitCreate(); })
        }
        .width('100%').margin({ top: 8 })
      }
      .width('100%').constraintSize({ maxHeight: '78%' })
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + this.navBarHeight })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        if (getProductKind() === 'novel') {
          this.StandaloneTitleBar()
        } else {
          CenteredTitleBar({ title: '小说创作', actionIcon: 'arrow_down_to_line',
            onAction: (): void => { this.importWorkspace(); } })
        }

        if (this.errorMsg.length > 0 && !this.renameDialogOpen && !this.createSheetOpen && this.importPreview === null && this.recoveryPreview === null) {
          Text(this.errorMsg)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(TEXT_STYLES.meta.family)
            .fontColor(ERROR)
            .padding({ left: 20, right: 20, bottom: 8 })
            .width('100%')
        }

        if (this.loading) {
          Column() {
            LoadingProgress().width(36).height(36).color(ACCENT)
            Text('加载中…')
              .fontSize(TEXT_STYLES.secondary.size)
              .fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3)
              .margin({ top: 12 })
          }
          .width('100%').padding({ top: 64, left: 18, right: 18 })
          .alignItems(HorizontalAlign.Start)
        } else if (this.projects.length === 0 && this.projectFailures.length === 0) {
          if (getProductKind() === 'novel') {
            this.StudioEmptyLibrary()
          } else {
            // 空态(iOS ContentUnavailableView):大图标座 + 标题 + 说明 + 新建/导入
            Column() {
              Row() {
                Icon({ name: 'book', iconSize: 30, color: ACCENT })
              }
              .width(76).height(76).borderRadius(38)
              .backgroundColor(ACCENT_LIGHT)
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)
              .margin({ bottom: 20 })
              Text('开始一本新小说')
                .fontSize(TITLE)
                .fontWeight(600)
                .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
                .fontFamily(FONT_SANS_SEMIBOLD)
                .fontColor(INK2)
              Text('项目会独立保存设定、正文、分支和创作记录。')
                .fontSize(TEXT_STYLES.secondary.size)
                .fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3)
                .margin({ top: 8 })
              Row({ space: 12 }) {
                BtnFill({ label: '新建项目', onTap: (): void => { this.openCreateSheet(); } })
                BtnGhost({ label: '导入项目', onTap: (): void => { this.importWorkspace(); } })
              }
              .margin({ top: 24 })
              .justifyContent(FlexAlign.Center)
              this.RestoreFooter()
            }
            .width('100%').layoutWeight(1)
            .justifyContent(FlexAlign.Center)
            .alignItems(HorizontalAlign.Center)
            .padding({ bottom: 96 })
          }
        } else {
          Scroll() {
            Column() {
              if (getProductKind() === 'novel') {
                Column() {
                  StudioHero({ kind: 'novel', title: '你的写作书房', caption: '故事在这里慢慢生长',
                    detail: this.projectStats(), compact: true, active: this.designVisible })
                }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 6 })
              }
              if (this.projects.length > 0) {
              PlainSection({ label: '项目' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ProjectRows(); } })
                .margin({ left: 16, right: 16 })
              }
              if (this.projectFailures.length > 0) {
                PlainSection({ label: '需要恢复的项目' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.FailedProjectRows(); } })
                  .margin({ left: 16, right: 16 })
              }
              Row() {
                Blank()
                this.RestoreFooter()
                Blank()
              }
              .width('100%')
              PageSpacer()
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
            .padding({ bottom: getProductKind() === 'novel'
              ? 96 + Math.max(this.navBarHeight, 24) + (this.modelSetupRequired ? 76 : 0) : 0 })
          }
          .layoutWeight(1)
          .align(Alignment.TopStart)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      if (!this.loading && !this.createSheetOpen &&
        (this.projects.length > 0 || this.projectFailures.length > 0 || this.modelSetupRequired) &&
        this.importPreview === null && this.recoveryPreview === null) {
        this.Fab()
      }

      if (this.importPreview !== null || this.recoveryPreview !== null) {
        this.RestorePreviewSheet()
      }

      // 创建 sheet(空白/快速开始)
      if (this.createSheetOpen) {
        this.CreateSheet()
      }

      // 重命名对话框叠层
      if (this.renameDialogOpen) {
        Column() {
          Column() {
            Text('重命名')
              .fontSize(TEXT_STYLES.screenTitle.size).fontWeight(TEXT_STYLES.screenTitle.weight)
              .lineHeight(TEXT_STYLES.screenTitle.lineHeight).fontFamily(TEXT_STYLES.screenTitle.family)
              .fontColor(INK).margin({ bottom: 16 })
            TextInput({ text: this.nameInput, placeholder: '例如：长夜来信' })
              .height(46).fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.nameInput = v; })
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(TEXT_STYLES.meta.family).fontColor(ERROR).margin({ top: 10 })
            }
            Row() {
              Text('取消').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => {
                  animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
                    this.renameDialogOpen = false; this.errorMsg = '';
                  });
                })
              Text('确定').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.submitRename(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(SURFACE).borderRadius(16)
          .shadow({ radius: 24, color: SHADOW_OVERLAY, offsetY: 8 })
          .onClick((): void => {})
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .transition(TransitionEffect.OPACITY
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        .onClick(() => {
          animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
            this.renameDialogOpen = false; this.errorMsg = '';
          });
        })
      }

      // 删除确认叠层
      if (this.confirmDeleteId.length > 0) {
        Column() {
          Column() {
            Text('删除项目？').fontSize(TEXT_STYLES.screenTitle.size).fontWeight(TEXT_STYLES.screenTitle.weight)
              .lineHeight(TEXT_STYLES.screenTitle.lineHeight).fontFamily(TEXT_STYLES.screenTitle.family)
              .fontColor(INK).margin({ bottom: 12 })
            Text('删除后可在本页「恢复删除的项目」找回。').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3)
            Row() {
              Text('取消').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => {
                  animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
                    this.confirmDeleteId = '';
                  });
                })
              Text('删除').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family).fontColor(ERROR).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.confirmDelete(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(SURFACE).borderRadius(16)
          .shadow({ radius: 24, color: SHADOW_OVERLAY, offsetY: 8 })
          .onClick((): void => {})
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .transition(TransitionEffect.OPACITY
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        .onClick(() => {
          animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
            this.confirmDeleteId = '';
          });
        })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}
