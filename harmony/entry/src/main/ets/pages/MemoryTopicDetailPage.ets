import { router } from '@kit.ArkUI';
import type { MemoryRecord, MemoryDerivedDocument } from '@amber/chat-domain';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';

interface MemoryTopicParams { topicId?: string; }
interface MemoryTopicMember { id: number; record: MemoryRecord | null; }

@Entry
@Component
struct MemoryTopicDetailPage {
  @State topic: MemoryRecord | null = null;
  @State members: MemoryTopicMember[] = [];
  @State documentPath: string = '';
  @State loaded: boolean = false;
  @State deleting: boolean = false;
  @State error: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  private topicId: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    const params: MemoryTopicParams = router.getParams() as MemoryTopicParams;
    this.topicId = Number(params?.topicId ?? '');
    this.alive = true; this.visible = true; this.reload();
  }
  onPageShow(): void { if (!this.visible) { this.visible = true; this.reload(); } }
  onPageHide(): void { this.visible = false; this.loadSeq++; }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void { if (this.alive && this.visible) this.reload(); }

  private async reload(): Promise<void> {
    const seq: number = ++this.loadSeq;
    if (!Number.isInteger(this.topicId) || this.topicId <= 0) {
      this.loaded = true; this.error = '主题 ID 无效'; return;
    }
    try {
      const records: MemoryRecord[] = await getMemoryTopicStoreOps().snapshotForTopics();
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      const found: MemoryRecord | null = records.find((item: MemoryRecord): boolean => item.id === this.topicId && item.kind === 'topic') ?? null;
      this.topic = found;
      this.members = found === null ? [] : found.memberIds.map((id: number): MemoryTopicMember => ({
        id: id, record: records.find((item: MemoryRecord): boolean => item.id === id && item.kind !== 'topic') ?? null,
      }));
      this.loaded = true; this.error = ''; this.documentPath = '';
      if (found === null || found.archived) return;
      const documents: MemoryDerivedDocument[] = await getMemoryTopicStoreOps().listDocuments();
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.documentPath = documents.find((item: MemoryDerivedDocument): boolean => item.topicId === this.topicId)?.relativePath ?? '';
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
      }
    }
  }

  private remove(): void {
    if (this.topic === null || this.deleting) return;
    AlertDialog.show({
      title: '删除主题',
      message: '删除这个主题及其派生文档，保留所有源记忆。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除主题', fontColor: this.colors.error, action: (): void => { this.deleteTopic(); } },
    });
  }

  private async deleteTopic(): Promise<void> {
    if (this.deleting) return;
    this.deleting = true;
    let failure: string = '';
    try { await getMemoryTopicStoreOps().deleteTopic(this.topicId); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    // A document refresh error may follow a successful source JSON write.
    await this.reload();
    if (this.alive) { this.deleting = false; if (failure.length > 0) this.error = failure; }
  }

  private memberStatus(member: MemoryTopicMember): string {
    const record: MemoryRecord | null = member.record;
    if (record === null) return `源记忆 #${member.id} 已不存在`;
    const state: string = record.archived ? '已归档' : record.expiresAt !== null && record.expiresAt <= Date.now() ? '已过期' : '有效';
    return `#${record.id} · ${record.scope} / ${record.kind} · ${state}`;
  }

  @Builder
  private SummaryCard(): void {
    Column({ space: 10 }) {
      Text(this.topic?.topicTitle ?? `主题 #${this.topicId}`).fontSize(18).fontWeight(FontWeight.Medium)
        .fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL)
      Text(this.topic?.archived ? '已归档 · 摘要已失效' : '自动维护 · 只读摘要')
        .fontSize(13).fontColor(this.topic?.archived ? this.colors.warning : this.colors.secondary).width('100%')
      if (this.topic?.archived) {
        Text('源记忆已变化或主题已失效。下面是原摘要，重新整理后才能成为有效主题。')
          .fontSize(12).fontColor(this.colors.warning).width('100%')
      }
      Text(this.topic?.content ?? '').fontSize(15).fontColor(this.colors.ink).width('100%').lineHeight(23)
        .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      Text(`主题 #${this.topicId} · ${this.members.length} 个成员 ID`)
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      if (this.documentPath.length > 0) {
        Button('查看只读文档').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage', params: { relativePath: this.documentPath } }); })
      } else {
        Text('该主题当前没有有效派生文档。').fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
    }.width('100%').padding(16)
  }

  @Builder
  private MembersCard(): void {
    Column({ space: 10 }) {
      Text('源记忆成员').fontSize(15).fontColor(this.colors.ink).width('100%')
      ForEach(this.members, (member: MemoryTopicMember): void => {
        Column({ space: 6 }) {
          Text(this.memberStatus(member)).fontSize(12).fontColor(this.colors.secondary).width('100%')
          if (member.record !== null) {
            Text(member.record.content).fontSize(14).fontColor(this.colors.ink).width('100%')
              .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
            Button('查看 / 编辑源记忆').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
              .fontColor(this.accentColor).backgroundColor(this.colors.inset)
              .onClick((): void => { router.pushUrl({ url: 'pages/MemoryRecordPage', params: { memoryId: member.id.toString() } }); })
          }
        }.width('100%').padding(12).backgroundColor(this.colors.surface).borderRadius(12)
          .border({ width: 1, color: this.colors.line })
      }, (member: MemoryTopicMember): string => `${member.id}-${member.record?.updatedAt ?? 0}-${member.record?.archived ?? false}`)
      Button(this.deleting ? '删除中' : '删除主题').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.colors.error).backgroundColor(this.colors.inset).enabled(!this.deleting)
        .onClick((): void => { this.remove(); })
    }.width('100%')
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '主题详情' })
      Scroll() {
        Column({ space: 12 }) {
          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          if (!this.loaded) {
            Text('正在读取主题…').fontSize(14).fontColor(this.colors.secondary).width('100%')
          } else if (this.topic === null) {
            Text('主题不存在或已删除。').fontSize(14).fontColor(this.colors.secondary).width('100%')
          } else {
            CardGroup({ content: (): void => { this.SummaryCard(); } })
            this.MembersCard()
          }
          Button('重新读取').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset).onClick((): void => { this.reload(); })
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
