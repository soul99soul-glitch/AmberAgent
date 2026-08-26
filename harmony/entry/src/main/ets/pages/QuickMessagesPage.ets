// QuickMessagesPage — 快捷消息列表
// 展示快捷消息模板(标题 + 内容预览),添加/内联编辑/滑动删除。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import type { KeyValueStore } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';

const DANGER: string = 'ERROR';
const QUICK_MESSAGES_KEY: string = 'quick_messages';

interface QuickMessage {
  id: string;
  title: string;
  content: string;
}

function makeMsg(id: string, title: string, content: string): QuickMessage {
  return { id: id, title: title, content: content };
}

function seedMessages(): QuickMessage[] {
  return [
    makeMsg('qm-1', '继续', '请继续上面的内容'),
    makeMsg('qm-2', '总结', '请用三点总结本次对话'),
    makeMsg('qm-3', '翻译为英文', '请将上文翻译为英文'),
    makeMsg('qm-4', '解释代码', '请逐行解释这段代码的作用'),
    makeMsg('qm-5', '优化建议', '请给出优化建议与改进方向'),
  ];
}

const decodeMessages = (raw: string): QuickMessage[] | null => {
  try {
    const parsed: QuickMessage[] = JSON.parse(raw) as QuickMessage[];
    if (!Array.isArray(parsed)) return null;
    const out: QuickMessage[] = [];
    for (const item of parsed) {
      if (typeof item.id !== 'string' || typeof item.title !== 'string' || typeof item.content !== 'string') {
        return null;
      }
      out.push(makeMsg(item.id, item.title, item.content));
    }
    return out;
  } catch {
    return null;
  }
};

@Entry
@Component
struct QuickMessagesPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: QuickMessage[] = [];
  @State loaded: boolean = false;
  @State editing: boolean = false;
  @State editId: string = '';
  @State editTitle: string = '';
  @State editContent: string = '';
  private pageAlive: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.refresh();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
  }

  private refresh(): void {
    const seq: number = ++this.loadSeq;
    const kv: KeyValueStore = getChatKvStore();
    kv.get(QUICK_MESSAGES_KEY).then((raw: string | null): Promise<QuickMessage[]> => {
      if (raw !== null) {
        const decoded: QuickMessage[] | null = decodeMessages(raw);
        return Promise.resolve(decoded === null ? seedMessages() : decoded);
      }
      const seed: QuickMessage[] = seedMessages();
      return this.saveList(kv, seed).then((): QuickMessage[] => seed);
    }).then((list: QuickMessage[]): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.items = list;
      this.loaded = true;
    }).catch((): void => {
      if (this.pageAlive && seq === this.loadSeq) this.loaded = true;
    });
  }

  private saveList(kv: KeyValueStore, list: QuickMessage[]): Promise<void> {
    return kv.put(QUICK_MESSAGES_KEY, JSON.stringify(list));
  }

  private startNew(): void {
    this.editId = '';
    this.editTitle = '';
    this.editContent = '';
    this.editing = true;
  }

  private startEdit(item: QuickMessage): void {
    this.editId = item.id;
    this.editTitle = item.title;
    this.editContent = item.content;
    this.editing = true;
  }

  private cancelEdit(): void {
    this.editing = false;
  }

  private nextId(): string {
    let max: number = 0;
    for (const item of this.items) {
      const n: number = Number(item.id.replace('qm-', ''));
      if (!isNaN(n) && n > max) max = n;
    }
    return 'qm-' + (max + 1).toString();
  }

  private saveEdit(): void {
    const title: string = this.editTitle.trim();
    if (title.length === 0) return;
    const content: string = this.editContent;
    const next: QuickMessage[] = [];
    if (this.editId.length === 0) {
      next.push(makeMsg(this.nextId(), title, content));
      for (const item of this.items) next.push(item);
    } else {
      for (const item of this.items) {
        next.push(item.id === this.editId ? makeMsg(item.id, title, content) : item);
      }
    }
    const seq: number = this.loadSeq;
    this.saveList(getChatKvStore(), next).then((): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.items = next;
      this.editing = false;
    }).catch((): void => {});
  }

  private deleteItem(item: QuickMessage): void {
    const next: QuickMessage[] = this.items.filter((m: QuickMessage): boolean => m.id !== item.id);
    const seq: number = this.loadSeq;
    this.saveList(getChatKvStore(), next).then((): void => {
      if (!this.pageAlive || seq !== this.loadSeq) return;
      this.items = next;
    }).catch((): void => {});
  }

  @Builder
  swipeActions(item: QuickMessage): void {
    Row() {
      Text('删除').fontSize(13).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(72).height('100%').backgroundColor(DANGER)
        .onClick((): void => { this.deleteItem(item); });
    }
  }

  build(): void {
    Column() {
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 }).onClick((): void => { router.back(); })
        Text('快捷消息').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        if (!this.editing) {
          Text('+ 新建').fontSize(BODY).fontColor(ACCENT).maxLines(1)
            .constraintSize({ minHeight: 40 })
            .onClick((): void => { this.startNew(); });
        }
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (this.editing) {
        Scroll() {
          Column() {
            Text(this.editId.length > 0 ? '编辑快捷消息' : '新建快捷消息')
              .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
              .width('100%').margin({ bottom: 12 })
            Text('标题').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ bottom: 6 })
            TextInput({ text: this.editTitle, placeholder: '消息标题' })
              .fontSize(BODY).fontColor(INK).backgroundColor(SURFACE).borderRadius(RADIUS)
              .width('100%').height(40).padding({ left: 12, right: 12 })
              .onChange((v: string): void => { this.editTitle = v; })
            Text('内容').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ top: 16, bottom: 6 })
            TextArea({ text: this.editContent, placeholder: '消息内容…' })
              .fontSize(BODY).fontColor(INK).backgroundColor(SURFACE).borderRadius(RADIUS)
              .width('100%').height(140).padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.editContent = v; })
            Row() {
              Text('取消').fontSize(BODY).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 14, bottom: 14 }).backgroundColor(SURFACE)
                .border({ width: 1, color: LINE }).borderRadius(12)
                .onClick((): void => { this.cancelEdit(); })
              Text('保存').fontSize(BODY).fontColor(SURFACE).layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 14, bottom: 14 }).backgroundColor(ACCENT).borderRadius(12)
                .margin({ left: 12 }).onClick((): void => { this.saveEdit(); })
            }.width('100%').margin({ top: 24 })
            Column() {}.height(40)
          }.width('100%').padding({ left: GUTTER, right: GUTTER, top: 16 })
        }
        .align(Alignment.Start).layoutWeight(1)
      } else if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.items.length === 0) {
        Column() {
          Text('还没有快捷消息').fontSize(BODY).fontColor(INK2)
          Text('点右上角新建一条').fontSize(META).fontColor(INK3).margin({ top: 8 })
        }.width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.items, (item: QuickMessage): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(item.content).fontSize(META).fontColor(INK3).margin({ top: 4 })
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              }.width('100%').alignItems(HorizontalAlign.Start)
                .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
                .backgroundColor(SURFACE).onClick((): void => { this.startEdit(item); })
            }.swipeAction({ end: this.swipeActions(item) });
          }, (item: QuickMessage): string => item.id)
        }.width('100%').layoutWeight(1).divider({ strokeWidth: 1, color: LINE })
      }
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
