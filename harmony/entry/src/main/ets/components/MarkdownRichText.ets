// MarkdownRichText — Markdown 渲染组件(阶段 B, audit 1.1)
//
// 方案: markdownToHtml(chat HAR 纯逻辑) → ArkUI RichText 组件渲染 HTML
// Android 基准: richtext/Markdown.kt(3476 行自建 AST→Compose) + MarkdownNew.kt(1555 行 HTML-based)
//   Harmony 选 HTML 路径,用 RichText 组件原生 HTML 支持
//
// 设备验证发现(2026-08-03):
//   RichText 不参与 ArkUI 布局测量 → 父容器高度计算错误 → 操作按钮不可见
//   RichText 是 ArkUI "孤岛"组件:渲染内容但不向宿主暴露布局信息
//   constraintSize(minHeight) 无法解决:RichText 实际渲染高度可能远超估算值
//
// 修复方案(2026-08-04):
//   默认用 ArkUI 原生 Text 组件渲染纯文本(布局可控,操作按钮可见)
//   RichText 作为 opt-in 模式(useRichText: true)保留(未来用原生渲染管线替代后可切回)
//   纯文本模式:剥离 HTML 标签 + 保留基本格式(缩进/换行)
//
// 能力边界:
//   纯文本模式:✅ 段落/换行/缩进;❌ 标题/粗斜体/链接/代码高亮/列表符号(纯文本)
//   RichText 模式:✅ 全格式;❌ 布局测量(操作按钮不可见)

import { markdownToHtml } from '@amber/chat-domain';
import { INK, INK2, SURFACE2, ACCENT, LINE, BODY } from '../design/tokens.ets';

// 剥离 HTML 标签,保留纯文本 + 基本格式
const stripHtml = (html: string): string => {
  return html
    // 代码块:保留内容,加缩进
    .replace(/<pre><code[^>]*>([\s\S]*?)<\/code><\/pre>/g, (_m: string, code: string): string => `\n    ${code.replace(/<[^>]+>/g, '').trim()}\n`)
    // 行内代码:保留内容
    .replace(/<code[^>]*>(.*?)<\/code>/g, (_m: string, code: string): string => code.replace(/<[^>]+>/g, ''))
    // 标题:加换行 + 保留文本
    .replace(/<h[1-6][^>]*>(.*?)<\/h[1-6]>/g, (_m: string, text: string): string => `\n${text.replace(/<[^>]+>/g, '')}\n`)
    // 列表项:加缩进
    .replace(/<li[^>]*>(.*?)<\/li>/g, (_m: string, text: string): string => `  • ${text.replace(/<[^>]+>/g, '')}\n`)
    // 换行/段落
    .replace(/<br\s*\/?>/g, '\n')
    .replace(/<\/p>/g, '\n')
    .replace(/<\/blockquote>/g, '\n')
    // 引用:加 > 前缀
    .replace(/<blockquote>/g, '> ')
    // 水平线
    .replace(/<hr\s*\/?>/g, '\n———\n')
    // 链接:保留文本
    .replace(/<a[^>]*>(.*?)<\/a>/g, (_m: string, text: string): string => text.replace(/<[^>]+>/g, ''))
    // 剥离所有剩余标签
    .replace(/<[^>]+>/g, '')
    // HTML 实体
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    // 清理多余空行
    .replace(/\n{3,}/g, '\n\n')
    .trim();
};

@Component
export struct MarkdownRichText {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop content: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  // 默认纯文本模式(布局可控);设 true 用 RichText(布局不可控但格式完整)
  @Prop useRichText: boolean = false;

  build(): void {
    Column() {
      if (this.content.trim().length > 0) {
        if (this.useRichText) {
          // RichText 模式(格式完整但布局不可控)
          RichText(this.buildHtml())
            .width('100%')
        } else {
          // 纯文本模式(布局可控,操作按钮可见)
          Text(this.stripToText())
            .fontSize(this.fontSize)
            .fontColor(this.fontColor)
            .lineHeight(this.fontSize * 1.6)
            .width('100%')
            .textAlign(TextAlign.Start)
        }
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }

  private stripToText(): string {
    const html: string = markdownToHtml(this.content);
    return stripHtml(html);
  }

  private buildHtml(): string {
    const html: string = markdownToHtml(this.content);
    return `<div style="font-size:${this.fontSize}px;color:${this.fontColor};line-height:1.6;">${html}</div>`;
  }
}
