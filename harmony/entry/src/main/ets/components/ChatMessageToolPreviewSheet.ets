// ChatMessageToolPreviewSheet — 工具详情 sheet(G4-S2 升级)
//
// Android 基准: ChatMessageToolPreviewSheet.kt(365 行)
// 升级: search_web 专项预览(answer + 结果卡片) + scrape_web 专项 + JsonTree 渲染
//   + tool input/output 分区 + 复制
// 降级(跳过): HighlightCodeBlock(用 JsonTree 替代) / ZoomableAsyncImage / FaviconRow /
//   memory delete(需 MemoryRepository) / MarkdownBlock answer(用 MarkdownText)

import type { UIMessagePartTool, UIMessagePartText, JsonValue } from '@amber/chat-domain';
import { toolInputAsJson, toolOutputJson, toolOutputText } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, ACCENT_LIGHT, BODY, META, LABEL } from '../design/tokens.ets';
import { JsonTree } from './ui/JsonTree.ets';
import { MarkdownText } from './MarkdownText.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';

// 搜索结果项
interface SearchResultItem {
  title: string;
  url: string;
  text: string;
}

@Component
export struct ChatMessageToolPreviewSheet {
  @Prop part: UIMessagePartTool;
  onDismiss: () => void = (): void => {};

  private copyToClipboard(text: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
    promptAction.showToast({ message: '已复制', duration: 1500 });
  }

  private getInputJson(): string {
    try {
      return JSON.stringify(toolInputAsJson(this.part), null, 2);
    } catch (_e) {
      return '{}';
    }
  }

  private getOutputJson(): JsonObject {
    try {
      return toolOutputJson(this.part);
    } catch (_e) {
      return {} as JsonObject;
    }
  }

  // search_web 专项:提取 answer + items
  private searchQuery(): string {
    const input: JsonObject = toolInputAsJson(this.part);
    const q: JsonValue = input['query'];
    return typeof q === 'string' ? q : '';
  }

  private searchAnswer(): string {
    const output: JsonObject = this.getOutputJson();
    const answer: JsonValue = output['answer'];
    return typeof answer === 'string' ? answer : '';
  }

  private searchItems(): SearchResultItem[] {
    const output: JsonObject = this.getOutputJson();
    const items: JsonValue = output['items'];
    if (!Array.isArray(items)) return [];
    const results: SearchResultItem[] = [];
    for (const item of items) {
      if (typeof item === 'object' && item !== null && !Array.isArray(item)) {
        const obj: JsonObject = item as JsonObject;
        const title: JsonValue = obj['title'];
        const url: JsonValue = obj['url'];
        const text: JsonValue = obj['text'];
        results.push({
          title: typeof title === 'string' ? title : '',
          url: typeof url === 'string' ? url : '',
          text: typeof text === 'string' ? text : '',
        } as SearchResultItem);
      }
    }
    return results;
  }

  // scrape_web 专项:提取 urls
  private scrapeUrls(): string[] {
    const input: JsonObject = toolInputAsJson(this.part);
    const urls: JsonValue = input['urls'];
    if (Array.isArray(urls)) {
      const result: string[] = [];
      for (const u of urls) {
        if (typeof u === 'string') result.push(u);
      }
      return result;
    }
    const singleUrl: JsonValue = input['url'];
    if (typeof singleUrl === 'string') return [singleUrl];
    return [];
  }

  private isSearchWeb(): boolean {
    return this.part.toolName === 'search_web';
  }

  private isScrapeWeb(): boolean {
    return this.part.toolName === 'scrape_web';
  }

