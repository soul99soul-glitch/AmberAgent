// MarkdownRichText — Markdown 渲染组件(阶段 B, audit 1.1)
//
// 方案: markdownToHtml(chat HAR 纯逻辑) → ArkUI RichText 组件渲染 HTML
// Android 基准: richtext/Markdown.kt(3476 行自建 AST→Compose) + MarkdownNew.kt(1555 行 HTML-based)
//   Harmony 选 HTML 路径,用 RichText 组件原生 HTML 支持
//
// 能力边界:
//   ✅ 标题/段落/粗斜体/链接/列表/代码块/行内代码/引用/图片/表格/水平线
//   ❌ 语法高亮(RichText 内 pre/code 无高亮,需后续 HighlightCodeBlock 独立组件)
//   ❌ Mermaid/LaTeX(markdownToHtml 不识别,原样保留为文本)
//
// 风险: RichText 实际渲染效果需设备验证(Gate 0 解锁后 spike)
//   source-only 可编码,HAP 构建可验证编译

import { markdownToHtml } from '@amber/chat-domain';
import { INK, INK2, SURFACE2, ACCENT, LINE, BODY } from '../design/tokens.ets';

@Component
export struct MarkdownRichText {
  @Prop content: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;

  build(): void {
    Column() {
      if (this.content.trim().length > 0) {
        RichText(this.buildHtml())
          .width('100%')
      }
    }
    .width('100%')
  }

  private buildHtml(): string {
    const html: string = markdownToHtml(this.content);
    // 包裹一层 div 控制 base 字体/颜色(RichText 不直接支持 fontSize 属性)
    return `<div style="font-size:${this.fontSize}px;color:${this.fontColor};line-height:1.6;">${html}</div>`;
  }
}
