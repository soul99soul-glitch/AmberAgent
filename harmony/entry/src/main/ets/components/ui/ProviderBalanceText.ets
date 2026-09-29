// ProviderBalanceText — Provider 余额显示(ai/ProviderBalanceText.kt:84 行)
// D3-1:getBalance HTTP 已移植(BalanceSupport.ets;2 分钟缓存);
//   失败/未配置 → 诚实降级 '~'
import { INK2, INK3, BODY, META, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';
import type { ProviderSetting } from '@amber/chat-domain';
import { fetchProviderBalance } from '../../platform_impl/BalanceSupport.ets';

@Component
export struct ProviderBalanceText {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onSettingChange') setting: ProviderSetting | null = null;
  @State balance: string = '~';
  private alive: boolean = false;
  private requestSeq: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.onSettingChange();
  }

  private onSettingChange(): void {
    const seq: number = ++this.requestSeq;
    this.balance = '~';
    if (!this.alive) return;
    const s: ProviderSetting | null = this.setting;
    if (s === null || !s.balanceOption.enabled) return;
    fetchProviderBalance(s).then((v: string): void => {
      if (this.alive && seq === this.requestSeq) this.balance = v;
    }).catch((): void => {});
  }

  aboutToDisappear(): void {
    this.alive = false;
    ++this.requestSeq;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.setting !== null && this.setting.balanceOption.enabled) {
      Row() {
        Icon({ name: 'money', iconSize: META, color: INK2 }).margin({ right: 4 })
        Text(this.balance).fontSize(LABEL).fontColor(INK3)
      }
    }
  }
}
