// ChainOfThought — 思维链折叠容器(第三梯队)
// Android 基准: ui/ChainOfThought.kt(600 行)— 连续 reasoning+tool 整体折叠/展开
//   折叠时显示摘要(步骤数/工具数);展开时逐步骤渲染
// Harmony MVP: 折叠/展开切换 + 步骤计数摘要
//
// 与 ChatMessageReasoning 的区别:
//   ChatMessageReasoning = 单个 reasoning part 的三态卡片
//   ChainOfThought = 把一组 ThinkingStep(reasoning+tool)包成可折叠容器

import type { ThinkingStep } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ChainOfThought {
  @Prop steps: ThinkingStep[];
  @Prop expanded: boolean = false;
  onToggle: () => void = (): void => {};
  @BuilderParam content: () => void;

  build(): void {
    Column() {
      // 头部(可点击折叠/展开)
      Row() {
        Text(this.expanded ? '▾' : '▸').fontSize(11).fontColor(INK3)
        Text(this.summary())
          .fontSize(META).fontColor(INK2)
          .margin({ left: 6 })
          .layoutWeight(1)
        Text(`${this.steps.length} 步`).fontSize(LABEL).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 6, bottom: 6 })
      .backgroundColor(SURFACE2)
      .borderRadius(8)
      .onClick((): void => { this.onToggle(); })

      // 展开时渲染子内容(由消费方通过 content slot 注入)
      if (this.expanded) {
        Column() {
          this.content();
        }
        .width('100%')
        .padding({ left: 12, top: 4 })
        .border({ width: { left: 2 }, color: { left: LINE } })
        .margin({ left: 6, top: 2 })
      }
    }
    .width('100%')
    .margin({ bottom: 4 })
  }

  // 摘要:统计 reasoning/tool 步骤数
  private summary(): string {
    let reasoningCount: number = 0;
    let toolCount: number = 0;
    for (const step of this.steps) {
      if (step.kind === 'reasoning') reasoningCount++;
      else if (step.kind === 'tool') toolCount++;
    }
    if (reasoningCount > 0 && toolCount > 0) {
      return `思考 ${reasoningCount} 段 · 工具 ${toolCount} 次`;
    }
    if (reasoningCount > 0) return `思考 ${reasoningCount} 段`;
    if (toolCount > 0) return `工具 ${toolCount} 次`;
    return '思考过程';
  }
}
