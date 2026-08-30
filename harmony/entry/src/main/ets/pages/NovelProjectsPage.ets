// NovelProjectsPage — 小说项目库:列表 / 新建 / 重命名 / 删除 / 进工作区
import { ACCENT, ACCENT_LIGHT, BG, INK, INK2, INK3, INK4, ERROR, MOTION_ENTER, MOTION_OVERLAY, MOTION_STAGGER, OVERLAY, SHADOW_OVERLAY, SURFACE, SURFACE2, TITLE, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SERIF, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, BtnFill, IconSeat, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import type { NovelProject } from '@amber/deepread-domain';

interface RowMenuResult {
  index: number;
}

@Entry
@Component
struct NovelProjectsPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State projects: NovelProject[] = [];
  @State loading: boolean = true;
  @State nameDialogOpen: boolean = false;
  @State editingId: string = '';   // '' = 新建
  @State nameInput: string = '';
  @State confirmDeleteId: string = '';
  @State restoring: boolean = false;
  @State errorMsg: string = '';
  @State pressedId: string = '';
  private pageAlive: boolean = false;
  private loadToken: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.loadProjects().catch((e: Error): void => {
      if (this.pageAlive) this.errorMsg = String(e);
    });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.loadToken++;
  }

  async loadProjects(): Promise<void> {
    const token: number = ++this.loadToken;
    if (this.pageAlive) this.loading = true;
    try {
      const projects: NovelProject[] = await getNovelCreation().listProjects();
      if (this.pageAlive && token === this.loadToken) {
        // 首次填充走 animateTo,让行交错入场 transition 生效
        if (this.loading && projects.length > 0) {
          animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
            this.projects = projects;
          });
        } else {
          this.projects = projects;
        }
        this.errorMsg = '';
      }
    } finally {
      if (this.pageAlive && token === this.loadToken) this.loading = false;
    }
  }

  openNew(): void {
    this.editingId = '';
    this.nameInput = '';
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.nameDialogOpen = true;
    });
  }

  openRename(p: NovelProject): void {
    this.editingId = p.id;
    this.nameInput = p.name;
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.nameDialogOpen = true;
    });
  }

  async submitName(): Promise<void> {
    const name: string = this.nameInput.trim();
    if (name.length === 0) {
      this.errorMsg = '请输入名称';
      return;
    }
    try {
      if (this.editingId.length === 0) {
        const created: NovelProject = await getNovelCreation().create(name);
        animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => { this.nameDialogOpen = false; });
        this.openWorkspace(created.id);
      } else {
        await getNovelCreation().rename(this.editingId, name);
        animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => { this.nameDialogOpen = false; });
        await this.loadProjects();
      }
      this.errorMsg = '';
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

  openWorkspace(projectId: string): void {
    const params: Record<string, string> = { 'projectId': projectId };
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
          Text(`${project.chapters.length} 章 · ${project.materials.length} 条资料 · ${project.messages.length} 条对话`)
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
      .onClick((): void => { this.openWorkspace(project.id); })
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

  build(): void {
    Stack() {
      Column() {
        PageTitleBar({ title: '小说创作', actionText: '新建', onAction: (): void => { this.openNew(); } })

        // N3:恢复最近删除的项目
        Row() {
          Blank()
          Text('恢复删除的项目').fontSize(12).fontColor(INK3)
            .padding({ left: 12, right: 20, top: 6, bottom: 6 })
            .onClick((): void => { this.restoreDeleted(); })
        }
        .width('100%')

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
          Column() {
            // 空态图标座:accent 淡底圆座,避免纯文字荒芜感
            Row() {
              Icon({ name: 'book', iconSize: 30, color: ACCENT })
            }
            .width(76).height(76).borderRadius(38)
            .backgroundColor(ACCENT_LIGHT)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .margin({ bottom: 20 })
            Text('开始你的第一部小说')
              .fontSize(TITLE)
              .fontWeight(600)
              .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(INK2)
            Text('建好项目后，在聊天区告诉 Amber 你想写什么。')
              .fontSize(TEXT_STYLES.secondary.size)
              .fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(INK3)
              .margin({ top: 8 })
            Row() {
              BtnFill({ label: '新建项目', onTap: (): void => { this.openNew(); } })
            }
            .margin({ top: 24 })
          }
          .width('100%').padding({ top: 64, left: 18, right: 18 })
          .alignItems(HorizontalAlign.Start)
        } else {
          Scroll() {
            Column() {
              SectionHeader({ label: '项目' })
              ForEach(this.projects, (p: NovelProject, idx: number): void => {
                this.projectRow(p, idx === this.projects.length - 1, idx)
              }, (p: NovelProject): string => p.id)
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

      // 名称对话框叠层
      if (this.nameDialogOpen) {
        Column() {
          Column() {
            Text(this.editingId.length === 0 ? '新建小说' : '重命名')
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
                    this.nameDialogOpen = false; this.errorMsg = '';
                  });
                })
              Text('确定').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.submitName(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(SURFACE).borderRadius(16)
          .shadow({ radius: 24, color: SHADOW_OVERLAY, offsetY: 8 })
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .transition(TransitionEffect.OPACITY
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        .onClick(() => {
          animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
            this.nameDialogOpen = false; this.errorMsg = '';
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
            Text('此操作不可撤销.').fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
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
    }
    .width('100%').height('100%')
  }
}
