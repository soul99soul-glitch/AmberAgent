import type { MemoryEvent } from '@amber/chat-domain';
import { INK, INK3, LINE, ERROR } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { memoryEventLabel, relativeMemoryDate } from './MemoryPresentation.ets';

@Component
export struct MemoryChangeLog {
  @Prop events: MemoryEvent[] = [];
  @Prop accentColor: string = '';
  @State expanded: boolean = false;

  @Builder
  private EventRow(event: MemoryEvent): void {
    Column({ space: 6 }) {
      Row({ space: 8 }) {
        Text(`${memoryEventLabel(event.type)}${event.memoryId === null ? '' : ` · #${event.memoryId}`}`)
          .fontSize(14).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(event.type.endsWith('failed') ? ERROR : event.type === 'memory_invalidated' || event.type === 'memory_archived' ? '#C27A2E' : INK)
          .layoutWeight(1)
        Text(relativeMemoryDate(event.createdAt, Date.now())).fontSize(12).fontColor(INK3)
          .constraintSize({ minWidth: 58 }).textAlign(TextAlign.End)
      }.width('100%').alignItems(VerticalAlign.Center)
      if (event.message.trim().length > 0) {
        Text(event.message).fontSize(13).fontColor(INK3).width('100%').wordBreak(WordBreak.BREAK_ALL)
          .copyOption(CopyOptions.LocalDevice)
      }
    }.width('100%').padding({ top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      if (this.events.length === 0) {
        Text('暂无记忆变更记录').fontSize(13).fontColor(INK3).width('100%').padding({ top: 12, bottom: 12 })
      } else {
        ForEach(this.expanded ? this.events : this.events.slice(0, 8), (event: MemoryEvent, index: number): void => {
          if (index > 0) Row().width('100%').height(1).backgroundColor(LINE)
          this.EventRow(event)
        }, (event: MemoryEvent): string => event.id)
        if (this.events.length > 8) {
          Text(this.expanded ? '收起' : `显示全部（${this.events.length} 条）`).fontSize(14)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColor).width('100%')
            .constraintSize({ minHeight: 44 }).textAlign(TextAlign.Center)
            .onClick((): void => { this.expanded = !this.expanded; })
        }
      }
    }.width('100%').padding({ left: 14, right: 14 })
  }
}
