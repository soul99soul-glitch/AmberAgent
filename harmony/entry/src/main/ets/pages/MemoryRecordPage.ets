import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import type { Conversation, MemoryRecord, MessageNode } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { getMemoryLibraryOps, getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';

interface MemoryRecordParams { memoryId?: string; }
interface MemorySourceMessage { messageId: string; nodeId: string | null; }

@Entry
@Component
struct MemoryRecordPage {
  @State record: MemoryRecord | null = null;
  @State draft: string = '';
  @State loaded: boolean = false;
  @State saving: boolean = false;
  @State removing: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  @State sourceStatus: string = '';
  @State sourceAvailable: boolean = false;
  @State sourceMessages: MemorySourceMessage[] = [];
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  private memoryId: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    const params: MemoryRecordParams = router.getParams() as MemoryRecordParams;
    this.memoryId = Number(params?.memoryId ?? '');
    this.alive = true; this.visible = true;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.reload();
  }
  onPageShow(): void { if (!this.visible) { this.visible = true; this.reload(true); } }
  onPageHide(): void { this.visible = false; this.loadSeq++; }
  aboutToDisappear(): void {
    this.alive = false; this.onPageHide();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.OFFSET);
  }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void { if (this.alive && this.visible && !this.saving && !this.removing) this.reload(true); }

  private async reload(preserveDraft: boolean = false): Promise<boolean> {
    const seq: number = ++this.loadSeq;
    let recordRead: boolean = false;
    if (!Number.isInteger(this.memoryId) || this.memoryId <= 0) {
      this.loaded = true; this.error = '源记忆 ID 无效'; return false;
    }
    try {
      const records: MemoryRecord[] = await getMemoryTopicStoreOps().snapshotForTopics();
      if (!this.alive || !this.visible || seq !== this.loadSeq) return false;
      const found: MemoryRecord | null = records.find((item: MemoryRecord): boolean => item.id === this.memoryId) ?? null;
      const cleanDraft: boolean = this.record === null || this.draft === this.record.content;
      this.record = found;
      recordRead = true;
      if (!preserveDraft || cleanDraft) this.draft = found?.content ?? '';
      this.loaded = true; this.error = '';
      this.sourceAvailable = false;
      this.sourceMessages = found === null ? [] : found.sourceMessageIds.map((messageId: string): MemorySourceMessage => ({
        messageId: messageId, nodeId: null,
      }));
      if (found === null) { this.sourceStatus = ''; return true; }
      if (found.sourceConversationId === null || found.sourceConversationId.length === 0) {
        this.sourceStatus = '此记录没有来源会话。'; return true;
      }
      const conversation: Conversation | null = await getChatRepository().getById(found.sourceConversationId);
      if (!this.alive || !this.visible || seq !== this.loadSeq) return false;
      this.sourceAvailable = conversation !== null;
      this.sourceStatus = conversation === null ? '来源会话不存在或已删除。' : conversation.title || '来源会话';
      this.sourceMessages = found.sourceMessageIds.map((messageId: string): MemorySourceMessage => ({
        messageId: messageId,
        nodeId: conversation?.messageNodes.find((node: MessageNode): boolean =>
          node.messages.some((message): boolean => message.id === messageId))?.id ?? null,
      }));
      return true;
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
      }
      return recordRead;
    }
  }

  private async save(): Promise<void> {
    if (this.record === null || this.record.kind === 'topic' || this.saving || this.removing) return;
    const text: string = this.draft.trim();
    if (text.length === 0) { this.error = '源记忆内容不能为空'; return; }
    this.saving = true; this.notice = ''; this.error = '';
    let failure: string = '';
    try { await getMemoryLibraryOps().updateContent(this.memoryId, text); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    const refreshed: boolean = await this.reload(true);
    if (!this.alive) return;
    if (refreshed && this.record?.content === text) {
      this.draft = this.record.content;
      this.notice = '已回读确认源记忆内容已保存。相关旧主题摘要会失效。';
    }
    if (failure.length > 0) this.error = failure;
    this.saving = false;
  }

  private remove(): void {
    if (this.record === null || this.record.kind === 'topic' || this.saving || this.removing) return;
    AlertDialog.show({
      title: '删除源记忆', message: '删除这条源记忆。依赖它的主题摘要和派生文档会随真实源记录刷新。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: this.colors.error, action: (): void => { this.deleteRecord(); } },
    });
  }

  private async deleteRecord(): Promise<void> {
    if (this.removing) return;
    this.removing = true; this.notice = ''; this.error = '';
    let failure: string = '';
    try { await getMemoryLibraryOps().deleteMemory(this.memoryId); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    const refreshed: boolean = await this.reload(true);
    if (!this.alive) return;
    if (refreshed && this.record === null) { this.draft = ''; this.notice = '已回读确认源记忆已删除。'; }
    if (failure.length > 0) this.error = failure;
    this.removing = false;
  }

  private async openSource(messageId: string | null = null): Promise<void> {
    const conversationId: string | null = this.record?.sourceConversationId ?? null;
    if (conversationId === null) return;
    try {
      const conversation: Conversation | null = await getChatRepository().getById(conversationId);
      if (!this.alive || !this.visible) return;
      if (conversation === null) { this.sourceAvailable = false; this.sourceStatus = '来源会话不存在或已删除。'; return; }
      if (messageId === null) {
        router.pushUrl({ url: 'pages/ChatPage', params: { conversationId: conversationId } });
        return;
      }
      const node: MessageNode | undefined = conversation.messageNodes.find((item: MessageNode): boolean =>
        item.messages.some((message): boolean => message.id === messageId));
      if (node === undefined) { this.error = '来源消息已不存在，无法定位。'; return; }
      router.pushUrl({ url: 'pages/ChatPage', params: { conversationId: conversationId, nodeId: node.id } });
    } catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : String(error); }
  }

  private recordLabel(): string {
    if (this.record === null) return '';
    const status: string = this.record.archived ? '已归档' : this.record.expiresAt !== null && this.record.expiresAt <= Date.now() ? '已过期' : '有效';
    return `#${this.record.id} · ${this.record.scope} / ${this.record.kind} · ${status}`;
  }

  @Builder
  private EditorCard(): void {
    Column({ space: 10 }) {
      Text(this.recordLabel()).fontSize(13).fontColor(this.colors.secondary).width('100%')
      if (this.record?.kind === 'topic') {
        Text('主题由整理模型维护，不能通过源记忆编辑器改写。').fontSize(14).fontColor(this.colors.secondary).width('100%')
        Button('打开只读主题详情').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .onClick((): void => { router.replaceUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: this.memoryId.toString() } }); })
      } else {
        TextArea({ text: this.draft, placeholder: '源记忆内容' }).width('100%').height(240)
          .fontSize(15).fontColor(this.colors.ink).placeholderColor(this.colors.secondary).backgroundColor(this.colors.inset)
          .enabled(!this.saving && !this.removing).onChange((value: string): void => { this.draft = value; })
        Text(`创建：${new Date(this.record?.createdAt ?? 0).toLocaleString()}\n更新：${new Date(this.record?.updatedAt ?? 0).toLocaleString()}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Row({ space: 8 }) {
          Button(this.saving ? '保存中' : '保存源记忆').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(14)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(!this.saving && !this.removing)
            .onClick((): void => { this.save(); })
          Button(this.removing ? '删除中' : '删除').type(ButtonType.Normal).height(44).constraintSize({ minWidth: 72 }).fontSize(14)
            .fontColor(this.colors.error).backgroundColor(this.colors.inset).enabled(!this.saving && !this.removing)
            .onClick((): void => { this.remove(); })
        }.width('100%')
      }
    }.width('100%').padding(16)
  }

  @Builder
  private SourceCard(): void {
    Column({ space: 10 }) {
      Text('真实来源').fontSize(15).fontColor(this.colors.ink).width('100%')
      Text(this.sourceStatus).fontSize(13).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
      if (this.record?.sourceConversationId !== null) {
        Text(this.record?.sourceConversationId ?? '').fontSize(12).fontColor(this.colors.secondary).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      }
      if (this.sourceAvailable) {
        Button('打开来源会话').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset).onClick((): void => { this.openSource(); })
      }
      if (this.record?.sourceMessageIds.length === 0) {
        Text('没有来源消息 ID。').fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
      ForEach(this.sourceMessages, (source: MemorySourceMessage): void => {
        Column({ space: 4 }) {
          Text(source.messageId).fontSize(12).fontColor(this.colors.secondary).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          if (source.nodeId !== null) {
            Button('定位来源消息所在节点').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
              .fontColor(this.accentColor).backgroundColor(this.colors.inset)
              .onClick((): void => { this.openSource(source.messageId); })
            Text('会话显示该节点当前选中的分支，可能与此来源消息不同。').fontSize(12)
              .fontColor(this.colors.secondary).width('100%')
          } else Text('此来源消息已不可用。').fontSize(12).fontColor(this.colors.secondary).width('100%')
        }.width('100%')
      }, (source: MemorySourceMessage): string => `${source.messageId}-${source.nodeId ?? ''}`)
    }.width('100%').padding(16)
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '源记忆' })
      Scroll() {
        Column({ space: 12 }) {
          if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          if (this.notice.length > 0) Text(this.notice).fontSize(13).fontColor(this.colors.secondary).width('100%')
          if (!this.loaded) {
            Text('正在读取源记忆…').fontSize(14).fontColor(this.colors.secondary).width('100%')
          } else if (this.record === null) {
            Text('源记忆不存在或已删除。').fontSize(14).fontColor(this.colors.secondary).width('100%')
          } else {
            CardGroup({ content: (): void => { this.EditorCard(); } })
            CardGroup({ content: (): void => { this.SourceCard(); } })
          }
          Button('重新读取').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(!this.saving && !this.removing)
            .onClick((): void => { this.reload(true); })
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
