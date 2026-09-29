// ToolResultPreview — 内联工具结果预览组件
// 渲染在消息列表中工具胶囊下方,按 toolName 分发不同预览样式。
// 对照 Android: ChatMessageTools.kt output 预览折叠段(简化内联版)
//
// 分发规则:
//   search_web   → AI answer 预览 + 结果数量徽章
//   scrape_web   → 抓取 url + 正文预览
//   memory_tool  → 记忆内容预览
//   file_*       → 文件名 + "点击预览"入口
//   terminal_*   → 命令 + 退出码
//
// 折叠: 默认 max 3 行,展开按钮切换全文。
// 错误检测: outputText 含 error/exception → 红字。
//
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避保留字;@Builder 内不声明 const/let。

import type { JsonObject, JsonValue } from '@amber/chat-domain';
import {
  INK, INK2, INK3, SURFACE, LINE, ACCENT, ERROR, LABEL,
} from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ToolResultPreview {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop toolName: string = '';
  @Prop outputText: string = '';
  @Prop outputJson: string = '';
  onOpenPreview: () => void = (): void => {};
  @State expanded: boolean = false;

  // —— JSON 解析(防御) ——
  private parsedJson(): JsonObject {
    if (this.outputJson.length === 0) return {} as JsonObject;
    try {
      return JSON.parse(this.outputJson) as JsonObject;
    } catch (_e) {
      return {} as JsonObject;
    }
  }

  // —— 工具类型判定 ——
  private isSearchWeb(): boolean { return this.toolName === 'search_web'; }
  private isScrapeWeb(): boolean { return this.toolName === 'scrape_web'; }
  private isMemoryTool(): boolean { return this.toolName.startsWith('memory_tool'); }
  private isFileTool(): boolean { return this.toolName.startsWith('file_'); }
  private isTerminalTool(): boolean { return this.toolName.startsWith('terminal_'); }

  // —— 错误检测 ——
  private hasError(): boolean {
    const lower: string = this.outputText.toLowerCase();
    return lower.includes('error') || lower.includes('exception');
  }

  // —— search_web: answer ——
  private searchAnswer(): string {
    const obj: JsonObject = this.parsedJson();
    const v: JsonValue = obj['answer'];
    return typeof v === 'string' ? v : '';
  }

  // —— search_web: 结果数量 ——
  private searchResultCount(): number {
    const obj: JsonObject = this.parsedJson();
    const items: JsonValue = obj['items'];
    if (Array.isArray(items)) return items.length;
    const count: JsonValue = obj['count'];
    return typeof count === 'number' ? count : 0;
  }

  // —— scrape_web: url ——
  private scrapeUrl(): string {
    const obj: JsonObject = this.parsedJson();
    const u: JsonValue = obj['url'];
    if (typeof u === 'string') return u;
    const urls: JsonValue = obj['urls'];
    if (Array.isArray(urls) && urls.length > 0) {
      const first: JsonValue = urls[0];
      return typeof first === 'string' ? first : '';
    }
    return '';
  }

  // —— file_*: 文件名 ——
  private fileFilename(): string {
    const obj: JsonObject = this.parsedJson();
    const name: JsonValue = obj['filename'];
    if (typeof name === 'string' && name.length > 0) return name;
    const path: JsonValue = obj['path'];
    if (typeof path === 'string' && path.length > 0) {
      const parts: string[] = path.split('/');
      return parts[parts.length - 1];
    }
    const f: JsonValue = obj['name'];
    return typeof f === 'string' ? f : '';
  }

  // —— terminal_*: 命令 ——
  private terminalCommand(): string {
    const obj: JsonObject = this.parsedJson();
    const cmd: JsonValue = obj['command'];
    if (typeof cmd === 'string') return cmd;
    const c: JsonValue = obj['cmd'];
    return typeof c === 'string' ? c : '';
  }

  // —— terminal_*: 退出码 ——
  private terminalExitCode(): number {
    const obj: JsonObject = this.parsedJson();
    const code: JsonValue = obj['exit_code'];
    if (typeof code === 'number') return code;
    const c: JsonValue = obj['exitCode'];
    return typeof c === 'number' ? c : -1;
  }

  // —— 主体预览文本 ——
  private previewBody(): string {
    if (this.isSearchWeb()) {
      const ans: string = this.searchAnswer();
      return ans.length > 0 ? ans : this.outputText;
    }
    return this.outputText;
  }

  private hasContent(): boolean {
    if (this.previewBody().length > 0) return true;
    if (this.isSearchWeb()) return true;
    if (this.isFileTool() && this.fileFilename().length > 0) return true;
    if (this.isTerminalTool()) return true;
    if (this.isScrapeWeb() && this.scrapeUrl().length > 0) return true;
    return false;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.hasContent()) {
      Column() {
        // 标签行(按工具类型)
        this.RenderLabelRow()

        // 主体预览(可折叠)
        if (this.previewBody().length > 0) {
          Column() {
            if (this.expanded) {
              Text(this.previewBody())
                .fontSize(LABEL)
                .fontColor(this.hasError() ? ERROR : INK2)
                .fontFamily('JetBrainsMono')
                .width('100%')
                .lineHeight(16)
            } else {
              Text(this.previewBody())
                .fontSize(LABEL)
                .fontColor(this.hasError() ? ERROR : INK2)
                .fontFamily('JetBrainsMono')
                .maxLines(3)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .lineHeight(16)
            }
            Text(this.expanded ? '收起' : '展开')
              .fontSize(LABEL)
              .fontColor(INK3)
              .margin({ top: 2 })
              .onClick((): void => { this.expanded = !this.expanded; })
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .backgroundColor(SURFACE)
          .borderRadius(8)
          .border({ width: 0.5, color: LINE })
          .margin({ top: 4, left: 32 })
        }

        // file_*: 点击预览入口
        if (this.isFileTool()) {
          Row() {
            Text('点击预览').fontSize(LABEL).fontColor(ACCENT)
            Text('›').fontSize(LABEL).fontColor(ACCENT).margin({ left: 4 })
          }
          .margin({ top: 4, left: 32 })
          .onClick((): void => { this.onOpenPreview(); })
        }
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .margin({ bottom: 4 })
    }
  }

  // —— 标签行:按工具类型分发 ——
  @Builder
  RenderLabelRow(): void {
    if (this.isSearchWeb()) {
      Row() {
        Icon({ name: 'search', iconSize: LABEL, color: INK3 })
        Text(' 搜索结果').fontSize(LABEL).fontColor(INK3)
        Blank()
        Text(`${this.searchResultCount()} 条`).fontSize(LABEL).fontColor(ACCENT)
      }
      .width('100%').margin({ top: 4, left: 32, bottom: 2 })
    } else if (this.isScrapeWeb()) {
      Row() {
        Icon({ name: 'globe', iconSize: LABEL, color: INK3 })
        Text(' 抓取').fontSize(LABEL).fontColor(INK3)
        if (this.scrapeUrl().length > 0) {
          Text(this.scrapeUrl())
            .fontSize(LABEL).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1).margin({ left: 6 })
        }
      }
      .width('100%').margin({ top: 4, left: 32, bottom: 2 })
    } else if (this.isMemoryTool()) {
      Row() {
        Icon({ name: 'edit', iconSize: LABEL, color: INK3 })
        Text(' 记忆').fontSize(LABEL).fontColor(INK3)
      }
      .width('100%').margin({ top: 4, left: 32, bottom: 2 })
    } else if (this.isFileTool()) {
      Row() {
        Icon({ name: 'doc', iconSize: LABEL, color: INK3 })
        Text(' 文件').fontSize(LABEL).fontColor(INK3)
        if (this.fileFilename().length > 0) {
          Text(this.fileFilename())
            .fontSize(LABEL).fontColor(INK2)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1).margin({ left: 6 })
        }
      }
      .width('100%').margin({ top: 4, left: 32, bottom: 2 })
    } else if (this.isTerminalTool()) {
      Row() {
        Text('⌨️ 终端').fontSize(LABEL).fontColor(INK3)
        if (this.terminalCommand().length > 0) {
          Text(this.terminalCommand())
            .fontSize(LABEL).fontColor(INK2).fontFamily('JetBrainsMono')
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1).margin({ left: 6 })
        }
        if (this.terminalExitCode() >= 0) {
          Text(`退出 ${this.terminalExitCode()}`)
            .fontSize(LABEL)
            .fontColor(this.terminalExitCode() === 0 ? INK3 : ERROR)
            .margin({ left: 6 })
        }
      }
      .width('100%').margin({ top: 4, left: 32, bottom: 2 })
    }
  }
}
