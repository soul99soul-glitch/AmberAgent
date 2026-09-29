// NovelChapterReaderPage — 章节阅读器(iOS NovelChapterReaderView 结构):
// push 独立页:principal(标题+第 N 章·字数)+ ⋯菜单(版本历史/编辑/整章润色/
// 整章重新生成/废弃/删除)+ Markdown 正文 + 底部上一章/下一章胶囊。
// 整章润色/重生成属工作区聊天流任务:此处触发后退回工作区由 reload 重挂进度。
import { router, promptAction, KeyboardAvoidMode } from '@kit.ArkUI';
import {
  ACCENT, BG, ERROR, INK, INK3, INK4, LINE, MOTION_ENTER, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2,
  WARNING_FG,
} from '../design/tokens.ets';
import { FONT_SERIF, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { getNovelCreation } from '../di/AppContainer.ets';
import {
  NovelChapter, NovelChapterVersion, NovelChapterVersionKind, NovelProject,
} from '@amber/deepread-domain';

interface ReaderParams {
  projectId?: string;
  chapterId?: string;
}

const charCountOf = (s: string): number => s.replace(/\s/g, '').length;

@Entry
@Component
struct NovelChapterReaderPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State chapterId: string = '';
  @State loaded: boolean = false;
  @State errorMsg: string = '';
  @State notice: string = '';
  @State busy: boolean = false;
  @State menuOpen: boolean = false;
  @State versionsOpen: boolean = false;
  @State editOpen: boolean = false;
  @State editTitle: string = '';
  @State editContent: string = '';
  private editOriginalTitle: string = '';
  private editOriginalContent: string = '';
  private projectId: string = '';
  private pageAlive: boolean = false;

  aboutToAppear(): void {
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.pageAlive = true;
    const params: ReaderParams = router.getParams() as ReaderParams;
    this.projectId = params?.projectId ?? '';
    this.chapterId = params?.chapterId ?? '';
    this.reload();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.OFFSET);
  }

  onBackPress(): boolean {
    if (this.editOpen) {
      this.confirmDiscardEdit();
      return true;
    }
    if (this.versionsOpen) {
      this.versionsOpen = false;
      return true;
    }
    if (this.menuOpen) {
      this.menuOpen = false;
      return true;
    }
    return false;
  }

  private async reload(): Promise<void> {
    try {
      const project: NovelProject = await getNovelCreation().open(this.projectId);
      if (!this.pageAlive) return;
      this.project = project;
      this.loaded = true;
      this.errorMsg = '';
    } catch (e) {
      if (this.pageAlive) {
        this.loaded = true;
        this.errorMsg = `项目无法打开：${String(e)}`;
      }
    }
  }

  private chapterIndex(): number {
    const p: NovelProject | null = this.project;
    if (p === null) return -1;
    return p.chapters.findIndex((c: NovelChapter): boolean => c.id === this.chapterId);
  }

  private chapter(): NovelChapter | null {
    const idx: number = this.chapterIndex();
    return idx < 0 ? null : (this.project?.chapters[idx] ?? null);
  }

  private jumpTo(idx: number): void {
    const p: NovelProject | null = this.project;
    if (p === null || idx < 0 || idx >= p.chapters.length) return;
    animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
      this.chapterId = p.chapters[idx].id;
      this.notice = '';
    });
  }

  private versionKindLabel(kind: NovelChapterVersionKind): string {
    if (kind === 'manual') return '手动保存';
    if (kind === 'polish') return '润色前';
    if (kind === 'regenerate') return '重生成前';
    return '收录';
  }

  private versionTime(ts: number): string {
    const d: Date = new Date(ts);
    const pad = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  private chapterVersions(): NovelChapterVersion[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.chapterVersions.filter((v: NovelChapterVersion): boolean => v.chapterId === this.chapterId)
      .slice().reverse();
  }

  // 触发整章任务(润色/重生成):任务进度挂在工作区创作流,触发后退回
  private async startChapterTask(kind: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      if (kind === 'polish') await getNovelCreation().polishChapter(this.projectId, this.chapterId);
      else await getNovelCreation().regenerateChapter(this.projectId, this.chapterId);
      promptAction.showToast({
        message: kind === 'polish' ? '已开始润色，进度见创作页' : '已开始重新生成，进度见创作页',
        duration: 1800,
      });
      router.back();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private editDirty(): boolean {
    return this.editTitle !== this.editOriginalTitle || this.editContent !== this.editOriginalContent;
  }

  private openEdit(): void {
    const c: NovelChapter | null = this.chapter();
    if (c === null) return;
    this.editTitle = c.title;
    this.editContent = c.content;
    this.editOriginalTitle = c.title;
    this.editOriginalContent = c.content;
    this.editOpen = true;
  }

  private confirmDiscardEdit(): void {
    if (!this.editDirty()) {
      this.editOpen = false;
      return;
    }
    AlertDialog.show({
      title: '放弃编辑？',
      message: '未保存的章节内容将丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: { value: '放弃', fontColor: ERROR, action: (): void => { this.editOpen = false; } },
    });
  }

  private async saveEdit(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().saveChapter(this.projectId, this.chapterId, this.editTitle, this.editContent);
      this.editOpen = false;
      this.notice = '已保存';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private async toggleDiscarded(): Promise<void> {
    const c: NovelChapter | null = this.chapter();
    if (c === null || this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().setChapterDiscarded(this.projectId, c.id, !c.discarded);
      this.notice = c.discarded ? '已恢复本章' : '已废弃本章';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private confirmDelete(): void {
    AlertDialog.show({
      title: '删除本章？',
      message: '删除不可恢复；如果只是想让它不参与剧情，请用「废弃本章」。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => { this.deleteChapter(); },
      },
    });
  }

  private async deleteChapter(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().deleteChapter(this.projectId, this.chapterId);
      router.back();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private async restoreVersion(versionId: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().restoreChapterVersion(this.projectId, versionId);
      this.versionsOpen = false;
      this.notice = '已恢复该版本为当前正文';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ⋯菜单行(含阻塞原因;iOS 禁用项标题附理由)
  @Builder
  private MenuRow(label: string, reason: string, danger: boolean, isLast: boolean,
    action: () => void): void {
    Column() {
      Row() {
        Text(reason.length > 0 ? `${label}（${reason}）` : label)
          .fontSize(15).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(reason.length > 0 ? INK4 : (danger ? ERROR : INK))
      }
      .width('100%').constraintSize({ minHeight: 48 })
      .padding({ left: 18, right: 18 })
      .enabled(reason.length === 0 && !this.busy)
      .onClick((): void => {
        this.menuOpen = false;
        action();
      })
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }.width('100%').padding({ left: 18, right: 18 })
      }
    }.width('100%')
  }

  @Builder
  private MenuSheet(): void {
    Column() {
      Blank().onClick((): void => { this.menuOpen = false; })
      Column() {
        this.MenuRow('版本历史', '', false, false, (): void => { this.versionsOpen = true; })
        this.MenuRow('编辑本章', '', false, false, (): void => { this.openEdit(); })
        this.MenuRow('整章润色', this.busy ? '任务进行中' : '', false, false,
          (): void => { this.startChapterTask('polish'); })
        this.MenuRow('整章重新生成', this.busy ? '任务进行中' : '', false, false,
          (): void => { this.startChapterTask('regenerate'); })
        this.MenuRow(this.chapter()?.discarded ? '恢复本章' : '废弃本章', '', false, false,
          (): void => { this.toggleDiscarded(); })
        this.MenuRow('删除本章', '', true, true, (): void => { this.confirmDelete(); })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .padding({ top: 10, bottom: 16 + this.navBarHeight })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private VersionsSheet(): void {
    Column() {
      Blank().onClick((): void => { this.versionsOpen = false; })
      Column() {
        Text('版本历史').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK).width('100%').padding({ left: 18, right: 18, top: 16, bottom: 8 })
        Scroll() {
          Column() {
            if (this.chapterVersions().length === 0) {
              Text('暂无历史版本').fontSize(13).fontColor(INK3)
                .width('100%').padding({ left: 18, right: 18, top: 12, bottom: 20 })
            }
            ForEach(this.chapterVersions(), (v: NovelChapterVersion, idx: number): void => {
              Row() {
                Column({ space: 2 }) {
                  Text(this.versionKindLabel(v.kind)).fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
                  Text(this.versionTime(v.createdAt)).fontSize(11).fontColor(INK3)
                }.alignItems(HorizontalAlign.Start).layoutWeight(1)
                Text('恢复').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
                  .fontColor(this.busy ? INK4 : ACCENT)
                  .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
                  .enabled(!this.busy)
                  .onClick((): void => { this.restoreVersion(v.id); })
              }
              .width('100%').padding({ left: 18, right: 18, top: 10, bottom: 10 })
              if (idx < this.chapterVersions().length - 1) {
                Row() {
                  Row().width('100%').height(1).backgroundColor(LINE)
                }.width('100%').padding({ left: 18, right: 18 })
              }
            }, (v: NovelChapterVersion): string => v.id)
          }.width('100%')
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
      }
      .width('100%').height('60%')
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .padding({ bottom: this.navBarHeight })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private EditSheet(): void {
    Column() {
      Blank().onClick((): void => { this.confirmDiscardEdit(); })
      Column() {
        Text('编辑本章').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK).width('100%').margin({ bottom: 12 })
        TextInput({ text: this.editTitle, placeholder: '章节标题' })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.editTitle = v; })
        TextArea({ text: this.editContent, placeholder: '章节正文' })
          .width('100%').layoutWeight(1).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(12).padding(12).margin({ top: 12 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.editContent = v; })
        Text(`${charCountOf(this.editContent)} 字`).fontSize(11).fontColor(INK3)
          .width('100%').margin({ top: 6 })
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.confirmDiscardEdit(); })
          Text(this.busy ? '保存中…' : '保存').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.saveEdit(); })
        }.width('100%').margin({ top: 8 })
      }
      .width('100%').height('85%')
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

  // 底部上一章/下一章胶囊(iOS 阅读器底部导航)
  @Builder
  private PrevNextBar(): void {
    Row() {
      Row() {
        Icon({ name: 'chevron_left', iconSize: 15, color: this.chapterIndex() > 0 ? INK : INK4 })
        Text('上一章').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.chapterIndex() > 0 ? INK : INK4).margin({ left: 4 })
      }
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 44 })
      .padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).border({ width: 0.5, color: LINE }).borderRadius(22)
      .opacity(this.chapterIndex() > 0 ? 1 : 0.45)
      .enabled(this.chapterIndex() > 0)
      .onClick((): void => { this.jumpTo(this.chapterIndex() - 1); })
      Blank()
      Row() {
        Text('下一章').fontSize(13).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.hasNext() ? INK : INK4).margin({ right: 4 })
        Icon({ name: 'chevron_right', iconSize: 15, color: this.hasNext() ? INK : INK4 })
      }
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 44 })
      .padding({ left: 16, right: 16 })
      .backgroundColor(SURFACE).border({ width: 0.5, color: LINE }).borderRadius(22)
      .opacity(this.hasNext() ? 1 : 0.45)
      .enabled(this.hasNext())
      .onClick((): void => { this.jumpTo(this.chapterIndex() + 1); })
    }
    .width('100%')
    .padding({ left: 20, right: 20, top: 8, bottom: 10 + Math.max(this.navBarHeight, 24) })
    .backgroundColor(BG)
  }

  private hasNext(): boolean {
    const p: NovelProject | null = this.project;
    const idx: number = this.chapterIndex();
    return p !== null && idx >= 0 && idx < p.chapters.length - 1;
  }

  build(): void {
    Stack() {
      Column() {
        CenteredTitleBar({
          title: (this.chapter()?.title ?? '').length > 0 ? this.chapter()?.title ?? '' : '未命名章节',
          subtitle: this.chapterIndex() >= 0
            ? `第 ${this.chapterIndex() + 1} 章 · ${charCountOf(this.chapter()?.content ?? '')} 字` : '',
          actionIcon: 'more',
          onAction: (): void => { this.menuOpen = true; },
        })
        if (this.errorMsg.length > 0) {
          Text(this.errorMsg).fontSize(12).fontColor(ERROR).lineHeight(18)
            .width('100%').padding({ left: 20, right: 20, bottom: 8 })
        }
        if (this.notice.length > 0) {
          Text(this.notice).fontSize(12).fontColor(INK3).lineHeight(18)
            .width('100%').padding({ left: 20, right: 20, bottom: 8 })
        }
        if (!this.loaded) {
          Column() {
            LoadingProgress().width(36).height(36).color(ACCENT)
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.chapter() === null) {
          Column() {
            Text('章节不存在或已删除。').fontSize(15).fontColor(INK3)
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else {
          if (this.chapter()?.discarded === true) {
            Text('已废弃 · 不参与剧情连贯与导出').fontSize(12).fontColor(WARNING_FG)
              .width('100%').padding({ left: 20, right: 20, bottom: 8 })
          }
          Scroll() {
            Column() {
              // 章节切换重挂载(与主题纪元同款):chapterId 变 → 内容销毁重建,
              // 进场透明+轻位移,换章不生硬跳变
              ForEach([this.chapterId], (id: string): void => {
                Column() {
                  MarkdownText({
                    content: (this.chapter()?.content ?? '').length > 0
                      ? this.chapter()?.content ?? '' : '这一章还没有正文。',
                    fontColor: INK, readingMode: true, enableLatexRendering: false,
                    codeBlockAutoCollapse: true,
                  })
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                .transition(TransitionEffect.OPACITY
                  .combine(TransitionEffect.translate({ y: 8 }))
                  .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
              }, (id: string): string => id)
            }
            .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 24 })
            .alignItems(HorizontalAlign.Start)
          }
          .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
          this.PrevNextBar()
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      if (this.menuOpen) {
        this.MenuSheet()
      }
      if (this.versionsOpen) {
        this.VersionsSheet()
      }
      if (this.editOpen) {
        this.EditSheet()
      }
    }
    .width('100%').height('100%')
  }
}
