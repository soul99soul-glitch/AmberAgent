// ReasoningPicker — 推理档位段集选择器(1:1 原型思考等级横条)
//
// 原型基准:模型菜单内 `// 思考等级` 眉标下 off/high/max 三段等宽横条;
//   圆角分段条，选中段使用强调色实底；点等级菜单保持打开。
//   档位段集由域逻辑 reasoningLevelsForModel 决定(调用方门控 length>1)。
import type { ReasoningLevelOption } from '@amber/chat-domain';
import { INK3, ACCENT, SURFACE2, LINE, LABEL } from '../design/tokens.ets';

@Component
export struct ReasoningPicker {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop levels: ReasoningLevelOption[] = [];
  @Prop currentLevel: string = '';
  onReasoningLevelChange: (level: string) => void = (_l: string): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.levels.length > 1) {
      Row() {
        Row() {
          ForEach(this.levels, (opt: ReasoningLevelOption): void => {
            Text(opt.label)
              .fontSize(LABEL)
              .fontColor(opt.level === this.currentLevel ? '#FFFFFF' : INK3)
              .layoutWeight(1).height(24)
              .textAlign(TextAlign.Center)
              .backgroundColor(opt.level === this.currentLevel ? this.accentColorStore : Color.Transparent)
              .borderRadius(12)
              .onClick((): void => { this.onReasoningLevelChange(opt.level); })
          }, (opt: ReasoningLevelOption): string => opt.level)
        }
        .width('100%').padding(2).backgroundColor(SURFACE2)
        .borderRadius(14).border({ width: 1, color: LINE })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    }
  }
}
