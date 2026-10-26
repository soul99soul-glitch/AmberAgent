import type {
  Conversation, ConversationRepository, ConversationArtifactIndex, ConversationArtifactSource,
  ConversationDocumentArtifact, UIMessagePartDocument,
} from '@amber/chat-domain';
import { collectConversationArtifacts, artifactSourceNodeIndex } from '@amber/chat-domain';
import type { ConversationRunService, ConversationRunSnapshot } from '../ConversationRunService.ets';

export interface ChatArtifactCallbacks {
  currentConversation: () => Conversation | null;
  fullyLoaded: () => boolean;
  enableGenerativeWidgets: () => boolean;
  error: (message: string) => void;
  locateSource: (conversation: Conversation, nodeId: string, index: number) => void;
}

// The page retains the owner across theme remounts. Canonical history remains
// with the page/run service and is resolved again for every shelf/source action.
@Observed
export class ChatArtifactSession {
  documentPreviewOpen: boolean = false;
  documentPreviewTitle: string = '';
  documentPreviewText: string = '';
  artifactShelfOpen: boolean = false;
  artifactDiffOpen: boolean = false;
  artifactDiffTitle: string = '';
  artifactDiffText: string = '';
  shelfArtifacts: ConversationArtifactIndex = {
    images: [], videos: [], docs: [], miniApps: [], widgets: [], svg: [], scenes: [], uiSnapshots: [], duplicateDocumentNames: [],
  };
  private documentPreviewSeq: number = 0;
  private previewReader: ((part: UIMessagePartDocument) => Promise<string>) | null = null;
  private alive: boolean = false;
  private visible: boolean = false;
  private repository: ConversationRepository | null = null;
  private runService: ConversationRunService | null = null;
  private callbacks: ChatArtifactCallbacks = {
    currentConversation: (): Conversation | null => null,
    fullyLoaded: (): boolean => false,
    enableGenerativeWidgets: (): boolean => false,
    error: (_message: string): void => {},
    locateSource: (_conversation: Conversation, _nodeId: string, _index: number): void => {},
  };

  // Start through the observed proxy, never from the constructor or child view.
  start(repository: ConversationRepository, runService: ConversationRunService, callbacks: ChatArtifactCallbacks,
    previewReader: (part: UIMessagePartDocument) => Promise<string>): void {
    this.previewReader = previewReader;
    this.repository = repository;
    this.runService = runService;
    this.callbacks = callbacks;
    this.alive = true;
    this.visible = true;
  }

  show(): void { if (this.alive) this.visible = true; }
  hide(): void { this.visible = false; }
  dispose(): void { this.alive = false; this.visible = false; }

  // 产物架使用完整已提交历史，不受聊天首屏分页窗口限制。
  async openArtifactShelf(): Promise<void> {
    const repository: ConversationRepository | null = this.repository;
    const runService: ConversationRunService | null = this.runService;
    if (repository === null || runService === null) return;
    const current: Conversation | null = this.callbacks.currentConversation();
    if (current === null) return;
    try {
      const stored: Conversation | null = this.callbacks.fullyLoaded()
        ? current : await repository.getById(current.id);
      if (!this.alive || !this.visible || this.callbacks.currentConversation()?.id !== current.id) return;
      const active: ConversationRunSnapshot | null = runService.snapshot(current.id);
      const full: Conversation | null = active?.conversation ?? stored;
      if (full === null) { this.callbacks.error('会话已不存在'); return; }
      this.shelfArtifacts = collectConversationArtifacts(full, this.callbacks.enableGenerativeWidgets());
      this.artifactShelfOpen = true;
    } catch (error) {
      if (this.alive && this.callbacks.currentConversation()?.id === current.id) this.callbacks.error(`无法打开产物架：${(error as Error).message}`);
    }
  }

  async openArtifactSource(source: ConversationArtifactSource): Promise<void> {
    const repository: ConversationRepository | null = this.repository;
    const runService: ConversationRunService | null = this.runService;
    if (repository === null || runService === null) return;
    if (this.callbacks.currentConversation()?.id !== source.conversationId) { this.callbacks.error('来源会话已切换'); return; }
    try {
      const stored: Conversation | null = await repository.getById(source.conversationId);
      if (!this.alive || !this.visible || this.callbacks.currentConversation()?.id !== source.conversationId) return;
      const active: ConversationRunSnapshot | null = runService.snapshot(source.conversationId);
      const full: Conversation | null = active?.conversation ?? stored;
      const index: number = full === null ? -1 : artifactSourceNodeIndex(full, source);
      if (full === null || index < 0) { this.callbacks.error('来源消息已不存在或不在当前分支'); return; }
      this.artifactShelfOpen = false;
      this.callbacks.locateSource(full, source.nodeId, index);
    } catch (error) {
      if (this.alive && this.callbacks.currentConversation()?.id === source.conversationId) this.callbacks.error(`无法定位来源：${(error as Error).message}`);
    }
  }

