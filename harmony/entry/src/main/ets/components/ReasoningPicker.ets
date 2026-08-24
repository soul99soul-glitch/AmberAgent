// ReasoningPicker — 推理档位段集选择器(1:1 原型思考等级横条)
//
// 原型基准:模型菜单内 `// 思考等级` 眉标下 off/high/max 三段等宽横条;
//   选中段 = 陶土 14% 淡底 + 陶土字;未选 = ink-3。点等级菜单保持打开。
//   档位段集由域逻辑 reasoningLevelsForModel 决定(调用方门控 length>1)。
import type { ReasoningLevelOption } from '@amber/chat-domain';
import { INK3, ACCENT, SURFACE2, LABEL } from '../design/tokens.ets';

@Component
export struct ReasoningPicker {
  @Prop levels: ReasoningLevelOption[] = [];
  @Prop currentLevel: string = '';
  onReasoningLevelChange: (level: string) => void = (_l: string): void => {};

  // 选中段 = accent 14% 透明底(随色板/主题切换)
  private selectedBg(): string {
    return '#24' + ACCENT.substring(1);
  }

  build(): void {
    if (this.levels.length > 1) {
      Row() {
        ForEach(this.levels, (opt: ReasoningLevelOption): void => {
          Text(opt.label)
            .fontSize(LABEL)
            .fontFamily('JetBrainsMono')
            .fontColor(opt.level === this.currentLevel ? ACCENT : INK3)
            .layoutWeight(1)
            .textAlign(TextAlign.Center)
            .padding({ top: 6, bottom: 6 })
            .backgroundColor(opt.level === this.currentLevel ? this.selectedBg() : SURFACE2)
            .borderRadius(8)
            .margin({ left: 3, right: 3 })
            .onClick((): void => { this.onReasoningLevelChange(opt.level); })
        }, (opt: ReasoningLevelOption): string => opt.level)
      }
      .width('100%')
      .padding({ left: 10, right: 10, top: 6, bottom: 6 })
    }
  }
}
