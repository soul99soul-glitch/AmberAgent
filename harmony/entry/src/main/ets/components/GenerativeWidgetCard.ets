// GenerativeWidgetCard — 生成式 Widget 卡片(第三梯队)
// Android 基准: GenerativeWidgetCard.kt(1995 行)— SVG/HTML/VChart 渲染 + WebView
//   依赖 GenerativeWidgetParser/GenerativeWidgetSegment(Harmony 未移植)+ WebView
//
// 当前状态: 诚实占位(Android 1995 行平台依赖组件,需 WebView + Widget Parser 移植)
//   不伪成功——显示"[生成式组件]"占位 + widget 类型提示
//   后续迭代: WebView 组件移植 + GenerativeWidgetParser 域逻辑 + SVG/HTML 渲染

import { INK3, SURFACE2, META } from '../design/tokens.ets';

@Component
export struct GenerativeWidgetCard {
  @Prop widgetType: string = ''; // 'svg' | 'html' | 'vchart' | 'unknown'
  @Prop widgetTitle: string = '';
  build(): void {
    Column() {
      Text('🧩 生成式组件').fontSize(META).fontColor(INK3)
      if (this.widgetTitle.length > 0) {
        Text(this.widgetTitle).fontSize(META).fontColor(INK3)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 2 })
      }
      if (this.widgetType.length > 0) {
        Text(`类型: ${this.widgetType}`).fontSize(10).fontColor(INK3)
          .margin({ top: 2 })
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(SURFACE2)
    .borderRadius(8)
    .margin({ bottom: 4 })
  }
}
