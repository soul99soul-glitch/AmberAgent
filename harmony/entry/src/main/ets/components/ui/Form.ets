// Form — 表单行布局(对齐 Android Form.kt)
// FormItem: label + description + content + tail 水平排列
import { INK, INK2, INK3, BODY, META } from '../../design/tokens.ets';

@Component
export struct FormItem {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop label: string = '';
  @Prop description: string = '';
  @BuilderParam content: () => void;
  @BuilderParam tail: () => void;
  build(): void {
    Row() {
      // 左侧: label + description
      Column() {
        Text(this.label)
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
        if (this.description.length > 0) {
          Text(this.description)
            .fontSize(META)
            .fontColor(INK3)
            .margin({ top: 2 })
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)

      // 中间: content(开关/输入等)
      this.content()

      // 右侧: tail
      this.tail()
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .justifyContent(FlexAlign.SpaceBetween)
    .alignItems(VerticalAlign.Center)
  }
}
