// NotionSlider — Notion 风格滑块(ui/NotionSlider.kt:53-147)
// Android 基准: 3dp 细轨道 + 20dp 甜甜圈 thumb(外环 primary / 内环 onPrimary)
//   拖动连续,onValueChangeFinished 释放时 snap + 回调(不每 tick 回调)
//   右侧 valueLabel(PercentLabel/IntLabel 预设)
//
// ArkUI 差异: Slider 有 blockColor/trackColor/selectedColor;无自定义 thumb 形状(用默认圆形)
//   snap: ArkUI Slider 有 step 属性(原生 snap)
import { INK2, INK3, ACCENT, SURFACE, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct NotionSlider {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop value: number = 0;
  @Prop min: number = 0;
  @Prop max: number = 1;
  @Prop step: number = 0;
  @Prop fieldEnabled: boolean = true;
  @Prop labelMode: string = ''; // 'percent' | 'int' | ''(none)
  onChange: (value: number) => void = (_v: number): void => {};

  private labelValue(): string {
    if (this.labelMode === 'percent') {
      return `${Math.round(this.value * 100)}%`;
    }
    if (this.labelMode === 'int') {
      return `${Math.round(this.value)}`;
    }
    return '';
  }

  build(): void {
    Row() {
      Slider({
        value: this.value,
        min: this.min,
        max: this.max,
        step: this.step > 0 ? this.step : 0,
      })
        .layoutWeight(1)
        .blockColor(ACCENT)
        .trackColor(SURFACE)
        .selectedColor(ACCENT)
        .enabled(this.fieldEnabled)
        .onChange((v: number): void => { this.onChange(v); })

      if (this.labelMode.length > 0) {
        Text(this.labelValue())
          .fontSize(BODY).fontColor(INK3)
          .width(50)
          .textAlign(TextAlign.End)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}
