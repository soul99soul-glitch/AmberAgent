// MemoryPage — 核心记忆(G7 杂志风)
// 全局记忆桶使用 MemoryStore 真实读写。

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import type { MemoryRecord } from '@amber/chat-domain';
import { MEMORY_BUCKET_GLOBAL } from '@amber/chat-domain';
import {
  getMemoryLibraryOps, MemoryLibraryOps, MemoryDocumentRefreshError,
} from '../platform_impl/MemoryStore.ets';
import {
  loadAgentRuntimeSnapshot, updateRecallToggles, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG,
  ACCENT, ERROR, TITLE, BODY, BODY2, META, GUTTER, RADIUS,
} from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';

interface MemoryDraft {
  id: number;
  text: string;
}

@Entry
@Component
struct MemoryPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  @State colors: SSHUIColors = new SSHUIColors();
  @State memories: MemoryRecord[] = [];
  @State autoMemory: boolean = true;
  @State loaded: boolean = false;
  @State draft: MemoryDraft | null = null;
  @State memorySaving: boolean = false;
  private ops: MemoryLibraryOps = getMemoryLibraryOps();
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.pageVisible = true;
    this.reload();
  }

  onPageShow(): void { if (!this.pageVisible) { this.pageVisible = true; this.reload(); } }
  onPageHide(): void { this.pageVisible = false; this.loadSeq++; }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void {
    if (this.pageAlive && this.pageVisible && this.draft === null && !this.memorySaving) this.reload();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.onPageHide();
  }

  private reload(): void {
    const seq: number = ++this.loadSeq;
    const kv = getChatKvStore();
    Promise.all([
      this.ops.listBucketRecords(MEMORY_BUCKET_GLOBAL),
      loadAgentRuntimeSnapshot(kv),
    ]).then((result: [MemoryRecord[], AgentRuntimeSnapshot]): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.memories = result[0];
      this.autoMemory = result[1].enableCoreMemory;
      this.loaded = true;
    }).catch((): void => {
      if (this.pageAlive && seq === this.loadSeq) this.loaded = true;
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2000 });
  }

  private toggleAutoMemory(value: boolean): void {
    this.autoMemory = value;
    const seq: number = this.loadSeq;
    updateRecallToggles(getChatKvStore(), { enableCoreMemory: value })
      .catch((): void => {
        if (this.pageAlive && seq === this.loadSeq) this.toast('自动记忆设置保存失败');
      });
  }

  private async togglePin(item: MemoryRecord): Promise<void> {
    if (this.memorySaving) return;
    const nextPinned: boolean = !item.pinned;
    const seq: number = this.loadSeq;
    this.memorySaving = true;
    try {
      const updated: MemoryRecord = await this.ops.updatePinned(item.id, nextPinned);
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.memories = this.memories.map((m: MemoryRecord): MemoryRecord => {
        return m.id === updated.id ? updated : m;
      });
    } catch (error) {
      if (this.pageAlive && seq === this.loadSeq) this.toast(error instanceof Error ? error.message : String(error));
    } finally {
      this.memorySaving = false;
      if (this.pageAlive && this.pageVisible && seq === this.loadSeq) this.reload();
    }
  }

  private openEdit(item: MemoryRecord): void {
    this.draft = { id: item.id, text: item.content };
  }

  private openAdd(): void {
    this.draft = { id: 0, text: '' };
  }

  private closeDraft(): void {
    if (this.memorySaving) return;
    this.draft = null;
    if (this.pageAlive && this.pageVisible) this.reload();
  }

  private async saveDraft(): Promise<void> {
    if (this.draft === null || this.memorySaving) return;
    const text: string = this.draft.text.trim();
    if (text.length === 0) return;
    const id: number = this.draft.id;
    const seq: number = this.loadSeq;
    this.memorySaving = true;
    try {
      if (id === 0) await this.ops.addMemory({ scope: 'core', kind: 'note', content: text });
      else await this.ops.updateContent(id, text);
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.draft = null;
    } catch (error) {
      if (this.pageAlive && seq === this.loadSeq) {
        if (error instanceof MemoryDocumentRefreshError && error.sourceSaved) this.draft = null;
        this.toast(error instanceof Error ? error.message : String(error));
      }
    } finally {
      this.memorySaving = false;
      if (this.pageAlive && this.pageVisible && seq === this.loadSeq) this.reload();
    }
  }

  private deleteMemory(item: MemoryRecord): void {
    if (this.memorySaving) return;
    AlertDialog.show({
      title: '确认删除',
      message: item.content,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '确认',
        action: (): void => { this.removeMemory(item.id); },
      },
    });
  }

  private async removeMemory(id: number): Promise<void> {
    if (this.memorySaving) return;
    const seq: number = this.loadSeq;
    this.memorySaving = true;
    try { await this.ops.deleteMemory(id); }
    catch (error) {
      if (this.pageAlive && seq === this.loadSeq) this.toast(error instanceof Error ? error.message : String(error));
    } finally {
      this.memorySaving = false;
      if (this.pageAlive && this.pageVisible && seq === this.loadSeq) this.reload();
    }
  }

  private scopeLabel(item: MemoryRecord): string {
    return `${item.scope} · ${item.kind}${item.pinned ? ' · pinned' : ''}`;
  }

  private timeLabel(timestamp: number): string {
    if (timestamp <= 0) return '';
    const date: Date = new Date(timestamp);
    const month: string = (date.getMonth() + 1).toString().padStart(2, '0');
    const day: string = date.getDate().toString().padStart(2, '0');
    const hour: string = date.getHours().toString().padStart(2, '0');
    const minute: string = date.getMinutes().toString().padStart(2, '0');
    return `${month}-${day} ${hour}:${minute}`;
  }

  @Builder
  topicEntries(): void {
    Column({ space: 8 }) {
      Button('记忆主题与源记忆').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.accentColor).backgroundColor(this.colors.surface)
        .onClick((): void => { router.pushUrl({ url: 'pages/MemoryTopicsPage' }); })
      Button('只读记忆文档').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.accentColor).backgroundColor(this.colors.surface)
        .onClick((): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage' }); })
    }.width('100%').margin({ top: 12 })
  }

  @Builder
  pageHeader(): void {
    Row() {
      Row() { Icon({ name: 'back', iconSize: 22, color: INK3 }) }
        .width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { router.back(); })
      Text('核心记忆').fontSize(TITLE).fontWeight(700).fontColor(INK)
        .layoutWeight(1).margin({ left: 8 })
    }
    .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)
  }

  @Builder
  autoCard(): void {
    Row() {
      Icon({ name: 'brain', iconSize: 20, color: ACCENT })
      Column() {
        Text('自动记忆').fontSize(14.5).fontWeight(600).fontColor(INK)
        Text('Amber 会记住对话里值得留存的信息')
          .fontSize(BODY2).fontColor(INK3).margin({ top: 2 })
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 13 })
      Toggle({ type: ToggleType.Switch, isOn: this.autoMemory })
        .selectedColor(ACCENT).onChange((v: boolean): void => { this.toggleAutoMemory(v); })
    }
    .width('100%').padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14).margin({ top: 8 })
  }

  @Builder
  memoryCard(item: MemoryRecord): void {
    Column() {
      Row() {
        Text(item.content).fontSize(BODY).fontColor(INK).lineHeight(21).layoutWeight(1)
          .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
        Row() { Icon({ name: 'pin', iconSize: 14, color: item.pinned ? ACCENT : INK4 }) }
          .width(44).height(44).justifyContent(FlexAlign.Center).enabled(!this.memorySaving)
          .onClick((): void => { this.togglePin(item); })
      }.width('100%')
      Row() {
        Text(this.scopeLabel(item)).fontSize(10.5).fontColor(INK3).fontFamily(FONT_MONO)
          .backgroundColor(SURFACE2).padding({ left: 7, right: 7, top: 2, bottom: 2 }).borderRadius(5)
        Text(this.timeLabel(item.updatedAt)).fontSize(10.5).fontColor(INK4)
          .fontFamily(FONT_MONO).layoutWeight(1).textAlign(TextAlign.End)
        Row() { Text('✎').fontSize(15).fontColor(INK4) }
          .width(44).height(44).justifyContent(FlexAlign.Center).enabled(!this.memorySaving)
          .onClick((): void => { this.openEdit(item); })
        Row() { Icon({ name: 'trash', iconSize: 15, color: INK4 }) }
          .width(44).height(44).justifyContent(FlexAlign.Center).enabled(!this.memorySaving)
          .onClick((): void => { this.deleteMemory(item); })
      }.width('100%').margin({ top: 9 })
    }
    .width('100%').padding({ left: GUTTER, right: GUTTER, top: 13, bottom: 13 })
    .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
  }

  @Builder
  draftDialog(draft: MemoryDraft): void {
    Stack() {
      Column() {
        Text(draft.id === 0 ? '新增记忆' : '编辑记忆')
          .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
        TextArea({ text: draft.text, placeholder: '记一条值得长期保留的信息…' })
          .fontSize(BODY).fontColor(INK).backgroundColor(SURFACE2).borderRadius(10)
          .width('100%').height(120).margin({ top: 12 }).enabled(!this.memorySaving)
          .onChange((v: string): void => {
            if (this.draft !== null) this.draft = { id: this.draft.id, text: v };
          })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.memorySaving).onClick((): void => { this.closeDraft(); })
          Text(this.memorySaving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.memorySaving).onClick((): void => { this.saveDraft(); })
        }.width('100%').margin({ top: 8 }).border({ width: { top: 0.5 }, color: LINE })
      }.width('84%').padding(20).backgroundColor(SURFACE).borderRadius(14)
    }.width('100%').height('100%').backgroundColor('#66000000')
      .onClick((): void => { this.closeDraft(); })
  }

  build(): void {
    Stack() {
      Column() {
        this.pageHeader()
        Scroll() {
          Column() {
            this.autoCard()
            this.topicEntries()
            Row() {
              Text('// 已存 ' + this.memories.length.toString() + ' 条')
                .fontSize(META).fontWeight(600).fontColor(INK3).fontFamily(FONT_MONO)
              Blank()
              Text('+ 新增').fontSize(BODY2).fontWeight(600).fontColor(ACCENT)
                .constraintSize({ minWidth: 64 }).height(44).textAlign(TextAlign.Center).enabled(!this.memorySaving)
                .onClick((): void => { this.openAdd(); })
            }.width('100%').padding({ left: 6, right: 6, top: 24, bottom: 10 })
            if (this.loaded && this.memories.length === 0) {
              Column() {
                Text('// 暂无核心记忆').fontSize(META).fontColor(INK3).fontFamily(FONT_MONO)
                Text('Agent 会在对话中自动沉淀，也可手动添加')
                  .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              }.width('100%').padding({ top: 48 }).alignItems(HorizontalAlign.Center)
            } else {
              Column({ space: 10 }) {
                ForEach(this.memories, (item: MemoryRecord): void => {
                  this.memoryCard(item);
                }, (item: MemoryRecord): string => item.id.toString())
              }.width('100%')
            }
          }.width('100%')
        }
        .align(Alignment.TopStart).layoutWeight(1).padding({ left: GUTTER, right: GUTTER, top: 0, bottom: 16 + this.navBarHeight }).backgroundColor(BG)
      }.width('100%').height('100%').backgroundColor(BG)
      if (this.draft !== null) this.draftDialog(this.draft)
    }
    .width('100%').height('100%')
  }
}
