import { router } from '@kit.ArkUI';
import type { MemoryDerivedDocument } from '@amber/chat-domain';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';

interface MemoryDocumentParams { relativePath?: string; }

@Entry
@Component
struct MemoryDocumentPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State documents: MemoryDerivedDocument[] = [];
  @State selected: MemoryDerivedDocument | null = null;
  @State content: string | null = null;
  @State loaded: boolean = false;
  @State syncing: boolean = false;
  @State rawExpanded: boolean = false;
  @State error: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  private relativePath: string = '';
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    const params: MemoryDocumentParams = router.getParams() as MemoryDocumentParams;
    this.relativePath = params?.relativePath ?? '';
    this.alive = true; this.visible = true; this.reload();
  }
  onPageShow(): void { if (!this.visible) { this.visible = true; this.reload(); } }
  onPageHide(): void { this.visible = false; this.loadSeq++; }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void { if (this.alive && this.visible && !this.syncing) this.reload(); }

  private async reload(): Promise<void> {
    const seq: number = ++this.loadSeq;
    try {
      const documents: MemoryDerivedDocument[] = await getMemoryTopicStoreOps().listDocuments();
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.documents = documents;
      this.selected = this.relativePath.length === 0 ? null : documents.find(
        (item: MemoryDerivedDocument): boolean => item.relativePath === this.relativePath) ?? null;
      this.error = '';
      if (this.relativePath.length === 0 || this.selected === null) {
        this.content = null; this.loaded = true; return;
      }
      const content: string | null = await getMemoryTopicStoreOps().readDocument(this.selected.relativePath);
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.content = content; this.loaded = true;
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
      }
    }
  }

  private async sync(): Promise<void> {
    if (this.syncing) return;
    this.syncing = true;
    let failure: string = '';
    try { await getMemoryTopicStoreOps().syncDocuments(); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    await this.reload();
    if (this.alive) { this.syncing = false; if (failure.length > 0) this.error = failure; }
  }

  private openDocument(path: string): void {
    router.pushUrl({ url: 'pages/MemoryDocumentPage', params: { relativePath: path } });
  }

  private renderedContent(): string {
    // Generated local links navigate through real descriptors below; never hand a
    // topics/*.md path to the shared Markdown renderer's external link action.
    return (this.content ?? '').replace(/\[([^\]]+)\]\((?:topics\/[^)\s]+\.md|index\.md)\)/g, '$1');
  }

  private visibleDocuments(): MemoryDerivedDocument[] {
    return this.documents.filter(
      (item: MemoryDerivedDocument): boolean => item.relativePath !== this.relativePath);
  }

  // 文档行组:白卡内行式条目(标题/预览/体积·时间 + 箭头),发丝线内缩
  @Builder
  private DocumentRows(): void {
    Column() {
      ForEach(this.visibleDocuments(), (item: MemoryDerivedDocument, idx: number): void => {
        Row({ space: 10 }) {
          Column({ space: 6 }) {
            Text(item.title).fontSize(15).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.colors.ink).width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(item.preview).fontSize(13).fontColor(this.colors.secondary).width('100%')
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(`${item.sizeBytes} bytes · ${new Date(item.modifiedAt).toLocaleString()}`)
              .fontSize(12).fontFamily(FONT_MONO_MEDIUM).fontColor(this.colors.muted).width('100%')
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
        }
        .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .onClick((): void => { this.openDocument(item.relativePath); })
        if (idx < this.visibleDocuments().length - 1) {
          Row() {
            Row().width('100%').height(1).backgroundColor(this.colors.line)
          }.width('100%').padding({ left: 14, right: 14 })
        }
      }, (item: MemoryDerivedDocument): string => `${item.relativePath}-${item.modifiedAt}-${item.sizeBytes}`)
    }.width('100%')
  }

  // 详情头卡:标题/路径(mono 可复制)/只读说明 + 主题导航(文本动作)
  @Builder
  private DocumentHeaderCard(): void {
    Column({ space: 8 }) {
      Text(this.selected?.title ?? '').fontSize(18).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL)
      Text(this.relativePath).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
        .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      Text('由真实源记忆派生，只读。源记忆可在主题成员中编辑。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      if (this.selected?.topicId !== null && this.selected !== null) {
        Row() {
          Text('查看主题和源成员').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.accentColor).constraintSize({ minHeight: 44 })
            .onClick((): void => { router.pushUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: this.selected?.topicId?.toString() ?? '' } }); })
          Blank()
        }.width('100%').margin({ top: 2 })
      }
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 10 })
  }

  // 正文卡:Markdown 渲染 + 底部「Markdown 原文」展开行(mono 可复制)
  @Builder
  private DocumentBodyCard(): void {
    Column() {
      Column() {
        MarkdownText({ content: this.renderedContent(), fontColor: this.colors.ink, readingMode: true,
          enableLatexRendering: false, codeBlockAutoCollapse: true })
      }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
      Row() {
        Row().width('100%').height(1).backgroundColor(this.colors.line)
      }.width('100%').padding({ left: 14, right: 14 })
      Row({ space: 12 }) {
        Text('Markdown 原文').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.colors.ink).layoutWeight(1)
        Text(this.rawExpanded ? '收起' : '查看 / 复制').fontSize(12).fontFamily(FONT_MONO_MEDIUM).fontColor(this.colors.muted)
        Icon({ name: 'chevron_down', iconSize: 16, color: this.colors.muted })
          .rotate({ angle: this.rawExpanded ? 180 : 0 })
      }
      .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.rawExpanded = !this.rawExpanded;
        });
      })
      if (this.rawExpanded) {
        Text(this.content ?? '').fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          .padding({ left: 14, right: 14, bottom: 14 })
          .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }.width('100%')
  }

  // 操作卡:重新生成(动作行,无箭头)
  @Builder
  private SyncCard(): void {
    CardRow({ label: this.syncing ? '正在重新生成…' : '重新生成文档', desc: '按当前源记忆刷新派生文档',
      iconName: 'retry', plateStyle: 'accent', plateSize: 28, isLast: true, showChevron: false,
      onRowClick: (): void => { if (!this.syncing) this.sync(); } })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '记忆文档', subtitle: this.relativePath.length > 0 ? this.selected?.title ?? '' : '' })
      Scroll() {
        Column() {
          if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          if (!this.loaded) {
            Text('正在读取记忆文档…').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 44 })
          } else if (this.relativePath.length > 0) {
            if (this.selected === null || this.content === null) {
              PlainSection({ label: '文档' })
              Text('文档不存在或已失效。源记忆变化后，旧主题文档会被移除。')
                .fontSize(13).fontColor(this.colors.secondary).width('100%')
                .textAlign(TextAlign.Center).padding({ top: 24, bottom: 12 })
            } else {
              PlainSection({ label: '文档' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.DocumentHeaderCard(); } })
              PlainSection({ label: '内容' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.DocumentBodyCard(); } })
            }
            if (this.visibleDocuments().length > 0) {
              PlainSection({ label: '其他记忆文档' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.DocumentRows(); } })
            }
            PlainSection({ label: '操作' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SyncCard(); } })
          } else {
            PlainSection({ label: '记忆文档' })
            if (this.documents.length === 0) {
              Column() {
                Text('当前没有记忆文档')
                  .fontSize(14).fontColor(this.colors.secondary)
                Text('没有有效的非核心源记忆时，不会生成索引。')
                  .fontSize(12).fontColor(this.colors.muted).margin({ top: 8 })
              }
              .width('100%').padding({ top: 44, bottom: 24 }).alignItems(HorizontalAlign.Center)
            } else {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.DocumentRows(); } })
              FooterNote({ text: '只读 Markdown 文档，内容来自当前真实源记忆。' })
            }
            PlainSection({ label: '操作' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SyncCard(); } })
          }
          PageSpacer()
        }.width('100%').padding({ left: 16, right: 16, top: 2, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
