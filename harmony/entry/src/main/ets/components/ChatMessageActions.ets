// ChatMessageActions — 消息操作按钮组件(1:1 原型 turn-actions)
//
// 原型基准:icon 按钮(copy/retry)17px、ink-3、gap 18、actbtn padding 2;
//   鸿蒙保留 fork/edit 入口(能力差异登记),同样图标化:
//   copy=square_on_square / fork=share / edit=square_and_pencil / retry=arrow_clockwise
//   (原型 tts 朗读随语音服务下线一并移除)
import { INK3, BG, ACCENT } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatMessageActions {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('darkMode') darkMode: boolean = false;
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  @Prop canCopy: boolean = true;
  @Prop canFork: boolean = true;
  @Prop canEdit: boolean = true;
  @Prop canRegenerate: boolean = true;
  @Prop canFavorite: boolean = true;
  @Prop isFavorite: boolean = false;
  onFork: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onCopy: () => void = (): void => {};
  onFavorite: () => void = (): void => {};

  build(): void {
    Row() {
      // copy:自绘双框线字形(SDK 无 outline 版 square_on_square,与其余细线图标统一)
      if (this.canCopy) {
        Row() {
          Stack() {
            Column().width(9).height(11).borderRadius(2)
              .border({ width: 1, color: INK3 })
              .offset({ x: 2.5, y: -2.5 })
            Column().width(9).height(11).borderRadius(2)
              .border({ width: 1, color: INK3 })
              .backgroundColor(BG)
              .offset({ x: -2.5, y: 2.5 })
          }
          .width(17).height(17)
        }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .margin({ right: 4 })
          .accessibilityText('复制消息')
          .onClick((): void => this.onCopy())
      }
      if (this.canFork) {
        Row() { Icon({ name: 'share', iconSize: 17, color: INK3 }) }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .margin({ right: 4 })
          .accessibilityText('创建消息分支')
          .onClick((): void => this.onFork())
      }
      if (this.isUser && this.canEdit) {
        Row() { Icon({ name: 'edit', iconSize: 17, color: INK3 }) }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .margin({ right: 4 })
          .accessibilityText('编辑消息')
          .onClick((): void => this.onEdit())
      }
      if ((this.isLastAssistant || this.isUser) && this.canRegenerate) {
        Row() { Icon({ name: 'retry', iconSize: 17, color: INK3 }) }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .accessibilityText('重新生成')
          .onClick((): void => this.onRegenerate())
      }
      if (!this.isUser && this.canFavorite) {
        // 收藏(真实 FavoriteStore 落盘)
        Row() { Icon({ name: 'star', iconSize: 17, color: this.isFavorite ? ACCENT : INK3 }) }
          .width(44).height(44)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .margin({ left: 4 })
          .accessibilityText(this.isFavorite ? '取消收藏消息' : '收藏消息')
          .onClick((): void => this.onFavorite())
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
      .padding({ top: 4, bottom: 4 })
  }
}
