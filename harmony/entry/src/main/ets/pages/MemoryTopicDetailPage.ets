import { router } from '@kit.ArkUI';
import type { MemoryRecord, MemoryDerivedDocument } from '@amber/chat-domain';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, PlainSection, CardGroup, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';

interface MemoryTopicParams { topicId?: string; }
interface MemoryTopicMember { id: number; record: MemoryRecord | null; }

@Entry
@Component
struct MemoryTopicDetailPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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

  // 摘要卡:标题/状态/正文 + 派生文档导航(文本动作)
  @Builder
  private SummaryCard(): void {
    Column({ space: 8 }) {
      Text(this.topic?.topicTitle ?? `主题 #${this.topicId}`).fontSize(18).fontWeight(FontWeight.Medium)
        .fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL)
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
        Row() {
          Text('查看只读文档').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.accentColor).constraintSize({ minHeight: 44 })
            .onClick((): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage', params: { relativePath: this.documentPath } }); })
          Blank()
        }.width('100%').margin({ top: 2 })
      } else {
        Text('该主题当前没有有效派生文档。').fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 10 })
  }

  // 成员行组:状态行 + 内容摘要 + 箭头(整行点进源记忆编辑);失效成员不可点
  @Builder
  private MembersCard(): void {
    Column() {
      ForEach(this.members, (member: MemoryTopicMember, idx: number): void => {
        Row({ space: 10 }) {
          Column({ space: 6 }) {
            Text(this.memberStatus(member)).fontSize(12).fontColor(this.colors.secondary).width('100%')
            if (member.record !== null) {
              Text(member.record.content).fontSize(14).fontColor(this.colors.ink).width('100%')
                .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
            }
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          if (member.record !== null) {
            Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
          }
        }
        .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .enabled(member.record !== null)
        .onClick((): void => {
          if (member.record === null) return;
          router.pushUrl({ url: 'pages/MemoryRecordPage', params: { memoryId: member.id.toString() } });
        })
        if (idx < this.members.length - 1) {
          Row() {
            Row().width('100%').height(1).backgroundColor(this.colors.line)
          }.width('100%').padding({ left: 14, right: 14 })
        }
      }, (member: MemoryTopicMember): string => `${member.id}-${member.record?.updatedAt ?? 0}-${member.record?.archived ?? false}`)
    }.width('100%')
  }

  // 操作卡:删除主题(危险文本行)
  @Builder
  private DangerCard(): void {
    Row() {
      Text(this.deleting ? '删除中' : '删除主题').fontSize(15).fontWeight(FontWeight.Medium)
        .fontFamily(FONT_SANS_MEDIUM).fontColor(this.deleting ? this.colors.muted : this.colors.error)
        .constraintSize({ minHeight: 44 })
        .enabled(!this.deleting).onClick((): void => { this.remove(); })
    }
    .width('100%').justifyContent(FlexAlign.Center)
    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '主题详情' })
      Scroll() {
        Column() {
          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          if (!this.loaded) {
            Text('正在读取主题…').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 44 })
          } else if (this.topic === null) {
            Text('主题不存在或已删除。').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 44 })
          } else {
            PlainSection({ label: '主题' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SummaryCard(); } })
            PlainSection({ label: `源记忆成员 · ${this.members.length}` })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.MembersCard(); } })
            PlainSection({ label: '操作' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.DangerCard(); } })
          }
          PageSpacer()
        }.width('100%').padding({ left: 16, right: 16, top: 2, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
