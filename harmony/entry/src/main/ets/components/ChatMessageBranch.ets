// ChatMessageBranch — 分支选择器(细线单色,与动作图标同风格)
// Android 基准: ChatMessageBranch.kt — ◀ n/total ▶ 分支切换。
//   ◀/▶ 文本字符会被 emoji 渲染成彩色方块 → 改 chevron_left/right 描边图标,
//   可用 INK3 / 不可用 INK4,计数等宽 10。
import { INK3, INK4 } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatMessageBranch {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  onSwitch: (index: number) => void = (_i: number): void => {};
  build(): void {
    if (this.themeEpoch >= 0 && this.branchCount > 1) {
      Row() {
        Row() {
          Icon({ name: 'chevron_left', iconSize: 17, color: this.selectIndex > 0 ? INK3 : INK4 })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .accessibilityText('上一个分支')
        .onClick((): void => {
          if (this.selectIndex > 0) this.onSwitch(this.selectIndex - 1);
        })
        Text(`${this.selectIndex + 1}/${this.branchCount}`)
          .fontSize(10).fontFamily('JetBrainsMono').fontColor(INK3)
          .margin({ left: 6, right: 6 })
        Row() {
          Icon({ name: 'chevron_right', iconSize: 17,
            color: this.selectIndex < this.branchCount - 1 ? INK3 : INK4 })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .margin({ right: 4 })
        .accessibilityText('下一个分支')
        .onClick((): void => {
          if (this.selectIndex < this.branchCount - 1) this.onSwitch(this.selectIndex + 1);
        })
      }
    }
  }
}
