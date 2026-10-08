import { router } from '@kit.ArkUI';
import type { MemoryRecord } from '@amber/chat-domain';
import { getMemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import { CenteredTitleBar, CardGroup, PageSpacer } from '../components/PageTemplate.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { countActiveMemories, filterMemoryRecords, isActiveMemoryRecord, memoryScopeLabel, memoryKindLabel } from '../components/MemoryPresentation.ets';
import type { MemoryListFilter } from '../components/MemoryPresentation.ets';

@Entry
@Component
struct MemoryRecordsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  @StorageProp('accentColor') accentColor: string = ACCENT;
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @State records: MemoryRecord[] = [];
  @State filtered: MemoryRecord[] = [];
  @State filter: MemoryListFilter = 'active';
  @State query: string = '';
  @State loaded: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  @State restoringId: number = -1;
  @State now: number = Date.now();
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void { this.alive = true; this.visible = true; this.reload(); }
  onPageShow(): void { if (!this.visible) { this.visible = true; this.reload(); } }
  onPageHide(): void { this.visible = false; this.loadSeq++; }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void { if (this.alive && this.visible && this.restoringId < 0) this.reload(); }

  private applyFilter(): void { this.filtered = filterMemoryRecords(this.records, this.filter, this.query, this.now); }

  private async reload(): Promise<void> {
    const seq: number = ++this.loadSeq;
    try {
      const records: MemoryRecord[] = await getMemoryLibraryOps().getAllRecords();
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.records = records; this.now = Date.now(); this.loaded = true; this.error = ''; this.applyFilter();
    } catch (error) {
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
    }
  }

  private async restore(id: number): Promise<void> {
    if (this.restoringId >= 0) return;
    this.restoringId = id; this.error = ''; this.notice = '';
    let failure: string = '';
    try { await getMemoryLibraryOps().restoreMemory(id); }
    catch (error) { failure = error instanceof Error ? error.message : String(error); }
    await this.reload();
    if (!this.alive) return;
    const restored: MemoryRecord | undefined = this.records.find((record: MemoryRecord): boolean => record.id === id);
    if (restored !== undefined && isActiveMemoryRecord(restored, this.now)) this.notice = `已恢复记忆 #${id}`;
    if (failure.length > 0) this.error = this.notice.length > 0 ? failure : `恢复失败：${failure}`;
    this.restoringId = -1;
  }

  @Builder
  private FilterTab(label: string, value: MemoryListFilter): void {
    Text(label).fontSize(14).fontFamily(FONT_SANS_MEDIUM)
      .fontColor(this.filter === value ? this.accentColor : this.colors.secondary)
      .backgroundColor(this.filter === value ? this.colors.inset : Color.Transparent)
      .borderRadius(10).layoutWeight(1).constraintSize({ minHeight: 44 }).textAlign(TextAlign.Center)
      .onClick((): void => { this.filter = value; this.now = Date.now(); this.applyFilter(); })
  }

  @Builder
  private RecordCard(record: MemoryRecord): void {
    Column({ space: 8 }) {
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Text(`#${record.id} · ${memoryScopeLabel(record.scope)} · ${memoryKindLabel(record.kind)}`)
            .fontSize(12).fontColor(this.colors.secondary).layoutWeight(1)
          if (!isActiveMemoryRecord(record, this.now)) {
            Text((record.invalidatedAt ?? null) !== null ? '已作废' : record.archived ? '已归档' : '已过期').fontSize(12).fontColor('#C27A2E')
          }
        }.width('100%').alignItems(VerticalAlign.Center)
        Text(record.content).fontSize(15).fontColor(this.colors.ink).width('100%').maxLines(4)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`记录日期：${new Date(record.createdAt).toLocaleDateString()}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        if (record.expiresAt !== null) {
          Text(`有效期至：${new Date(record.expiresAt).toLocaleString()}`)
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
      }.width('100%').constraintSize({ minHeight: 44 })
        .onClick((): void => { router.pushUrl({ url: 'pages/MemoryRecordPage', params: { memoryId: record.id.toString() } }); })
      if (!isActiveMemoryRecord(record, this.now)) {
        Row() {
          Text(this.restoringId === record.id ? '恢复中…' : '恢复').fontSize(14).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.restoringId >= 0 ? this.colors.muted : this.accentColor)
            .constraintSize({ minWidth: 64, minHeight: 44 }).textAlign(TextAlign.Center)
            .enabled(this.restoringId < 0).onClick((): void => { this.restore(record.id); })
          Blank()
        }.width('100%')
      }
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        CenteredTitleBar({ title: '全部记忆', subtitle: `${countActiveMemories(this.records, this.now)} 条活跃记忆` })
        Column({ space: 8 }) {
          TextInput({ text: this.query, placeholder: '搜索内容、类型或范围' }).fontSize(14)
            .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset)
            .onChange((value: string): void => { this.query = value; this.applyFilter(); })
          Row({ space: 4 }) {
            this.FilterTab('活跃', 'active')
            this.FilterTab('已归档', 'archived')
            this.FilterTab('全部', 'all')
          }.width('100%')
          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            Text('重新读取').fontSize(14).fontColor(this.accentColor).constraintSize({ minHeight: 44 })
              .onClick((): void => { this.reload(); })
          }
          if (this.notice.length > 0) Text(this.notice).fontSize(13).fontColor(this.colors.secondary).width('100%')
        }.width('100%').padding({ left: 16, right: 16, bottom: 8 })
        Scroll() {
          Column({ space: 10 }) {
            if (!this.loaded) {
              Text('正在读取记忆…').fontSize(14).fontColor(this.colors.secondary).padding({ top: 44 })
            } else if (this.filtered.length === 0) {
              Text(this.query.trim().length > 0 ? '没有匹配的记忆' : this.filter === 'archived' ? '暂无已归档记忆' : '暂无记忆')
                .fontSize(14).fontColor(this.colors.secondary).padding({ top: 44 })
            } else {
              ForEach(this.filtered, (record: MemoryRecord): void => {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.RecordCard(record); } })
              }, (record: MemoryRecord): string => `${record.id}-${record.updatedAt}-${record.archived}`)
            }
            PageSpacer()
          }.width('100%').padding({ left: 16, right: 16, bottom: 16 + this.navBarHeight })
        }.width('100%').layoutWeight(1).align(Alignment.TopStart)
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
