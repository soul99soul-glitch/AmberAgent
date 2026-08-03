// ReasoningPicker — 推理档位段集选择器(M2.1)
// Android 基准: feature/ui/components/ai/ChatInputComposers.kt:293-314(thinking footer)
//   + ModelList.kt:1135-1190 ThinkingLevelSegment(段集选择)
//   + ai/ReasoningPicker.kt:89-210(底部 sheet 完整版,鸿蒙用段集替代,更轻量)
//
// 渲染:一行可点 chip(每档位一个),selected = accent 底白字。
//   档位段集由域逻辑 reasoningLevelsForModel(model) 决定(deepseek 3 / claude 6 / gpt 4 / 默认 2)。
//   调用方负责模型能力门控(levels.length <= 1 时不渲染本组件)。
//
// 鸿蒙差异:
//   - 用段集 chip 而非 Android 的 Slider+底部 sheet(更轻量,对齐 ChatAssistantsPage 既有 chip 风格)
//   - 选择委托 onReasoningLevelChange;持久化由调用方(ChatPage)经 withReasoningLevelForModel 完成
import type { ReasoningLevelOption } from '@amber/chat-domain';
import { INK3, ACCENT, SURFACE2, LABEL, META } from '../design/tokens.ets';

@Component
export struct ReasoningPicker {
  @Prop levels: ReasoningLevelOption[] = [];
  @Prop currentLevel: string = '';
  onReasoningLevelChange: (level: string) => void = (_l: string): void => {};

  build(): void {
    // 段集 ≤1 时不渲染(单档或空 = 模型不支持 reasoning 选择,诚实降级)
    if (this.levels.length > 1) {
      Scroll() {
        Row() {
          Text('思考')
            .fontSize(LABEL).fontColor(INK3)
            .margin({ right: 6 })
          ForEach(this.levels, (opt: ReasoningLevelOption): void => {
            Text(opt.label)
              .fontSize(LABEL)
              .fontColor(opt.level === this.currentLevel ? '#FFFFFF' : INK3)
              .padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .backgroundColor(opt.level === this.currentLevel ? ACCENT : SURFACE2)
              .borderRadius(10)
              .margin({ right: 4 })
              .onClick((): void => { this.onReasoningLevelChange(opt.level); })
          }, (opt: ReasoningLevelOption): string => opt.level)
        }
        .padding({ left: 12, right: 12 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
      .padding({ top: 2, bottom: 2 })
    }
  }
}
