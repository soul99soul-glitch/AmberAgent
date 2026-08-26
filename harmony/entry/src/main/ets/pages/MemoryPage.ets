// MemoryPage — 核心记忆(G7 杂志风)
// 全局记忆桶使用 MemoryStore 真实读写。

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import type { MemoryRecord } from '@amber/chat-domain';
import { MEMORY_BUCKET_GLOBAL } from '@amber/chat-domain';
import {
  getMemoryLibraryOps, MemoryLibraryOps,
} from '../platform_impl/MemoryStore.ets';
import {
  loadAgentRuntimeSnapshot, updateRecallToggles, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG,
  ACCENT, ERROR, TITLE, BODY, BODY2, META, GUTTER, RADIUS,
} from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';

interface MemoryDraft {
  id: number;
  text: string;
}

@Entry
@Component
struct MemoryPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State memories: MemoryRecord[] = [];
  @State autoMemory: boolean = true;
  @State loaded: boolean = false;
  @State draft: MemoryDraft | null = null;
  private ops: MemoryLibraryOps = getMemoryLibraryOps();
  private pageAlive: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.reload();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
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

  private togglePin(item: MemoryRecord): void {
    const nextPinned: boolean = !item.pinned;
    const seq: number = this.loadSeq;
    this.ops.updatePinned(item.id, nextPinned).then((): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.memories = this.memories.map((m: MemoryRecord): MemoryRecord => {
        if (m.id !== item.id) return m;
        return {
          id: m.id, content: m.content, scope: m.scope, kind: m.kind,
          assistantId: m.assistantId, sourceConversationId: m.sourceConversationId,
          sourceMessageIds: m.sourceMessageIds, supersedesIds: m.supersedesIds,
          expiresAt: m.expiresAt, confidence: m.confidence, pinned: nextPinned,
          archived: m.archived, createdAt: m.createdAt, updatedAt: Date.now(),
          lastUsedAt: m.lastUsedAt,
        };
      });
    }).catch((): void => {
      if (this.pageAlive && seq === this.loadSeq) this.toast('置顶状态保存失败');
    });
  }

  private openEdit(item: MemoryRecord): void {
    this.draft = { id: item.id, text: item.content };
  }

  private openAdd(): void {
    this.draft = { id: 0, text: '' };
  }

  private closeDraft(): void {
    this.draft = null;
  }

  private saveDraft(): void {
    if (this.draft === null) return;
    const text: string = this.draft.text.trim();
    if (text.length === 0) return;
    const id: number = this.draft.id;
    const seq: number = this.loadSeq;
    const future: Promise<MemoryRecord> = id === 0
      ? this.ops.addMemory({ scope: 'core', kind: 'note', content: text })
      : this.ops.updateContent(id, text);
    future.then((): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.draft = null;
      this.reload();
    }).catch((): void => {
      if (this.pageAlive && seq === this.loadSeq) this.toast('记忆保存失败');
    });
  }

  private deleteMemory(item: MemoryRecord): void {
    AlertDialog.show({
      title: '确认删除',
      message: item.content,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '确认',
        action: (): void => {
          const seq: number = this.loadSeq;
          this.ops.deleteMemory(item.id).then((): void => {
            if (!this.pageAlive || seq !== this.loadSeq) return;
            this.reload();
          }).catch((): void => {
            if (this.pageAlive && seq === this.loadSeq) this.toast('记忆删除失败');
          });
        },
      },
    });
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
  pageHeader(): void {
    Row() {
      Icon({ name: 'back', iconSize: 22, color: INK3 })
        .onClick((): void => { router.back(); })
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
        Icon({ name: 'pin', iconSize: 14, color: item.pinned ? ACCENT : INK4 })
          .margin({ left: 10 }).onClick((): void => { this.togglePin(item); })
      }.width('100%')
      Row() {
        Text(this.scopeLabel(item)).fontSize(10.5).fontColor(INK3).fontFamily('JetBrainsMono')
          .backgroundColor(SURFACE2).padding({ left: 7, right: 7, top: 2, bottom: 2 }).borderRadius(5)
        Text(this.timeLabel(item.updatedAt)).fontSize(10.5).fontColor(INK4)
          .fontFamily('JetBrainsMono').layoutWeight(1).textAlign(TextAlign.End)
        Text('✎').fontSize(15).fontColor(INK4).margin({ left: 10 })
          .onClick((): void => { this.openEdit(item); })
        Icon({ name: 'trash', iconSize: 15, color: INK4 }).margin({ left: 8 })
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
          .width('100%').height(120).margin({ top: 12 })
          .onChange((v: string): void => {
            if (this.draft !== null) this.draft = { id: this.draft.id, text: v };
          })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 6 }).onClick((): void => { this.closeDraft(); })
          Text('保存').fontSize(BODY).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 6 }).onClick((): void => { this.saveDraft(); })
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
            Row() {
              Text('// 已存 ' + this.memories.length.toString() + ' 条')
                .fontSize(META).fontWeight(600).fontColor(INK3).fontFamily('JetBrainsMono')
              Blank()
              Text('+ 新增').fontSize(BODY2).fontWeight(600).fontColor(ACCENT)
                .onClick((): void => { this.openAdd(); })
            }.width('100%').padding({ left: 6, right: 6, top: 24, bottom: 10 })
            if (this.loaded && this.memories.length === 0) {
              Column() {
                Text('// 暂无核心记忆').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
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
        .align(Alignment.Start).layoutWeight(1).padding({ left: GUTTER, right: GUTTER, top: 0, bottom: 28 }).backgroundColor(BG)
      }.width('100%').height('100%').backgroundColor(BG)
      if (this.draft !== null) this.draftDialog(this.draft)
    }
    .width('100%').height('100%')
  }
}
