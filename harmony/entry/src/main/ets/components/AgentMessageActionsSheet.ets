// Agent 回答长按操作；选择文字复用完整消息选区页，复制直接写入剪贴板。
import { BODY, INK, INK2, LINE, SURFACE } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct AgentMessageActionsSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  onSelectText: () => void = (): void => {};
  onCopy: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    Column() {
      Column() {
        this.actionRow('edit', '选择文字', (): void => { this.onSelectText(); })
        Divider().color(LINE).strokeWidth(0.5).margin({ left: 48 })
        this.actionRow('copy', '复制', (): void => { this.onCopy(); })
        Divider().color(LINE).strokeWidth(0.5).margin({ left: 48 })
        this.actionRow('retry', '重新生成', (): void => { this.onRegenerate(); })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius(12)
      .border({ width: 1, color: LINE })
      .clip(true)
    }
    .enabled(this.themeEpoch >= 0)
    .width('100%')
    .padding({ left: 16, right: 16, top: 10, bottom: 24 })
  }

  @Builder
  private actionRow(iconName: string, label: string, action: () => void): void {
    Row() {
      Icon({ name: iconName, iconSize: 18, color: INK2 }).margin({ right: 14 })
      Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
    }
    .width('100%').height(48)
    .padding({ left: 16, right: 16 })
    .alignItems(VerticalAlign.Center)
    .accessibilityText(label)
    .onClick((): void => {
      this.onDismiss();
      action();
    })
  }
}