  build(): void {
    Column() {
      // 标题行
      Row() {
        Text(`工具详情: ${this.part.toolName}`)
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
        Blank()
        Text('关闭')
          .fontSize(META)
          .fontColor(INK3)
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      Scroll() {
        Column() {
          if (this.isSearchWeb()) {
            this.RenderSearchPreview()
          } else if (this.isScrapeWeb()) {
            this.RenderScrapePreview()
          } else {
            this.RenderGenericPreview()
          }
        }
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('70%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }

  // search_web 专项预览
  @Builder
  RenderSearchPreview(): void {
    Column() {
      // 搜索查询
      Text(`搜索: ${this.searchQuery()}`)
        .fontSize(META)
        .fontColor(INK2)
        .width('100%')
        .padding({ left: 16, right: 16, top: 8 })

      // AI answer(MarkdownText 渲染)
      if (this.searchAnswer().length > 0) {
        Column() {
          Text('AI 回答').fontSize(LABEL).fontColor(INK3)
          MarkdownText({ content: this.searchAnswer(), fontSize: 13, fontColor: INK2 })
        }
        .width('100%')
        .padding(12)
        .backgroundColor(ACCENT_LIGHT)
        .borderRadius(8)
        .margin({ left: 16, right: 16, top: 8 })
      }

      // 搜索结果列表
      if (this.searchItems().length > 0) {
        Text(`搜索结果 ${this.searchItems().length} 条`)
          .fontSize(LABEL)
          .fontColor(INK3)
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 4 })

        ForEach(this.searchItems(), (item: SearchResultItem, idx: number): void => {
          Column() {
            Text(item.title)
              .fontSize(META)
              .fontColor(ACCENT)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
            if (item.text.length > 0) {
              Text(item.text)
                .fontSize(LABEL)
                .fontColor(INK2)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .margin({ top: 2 })
            }
            if (item.url.length > 0) {
              Text(item.url)
                .fontSize(LABEL)
                .fontColor(INK3)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .margin({ top: 2 })
            }
          }
          .width('100%')
          .padding(10)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .margin({ left: 16, right: 16, bottom: 6 })
        }, (item: SearchResultItem, idx: number): string => `search-${idx}`)
      } else {
        // 无结果 → JSON 树
        this.RenderOutputJson()
      }
    }
    .width('100%')
  }

  // scrape_web 专项预览
  @Builder
  RenderScrapePreview(): void {
    Column() {
      Text(`抓取: ${this.scrapeUrls().join(', ')}`)
        .fontSize(META)
        .fontColor(INK2)
        .width('100%')
        .padding({ left: 16, right: 16, top: 8 })

      // output 文本(MarkdownText)
      if (toolOutputText(this.part).length > 0) {
        MarkdownText({
          content: toolOutputText(this.part),
          fontSize: 13,
          fontColor: INK2,
        })
          .width('100%')
          .padding(12)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .margin({ left: 16, right: 16, top: 8 })
      }
    }
    .width('100%')
  }

  // 通用预览:input JSON + output JSON
  @Builder
  RenderGenericPreview(): void {
    Column() {
      // Input JSON
      Row() {
        Text('输入参数').fontSize(META).fontColor(INK3).layoutWeight(1)
        Text('复制').fontSize(LABEL).fontColor(ACCENT)
          .onClick((): void => { this.copyToClipboard(this.getInputJson()); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 4 })

      JsonTree({ json: this.getInputJson() })
        .width('100%')
        .padding(12)
        .backgroundColor(SURFACE2)
        .borderRadius(8)
        .margin({ left: 16, right: 16, bottom: 8 })

      // Output
      Row() {
        Text('输出结果').fontSize(META).fontColor(INK3).layoutWeight(1)
        Text('复制').fontSize(LABEL).fontColor(ACCENT)
          .onClick((): void => { this.copyToClipboard(toolOutputText(this.part)); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 4 })

      if (Object.keys(this.getOutputJson()).length > 0) {
        JsonTree({ json: JSON.stringify(this.getOutputJson(), null, 2) })
          .width('100%')
          .padding(12)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .margin({ left: 16, right: 16, bottom: 16 })
      } else {
        Text(toolOutputText(this.part))
          .fontSize(LABEL)
          .fontColor(INK2)
          .fontFamily('JetBrainsMono')
          .width('100%')
          .padding(12)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .margin({ left: 16, right: 16, bottom: 16 })
      }
    }
    .width('100%')
  }

  @Builder
  RenderOutputJson(): void {
    Column() {
      Text('原始输出')
        .fontSize(LABEL)
        .fontColor(INK3)
        .width('100%')
        .padding({ left: 16, right: 16, top: 8, bottom: 4 })
      Text(toolOutputText(this.part))
        .fontSize(LABEL)
        .fontColor(INK2)
        .fontFamily('JetBrainsMono')
        .width('100%')
        .padding(12)
        .backgroundColor(SURFACE2)
        .borderRadius(8)
        .margin({ left: 16, right: 16, bottom: 16 })
    }
    .width('100%')
  }
}
