import type { PendingUserMessage } from '@amber/chat-domain';
import { pendingPreviewText } from '@amber/chat-domain';
import { BODY, INK, INK2, INK3, LINE, META, SURFACE, SURFACE2 } from '../design/tokens.ets';

@Component
export struct ChatPendingQueueSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColor: string = '#B46338';
  @Prop messages: PendingUserMessage[] = [];
  onCancel: (id: string) => void = (_id: string): void => {};
  onRestore: (id: string) => void = (_id: string): void => {};
  onClear: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  private modeLabel(message: PendingUserMessage): string {
    if (message.mode === 'STEER') return '插话';
    if (message.mode === 'COLLECT') return '合并';
    return '排队';
  }

  build(): void {
    Column() {
      Row() {
        Text(`消息队列 · ${this.messages.length}`).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
      }.width('100%').height(56).padding({ top: 12, right: 44 })
      Text('排队：当前回复后发送；插话：下一轮工具处理时加入；合并：相邻合并消息一起发送。')
        .fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 12 })
      Scroll() {
        Column({ space: 10 }) {
          ForEach(this.messages, (message: PendingUserMessage): void => {
            Column() {
              Text(this.modeLabel(message)).fontSize(META).fontColor(this.accentColor).width('100%')
              Text(pendingPreviewText(message)).fontSize(BODY).fontColor(INK2)
                .maxLines(5).textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%').margin({ top: 6, bottom: 4 })
              Row() {
                Text('恢复到输入框').fontSize(META).fontColor(this.accentColor)
                  .constraintSize({ minHeight: 44 })
                  .onClick((): void => { this.onRestore(message.id); })
                Blank()
                Text('取消').fontSize(META).fontColor(INK3)
                  .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
                  .onClick((): void => { this.onCancel(message.id); })
              }.width('100%')
            }.width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(16)
          }, (message: PendingUserMessage): string => message.id)
          if (this.messages.length === 0) {
            Text('没有排队消息').fontSize(BODY).fontColor(INK3).padding({ top: 24, bottom: 24 })
          }
        }.width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Off)
      if (this.messages.length > 0) {
        Text('清空队列').fontSize(META).fontColor(INK3).width('100%').height(44)
          .textAlign(TextAlign.Center).margin({ top: 8 })
          .border({ width: 1, color: LINE }).borderRadius(22)
          .onClick((): void => { this.onClear(); })
      }
    }.width('100%').height('100%').padding({ left: 20, right: 20, bottom: 20 })
      .backgroundColor(SURFACE).opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
