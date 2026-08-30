// NovelWorkspacePage — 小说工作区:三页签(聊天写作 / 章节正文 / 活资料)
// 单文件承载三个 pane(@Builder)与对话框(Stack 叠层),状态集中于本页 @State。
import { ACCENT, BG, INK, INK2, INK3, INK4, LINE, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { getNovelCreation } from '../di/AppContainer.ets';
import { MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget,
  NovelRunKind , NovelRun, NovelChapterVersion, NovelChapterVersionKind } from '@amber/deepread-domain';

const GREEN: string = '#43A047';

type ComposeMode = 'discuss' | 'continue' | 'whole_chapter';
type MaterialFilter = 'all' | NovelMaterialKind;

const KIND_FILTERS: MaterialFilter[] = ['all', 'world', 'character', 'outline', 'requirement', 'other'];
// ArkTS 不允许 Record<string,string> 对象字面量,用显式 interface
interface KindFilterLabels {
  all: string;
  world: string;
  character: string;
  outline: string;
  requirement: string;
  other: string;
}
const KIND_FILTER_LABELS: KindFilterLabels = {
  all: '全部', world: '世界', character: '人物', outline: '大纲', requirement: '写作要求', other: '其他',
};

const filterLabel = (f: MaterialFilter): string => {
  switch (f) {
    case 'all': return KIND_FILTER_LABELS.all;
    case 'world': return KIND_FILTER_LABELS.world;
    case 'character': return KIND_FILTER_LABELS.character;
    case 'outline': return KIND_FILTER_LABELS.outline;
    case 'requirement': return KIND_FILTER_LABELS.requirement;
    default: return KIND_FILTER_LABELS.other;
  }
};

@Entry
@Component
struct NovelWorkspacePage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State project: NovelProject | null = null;
  @State tab: number = 0;
  @State errorMsg: string = '';
  @State busy: boolean = false;
  // 聊天
  @State draft: string = '';
  @State composeMode: ComposeMode = 'whole_chapter';
  @State streamingText: string = '';
  @State generationStatus: string = '';
  @State activeRunId: string = '';
  // N3:讨论归档蒸馏(取当前讨论模式中未收录的 assistant 消息)
  async archiveDiscussion(): Promise<void> {
    if (this.project === null || this.activeRunId.length > 0) return;
    const discussMsgs: NovelMessage[] = this.project.messages.filter(
      (m: NovelMessage): boolean => m.mode === 'discuss' && m.collectedChapterId === null);
    if (discussMsgs.length === 0) {
      promptAction.showToast({ message: '没有可归档的讨论内容', duration: 1400 });
      return;
    }
    this.busy = true;
    try {
      const ids: string[] = discussMsgs.map((m: NovelMessage): string => m.id);
      const r = await getNovelCreation().archiveDiscussion(this.projectId, ids);
      promptAction.showToast({ message: `归档完成,产出 ${r.count} 条资料建议`, duration: 1800 });
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // N2:章节版本历史 sheet
  @State versionsChapterId: string = '';
  @State versionsOpen: boolean = false;
  @State chapterMenuId: string = '';
  @State pendingUserText: string = '';
  @State collectMessageId: string = '';
  @State collectTitle: string = '';
  // 章节
  @State selectedChapterId: string = '';
  @State chapterEditorOpen: boolean = false;
  @State editingChapterId: string = '';
  @State chapterTitle: string = '';
  @State chapterContent: string = '';
  // 素材
  @State matFilter: MaterialFilter = 'all';
  @State expandedMaterialId: string = '';
  @State materialEditorOpen: boolean = false;
  @State editingMaterialId: string = '';
  @State materialKind: NovelMaterialKind = 'world';
  @State materialTitle: string = '';
  @State materialContent: string = '';
  @State materialEnabled: boolean = true;

  private projectId: string = '';
  private currentUnsub: (() => void) | null = null;
  private pageAlive: boolean = false;
  private reloadToken: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    const params = router.getParams() as Record<string, string>;
    this.projectId = params ? (params['projectId'] ?? '') : '';
    this.reload();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.reloadToken++;
    if (this.currentUnsub !== null) {
      this.currentUnsub();
const unsub: (() => void) | null = this.currentUnsub;
        this.currentUnsub = null;
        if (unsub !== null) unsub(); // 终结即退订,emitter 不再持有页面闭包
    }
  }

  async reload(): Promise<void> {
    if (this.projectId.length === 0) return;
    const token: number = ++this.reloadToken;
    const projectId: string = this.projectId;
    try {
      const project: NovelProject = await getNovelCreation().open(projectId);
      if (this.pageAlive && token === this.reloadToken) this.project = project;
    } catch (e) {
      if (this.pageAlive && token === this.reloadToken) this.errorMsg = '项目无法打开';
    }
  }

  // N1:导出 Markdown(标题+全文各章,复制到剪贴板;系统分享面板后续接)
  private exportMarkdown(): void {
    if (this.project === null) return;
    const p: NovelProject = this.project;
    let md: string = `# ${p.name}\n\n`;
    for (const c of p.chapters.filter((c: NovelChapter): boolean => !c.discarded)) {
      md += `## ${c.title}\n\n${c.content}\n\n`;
    }
    pasteboard.getSystemPasteboard().setDataSync(
      pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md));
    promptAction.showToast({ message: `已导出 ${p.chapters.length} 章到剪贴板`, duration: 1600 });
  }

  // ===== 聊天 =====
  assistantLabel(m: NovelMessage): string {
    if (m.interrupted) return 'Amber · 已停止（内容已保留）';
    if (m.mode === 'discuss') return 'Amber · 讨论';
    return m.granularity === 'continuation' ? 'Amber · 续写' : 'Amber · 整章';
  }

  send(runKindOverride: NovelRunKind | null = null): void {
    const text: string = this.draft.trim();
    if (text.length === 0 || this.activeRunId.length > 0 || this.project === null) return;
    const mode: NovelChatMode = this.composeMode === 'discuss' ? 'discuss' : 'write';
    let gran: NovelGenerationGranularity | null = null;
    if (this.composeMode === 'continue') gran = 'continuation';
    else if (this.composeMode === 'whole_chapter') gran = 'whole_chapter';

    this.pendingUserText = text;
    this.draft = '';
    this.streamingText = '';
    this.generationStatus = '正在连接模型并组织正文…';
    this.errorMsg = '';

    const run = getNovelCreation().generate(this.projectId, text, mode, gran);
    this.activeRunId = run.id;
    this.currentUnsub = run.subscribe((e): void => {
      if (e.kind === 'status') {
        if (e.text.length > 0) this.generationStatus = e.text;
      } else if (e.kind === 'delta') {
        this.streamingText += e.text;
      } else if (e.kind === 'replace') {
        this.streamingText = e.text;
      } else if (e.kind === 'completed' || e.kind === 'interrupted' || e.kind === 'failed') {
        this.activeRunId = '';
        this.pendingUserText = '';
        this.streamingText = '';
        this.generationStatus = '';
        if (e.kind === 'failed') this.errorMsg = e.message;
        this.currentUnsub = null;
        this.reload();
      }
    });
  }

  interrupt(): void {
    if (this.activeRunId.length > 0) getNovelCreation().interrupt(this.activeRunId);
  }

  openCollect(messageId: string): void {
    this.collectMessageId = messageId;
    const p: NovelProject | null = this.project;
    this.collectTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
  }

  async doCollect(target: NovelCollectionTarget): Promise<void> {
    if (this.collectMessageId.length === 0) return;
    this.busy = true;
    try {
      await getNovelCreation().collectMessage(this.projectId, this.collectMessageId, target);
      this.collectMessageId = '';
      this.errorMsg = '';
      await this.reload();
      this.tab = 2; // 收录后跳到活资料查看建议
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 章节 =====
  openNewChapter(): void {
    this.editingChapterId = '';
    const p: NovelProject | null = this.project;
    this.chapterTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
    this.chapterContent = '';
    this.chapterEditorOpen = true;
  }

  openEditChapter(c: NovelChapter): void {
    this.editingChapterId = c.id;
    this.chapterTitle = c.title;
    this.chapterContent = c.content;
    this.chapterEditorOpen = true;
  }

  // N2:订阅章节级 Run(复用 send 的流式订阅;终态 reload)
  private attachChapterRun(run: NovelRun, statusText: string): void {
    this.activeRunId = run.id;
    this.streamingText = '';
    this.generationStatus = statusText;
    this.errorMsg = '';
    this.currentUnsub = run.subscribe((e): void => {
      if (e.kind === 'status') {
        if (e.text.length > 0) this.generationStatus = e.text;
      } else if (e.kind === 'delta') {
        this.streamingText += e.text;
      } else if (e.kind === 'replace') {
        this.streamingText = e.text;
      } else if (e.kind === 'completed' || e.kind === 'interrupted' || e.kind === 'failed') {
        this.activeRunId = '';
        this.streamingText = '';
        this.generationStatus = '';
        if (e.kind === 'failed') this.errorMsg = e.message;
        this.currentUnsub = null;
        this.reload();
      }
    });
  }

  // N2:单章重新生成(整章 regeneration Run)
  async regenerateChapter(chapterId: string): Promise<void> {
    if (this.activeRunId.length > 0) return;
    try {
      const creation = getNovelCreation();
      const run: NovelRun = await creation.regenerateChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '重新生成中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  // N2:单章润色(polish 哨兵协议)
  async polishChapter(chapterId: string): Promise<void> {
    if (this.activeRunId.length > 0) return;
    try {
      const creation = getNovelCreation();
      const run: NovelRun = await creation.polishChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '润色中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  // N2:章节版本历史
  async openChapterVersions(chapterId: string): Promise<void> {
    const p: NovelProject | null = await getNovelCreation().open(this.projectId);
    if (this.pageAlive && p !== null) {
      this.project = p;
      this.versionsChapterId = chapterId;
      this.versionsOpen = true;
    }
  }

  async saveChapter(): Promise<void> {
    this.busy = true;
    try {
      const saved: NovelChapter = await getNovelCreation().saveChapter(
        this.projectId, this.editingChapterId.length > 0 ? this.editingChapterId : null,
        this.chapterTitle, this.chapterContent);
      this.chapterEditorOpen = false;
      this.selectedChapterId = saved.id;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async deleteChapter(id: string): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().deleteChapter(this.projectId, id);
      if (this.selectedChapterId === id) this.selectedChapterId = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 素材 =====
  openNewMaterial(): void {
    this.editingMaterialId = '';
    this.materialKind = 'world';
    this.materialTitle = '';
    this.materialContent = '';
    this.materialEnabled = true;
    this.materialEditorOpen = true;
  }

  openEditMaterial(m: NovelMaterial): void {
    this.editingMaterialId = m.id;
    this.materialKind = m.kind;
    this.materialTitle = m.title;
    this.materialContent = m.content;
    this.materialEnabled = m.enabled;
    this.materialEditorOpen = true;
  }

  async saveMaterial(): Promise<void> {
    this.busy = true;
    try {
      const saved: NovelMaterial = await getNovelCreation().upsertMaterial(
        this.projectId, this.editingMaterialId.length > 0 ? this.editingMaterialId : null,
        this.materialKind, this.materialTitle, this.materialContent, this.materialEnabled);
      this.materialEditorOpen = false;
      this.expandedMaterialId = saved.id;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async deleteMaterial(id: string): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().deleteMaterial(this.projectId, id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async resolveSuggestion(id: string, accept: boolean): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().resolveMaterialSuggestion(this.projectId, id, accept);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async reanalyze(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || p.chapters.length === 0) return;
    this.busy = true;
    try {
      const last: NovelChapter = p.chapters[p.chapters.length - 1];
      await getNovelCreation().refreshMaterialSuggestions(this.projectId, last.id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 页签内容 =====
  @Builder
  chatPane(): void {
    Row() {
      Blank()
      Text('归档讨论').fontSize(12).fontColor(INK3)
        .padding({ left: 12, right: 16, top: 6, bottom: 6 })
        .onClick((): void => { this.archiveDiscussion(); })
    }
    .width('100%')
    Column() {
      // 消息列表
      List({ space: 0 }) {
        if (this.project !== null) {
          ForEach(this.project.messages, (m: NovelMessage) => {
            ListItem() {
              this.messageCard(m)
            }
          }, (m: NovelMessage) => m.id)
        }
        // 乐观用户气泡
        if (this.pendingUserText.length > 0) {
          ListItem() {
            this.userBubble(this.pendingUserText)
          }
        }
        // 流式卡片
        if (this.activeRunId.length > 0) {
          ListItem() {
            Column() {
              Text('Amber 正在写').fontSize(12).fontColor(ACCENT).margin({ bottom: 6 })
              if (this.streamingText.length > 0) {
                Text(this.streamingText + '▍').fontSize(14).fontColor(INK).lineHeight(24)
              } else {
                Text(this.generationStatus).fontSize(13).fontColor(INK3)
              }
            }
            .width('94%').padding(14).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })
            .alignItems(HorizontalAlign.Start)
          }
        }
        if (this.project !== null && this.project.messages.length === 0 && this.activeRunId.length === 0) {
          ListItem() {
            Column() {
              Text('从一个念头开始').fontSize(15).fontColor(INK2)
              Text('讨论设定、续写当前场景，或直接生成完整章节。')
                .fontSize(12).fontColor(INK3).margin({ top: 6 })
            }
            .width('100%').padding({ top: 40, bottom: 20 })
          }
        }
      }
      .layoutWeight(1).padding({ left: 16, right: 16, top: 8 })

      if (this.errorMsg.length > 0) {
        Text(this.errorMsg).fontSize(12).fontColor('#E53935').padding({ left: 16, right: 16, bottom: 6 })
      }

      // 输入器
      Column() {
        Row() {
          this.modeChip('讨论', 'discuss')
          this.modeChip('续写', 'continue')
          this.modeChip('整章', 'whole_chapter')
          Blank()
          if (this.activeRunId.length > 0) {
            Text('停止').fontSize(13).fontColor('#E53935')
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .onClick(() => { this.interrupt(); })
          } else {
            Text('发送').fontSize(13).fontColor('#FFFFFF')
              .padding({ left: 18, right: 18, top: 8, bottom: 8 }).borderRadius(16)
              .backgroundColor(this.draft.trim().length > 0 ? ACCENT : INK4)
              .onClick(() => { this.send(); })
          }
        }
        .width('100%').margin({ bottom: 8 })

        TextArea({ text: this.draft, placeholder: this.composerPlaceholder() })
          .height(72).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12)
          .onChange((v: string) => { this.draft = v; })
      }
      .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 12 })
      .border({ width: { top: 0.5 }, color: { top: LINE } })
    }
    .width('100%').height('100%')
  }

  composerPlaceholder(): string {
    if (this.composeMode === 'discuss') return '聊人物、情节或世界观…';
    if (this.composeMode === 'continue') return '描述接下来发生的场景…';
    return '描述本章要点，生成完整章节…';
  }

  @Builder
  modeChip(label: string, mode: ComposeMode): void {
    Text(label)
      .fontSize(13).padding({ left: 14, right: 14, top: 8, bottom: 8 }).borderRadius(16)
      .fontColor(this.composeMode === mode ? '#FFFFFF' : INK2)
      .backgroundColor(this.composeMode === mode ? ACCENT : SURFACE2)
      .margin({ right: 8 })
      .opacity(this.activeRunId.length > 0 ? 0.5 : 1)
      .onClick(() => { if (this.activeRunId.length === 0) this.composeMode = mode; })
  }

  @Builder
  userBubble(text: string): void {
    Row() {
      Blank()
      Text(text).fontSize(14).fontColor('#FFFFFF').lineHeight(22)
        .padding(12).backgroundColor(ACCENT).borderRadius(12)
    }
    .width('100%').margin({ bottom: 12 })
  }

  @Builder
  messageCard(m: NovelMessage): void {
    if (m.role === 'user') {
      this.userBubble(m.content)
    } else {
      Column() {
        Text(this.assistantLabel(m)).fontSize(12).fontColor(INK3).margin({ bottom: 6 })
        Text(m.content).fontSize(14).fontColor(INK).lineHeight(24)
        if (m.mode === 'write' && m.collectedChapterId === null && m.content.trim().length > 0) {
          // write 模式且有正文即可收录(含 interrupted 的部分内容)
          Text(m.interrupted ? '收进章节（部分）' : '收进章节').fontSize(12).fontColor(ACCENT)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(14)
            .backgroundColor('#FFF5F0').margin({ top: 10 })
            .onClick(() => { this.openCollect(m.id); })
        } else if (m.collectedChapterId !== null) {
          Text('已收进章节').fontSize(12).fontColor(GREEN).margin({ top: 10 })
        }
      }
      .width('94%').padding(14).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: LINE }).margin({ bottom: 12 })
      .alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  chapterPane(): void {
    Column() {
      if (this.selectedChapterId.length === 0) {
        // 目录
        Row() {
          Text('章节目录').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
          Blank()
          Text('新建章节').fontSize(12).fontColor(ACCENT)
            .onClick(() => { this.openNewChapter(); })
        }
        .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 10 })

        if (this.project !== null && this.project.chapters.length === 0) {
          Text('还没有章节。新建第一章，或在聊天写作中把 Amber 的正文收进章节。')
            .fontSize(13).fontColor(INK3).padding({ left: 20, right: 20, top: 20 })
        } else {
          List({ space: 0 }) {
            if (this.project !== null) {
              ForEach(this.project.chapters, (c: NovelChapter, idx: number) => {
                ListItem() {
                  Row() {
                    Text((idx + 1) < 10 ? '0' + (idx + 1) : '' + (idx + 1))
                      .fontSize(13).fontColor(INK4).width(30)
                    Column() {
                      Text((c.discarded ? '[已废弃] ' : '') + (c.title.length > 0 ? c.title : '未命名章节'))
                        .fontSize(15).fontColor(c.discarded ? INK4 : INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      Text(`${this.charCount(c.content)} 字`).fontSize(11).fontColor(INK3).margin({ top: 4 })
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    Text('⋯').fontSize(16).fontColor(INK3)
                      .width(36).height(36).textAlign(TextAlign.Center)
                      .onClick(() => { this.chapterMenuId = c.id; })
                    Text('›').fontSize(18).fontColor(INK4)
                  }
                  .width('100%').padding({ left: 20, right: 20, top: 14, bottom: 14 })
                  .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                  .onClick(() => { this.selectedChapterId = c.id; })
                }
              }, (c: NovelChapter) => c.id)
            }
          }
          .layoutWeight(1)
        }
      } else {
        this.chapterReader()
      }
    }
    .width('100%').height('100%')
  }

  charCount(s: string): number {
    return s.replace(/\s/g, '').length;
  }

  @Builder
  chapterReader(): void {
    Column() {
      Row() {
        Text('← 目录').fontSize(13).fontColor(ACCENT)
          .onClick(() => { this.selectedChapterId = ''; })
        Blank()
        Text('编辑').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick(() => {
            const c: NovelChapter | undefined = this.project?.chapters.find(x => x.id === this.selectedChapterId);
            if (c !== undefined) this.openEditChapter(c);
          })
        Text('删除').fontSize(13).fontColor('#E53935')
          .onClick(() => { this.deleteChapter(this.selectedChapterId); })
      }
      .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 10 })

      Scroll() {
        Column() {
          if (this.project !== null) {
            ForEach(this.project.chapters.filter(c => c.id === this.selectedChapterId), (c: NovelChapter) => {
              Text(c.title).fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
                .fontFamily(FONT_SERIF).margin({ bottom: 6 })
              Text(`${this.charCount(c.content)} 字`).fontSize(12).fontColor(INK3).margin({ bottom: 16 })
              Text(c.content.length > 0 ? c.content : '这一章还没有正文。')
                .fontSize(15).fontColor(INK).lineHeight(28)
            }, (c: NovelChapter) => c.id)
          }
        }
        .width('100%').padding({ left: 20, right: 20, bottom: 24 })
        .alignItems(HorizontalAlign.Start)
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%')
  }

  @Builder
  materialPane(): void {
    Column() {
      Row() {
        Text('活资料').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
        Blank()
        Text('重新分析').fontSize(12).fontColor(INK3).margin({ right: 16 })
          .onClick(() => { this.reanalyze(); })
        Text('新建资料').fontSize(12).fontColor(ACCENT)
          .onClick(() => { this.openNewMaterial(); })
      }
      .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 10 })

      Scroll() {
        Column() {
          // 待确认建议
          if (this.project !== null && this.project.materialSuggestions.some(s => s.status === 'pending')) {
            Text('待确认').fontSize(13).fontColor(INK2).margin({ bottom: 8 })
            Text('Amber 从正文中发现了资料变化，采用后才会写入资料库。')
              .fontSize(11).fontColor(INK3).margin({ bottom: 10 })
            ForEach(this.project.materialSuggestions.filter(s => s.status === 'pending'), (s: NovelMaterialSuggestion) => {
              Column() {
                Row() {
                  Text(MATERIAL_KIND_LABELS[s.kind]).fontSize(11).fontColor(ACCENT)
                    .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8).backgroundColor('#FFF5F0')
                  Text(s.title).fontSize(14).fontColor(INK).margin({ left: 8 }).layoutWeight(1)
                }
                .width('100%')
                Text(s.content).fontSize(13).fontColor(INK2).lineHeight(20).margin({ top: 8 })
                Row() {
                  Blank()
                  Text('忽略').fontSize(13).fontColor(INK3).margin({ right: 20 })
                    .onClick(() => { this.resolveSuggestion(s.id, false); })
                  Text('采用').fontSize(13).fontColor('#FFFFFF')
                    .padding({ left: 16, right: 16, top: 6, bottom: 6 }).borderRadius(14).backgroundColor(ACCENT)
                    .onClick(() => { this.resolveSuggestion(s.id, true); })
                }
                .width('100%').margin({ top: 10 })
              }
              .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 1, color: LINE }).margin({ bottom: 12 })
            }, (s: NovelMaterialSuggestion) => s.id)
          }

          // 过滤 chips
          Row() {
            ForEach(KIND_FILTERS, (f: MaterialFilter) => {
              Text(filterLabel(f))
                .fontSize(12).padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(14)
                .fontColor(this.matFilter === f ? '#FFFFFF' : INK2)
                .backgroundColor(this.matFilter === f ? ACCENT : SURFACE2)
                .margin({ right: 8 })
                .onClick(() => { this.matFilter = f; })
            }, (f: MaterialFilter) => f)
          }
          .width('100%').margin({ top: 8, bottom: 12 })

          // 素材库
          if (this.project !== null) {
            ForEach(this.filteredMaterials(), (m: NovelMaterial) => {
              Column() {
                Row() {
                  Text(MATERIAL_KIND_LABELS[m.kind]).fontSize(11).fontColor(ACCENT)
                    .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8).backgroundColor('#FFF5F0')
                  Text(m.title).fontSize(14).fontColor(INK).margin({ left: 8 }).layoutWeight(1)
                  if (!m.enabled) Text('· 不参与写作').fontSize(11).fontColor('#E53935')
                  Text(this.expandedMaterialId === m.id ? '收起' : '展开').fontSize(12).fontColor(INK3)
                    .margin({ left: 8 })
                }
                .width('100%')
                .onClick(() => {
                  this.expandedMaterialId = this.expandedMaterialId === m.id ? '' : m.id;
                })
                if (this.expandedMaterialId === m.id) {
                  Text(m.content).fontSize(13).fontColor(INK2).lineHeight(20).margin({ top: 10 })
                  Row() {
                    Blank()
                    Text('删除').fontSize(12).fontColor('#E53935').margin({ right: 16 })
                      .onClick(() => { this.deleteMaterial(m.id); })
                    Text('编辑').fontSize(12).fontColor(ACCENT)
                      .onClick(() => { this.openEditMaterial(m); })
                  }
                  .width('100%').margin({ top: 10 })
                }
              }
              .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 1, color: LINE }).margin({ bottom: 10 })
            }, (m: NovelMaterial) => m.id)
          }
        }
        .width('100%').padding({ left: 20, right: 20, bottom: 24 })
        .alignItems(HorizontalAlign.Start)
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%')
  }

  filteredMaterials(): NovelMaterial[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    if (this.matFilter === 'all') return p.materials;
    return p.materials.filter(m => m.kind === this.matFilter);
  }

  build(): void {
    Stack() {
      Column() {
        // 顶栏
        Row() {
          Icon({ name: 'back', iconSize: 22, color: INK3 }).onClick(() => { router.back(); })
          Text(this.project !== null ? this.project.name : '小说创作')
            .fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
            .fontFamily(FONT_SERIF).margin({ left: 12 }).layoutWeight(1)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (this.busy) LoadingProgress().width(16).height(16).color(ACCENT)
          Text('导出').fontSize(14).fontColor(ACCENT).margin({ left: 10 })
            .onClick((): void => { this.exportMarkdown(); })
        }
        .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
        .backgroundColor(BG)

        // 页签
        Row() {
          this.tabChip('聊天写作', 0)
          this.tabChip('章节正文', 1)
          this.tabChip('活资料', 2)
        }
        .width('100%').padding({ left: 16, right: 16, top: 4, bottom: 8 })

        if (this.project === null) {
          Column() {
            Text('项目无法打开').fontSize(15).fontColor(INK3)
          }
          .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.tab === 0) {
          this.chatPane()
        } else if (this.tab === 1) {
          this.chapterPane()
        } else {
          this.materialPane()
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      // 收录对话框
      if (this.collectMessageId.length > 0) {
        this.collectDialog()
      }
      // 章节编辑器
      if (this.chapterEditorOpen) {
        this.chapterEditor()
      }
      // 素材编辑器
      if (this.materialEditorOpen) {
        this.materialEditor()
      }
      // N2:章节操作菜单
      if (this.chapterMenuId.length > 0) {
        this.chapterMenuOverlay()
      }
      // N2:版本历史 sheet
      if (this.versionsOpen) {
        this.versionsSheetOverlay()
      }
    }
    .width('100%').height('100%')
  }

  @Builder
  tabChip(label: string, idx: number): void {
    Text(label)
      .fontSize(13).layoutWeight(1).textAlign(TextAlign.Center)
      .padding({ top: 9, bottom: 9 }).borderRadius(18)
      .fontColor(this.tab === idx ? '#FFFFFF' : INK2)
      .backgroundColor(this.tab === idx ? ACCENT : SURFACE2)
      .margin({ left: 4, right: 4 })
      .onClick(() => { this.tab = idx; })
  }

  @Builder
  collectDialog(): void {
    Column() {
      Column() {
        Text('收进章节').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        // 新开
        Text(`新开第 ${this.project !== null ? this.project.chapters.length + 1 : 1} 章`)
          .fontSize(14).fontColor(ACCENT).margin({ bottom: 10 })
          .onClick(() => { this.doCollect({ kind: 'new_chapter', title: this.collectTitle }); })
        // 并入/替换已有章节
        if (this.project !== null) {
          ForEach(this.project.chapters, (c: NovelChapter, idx: number) => {
            Column() {
              Text(`并入第 ${idx + 1} 章《${c.title}》`).fontSize(13).fontColor(INK2)
                .onClick(() => { this.doCollect({ kind: 'append', chapterId: c.id }); })
              Text(`替换第 ${idx + 1} 章《${c.title}》`).fontSize(12).fontColor('#E53935').margin({ top: 6 })
                .onClick(() => { this.doCollect({ kind: 'replace', chapterId: c.id }); })
            }
            .width('100%').padding({ top: 8, bottom: 8 })
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
          }, (c: NovelChapter) => c.id)
        }
        Text('取消').fontSize(14).fontColor(INK3).margin({ top: 16 })
          .onClick(() => { this.collectMessageId = ''; })
      }
      .width('84%').padding(22).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .onClick(() => { this.collectMessageId = ''; })
  }

  @Builder
  chapterEditor(): void {
    Column() {
      Column() {
        Text(this.editingChapterId.length > 0 ? '编辑本章' : '新建章节')
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        TextInput({ text: this.chapterTitle, placeholder: '章节标题' })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .onChange((v: string) => { this.chapterTitle = v; })
        TextArea({ text: this.chapterContent, placeholder: '章节正文' })
          .height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
          .onChange((v: string) => { this.chapterContent = v; })
        Text(`${this.charCount(this.chapterContent)} 字`).fontSize(11).fontColor(INK3).margin({ top: 6 })
        Row() {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .onClick(() => { this.chapterEditorOpen = false; })
          Text('保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .onClick(() => { this.saveChapter(); })
        }
        .width('100%').margin({ top: 16 })
      }
      .width('88%').padding(22).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  materialEditor(): void {
    Column() {
      Column() {
        Text(this.editingMaterialId.length > 0 ? '编辑资料' : '新建资料')
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        // kind chips
        Row() {
          ForEach(KIND_FILTERS.filter(f => f !== 'all'), (f: MaterialFilter) => {
            Text(filterLabel(f))
              .fontSize(12).padding({ left: 10, right: 10, top: 6, bottom: 6 }).borderRadius(14)
              .fontColor(this.materialKind === f ? '#FFFFFF' : INK2)
              .backgroundColor(this.materialKind === f ? ACCENT : SURFACE2)
              .margin({ right: 6 })
              .onClick(() => { this.materialKind = f as NovelMaterialKind; })
          }, (f: MaterialFilter) => f)
        }
        .width('100%').margin({ bottom: 12 })

        TextInput({ text: this.materialTitle, placeholder: this.materialKind === 'character' ? '人物姓名' : '名称' })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .onChange((v: string) => { this.materialTitle = v; })
        TextArea({ text: this.materialContent, placeholder: '资料内容' })
          .height(140).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
          .onChange((v: string) => { this.materialContent = v; })
        Row() {
          Text('参与写作上下文').fontSize(13).fontColor(INK2).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.materialEnabled })
            .selectedColor(ACCENT)
            .onChange((v: boolean) => { this.materialEnabled = v; })
        }
        .width('100%').margin({ top: 12 })
        Row() {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .onClick(() => { this.materialEditorOpen = false; })
          Text('保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .onClick(() => { this.saveMaterial(); })
        }
        .width('100%').margin({ top: 16 })
      }
      .width('88%').padding(22).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  chapterMenuOverlay(): void {
    if (this.chapterMenuId.length > 0) {
      Column() {
        Blank().onClick(() => { this.chapterMenuId = ''; })
        Column() {
          this.menuRow('重新生成', 'regenerate')
          this.menuRow('润色', 'polish')
          this.menuRow('版本历史', 'versions')
          this.menuRow('编辑', 'edit')
          this.menuRow(this.isDiscarded(this.chapterMenuId) ? '取消废弃' : '废弃章节', 'discard')
        }
        .width('100%')
        .backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ top: 10, bottom: 16 })
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .justifyContent(FlexAlign.End)
    }
  }

  // action: 'regenerate' | 'polish' | 'versions' | 'edit' | 'discard'
  @Builder
  menuRow(label: string, action: string): void {
    Row() {
      Text(label).fontSize(15).fontColor(INK)
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 22, right: 22 })
    .onClick((): void => { this.onChapterMenuAction(action); })
  }

  private onChapterMenuAction(action: string): void {
    const id: string = this.chapterMenuId;
    this.chapterMenuId = '';
    if (id.length === 0) return;
    if (action === 'regenerate') this.regenerateChapter(id);
    else if (action === 'polish') this.polishChapter(id);
    else if (action === 'versions') this.openChapterVersions(id);
    else if (action === 'edit') {
      const c: NovelChapter | null = this.project !== null
        ? this.project.chapters.find(x => x.id === id) ?? null : null;
      if (c !== null) this.openEditChapter(c);
    } else if (action === 'discard') {
      this.toggleDiscarded(id, !this.isDiscarded(id));
    }
  }

  @Builder
  versionsSheetOverlay(): void {
    if (this.versionsOpen) {
      Column() {
        Blank().onClick(() => { this.versionsOpen = false; })
        Column() {
          Text('版本历史').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
            .width('100%').padding({ left: 20, right: 20, top: 16, bottom: 8 })
          if (this.project === null || this.versionsFor(this.versionsChapterId).length === 0) {
            Text('暂无历史版本').fontSize(13).fontColor(INK3)
              .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 20 })
          } else {
            List() {
              ForEach(this.versionsFor(this.versionsChapterId), (v: NovelChapterVersion) => {
                ListItem() {
                  Row() {
                    Column() {
                      Text(this.versionKindLabel(v.kind)).fontSize(14).fontColor(INK)
                      Text(this.versionTime(v.createdAt)).fontSize(11).fontColor(INK3).margin({ top: 2 })
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    Text('恢复').fontSize(13).fontColor(ACCENT)
                      .padding({ left: 12, right: 4, top: 8, bottom: 8 })
                      .onClick((): void => { this.restoreVersion(v.id); })
                  }
                  .width('100%').padding({ left: 20, right: 20, top: 10, bottom: 10 })
                }
              }, (v: NovelChapterVersion): string => v.id)
            }
            .height(320)
          }
        }
        .width('100%')
        .backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ bottom: 16 })
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .justifyContent(FlexAlign.End)
    }
  }

  private isDiscarded(chapterId: string): boolean {
    if (this.project === null) return false;
    const c: NovelChapter | null = this.project.chapters.find(x => x.id === chapterId) ?? null;
    return c !== null && c.discarded;
  }

  private async toggleDiscarded(chapterId: string, discarded: boolean): Promise<void> {
    this.busy = true;
    try {
      this.project = await getNovelCreation().setChapterDiscarded(this.projectId, chapterId, discarded);
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private versionsFor(chapterId: string): NovelChapterVersion[] {
    if (this.project === null) return [];
    return this.project.chapterVersions.filter(v => v.chapterId === chapterId).slice().reverse();
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

  private async restoreVersion(versionId: string): Promise<void> {
    this.busy = true;
    try {
      this.project = await getNovelCreation().restoreChapterVersion(this.projectId, versionId);
      this.versionsOpen = false;
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }
}
