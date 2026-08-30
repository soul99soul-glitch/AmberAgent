// NovelWorkspacePage — 小说工作区:三页签(聊天写作 / 章节正文 / 活资料)
// 单文件承载三个 pane(@Builder)与对话框(Stack 叠层),状态集中于本页 @State。
import { ACCENT, BG, INK, INK2, INK3, INK4, LINE, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { ChatInput } from '../components/ChatInput.ets';
import {
  AgentMessageRow, NO_AGENT_MESSAGE_ROW_CAPABILITIES,
} from '../components/AgentMessageRow.ets';
import { InteractiveMessageTimeline } from '../components/InteractiveMessageTimeline.ets';
import { NovelDiscussionAskCard } from '../components/NovelDiscussionAskCard.ets';
import { NovelSettingProposalCard } from '../components/NovelSettingProposalCard.ets';
import {
  NovelBatchPolishSheet, NovelBatchPolishSheetChapter, NovelBatchPolishSheetResult,
} from '../components/NovelBatchPolishSheet.ets';
import { KeyboardAvoidMode, router, promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { getNovelCreation, getNovelWorkspaceService } from '../di/AppContainer.ets';
import type { NovelBookExportBundle } from '../platform_impl/NovelWorkspaceService.ets';
import { MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget,
  NovelRunKind, NovelRun, NovelChapterVersion, NovelChapterVersionKind,
  parseDiscussionAsk, stripDiscussionAskFence, stripSettingProposalsFence, formatDiscussionAnswersFromToolPayload,
  NovelDiscussionAskParsed, NovelSettingProposal, NovelBatchPolishEvent, NovelBatchPolishRun,
  StreamTransportState, UIMessage, UIMessageAnnotation, makeUIMessage,
} from '@amber/deepread-domain';
import {
  shouldScrollOnStreamUpdate, transitionOnGenerateEnd, transitionOnJumpToBottom,
  transitionOnStreamUpdate, transitionOnUserScroll,
} from '@amber/chat-domain';
import type { FollowMode } from '@amber/chat-domain';

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
interface ExportMenuResult {
  index: number;
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
  @State activeMessages: UIMessage[] = [];
  @State activeTextDeltasLive: boolean = false;
  @State activeTransport: StreamTransportState = 'unavailable';
  @State activeFrameSeen: boolean = false;
  @State followMode: FollowMode = 'idle';
  @State atBottomApprox: boolean = true;
  @State jumpButtonVisible: boolean = false;
  @State generationStatus: string = '';
  @State activeRunId: string = '';
  @State activeRunCreatedAt: number = 0;
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
  @StorageProp('navBarHeight') navBarHeight: number = 0;
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
  // 批量润色：所有集合型状态均用整体赋值，保证 ArkUI V1 可观察。
  @State batchPolishOpen: boolean = false;
  @State batchPolishRunning: boolean = false;
  @State batchPolishCurrent: number = 0;
  @State batchPolishTotal: number = 0;
  @State batchPolishTitle: string = '';
  @State batchPolishResults: NovelBatchPolishSheetResult[] = [];
  private batchPolishRun: NovelBatchPolishRun | null = null;
  private batchPolishUnsub: (() => void) | null = null;

  private projectId: string = '';
  private currentUnsub: (() => void) | null = null;
  private chatScroller: Scroller = new Scroller();
  private pageAlive: boolean = false;
  private reloadToken: number = 0;
  private initialScrollArmed: boolean = false;
  private userTouchActive: boolean = false;

  aboutToAppear(): void {
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.pageAlive = true;
    const params = router.getParams() as Record<string, string>;
    this.projectId = params ? (params['projectId'] ?? '') : '';
    this.reload();
  }

  aboutToDisappear(): void {
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.OFFSET);
    this.pageAlive = false;
    this.reloadToken++;
    if (this.activeRunId.length > 0) {
      getNovelCreation().interrupt(this.activeRunId);
    }
    if (this.currentUnsub !== null) {
      const unsub: (() => void) = this.currentUnsub;
      this.currentUnsub = null;
      unsub(); // 终结即退订,emitter 不再持有页面闭包
    }
    if (this.batchPolishRun !== null) {
      this.batchPolishRun.cancel();
      this.batchPolishRun = null;
    }
    if (this.batchPolishUnsub !== null) {
      const unsub: (() => void) = this.batchPolishUnsub;
      this.batchPolishUnsub = null;
      unsub();
    }
  }

  async reload(): Promise<void> {
    if (this.projectId.length === 0) return;
    const token: number = ++this.reloadToken;
    const projectId: string = this.projectId;
    try {
      const project: NovelProject = await getNovelCreation().open(projectId);
      if (this.pageAlive && token === this.reloadToken) {
        this.project = project;
        // 等 List 收到新 rows 的可见范围回调再落底；Promise 返回后的
        // setTimeout 仍可能早于 ArkUI 完成 ForEach 布局。
        this.initialScrollArmed = true;
      }
    } catch (e) {
      if (this.pageAlive && token === this.reloadToken) this.errorMsg = '项目无法打开';
    }
  }

  private writeExportBytes(uri: string, bytes: Uint8Array): void {
    const file: fileIo.File = fileIo.openSync(
      uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const exact: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      fileIo.writeSync(file.fd, exact);
    } finally {
      fileIo.closeSync(file);
    }
  }

  private encodeExportText(text: string): Uint8Array {
    const values: number[] = [];
    for (let i: number = 0; i < text.length; i++) {
      let cp: number = text.charCodeAt(i);
      if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
        const low: number = text.charCodeAt(i + 1);
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
          i += 1;
        }
      }
      if (cp < 0x80) values.push(cp);
      else if (cp < 0x800) values.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
      else if (cp < 0x10000) {
        values.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      } else {
        values.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
          0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      }
    }
    return new Uint8Array(values);
  }

  private async saveExportFile(fileName: string, bytes: Uint8Array): Promise<void> {
    const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    options.newFileNames = [fileName];
    const context: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    const uris: string[] = await documentPicker.save(options);
    if (uris.length === 0) return;
    this.writeExportBytes(uris[0], bytes);
    promptAction.showToast({ message: `已导出 ${fileName}`, duration: 1600 });
  }

  private async exportArtifact(index: number): Promise<void> {
    if (this.project === null || this.busy) return;
    this.busy = true;
    try {
      const safeName: string = this.project.name.replace(/[\\/:*?"<>|]/g, '-');
      if (index === 0) {
        const zip: Uint8Array = await getNovelWorkspaceService().exportWorkspaceZip(this.projectId);
        await this.saveExportFile(`${safeName}.amber-novel.zip`, zip);
      } else {
        const book: NovelBookExportBundle = await getNovelWorkspaceService().exportBook(this.projectId);
        if (index === 1) await this.saveExportFile(`${safeName}.txt`, this.encodeExportText(book.text));
        else if (index === 2) {
          await this.saveExportFile(`${safeName}.md`, this.encodeExportText(book.markdown));
        } else if (index === 3) await this.saveExportFile(`${safeName}.epub`, book.epub);
      }
      this.errorMsg = '';
    } catch (e) {
      this.errorMsg = `导出失败：${String(e)}`;
      promptAction.showToast({ message: this.errorMsg, duration: 2400 });
    } finally {
      this.busy = false;
    }
  }

  private showExportMenu(): void {
    if (this.project === null || this.busy) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '工作区 ZIP', color: INK },
        { text: '正文 TXT', color: INK },
        { text: '正文 Markdown', color: INK },
        { text: '正文 EPUB', color: INK },
      ],
    }).then((result: ExportMenuResult): void => {
      this.exportArtifact(result.index);
    }).catch((): void => {});
  }

  // ===== 聊天 =====
  assistantLabel(m: NovelMessage): string {
    if (m.interrupted) return '已停止（内容已保留）';
    if (m.mode === 'discuss') return '讨论';
    return m.granularity === 'continuation' ? '续写' : '整章';
  }

  private messageTimestamp(epochMillis: number): string {
    return new Date(epochMillis).toISOString();
  }

  private displayedNovelContent(message: NovelMessage): string {
    if (message.mode !== 'discuss') return message.content;
    return stripSettingProposalsFence(stripDiscussionAskFence(message.content));
  }

  private projectMessageUi(message: NovelMessage): UIMessage {
    const annotations: UIMessageAnnotation[] = message.interrupted
      ? [{ type: 'generation_interrupted', reason: '用户停止生成' }]
      : [];
    const createdAt: string = this.messageTimestamp(message.createdAt);
    return makeUIMessage(message.role, [{
      type: 'text',
      text: this.displayedNovelContent(message),
      metadata: null,
    }], {
      id: message.id,
      annotations,
      createdAt,
      finishedAt: createdAt,
      modelId: this.project?.modelId ?? null,
    });
  }

  private pendingUserUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('user', [{ type: 'text', text: this.pendingUserText, metadata: null }], {
      id: `novel-user:${this.activeRunId}`,
      createdAt,
      finishedAt: createdAt,
      modelId: this.project?.modelId ?? null,
    });
  }

  private activeStatusUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('assistant', [{ type: 'text', text: this.generationStatus, metadata: null }], {
      id: `novel-run:${this.activeRunId}`,
      createdAt,
      finishedAt: null,
      modelId: this.project?.modelId ?? null,
    });
  }

  private scheduleNovelScrollToBottom(): void {
    setTimeout((): void => {
      if (!this.pageAlive) return;
      this.atBottomApprox = true;
      this.jumpButtonVisible = false;
      this.chatScroller.scrollEdge(Edge.Bottom);
    }, 0);
  }

  private beginNovelGenerationFollow(): void {
    this.followMode = transitionOnJumpToBottom(true);
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.scheduleNovelScrollToBottom();
  }

  private followNovelStreamingUpdate(): void {
    this.followMode = transitionOnStreamUpdate(this.followMode, this.atBottomApprox);
    this.jumpButtonVisible = this.followMode === 'paused';
    if (shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) {
      this.scheduleNovelScrollToBottom();
    }
  }

  private finishNovelGenerationFollow(): void {
    const wasPaused: boolean = this.followMode === 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.jumpButtonVisible = wasPaused;
    if (!wasPaused) this.scheduleNovelScrollToBottom();
  }

  private reconcileNovelUserScroll(): void {
    let atEnd: boolean = false;
    try {
      atEnd = this.chatScroller.isAtEnd();
    } catch (_error) {
      atEnd = this.atBottomApprox;
    }
    this.atBottomApprox = atEnd;
    this.followMode = transitionOnUserScroll(
      this.followMode, atEnd, this.activeRunId.length > 0);
    this.jumpButtonVisible = !atEnd;
  }

  private jumpNovelToBottom(): void {
    this.followMode = transitionOnJumpToBottom(this.activeRunId.length > 0);
    this.scheduleNovelScrollToBottom();
  }

  send(runKindOverride: NovelRunKind | null = null): void {
    const text: string = this.draft.trim();
    if (text.length === 0 || this.hasBlockingNovelRun() || this.project === null) return;
    const mode: NovelChatMode = this.composeMode === 'discuss' ? 'discuss' : 'write';
    let gran: NovelGenerationGranularity | null = null;
    if (this.composeMode === 'continue') gran = 'continuation';
    else if (this.composeMode === 'whole_chapter') gran = 'whole_chapter';
    let runKind: NovelRunKind = 'discussion';
    if (this.composeMode === 'continue') runKind = 'prose_continuation';
    else if (this.composeMode === 'whole_chapter') runKind = 'prose_whole_chapter';
    if (runKindOverride !== null) runKind = runKindOverride;

    this.pendingUserText = text;
    this.draft = '';
    this.activeMessages = [];
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = '正在连接模型并组织正文…';
    this.errorMsg = '';

    const run = getNovelCreation().generate(this.projectId, text, mode, gran, runKind);
    this.activeRunId = run.id;
    this.activeRunCreatedAt = Date.now();
    this.beginNovelGenerationFollow();
    this.currentUnsub = run.subscribe((e): void => {
      if (e.kind === 'status') {
        if (e.text.length > 0) this.generationStatus = e.text;
      } else if (e.kind === 'snapshot') {
        this.activeMessages = e.messages.slice();
        this.activeTextDeltasLive = e.textDeltasLive;
        this.activeTransport = e.transport;
        this.activeFrameSeen = true;
        this.followNovelStreamingUpdate();
      } else if (e.kind === 'completed' || e.kind === 'interrupted' || e.kind === 'failed') {
        this.finishNovelGenerationFollow();
        this.activeRunId = '';
        this.activeRunCreatedAt = 0;
        this.pendingUserText = '';
        this.activeMessages = [];
        this.activeTextDeltasLive = false;
        this.activeTransport = 'unavailable';
        this.activeFrameSeen = false;
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

  private hasBlockingNovelRun(): boolean {
    return this.activeRunId.length > 0 || this.batchPolishRunning;
  }

  openCollect(messageId: string): void {
    this.collectMessageId = messageId;
    const p: NovelProject | null = this.project;
    this.collectTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
  }

  private discussionAskFor(message: NovelMessage): NovelDiscussionAskParsed | null {
    if (message.role !== 'assistant' || message.mode !== 'discuss') return null;
    return parseDiscussionAsk(message.content);
  }

  // 一旦结构化提问之后已经出现用户讨论消息，该卡不再显示，避免旧问题重复提交。
  private shouldShowDiscussionAsk(messageId: string): boolean {
    const p: NovelProject | null = this.project;
    if (p === null) return false;
    const index: number = p.messages.findIndex((message: NovelMessage): boolean => message.id === messageId);
    if (index < 0) return false;
    for (let cursor: number = index + 1; cursor < p.messages.length; cursor++) {
      const next: NovelMessage = p.messages[cursor];
      if (next.role === 'user' && next.mode === 'discuss') return false;
    }
    return true;
  }

  private discussionAskInputFor(message: NovelMessage): string {
    const ask: NovelDiscussionAskParsed | null = this.discussionAskFor(message);
    return ask !== null ? ask.toolInput : '';
  }

  private submitDiscussionAsk(source: NovelMessage, payload: string): void {
    const ask: NovelDiscussionAskParsed | null = this.discussionAskFor(source);
    if (ask === null || this.hasBlockingNovelRun()) return;
    this.draft = formatDiscussionAnswersFromToolPayload(payload, ask.questions);
    this.composeMode = 'discuss';
    this.send('discussion');
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

  private async resolveSettingProposal(proposal: NovelSettingProposal, accept: boolean): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().resolveSettingProposal(this.projectId, proposal.id, accept);
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 章节 =====
  openNewChapter(): void {
    if (this.batchPolishRunning) return;
    this.editingChapterId = '';
    const p: NovelProject | null = this.project;
    this.chapterTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
    this.chapterContent = '';
    this.chapterEditorOpen = true;
  }

  openEditChapter(c: NovelChapter): void {
    if (this.batchPolishRunning) return;
    this.editingChapterId = c.id;
    this.chapterTitle = c.title;
    this.chapterContent = c.content;
    this.chapterEditorOpen = true;
  }

  // N2:订阅章节级 Run(复用 send 的流式订阅;终态 reload)
  private attachChapterRun(run: NovelRun, statusText: string): void {
    this.activeRunId = run.id;
    this.activeRunCreatedAt = Date.now();
    this.activeMessages = [];
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = statusText;
    this.errorMsg = '';
    this.beginNovelGenerationFollow();
    this.currentUnsub = run.subscribe((e): void => {
      if (e.kind === 'status') {
        if (e.text.length > 0) this.generationStatus = e.text;
      } else if (e.kind === 'snapshot') {
        this.activeMessages = e.messages.slice();
        this.activeTextDeltasLive = e.textDeltasLive;
        this.activeTransport = e.transport;
        this.activeFrameSeen = true;
        this.followNovelStreamingUpdate();
      } else if (e.kind === 'completed' || e.kind === 'interrupted' || e.kind === 'failed') {
        this.finishNovelGenerationFollow();
        this.activeRunId = '';
        this.activeRunCreatedAt = 0;
        this.activeMessages = [];
        this.activeTextDeltasLive = false;
        this.activeTransport = 'unavailable';
        this.activeFrameSeen = false;
        this.generationStatus = '';
        if (e.kind === 'failed') this.errorMsg = e.message;
        this.currentUnsub = null;
        this.reload();
      }
    });
  }

  // N2:单章重新生成(整章 regeneration Run)
  async regenerateChapter(chapterId: string): Promise<void> {
    if (this.hasBlockingNovelRun()) return;
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
    if (this.hasBlockingNovelRun()) return;
    try {
      const creation = getNovelCreation();
      const run: NovelRun = await creation.polishChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '润色中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  private batchSheetChapters(): NovelBatchPolishSheetChapter[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.chapters.map((chapter: NovelChapter): NovelBatchPolishSheetChapter => ({
      id: chapter.id,
      title: chapter.title,
      discarded: chapter.discarded,
    }));
  }

  private chapterTitleFor(chapterId: string): string {
    const p: NovelProject | null = this.project;
    const chapter: NovelChapter | undefined = p?.chapters.find((item: NovelChapter): boolean => item.id === chapterId);
    return chapter !== undefined && chapter.title.length > 0 ? chapter.title : '未命名章节';
  }

  private startBatchPolish(chapterIds: string[]): void {
    if (this.batchPolishRunning || chapterIds.length === 0) return;
    this.batchPolishResults = [];
    this.batchPolishCurrent = 0;
    this.batchPolishTotal = chapterIds.length;
    this.batchPolishTitle = '';
    this.batchPolishRunning = true;
    const run: NovelBatchPolishRun = getNovelCreation().startBatchPolish(this.projectId, chapterIds);
    this.batchPolishRun = run;
    this.batchPolishUnsub = run.subscribe((event: NovelBatchPolishEvent): void => {
      this.handleBatchPolishEvent(event);
    });
  }

  private handleBatchPolishEvent(event: NovelBatchPolishEvent): void {
    if (event.kind === 'progress') {
      this.batchPolishCurrent = event.completed + 1;
      this.batchPolishTotal = event.total;
      this.batchPolishTitle = this.chapterTitleFor(event.chapterId);
      return;
    }
    if (event.kind === 'result') {
      this.putBatchPolishResult(event.result.chapterId, event.result.status, event.result.messageId, event.result.error);
      return;
    }
    this.batchPolishResults = event.results.map((result) => this.toBatchPolishSheetResult(
      result.chapterId, result.status, result.messageId, result.error));
    this.batchPolishRunning = false;
    this.batchPolishRun = null;
    if (this.batchPolishUnsub !== null) {
      const unsub: (() => void) = this.batchPolishUnsub;
      this.batchPolishUnsub = null;
      unsub();
    }
    this.reload();
  }

  private putBatchPolishResult(
    chapterId: string, status: 'succeeded' | 'failed' | 'cancelled', messageId: string | null, error: string | null,
  ): void {
    const next: NovelBatchPolishSheetResult[] = this.batchPolishResults.filter(
      (result: NovelBatchPolishSheetResult): boolean => result.chapterId !== chapterId);
    next.push(this.toBatchPolishSheetResult(chapterId, status, messageId, error));
    this.batchPolishResults = next;
  }

  private toBatchPolishSheetResult(
    chapterId: string, status: 'succeeded' | 'failed' | 'cancelled', messageId: string | null, error: string | null,
  ): NovelBatchPolishSheetResult {
    return {
      chapterId: chapterId,
      title: this.chapterTitleFor(chapterId),
      status: status === 'succeeded' ? 'ready' : status,
      detail: error ?? '',
      messageId: messageId ?? '',
    };
  }

  private cancelBatchPolish(): void {
    if (this.batchPolishRun !== null) this.batchPolishRun.cancel();
  }

  private closeBatchPolishSheet(): void {
    if (this.batchPolishRunning) return;
    this.batchPolishOpen = false;
    this.batchPolishResults = [];
    this.batchPolishCurrent = 0;
    this.batchPolishTotal = 0;
    this.batchPolishTitle = '';
  }

  private async adoptBatchPolish(chapterId: string, messageId: string): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().adoptPolishMessage(this.projectId, chapterId, messageId);
      this.batchPolishResults = this.batchPolishResults.map((result: NovelBatchPolishSheetResult): NovelBatchPolishSheetResult =>
        result.chapterId === chapterId ? {
          chapterId: result.chapterId, title: result.title, status: 'adopted', detail: result.detail,
          messageId: result.messageId,
        } : result);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
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
    if (this.batchPolishRunning) return;
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
    if (this.batchPolishRunning) return;
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
  novelMessageHeader(message: NovelMessage): void {
    if (message.role === 'assistant') {
      Text(this.assistantLabel(message))
        .fontSize(12)
        .fontColor(INK3)
        .margin({ bottom: 4 })
    }
  }

  @Builder
  novelMessageFooter(message: NovelMessage): void {
    if (!this.hasBlockingNovelRun() && this.shouldShowDiscussionAsk(message.id) &&
      this.discussionAskInputFor(message).length > 0) {
      NovelDiscussionAskCard({
        input: this.discussionAskInputFor(message),
        askId: message.id,
        onSubmit: (payload: string): void => { this.submitDiscussionAsk(message, payload); },
      })
    }
    if (message.mode === 'write' && message.collectedChapterId === null &&
      message.content.trim().length > 0) {
      Row() {
        Text(message.interrupted ? '收进章节（部分）' : '收进章节')
          .fontSize(12)
          .fontColor(ACCENT)
      }
      .constraintSize({ minHeight: 44 })
      .padding({ left: 12, right: 12 })
      .borderRadius(22)
      .backgroundColor(SURFACE2)
      .margin({ top: 6 })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => { this.openCollect(message.id); })
      .accessibilityText(message.interrupted ? '收进章节，部分内容' : '收进章节')
    } else if (message.collectedChapterId !== null) {
      Text('已收进章节').fontSize(12).fontColor(GREEN).margin({ top: 10 })
    }
  }

  @Builder
  activeRunHeader(show: boolean = true): void {
    if (show) {
      Text(this.activeMessages.length > 0 ? '正在写' : '正在思考')
        .fontSize(12)
        .fontColor(ACCENT)
        .margin({ bottom: 4 })
      if (this.activeFrameSeen) {
        Text(this.activeTransport === 'live' ? '实时' :
          (this.activeTransport === 'buffered' ? '缓冲' : '非流式'))
          .fontSize(10)
          .fontColor(INK3)
          .margin({ left: 8, bottom: 4 })
      }
    }
  }

  @Builder
  emptyAgentRowSlot(): void {}

  @Builder
  novelTimelineRows(): void {
    if (this.project !== null) {
      ForEach(this.project.messages, (message: NovelMessage): void => {
        ListItem() {
          AgentMessageRow({
            message: this.projectMessageUi(message),
            capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
            hostHeader: (): void => { this.novelMessageHeader(message); },
            hostFooter: (): void => { this.novelMessageFooter(message); },
            streaming: false,
          })
        }
      }, (message: NovelMessage): string => message.id)
      ForEach(this.project.settingProposals.filter(
        (proposal: NovelSettingProposal): boolean => proposal.status === 'pending'),
      (proposal: NovelSettingProposal): void => {
        ListItem() {
          NovelSettingProposalCard({
            title: proposal.title,
            content: proposal.content,
            kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
            onResolve: (accept: boolean): void => { this.resolveSettingProposal(proposal, accept); },
          })
        }
      }, (proposal: NovelSettingProposal): string => proposal.id)
    }
    if (this.pendingUserText.length > 0) {
      ListItem() {
        AgentMessageRow({
          message: this.pendingUserUi(),
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.emptyAgentRowSlot(); },
          hostFooter: (): void => { this.emptyAgentRowSlot(); },
          streaming: false,
        })
      }
    }
  }

  @Builder
  novelTimelineActiveTail(): void {
    if (this.activeRunId.length > 0) {
      if (this.activeMessages.length > 0) {
        ForEach(this.activeMessages, (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({
              message,
              capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
              hostHeader: (): void => { this.activeRunHeader(index === 0); },
              hostFooter: (): void => { this.emptyAgentRowSlot(); },
              streaming: true,
              textDeltasLive: this.activeTextDeltasLive,
              isLastAssistant: index === this.activeMessages.length - 1,
              onTextGrow: (): void => { this.followNovelStreamingUpdate(); },
            })
          }
        }, (message: UIMessage): string => message.id)
      } else {
        ListItem() {
          AgentMessageRow({
            message: this.activeStatusUi(),
            capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
            hostHeader: (): void => { this.activeRunHeader(); },
            hostFooter: (): void => { this.emptyAgentRowSlot(); },
            streaming: true,
            textDeltasLive: false,
            isLastAssistant: true,
            onTextGrow: (): void => { this.followNovelStreamingUpdate(); },
          })
        }
      }
    }
  }

  @Builder
  emptyTimelineSlot(): void {}

  @Builder
  chatPane(): void {
    Column() {
    Row() {
      Blank()
      Text('归档讨论').fontSize(12).fontColor(INK3)
        .padding({ left: 12, right: 20, top: 6, bottom: 6 })
        .onClick((): void => { this.archiveDiscussion(); })
    }
    .width('100%').padding({ top: 8 })
    Column() {
      if (this.project !== null && this.project.messages.length === 0 && this.activeRunId.length === 0) {
        Column() {
          Text('从一个念头开始').fontSize(15).fontColor(INK2)
          Text('讨论设定、续写当前场景，或直接生成完整章节。')
            .fontSize(12).fontColor(INK3).margin({ top: 6 })
            .textAlign(TextAlign.Center)
        }
        .alignItems(HorizontalAlign.Center)
        .width('100%').padding({ left: 24, right: 24, top: 120, bottom: 24 })
      }
      // Chat 与 Novel 共享同一 Timeline/AgentMessageRow；Timeline 独占 16vp 水平 gutter。
      InteractiveMessageTimeline({
        scroller: this.chatScroller,
        horizontalGutter: 16,
        topPadding: 8,
        bottomPadding: 0,
        cachedItems: 5,
        showOlderLoader: false,
        showDivider: false,
        rows: (): void => { this.novelTimelineRows(); },
        activeTail: (): void => { this.novelTimelineActiveTail(); },
        hostTail: (): void => { this.emptyTimelineSlot(); },
        onTimelineAppear: (): void => {
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onVisibleRangeChange: (_start: number, _end: number): void => {
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) this.userTouchActive = true;
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.userTouchActive = false;
            this.reconcileNovelUserScroll();
          }
        },
        onTimelineScroll: (_offset: number, state: ScrollState): void => {
          if (this.userTouchActive &&
            (state === ScrollState.Scroll || state === ScrollState.Fling)) {
            this.reconcileNovelUserScroll();
          }
        },
        onTimelineScrollStop: (): void => { this.reconcileNovelUserScroll(); },
      })
      .overlay(this.novelJumpButton(), { align: Alignment.BottomEnd })
      .layoutWeight(1)
      .clip(true)

      if (this.errorMsg.length > 0) {
        Text(this.errorMsg).fontSize(12).fontColor('#E53935').padding({ left: 16, right: 16, bottom: 6 })
      }

      // 输入器(复用聊天页 ChatInput;上方保留模式切换)
      Column() {
        Row() {
          this.modeChip('讨论', 'discuss')
          this.modeChip('续写', 'continue')
          this.modeChip('整章', 'whole_chapter')
          Blank()
        }
        .width('100%').margin({ bottom: 4 })

        ChatInput({
          text: this.draft,
          sending: this.hasBlockingNovelRun(),
          onTextChange: (v: string): void => { this.draft = v; },
          onSend: (): void => { this.send(); },
          onStop: (): void => {
            if (this.batchPolishRunning) this.cancelBatchPolish();
            else this.interrupt();
          },
          onClearInput: (): void => { this.draft = ''; },
        })
      }
      // 全屏布局下手势条悬浮(bottomRect=0 → navBarHeight=0),须自留 ≥24vp 安全区
      .width('100%').padding({ left: 16, right: 16, top: 8,
        bottom: 12 + Math.max(this.navBarHeight, 24) })
      .border({ width: { top: 0.5 }, color: { top: LINE } })
    }
    .layoutWeight(1)
    .width('100%')
    }
    .layoutWeight(1)
    .width('100%')
  }

  @Builder
  private novelJumpButton(): void {
    if (this.jumpButtonVisible) {
      Stack() {
        Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
      }
      .width(44)
      .height(44)
      .margin({ right: 16, bottom: 12 })
      .backgroundColor(SURFACE2)
      .borderRadius(22)
      .border({ width: 1, color: LINE })
      .alignContent(Alignment.Center)
      .onClick((): void => { this.jumpNovelToBottom(); })
      .accessibilityText('回到消息底部')
    }
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
      .opacity(this.hasBlockingNovelRun() ? 0.5 : 1)
      .onClick(() => { if (!this.hasBlockingNovelRun()) this.composeMode = mode; })
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
        Text(m.mode === 'discuss' ? stripSettingProposalsFence(stripDiscussionAskFence(m.content)) : m.content)
          .fontSize(14).fontColor(INK).lineHeight(24)
        if (!this.hasBlockingNovelRun() && this.shouldShowDiscussionAsk(m.id) && this.discussionAskInputFor(m).length > 0) {
          NovelDiscussionAskCard({
            input: this.discussionAskInputFor(m),
            askId: m.id,
            onSubmit: (payload: string): void => { this.submitDiscussionAsk(m, payload); },
          })
        }
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
          Text('批量润色').fontSize(12).fontColor(INK3).margin({ right: 16 })
            .opacity(this.batchPolishRunning ? 0.5 : 1)
            .onClick((): void => {
              if (!this.batchPolishRunning) this.batchPolishOpen = true;
            })
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
          Text('导出').fontSize(14).fontColor(this.busy ? INK4 : ACCENT)
            .width(44).height(44).textAlign(TextAlign.Center)
            .onClick((): void => { this.showExportMenu(); })
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
      if (this.batchPolishOpen) {
        NovelBatchPolishSheet({
          chapters: this.batchSheetChapters(),
          running: this.batchPolishRunning,
          current: this.batchPolishCurrent,
          total: this.batchPolishTotal,
          currentTitle: this.batchPolishTitle,
          results: this.batchPolishResults,
          onStart: (chapterIds: string[]): void => { this.startBatchPolish(chapterIds); },
          onCancel: (): void => { this.cancelBatchPolish(); },
          onAdopt: (chapterId: string, messageId: string): void => { this.adoptBatchPolish(chapterId, messageId); },
          onClose: (): void => { this.closeBatchPolishSheet(); },
        })
      }
    }
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
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
    if (id.length === 0 || this.batchPolishRunning) return;
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
    if (this.batchPolishRunning) return;
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
    if (this.batchPolishRunning) return;
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
