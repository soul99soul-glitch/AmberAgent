// ProviderBalanceText — Provider 余额显示(ai/ProviderBalanceText.kt:84 行)
// Android 基准: getBalance() HTTP 调用 + 2 分钟缓存 + MoneyBag02 图标 + 余额文本
// ArkUI MVP: getBalance() HTTP 运行时未移植 → 诚实占位显示 "~"
//   后续:移植 getBalance HTTP 调用 + LRU 缓存后替换为真实余额
import { INK2, INK3, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct ProviderBalanceText {
  @Prop balanceEnabled: boolean = false;
  build(): void {
    if (this.balanceEnabled) {
      Row() {
        Text('💰').fontSize(META).margin({ right: 4 })
        Text('~').fontSize(LABEL).fontColor(INK3)
      }
    }
  }
}
