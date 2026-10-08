import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import type { Conversation, MemoryRecord, MessageNode } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { getMemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, PlainSection, CardGroup, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { isActiveMemoryRecord, memoryScopeLabel, memoryKindLabel } from '../components/MemoryPresentation.ets';

interface MemoryRecordParams { memoryId?: string; }
interface MemorySourceMessage { messageId: string; nodeId: string | null; }

@Entry
@Component
struct MemoryRecordPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State record: MemoryRecord | null = null;
  @State draft: string = '';
  @State loaded: boolean = false;
  @State saving: boolean = false;
  @State removing: boolean = false;
  @State restoring: boolean = false;
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
  private memoryChanged(): void { if (this.alive && this.visible && !this.saving && !this.removing && !this.restoring) this.reload(true); }

  private async reload(preserveDraft: boolean = false): Promise<boolean> {
    const seq: number = ++this.loadSeq;
    let recordRead: boolean = false;
    if (!Number.isInteger(this.memoryId) || this.memoryId <= 0) {
      this.loaded = true; this.error = '源记忆 ID 无效'; return false;
    }
    try {
      const records: MemoryRecord[] = await getMemoryLibraryOps().getAllRecords();
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
    if (this.record === null || this.record.kind === 'topic' || this.saving || this.removing || this.restoring) return;
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
    if (this.record === null || this.record.kind === 'topic' || this.saving || this.removing || this.restoring) return;
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
    const status: string = (this.record.invalidatedAt ?? null) !== null ? '已作废' : this.record.archived ? '已归档' : this.record.expiresAt !== null && this.record.expiresAt <= Date.now() ? '已过期' : '有效';
    return `#${this.record.id} · ${memoryScopeLabel(this.record.scope)} / ${memoryKindLabel(this.record.kind)} · ${status}`;
  }

  // 编辑卡:标识行 + 圆角文本域(r12 内边距)+ 时间戳 + 文本动作(保存/删除)
  @Builder
  private EditorCard(): void {
    Column({ space: 8 }) {
      Text(this.recordLabel()).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
      if (this.record?.kind === 'topic') {
        Text('主题由整理模型维护，不能通过源记忆编辑器改写。').fontSize(14).fontColor(this.colors.secondary).width('100%')
        Row() {
          Text('打开只读主题详情').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.accentColor).constraintSize({ minHeight: 44 })
            .onClick((): void => { router.replaceUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: this.memoryId.toString() } }); })
          Blank()
        }.width('100%')
      } else {
        TextArea({ text: this.draft, placeholder: '源记忆内容' }).width('100%').height(240)
          .fontSize(15).fontColor(this.colors.ink).placeholderColor(this.colors.muted)
          .backgroundColor(this.colors.inset).borderRadius(12).padding(12)
          .enabled(!this.saving && !this.removing && !this.restoring).onChange((value: string): void => { this.draft = value; })
        Text(`记录日期：${new Date(this.record?.createdAt ?? 0).toLocaleString()}\n更新：${new Date(this.record?.updatedAt ?? 0).toLocaleString()}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        if (this.record?.expiresAt !== null) {
          Text(`有效期至：${new Date(this.record?.expiresAt ?? 0).toLocaleString()}`)
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
        if ((this.record?.invalidatedAt ?? null) !== null) {
          Text(`失效日期：${new Date(this.record?.invalidatedAt ?? 0).toLocaleString()}`)
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
        if ((this.record?.supersedesIds.length ?? 0) > 0) {
          Text(`取代旧版本：${this.record?.supersedesIds.map((id: number): string => '#' + id).join('、') ?? ''}`)
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
        if (this.record !== null && !isActiveMemoryRecord(this.record, Date.now())) {
          Row() {
            Text(this.restoring ? '恢复中…' : '恢复记忆').fontSize(14).fontColor(this.accentColor)
              .constraintSize({ minWidth: 80, minHeight: 44 })
              .enabled(!this.saving && !this.removing && !this.restoring)
              .onClick((): void => { this.restore(); })
            Blank()
          }.width('100%')
        }
        Row() {
          Text(this.saving ? '保存中' : '保存源记忆').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.saving || this.removing || this.restoring ? this.colors.muted : this.accentColor)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.saving && !this.removing && !this.restoring).onClick((): void => { this.save(); })
          Blank()
          Text(this.removing ? '删除中' : '删除').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.saving || this.removing || this.restoring ? this.colors.muted : this.colors.error)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.saving && !this.removing && !this.restoring).onClick((): void => { this.remove(); })
        }.width('100%')
      }
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 10 })
  }

  private async restore(): Promise<void> {
    if (this.record === null || this.restoring || this.saving || this.removing) return;
    this.restoring = true; this.notice = ''; this.error = '';
    let failure: string = '';
    try { await getMemoryLibraryOps().restoreMemory(this.memoryId); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    const refreshed: boolean = await this.reload(true);
    if (!this.alive) return;
    if (refreshed && this.record !== null && isActiveMemoryRecord(this.record, Date.now())) this.notice = '记忆已恢复。';
    if (failure.length > 0) this.error = this.notice.length > 0 ? failure : `恢复失败：${failure}`;
    this.restoring = false;
  }

  @Builder
  private EvidenceCard(): void {
    Text(this.record?.evidence ?? '').fontSize(14).fontColor(this.colors.ink).width('100%')
      .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  // 来源卡:会话出处 + 可定位的来源消息(行式 + 箭头)
  @Builder
  private SourceCard(): void {
    Column({ space: 8 }) {
      Text(this.sourceStatus).fontSize(13).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
      if (this.record?.sourceConversationId !== null) {
        Text(this.record?.sourceConversationId ?? '').fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
          .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      }
      if (this.sourceAvailable) {
        Row() {
          Text('打开来源会话').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.accentColor).constraintSize({ minHeight: 44 })
            .onClick((): void => { this.openSource(); })
          Blank()
        }.width('100%')
      }
      if (this.record?.sourceMessageIds.length === 0) {
        Text('没有来源消息 ID。').fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
      ForEach(this.sourceMessages, (source: MemorySourceMessage, idx: number): void => {
        if (idx > 0) {
          Row() {
            Row().width('100%').height(1).backgroundColor(this.colors.line)
          }.width('100%').padding({ top: 4, bottom: 4 })
        }
        Column({ space: 4 }) {
          Text(source.messageId).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          if (source.nodeId !== null) {
            Row({ space: 10 }) {
              Text('定位来源消息所在节点').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
                .fontColor(this.accentColor).layoutWeight(1).constraintSize({ minHeight: 44 })
              Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
            }.width('100%').alignItems(VerticalAlign.Center)
            .onClick((): void => { this.openSource(source.messageId); })
            Text('会话显示该节点当前选中的分支，可能与此来源消息不同。').fontSize(12)
              .fontColor(this.colors.secondary).width('100%')
          } else {
            Text('此来源消息已不可用。').fontSize(12).fontColor(this.colors.secondary).width('100%')
          }
        }.width('100%')
      }, (source: MemorySourceMessage): string => `${source.messageId}-${source.nodeId ?? ''}`)
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 10 })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '记忆详情' })
      Scroll() {
        Column() {
          if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          if (this.notice.length > 0) Text(this.notice).fontSize(13).fontColor(this.colors.secondary).width('100%')
          if (!this.loaded) {
            Text('正在读取源记忆…').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 44 })
          } else if (this.record === null) {
            Text('源记忆不存在或已删除。').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 44 })
          } else {
            PlainSection({ label: '源记忆' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.EditorCard(); } })
            if ((this.record.evidence ?? '').trim().length > 0) {
              PlainSection({ label: '用户原话' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.EvidenceCard(); } })
            }
            PlainSection({ label: '真实来源' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourceCard(); } })
          }
          PageSpacer()
        }.width('100%').padding({ left: 16, right: 16, top: 2, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
