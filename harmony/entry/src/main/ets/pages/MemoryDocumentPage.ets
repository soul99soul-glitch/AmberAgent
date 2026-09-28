import { router } from '@kit.ArkUI';
import type { MemoryDerivedDocument } from '@amber/chat-domain';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

interface MemoryDocumentParams { relativePath?: string; }

@Entry
@Component
struct MemoryDocumentPage {
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

  @Builder
  private DocumentLinks(): void {
    Column({ space: 10 }) {
      ForEach(this.documents, (item: MemoryDerivedDocument): void => {
        if (item.relativePath !== this.relativePath) {
          Column({ space: 6 }) {
            Text(item.title).fontSize(15).fontWeight(FontWeight.Medium).fontColor(this.colors.ink).width('100%')
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(item.relativePath).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
              .wordBreak(WordBreak.BREAK_ALL)
            Text(item.preview).fontSize(13).fontColor(this.colors.secondary).width('100%')
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(`${item.sizeBytes} bytes · ${new Date(item.modifiedAt).toLocaleString()}`)
              .fontSize(12).fontColor(this.colors.secondary).width('100%')
          }.width('100%').padding(16).constraintSize({ minHeight: 52 }).backgroundColor(this.colors.surface)
            .borderRadius(14).border({ width: 1, color: this.colors.line })
            .onClick((): void => { this.openDocument(item.relativePath); })
        }
      }, (item: MemoryDerivedDocument): string => `${item.relativePath}-${item.modifiedAt}-${item.sizeBytes}`)
    }.width('100%')
  }

  @Builder
  private DocumentBody(): void {
    Column({ space: 12 }) {
      Text(this.selected?.title ?? '').fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.colors.ink).width('100%')
        .wordBreak(WordBreak.BREAK_ALL)
      Text(this.relativePath).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
        .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      Text('由真实源记忆派生，只读。内部文档通过下方列表打开；源记忆可在主题成员中编辑。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      if (this.selected?.topicId !== null && this.selected !== null) {
        Button('查看主题和源成员').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: this.selected?.topicId?.toString() ?? '' } }); })
      }
      MarkdownText({ content: this.renderedContent(), fontColor: this.colors.ink, readingMode: true,
        enableLatexRendering: false, codeBlockAutoCollapse: true })
      Button(this.rawExpanded ? '收起 Markdown 原文' : '查看 / 复制完整 Markdown 原文')
        .type(ButtonType.Normal).height(44).width('100%').fontSize(14).fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .onClick((): void => { this.rawExpanded = !this.rawExpanded; })
      if (this.rawExpanded) {
        Text(this.content ?? '').fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      }
    }.width('100%')
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '记忆文档' })
      Scroll() {
        Column({ space: 12 }) {
          if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          if (!this.loaded) {
            Text('正在读取记忆文档…').fontSize(14).fontColor(this.colors.secondary).width('100%')
          } else if (this.relativePath.length > 0) {
            if (this.selected === null || this.content === null) {
              Text('文档不存在或已失效。源记忆变化后，旧主题文档会被移除。')
                .fontSize(14).fontColor(this.colors.secondary).width('100%')
            } else this.DocumentBody()
            Text('其他记忆文档').fontSize(15).fontColor(this.colors.ink).width('100%')
            this.DocumentLinks()
          } else {
            Text('只读 Markdown 文档，内容来自当前真实源记忆。').fontSize(13).fontColor(this.colors.secondary).width('100%')
            if (this.documents.length === 0) {
              Text('当前没有记忆文档。没有有效的非核心源记忆时，不会生成索引。')
                .fontSize(14).fontColor(this.colors.secondary).width('100%')
            }
            this.DocumentLinks()
          }
          Row({ space: 8 }) {
            Button('重新读取').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(14)
              .fontColor(this.accentColor).backgroundColor(this.colors.inset).onClick((): void => { this.reload(); })
            Button(this.syncing ? '刷新中' : '重新生成文档').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(14)
              .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(!this.syncing).onClick((): void => { this.sync(); })
          }.width('100%')
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
