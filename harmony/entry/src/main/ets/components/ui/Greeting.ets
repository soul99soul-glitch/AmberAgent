// Greeting — 时段问候语(ui/Greeting.kt:36 行)
// Android 基准: Calendar.HOUR_OF_DAY 分段 → morning/afternoon/evening/night 字符串
// ArkUI: 纯逻辑 + Text 渲染
import { INK, INK2, BODY } from '../../design/tokens.ets';

export const greetingMessage = (): string => {
  const hour: number = new Date().getHours();
  if (hour >= 5 && hour <= 11) return '早上好';
  if (hour >= 12 && hour <= 17) return '下午好';
  if (hour >= 18 && hour <= 22) return '晚上好';
  return '夜深了';
};

@Component
export struct Greeting {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop fontSize: number = BODY;
  @Prop color: string = INK;
  build(): void {
    Text(greetingMessage())
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(this.fontSize).fontColor(this.color)
  }
}
