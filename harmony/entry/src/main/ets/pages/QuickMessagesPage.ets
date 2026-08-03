// QuickMessagesPage — 快捷消息列表
// 展示快捷消息模板(标题 + 内容预览),添加/内联编辑/滑动删除
// Android 基准: feature/ui/pages/quick/QuickMessagesPage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

const DANGER: string = '#B43A3A';

interface QuickMessage {
  id: string;
  title: string;
  content: string;
}

function makeMsg(id: string, title: string, content: string): QuickMessage {
  return { id: id, title: title, content: content };
}

function placeholderLoadMessages(): Promise<QuickMessage[]> {
  const seed: QuickMessage[] = [
    makeMsg('qm-1', '继续', '请继续上面的内容'),
    makeMsg('qm-2', '总结', '请用三点总结本次对话'),
    makeMsg('qm-3', '翻译为英文', '请将上文翻译为英文'),
    makeMsg('qm-4', '解释代码', '请逐行解释这段代码的作用'),
    makeMsg('qm-5', '优化建议', '请给出优化建议与改进方向'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct QuickMessagesPage {
  @State items: QuickMessage[] = [];
  @State loaded: boolean = false;
  @State editing: boolean = false;
  @State editId: string = '';
  @State editTitle: string = '';
  @State editContent: string = '';

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    placeholderLoadMessages().then((list: QuickMessage[]): void => {
      this.items = list;
      this.loaded = true;
    });
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

  private saveEdit(): void {
    const title: string = this.editTitle.trim();
    if (title.length === 0) return;
    if (this.editId.length === 0) {
      const id: string = 'qm-' + (this.items.length + 1).toString();
      const next: QuickMessage = makeMsg(id, title, this.editContent);
      const newList: QuickMessage[] = [];
      newList.push(next);
      for (let i = 0; i < this.items.length; i++) {
        newList.push(this.items[i]);
      }
      this.items = newList;
    } else {
      this.items = this.items.map((m: QuickMessage): QuickMessage => {
        if (m.id === this.editId) {
          return makeMsg(m.id, title, this.editContent);
        }
        return m;
      });
    }
    this.editing = false;
  }

  private deleteItem(item: QuickMessage): void {
    this.items = this.items.filter((m: QuickMessage): boolean => m.id !== item.id);
  }

  @Builder swipeActions(item: QuickMessage): void {
    Row() {
      Text('删除').fontSize(13).fontColor(Color.White)
        .textAlign(TextAlign.Center)
        .width(72).height('100%')
        .backgroundColor(DANGER)
        .onClick((): void => { this.deleteItem(item); })
    }
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('快捷消息').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        if (!this.editing) {
          Text('+ 新建').fontSize(BODY).fontColor(ACCENT)
            .onClick((): void => { this.startNew(); })
        }
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (this.editing) {
        // 内联编辑器
        Scroll() {
          Column() {
            Text(this.editId.length > 0 ? '编辑快捷消息' : '新建快捷消息')
              .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
              .width('100%').margin({ bottom: 12 })

            Text('标题').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ bottom: 6 })
            TextInput({ text: this.editTitle, placeholder: '消息标题' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.editTitle = v; })

            Text('内容').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ top: 16, bottom: 6 })
            TextArea({ text: this.editContent, placeholder: '消息内容…' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').height(140).padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.editContent = v; })

            Row() {
              Text('取消').fontSize(BODY).fontColor(INK3)
                .layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 12, bottom: 12 })
                .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(12)
                .onClick((): void => { this.cancelEdit(); })
              Text('保存').fontSize(BODY).fontColor(SURFACE)
                .layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 12, bottom: 12 })
                .backgroundColor(ACCENT).borderRadius(12)
                .margin({ left: 12 })
                .onClick((): void => { this.saveEdit(); })
            }
            .width('100%').margin({ top: 24 })

            Column() {}.height(40)
          }
          .width('100%').padding({ left: 16, right: 16, top: 16 })
        }
        .layoutWeight(1)
      } else if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.items.length === 0) {
        Column() {
          Text('还没有快捷消息').fontSize(BODY).fontColor(INK2)
          Text('点右上角新建一条').fontSize(META).fontColor(INK3).margin({ top: 8 })
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.items, (item: QuickMessage): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(item.content).fontSize(META).fontColor(INK3).margin({ top: 4 })
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.startEdit(item); })
            }
            .swipeAction({ end: this.swipeActions(item) })
          }, (item: QuickMessage): string => item.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
