// WorkspaceFilePreview — workspace 文件预览(G6-S1,对齐 Android WorkspaceFilePreview.kt:202 行)
// Android 基准: Dialog + Surface + HeaderRow(名称/分享/关闭) + 内容按扩展名分发:
//   md/txt/json/html/csv/xml/yml/yaml/log → MarkdownBlock 或 SelectionContainer+Monospace
//   png/jpg/jpeg/gif/webp → Image(bitmap)
//   else → Error("不支持预览此文件类型")
// Harmony 降级: 无 bitmap 解码/PDF 渲染 → 图片/PDF 走占位;文本走 MarkdownText 或
//   等宽 Text(Scroll);内容由父页注入(text 字段),本组件不直接读盘(对齐 VM 占位)
// ArkUI 规则:
//   - 无 as unknown / 无对象展开 / 无 any/unknown
//   - @Prop 无保留字(file 为字段名,安全)
//   - @Builder 内不声明 const/let(UI 树优先)

import { ACCENT, BODY, ERROR, INK, INK2, INK3, INK4, LABEL, LINE, META, OVERLAY, SURFACE, SURFACE2, TITLE } from '../design/tokens.ets';
import { type WorkspaceFileInfo, extOf, formatFileSize } from './WorkspaceFileVM.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';
import { messageArchiveSource } from '@amber/chat-domain';
import type { ChatMessageArchiveSource } from '@amber/chat-domain';

// 预览内容类别(对齐 Android sealed PreviewContent)
type PreviewKind = 'markdown' | 'plaintext' | 'image' | 'pdf' | 'unsupported';

function previewKind(name: string): PreviewKind {
  const ext: string = extOf(name);
  if (ext === 'md' || ext === 'markdown') return 'markdown';
  if (ext === 'txt' || ext === 'json' || ext === 'csv' || ext === 'html'
    || ext === 'htm' || ext === 'xml' || ext === 'yml' || ext === 'yaml' || ext === 'log') {
    return 'plaintext';
  }
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'webp') {
    return 'image';
  }
  if (ext === 'pdf') return 'pdf';
  return 'unsupported';
}

// 文件类型显示名
function typeLabel(name: string): string {
  const ext: string = extOf(name);
  if (ext.length === 0) return '文件';
  if (ext === 'md' || ext === 'markdown') return 'Markdown';
  if (ext === 'json') return 'JSON';
  if (ext === 'csv') return 'CSV';
  if (ext === 'html' || ext === 'htm') return 'HTML';
  if (ext === 'xml') return 'XML';
  if (ext === 'yml' || ext === 'yaml') return 'YAML';
  if (ext === 'txt') return '文本';
  if (ext === 'log') return '日志';
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'webp') {
    return '图片';
  }
  if (ext === 'pdf') return 'PDF';
  if (ext === 'zip' || ext === 'gz' || ext === 'tar' || ext === 'rar') return '压缩包';
  return ext.toUpperCase();
}

@Component
export struct WorkspaceFilePreview {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop file: WorkspaceFileInfo | null = null;
  // 文本内容(父页读盘后注入;非文本类可为空)
  @Prop textContent: string = '';
  // 图片资源 URI(父页读盘转 data-uri 或 workspace 路径后注入)
  @Prop imageUri: string = '';
  onDismiss: () => void = (): void => {};
  onOpenSource: (source: ChatMessageArchiveSource) => void = (_source: ChatMessageArchiveSource): void => {};

  private openMessageSource(): void {
    const source: ChatMessageArchiveSource | null = messageArchiveSource(this.textContent);
    if (source !== null) this.onOpenSource(source);
  }

  private previewMarkdown(): string {
    return messageArchiveSource(this.textContent) === null ? this.textContent
      : this.textContent.substring(this.textContent.indexOf('\n') + 1).trimStart();
  }

  private currentKind(): PreviewKind {
    if (this.file === null) return 'unsupported';
    return previewKind(this.file!.name);
  }

  private currentName(): string {
    if (this.file === null) return '';
    return this.file!.name;
  }

  private currentSize(): string {
    if (this.file === null) return '';
    return formatFileSize(this.file!.size);
  }

  private currentType(): string {
    if (this.file === null) return '';
    return typeLabel(this.file!.name);
  }

  @Builder
  HeaderRow(): void {
    Row() {
      Text(this.currentName())
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK)
        .layoutWeight(1)
        .maxLines(1)
        .wordBreak(WordBreak.BREAK_ALL)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('✕')
        .fontSize(16)
        .fontColor(INK3)
        .width(32).height(32)
        .textAlign(TextAlign.Center)
        .onClick((): void => { this.onDismiss(); })
    }
    .width('100%')
    .padding({ left: 12, right: 8, top: 8, bottom: 8 })
  }

  @Builder
  InfoBar(): void {
    Row() {
      Text(this.currentType())
        .fontSize(LABEL)
        .fontColor(ACCENT)
        .padding({ left: 8, right: 8, top: 3, bottom: 3 })
        .backgroundColor(SURFACE2)
        .borderRadius(999)
      Text(this.currentSize())
        .fontSize(LABEL)
        .fontColor(INK3)
        .margin({ left: 8 })
      if (messageArchiveSource(this.textContent) !== null) {
        Blank()
        Text('查看原消息').fontSize(LABEL).fontColor(ACCENT)
          .padding({ left: 8, right: 8, top: 8, bottom: 8 })
          .onClick((): void => { this.openMessageSource(); })
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12, bottom: 8 })
  }

  @Builder
  MarkdownBody(): void {
    Scroll() {
      MarkdownText({ content: this.previewMarkdown(), fontSize: 13, fontColor: INK2 })
    }
        .align(Alignment.TopStart)
    .layoutWeight(1)
    .padding({ left: 16, right: 16, bottom: 16 })
  }

  @Builder
  PlainTextBody(): void {
    Scroll() {
      Text(this.textContent)
        .fontSize(12)
        .fontColor(INK2)
        .fontFamily('JetBrainsMono')
        .width('100%')
        .copyOption(CopyOptions.LocalDevice)
    }
        .align(Alignment.TopStart)
    .layoutWeight(1)
    .padding({ left: 16, right: 16, bottom: 16 })
  }

  @Builder
  ImageBody(): void {
    Column() {
      if (this.imageUri.length > 0) {
        Image(this.imageUri)
          .width('100%')
          .layoutWeight(1)
          .objectFit(ImageFit.Contain)
      } else {
        this.Placeholder('图片预览不可用')
      }
    }
    .layoutWeight(1)
    .width('100%')
  }

  @Builder
  Placeholder(msg: string): void {
    Column() {
      Icon({ name: 'ban', iconSize: 36, color: INK4 })
      Text(msg)
        .fontSize(BODY)
        .fontColor(INK3)
        .margin({ top: 12 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.file !== null) {
      Stack() {
        Column() {
          this.HeaderRow()
          this.InfoBar()
          Divider().color(LINE).strokeWidth(0.5)

          // 按类型分发
          if (this.currentKind() === 'markdown') {
            this.MarkdownBody()
          } else if (this.currentKind() === 'plaintext') {
            this.PlainTextBody()
          } else if (this.currentKind() === 'image') {
            this.ImageBody()
          } else if (this.currentKind() === 'pdf') {
            this.Placeholder('PDF 预览不可用')
          } else {
            this.Placeholder('无法预览此文件类型')
          }
        }
        .width('100%')
        .height('100%')
        .backgroundColor(SURFACE)
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
    }
  }
}
