// NovelProjectsPage — 小说项目库:列表 / 新建 / 重命名 / 删除 / 进工作区
import { ACCENT, BG, INK, INK2, INK3, INK4, LINE, OVERLAY, SURFACE, SURFACE2, TITLE } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import type { NovelProject } from '@amber/deepread-domain';


@Entry
@Component
struct NovelProjectsPage {
  @State projects: NovelProject[] = [];
  @State loading: boolean = true;
  @State nameDialogOpen: boolean = false;
  @State editingId: string = '';   // '' = 新建
  @State nameInput: string = '';
  @State confirmDeleteId: string = '';
  @State errorMsg: string = '';
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
        this.projects = projects;
        this.errorMsg = '';
      }
    } finally {
      if (this.pageAlive && token === this.loadToken) this.loading = false;
    }
  }

  openNew(): void {
    this.editingId = '';
    this.nameInput = '';
    this.nameDialogOpen = true;
  }

  openRename(p: NovelProject): void {
    this.editingId = p.id;
    this.nameInput = p.name;
    this.nameDialogOpen = true;
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
        this.nameDialogOpen = false;
        this.openWorkspace(created.id);
      } else {
        await getNovelCreation().rename(this.editingId, name);
        this.nameDialogOpen = false;
        await this.loadProjects();
      }
      this.errorMsg = '';
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  async confirmDelete(): Promise<void> {
    if (this.confirmDeleteId.length === 0) return;
    await getNovelCreation().delete(this.confirmDeleteId);
    this.confirmDeleteId = '';
    await this.loadProjects();
  }

  openWorkspace(projectId: string): void {
    const params: Record<string, string> = { 'projectId': projectId };
    router.pushUrl({ url: 'pages/NovelWorkspacePage', params: params });
  }

  build(): void {
    Stack() {
      Column() {
        // 顶栏
        Row() {
          Icon({ name: 'back', iconSize: 20, color: INK }).onClick(() => { router.back(); })
          Text('小说创作').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
            .fontFamily('serif').margin({ left: 12 })
          Blank()
          Text('新建').fontSize(13).fontColor(ACCENT)
            .onClick(() => { this.openNew(); })
        }
        .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 })
        .backgroundColor(BG)

        if (this.loading) {
          Column() {
            LoadingProgress().width(36).height(36).color(ACCENT)
            Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 12 })
          }
          .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.projects.length === 0) {
          Column() {
            Text('开始你的第一部小说').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK2)
            Text('建好项目后，在聊天区告诉 Amber 你想写什么。')
              .fontSize(13).fontColor(INK3).margin({ top: 8 })
            Text('新建项目').fontSize(14).fontColor('#FFFFFF')
              .padding({ left: 24, right: 24, top: 10, bottom: 10 }).borderRadius(20)
              .backgroundColor(ACCENT).margin({ top: 24 })
              .onClick(() => { this.openNew(); })
          }
          .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else {
          List({ space: 0 }) {
            ForEach(this.projects, (p: NovelProject) => {
              ListItem() {
                Row() {
                  Column() {
                    Text(p.name).fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
                      .fontFamily('serif').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${p.chapters.length} 章 · ${p.materials.length} 条资料 · ${p.messages.length} 条对话`)
                      .fontSize(12).fontColor(INK3).margin({ top: 6 })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  Text('重命名').fontSize(12).fontColor(INK3).margin({ right: 16 })
                    .onClick(() => { this.openRename(p); })
                  Text('删除').fontSize(12).fontColor('#E53935')
                    .onClick(() => { this.confirmDeleteId = p.id; })
                }
                .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
                .border({ width: 1, color: LINE }).margin({ bottom: 14 })
                .onClick(() => { this.openWorkspace(p.id); })
              }
            }, (p: NovelProject) => p.id)
          }
          .width('100%').layoutWeight(1).padding({ left: 20, right: 20, top: 8 })
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      // 名称对话框叠层
      if (this.nameDialogOpen) {
        Column() {
          Column() {
            Text(this.editingId.length === 0 ? '新建小说' : '重命名')
              .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
            TextInput({ text: this.nameInput, placeholder: '例如：长夜来信' })
              .height(46).fontSize(15).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.nameInput = v; })
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(12).fontColor('#E53935').margin({ top: 10 })
            }
            Row() {
              Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.nameDialogOpen = false; this.errorMsg = ''; })
              Text('确定').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.submitName(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(SURFACE).borderRadius(16)
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .onClick(() => { this.nameDialogOpen = false; this.errorMsg = ''; })
      }

      // 删除确认叠层
      if (this.confirmDeleteId.length > 0) {
        Column() {
          Column() {
            Text('删除项目？').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 12 })
            Text('此操作不可撤销。').fontSize(13).fontColor(INK3)
            Row() {
              Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.confirmDeleteId = ''; })
              Text('删除').fontSize(14).fontColor('#E53935').layoutWeight(1).textAlign(TextAlign.Center)
                .onClick(() => { this.confirmDelete(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(SURFACE).borderRadius(16)
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .onClick(() => { this.confirmDeleteId = ''; })
      }
    }
    .width('100%').height('100%')
  }
}