  // D0-12:同名文档两最新版本对比(readDocumentPreview 取文本;失败诚实展示)
  async openArtifactDiff(fileName: string): Promise<void> {
    const readDocumentPreview = this.previewReader;
    if (readDocumentPreview === null) return;
    const matches: UIMessagePartDocument[] = this.shelfArtifacts.docs.map(
      (artifact: ConversationDocumentArtifact): UIMessagePartDocument => artifact.part).filter(
      (d: UIMessagePartDocument): boolean =>
        (d.fileName.length > 0 ? d.fileName : '(未命名)') === fileName);
    if (matches.length < 2) return;
    this.artifactDiffTitle = `版本对比:${fileName}`;
    this.artifactDiffText = '正在读取两个版本…';
    this.artifactDiffOpen = true;
    try {
      const older: string = await readDocumentPreview(matches[matches.length - 2]);
      const newer: string = await readDocumentPreview(matches[matches.length - 1]);
      this.artifactDiffText = this.diffTextSummary(older, newer);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.artifactDiffText = `无法对比:${msg}`;
    }
  }

  // soul diff 同款诚实行级摘要:行多重集增减统计 + 首个位置差异上下文(非 LCS)
  private diffTextSummary(older: string, newer: string): string {
    const la: string[] = older.split('\n');
    const lb: string[] = newer.split('\n');
    const counts: Map<string, number> = new Map<string, number>();
    for (const l of la) counts.set(l, (counts.get(l) ?? 0) + 1);
    let added: number = 0;
    for (const l of lb) {
      const c: number = counts.get(l) ?? 0;
      if (c > 0) counts.set(l, c - 1); else added++;
    }
    let removed: number = 0;
    counts.forEach((c: number): void => { removed += c; });
    let firstDiff: number = -1;
    const minLen: number = Math.min(la.length, lb.length);
    for (let i = 0; i < minLen; i++) {
      if (la[i] !== lb[i]) { firstDiff = i; break; }
    }
    if (firstDiff < 0 && la.length !== lb.length) firstDiff = minLen;
    let out: string = `旧版本 ${la.length} 行 · 新版本 ${lb.length} 行\n` +
      `+${added} 行新增 · −${removed} 行移除(按行统计,非逐字 diff)\n`;
    if (added === 0 && removed === 0) {
      out += '\n两个版本内容一致。';
      return out;
    }
    if (firstDiff >= 0) {
      const lo: number = Math.max(0, firstDiff - 2);
      const hi: number = Math.min(Math.max(la.length, lb.length), firstDiff + 3);
      out += `\n首处差异(第 ${firstDiff + 1} 行附近):\n`;
      for (let i = lo; i < hi; i++) {
        const a: string = i < la.length ? la[i] : '';
        const b: string = i < lb.length ? lb[i] : '';
        if (a === b) {
          out += `  ${i + 1}  ${a}\n`;
        } else {
          if (a.length > 0) out += `− ${i + 1}  ${a}\n`;
          if (b.length > 0) out += `+ ${i + 1}  ${b}\n`;
        }
      }
    }
    return out;
  }

  openDocumentPreview(part: UIMessagePartDocument): void {
    const readDocumentPreview = this.previewReader;
    if (readDocumentPreview === null) return;
    const seq: number = ++this.documentPreviewSeq;
    this.documentPreviewTitle = part.fileName;
    this.documentPreviewText = '正在读取附件…';
    this.documentPreviewOpen = true;
    readDocumentPreview(part).then((text: string): void => {
      if (this.alive && this.documentPreviewOpen && seq === this.documentPreviewSeq) {
        this.documentPreviewText = text;
      }
    }).catch((e: Error): void => {
      if (this.alive && this.documentPreviewOpen && seq === this.documentPreviewSeq) {
        this.documentPreviewText = `无法预览：${e.message}`;
      }
    });
  }

}
