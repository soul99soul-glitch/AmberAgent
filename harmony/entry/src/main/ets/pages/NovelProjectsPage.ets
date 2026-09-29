// NovelProjectsPage — 小说项目库(iOS NovelProjectListView 结构:右上导入 +
// 底部悬浮新建胶囊 + 创建 sheet(空白/快速开始)+ 空态双按钮)
// 行操作收进长按菜单(对齐会话首页交互);恢复删除项目为列表尾注链接。
import { ACCENT, ACCENT_INK, ACCENT_LIGHT, BG, INK, INK2, INK3, INK4, ERROR, MOTION_ENTER, MOTION_OVERLAY, MOTION_STAGGER, OVERLAY, SHADOW_OVERLAY, SURFACE, SURFACE2, TITLE, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SERIF, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, BtnFill, BtnGhost, Seg, IconSeat, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction, KeyboardAvoidMode } from '@kit.ArkUI';
import { getNovelCreation, getNovelWorkspaceService } from '../di/AppContainer.ets';
import type { NovelProject } from '@amber/deepread-domain';
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
  @State loading: boolean = true;
  @State renameDialogOpen: boolean = false;
  @State renameId: string = '';
  @State nameInput: string = '';
  @State confirmDeleteId: string = '';
  @State restoring: boolean = false;
  @State importing: boolean = false;
  @State errorMsg: string = '';
  @State pressedId: string = '';
  // 创建 sheet(iOS NovelProjectCreateSheet):空白项目 / 快速开始(题材+核心想法)
  @State createSheetOpen: boolean = false;
  @State createMode: number = 0;
  @State createName: string = '';
  @State quickGenre: string = '';
  @State quickIdea: string = '';
  @State creating: boolean = false;
  private pageAlive: boolean = false;
  private loadToken: number = 0;
  // F41:仅首次加载展示整页 loading;后续刷新保留既有列表(不卸载已读内容)
  private loadedOnce: boolean = false;
  // aboutToAppear 与首次 onPageShow 都会触发,首屏只加载一次
  private firstShowDone: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.loadProjects().catch((e: Error): void => {
      if (this.pageAlive) this.errorMsg = String(e);
    });
  }

  onPageShow(): void {
    if (this.firstShowDone) {
      this.loadProjects().catch((e: Error): void => {
        if (this.pageAlive) this.errorMsg = String(e);
      });
    } else {
      this.firstShowDone = true;
    }
  }

  onPageHide(): void { this.restoreKeyboardAvoidance(); }

  aboutToDisappear(): void {
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.loadToken++;
  }

  onBackPress(): boolean {
    if (this.createSheetOpen) {
      if (!this.creating) this.dismissCreateSheet();
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
      const projects: NovelProject[] = await getNovelCreation().listProjects();
      if (this.pageAlive && token === this.loadToken) {
        // 首次填充走 animateTo,让行交错入场 transition 生效
        if (firstLoad && projects.length > 0) {
          animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
            this.projects = projects;
          });
        } else {
          this.projects = projects;
        }
        this.loadedOnce = true;
        this.errorMsg = '';
      }
    } finally {
      if (this.pageAlive && token === this.loadToken) this.loading = false;
    }
  }

  openCreateSheet(): void {
    this.createMode = 0;
    this.createName = '';
    this.quickGenre = '';
    this.quickIdea = '';
    this.errorMsg = '';
    this.enableKeyboardAvoidance();
    this.createSheetOpen = true;
  }

  private dismissCreateSheet(): void {
    this.createSheetOpen = false;
    this.errorMsg = '';
    this.restoreKeyboardAvoidance();
  }

  openRename(p: NovelProject): void {
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
      const created: NovelProject = await getNovelCreation().create(name);
      // 快速开始:题材+核心想法拼成种子,进工作区自动发起 quick_start
      const seed: string = this.createMode === 1
        ? `题材：${this.quickGenre.trim()}\n核心想法：${this.quickIdea.trim()}` : '';
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
    if (this.restoring) return;
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
    if (this.importing) return;
    const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    options.fileSuffixFilters = ['小说工作区 ZIP|.zip'];
    const context: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    try {
      const uris: string[] = await documentPicker.select(options);
      if (uris.length === 0) return;
      this.importing = true;
      const project: NovelProject = await getNovelWorkspaceService().importZip(
        this.readSelectedBytes(uris[0]));
      await this.loadProjects();
      this.openWorkspace(project.id, '');
    } catch (e) {
      this.errorMsg = `导入失败：${String(e)}`;
    } finally {
      this.importing = false;
    }
  }

  openWorkspace(projectId: string, quickStartSeed: string): void {
    const params: Record<string, string> = { 'projectId': projectId };
    if (quickStartSeed.length > 0) params['quickStartSeed'] = quickStartSeed;
    router.pushUrl({ url: 'pages/NovelWorkspacePage', params: params });
  }

  // 行操作收进长按菜单(对齐会话首页交互),列表不再常驻两个盒按钮
  private showRowMenu(p: NovelProject): void {
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
            .fontFamily(FONT_SERIF)
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
        Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressedId === project.id ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressedId = project.id;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressedId = '';
      })
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
      .onClick((): void => { this.openCreateSheet(); })
      .accessibilityText('新建小说项目')
      .transition(TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: 10 }))
        .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%')
    .alignItems(HorizontalAlign.Center)
    .hitTestBehavior(HitTestMode.Transparent)
  }

  // 创建 sheet:空白项目 / 快速开始(题材+核心想法;AI 只生成建议,确认后才写入设定)
  @Builder
  CreateSheet(): void {
    Column() {
      Blank().onClick((): void => { if (!this.creating) this.dismissCreateSheet(); })
      Column() {
        Text('新建小说')
          .fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          .width('100%').margin({ bottom: 12 })
        Seg({ options: ['空白项目', '快速开始'], selected: this.createMode,
          onSelect: (idx: number): void => { this.createMode = idx; } })
        Scroll() {
          Column({ space: 12 }) {
            TextInput({ text: this.createName, placeholder: '小说名称，例如：长夜来信' })
              .height(46).fontSize(TEXT_STYLES.body.size).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .margin({ top: 12 })
              .onChange((v: string) => { this.createName = v; })
            if (this.createMode === 1) {
              TextInput({ text: this.quickGenre, placeholder: '题材，例如：近未来悬疑' })
                .height(46).fontSize(TEXT_STYLES.body.size).fontFamily(TEXT_STYLES.body.family)
                .fontColor(INK).placeholderColor(INK4)
                .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
                .onChange((v: string) => { this.quickGenre = v; })
              TextArea({ text: this.quickIdea, placeholder: '核心想法：一句话讲清这个故事最想说的事…' })
                .height(120).fontSize(14).fontColor(INK).placeholderColor(INK4)
                .backgroundColor(SURFACE2).borderRadius(10).padding(12)
                .onChange((v: string) => { this.quickIdea = v; })
              Text('AI 只会生成建议，确认后才写入项目设定。')
                .fontSize(12).fontColor(INK3).width('100%')
            }
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(13).fontColor(ERROR).width('100%')
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.creating)
            .onClick((): void => { this.dismissCreateSheet(); })
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
        CenteredTitleBar({ title: '小说创作', actionIcon: 'arrow_down_to_line',
          onAction: (): void => { this.importWorkspace(); } })

        if (this.errorMsg.length > 0 && !this.renameDialogOpen && !this.createSheetOpen) {
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
        } else if (this.projects.length === 0) {
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
        } else {
          Scroll() {
            Column() {
              PlainSection({ label: '项目' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ProjectRows(); } })
                .margin({ left: 16, right: 16 })
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
          }
          .layoutWeight(1)
          .align(Alignment.TopStart)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      if (!this.loading && !this.createSheetOpen) {
        this.Fab()
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
