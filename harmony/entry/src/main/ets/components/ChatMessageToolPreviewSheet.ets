// ChatMessageToolPreviewSheet — 工具详情 sheet(G4-S2 升级)
//
// Android 基准: ChatMessageToolPreviewSheet.kt(365 行)
// 升级: search_web 专项预览(answer + 结果卡片) + scrape_web 专项 + JsonTree 渲染
//   + tool input/output 分区 + 复制
// 降级(跳过): HighlightCodeBlock(用 JsonTree 替代) / ZoomableAsyncImage / FaviconRow /
//   memory delete(需 MemoryRepository) / MarkdownBlock answer(用 MarkdownText)

import type { UIMessagePartTool, UIMessagePartImage, JsonValue } from '@amber/chat-domain';
import {
  toolInputAsJson, toolOutputJson, maskSensitiveJsonObject, maskSensitiveJsonText, toolDisplayTitle,
} from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import {
  INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, ACCENT_LIGHT, BODY, BODY2, META, LABEL, RADIUS,
} from '../design/tokens.ets';
import { JsonTree } from './ui/JsonTree.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { isSSHApprovalTool, sshApprovalTargetLabel, sshApprovalCommand, sshApprovalProtocolLabel } from './SSHApprovalSummary.ets';
import { SSHUIColors } from './SSHUIColors.ets';
import { RecipeApprovalView, recipeApprovalView } from './RecipeApprovalSummary.ets';
import { pluginApprovalView } from './PluginApprovalSummary.ets';
import { JevApprovalFacts } from './JevApprovalFacts.ets';
import { common } from '@kit.AbilityKit';

// 原始输出文本兜底:折叠态只渲染前 N 字符 + 行数上限;展开后仍有硬上限,
//   超出提示用「复制」获取全文(单个 Text 渲染数十万字符会卡死布局)
const RAW_OUTPUT_COLLAPSED_CHARS: number = 4000;
const RAW_OUTPUT_COLLAPSED_LINES: number = 40;
const RAW_OUTPUT_EXPANDED_CHARS: number = 60000;
const RAW_OUTPUT_EXPANDED_LINES: number = 100000;
// photos_pick 缩略图
const PHOTO_THUMB_SIZE: number = 72;
const PHOTO_THUMB_GAP: number = 8;

// 搜索结果项
interface SearchResultItem {
  title: string;
  url: string;
  text: string;
}

@Component
export struct ChatMessageToolPreviewSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  @Prop jevSubjectHash: string = '';
  onDismiss: () => void = (): void => {};
  @State sshColors: SSHUIColors = new SSHUIColors();
  @State rawOutputExpanded: boolean = false;
  @StorageProp('darkMode') @Watch('refreshSSHColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshSSHColors') themePackId: string = '';

  private refreshSSHColors(): void { this.sshColors = new SSHUIColors(); }

  private packageApprovalView(): RecipeApprovalView | null {
    return recipeApprovalView(this.part) ?? pluginApprovalView(this.part);
  }

  private copyToClipboard(text: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
    promptAction.showToast({ message: '已复制', duration: 1500 });
  }

  private getInputJson(): string {
    try {
      const recipe: RecipeApprovalView | null = this.packageApprovalView();
      if (recipe !== null && recipe.pendingStep !== null) {
        return maskSensitiveJsonText(recipe.pendingStep.input);
      }
      return JSON.stringify(maskSensitiveJsonObject(toolInputAsJson(this.part)), null, 2);
    } catch (_e) {
      return '{}';
    }
  }

  private getOutputJson(): JsonObject {
    try {
      return maskSensitiveJsonObject(toolOutputJson(this.part));
    } catch (_e) {
      return {} as JsonObject;
    }
  }

  // 按 part 实例记忆:build 内多处读取,避免对大输出重复 JSON 解析脱敏
  private outputTextSource: UIMessagePartTool | null = null;
  private outputTextCache: string = '';

  private getOutputText(): string {
    if (this.outputTextSource === this.part) return this.outputTextCache;
    const texts: string[] = [];
    for (const part of this.part.output) {
      if (part.type === 'text') texts.push(maskSensitiveJsonText(part.text));
    }
    this.outputTextCache = texts.join('\n');
    this.outputTextSource = this.part;
    return this.outputTextCache;
  }

  private getImageUrls(): string[] {
    const urls: string[] = [];
    for (const p of this.part.output) {
      if (p.type === 'image') urls.push((p as UIMessagePartImage).url);
    }
    return urls;
  }

  private rawOutputLimit(): number {
    return this.rawOutputExpanded ? RAW_OUTPUT_EXPANDED_CHARS : RAW_OUTPUT_COLLAPSED_CHARS;
  }

  // 截断后的原始输出(展开态仍有硬上限)
  private rawOutputShown(): string {
    const text: string = this.getOutputText();
    const limit: number = this.rawOutputLimit();
    return text.length > limit ? `${text.slice(0, limit)}\n…` : text;
  }

  // 折叠态是否还有未显示内容(字符超限,或行数超过折叠行数)
  private rawOutputCanExpand(): boolean {
    const text: string = this.getOutputText();
    if (text.length > RAW_OUTPUT_COLLAPSED_CHARS) return true;
    let lines: number = 1;
    for (let i: number = 0; i < text.length && lines <= RAW_OUTPUT_COLLAPSED_LINES; i++) {
      if (text.charCodeAt(i) === 10) lines++;
    }
    return lines > RAW_OUTPUT_COLLAPSED_LINES;
  }

  private rawOutputTruncatedWhenExpanded(): boolean {
    return this.getOutputText().length > RAW_OUTPUT_EXPANDED_CHARS;
  }

  private openSearchSource(url: string): void {
    if (url.length === 0) return;
    const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    ctx.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: url })
      .catch((_error: Error): void => {
        promptAction.showToast({ message: '无法打开来源链接', duration: 1500 });
      });
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
        Text(toolDisplayTitle(this.part))
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .accessibilityText(`工具详情：${toolDisplayTitle(this.part)}`)
        Row() {
          Icon({ name: 'close', iconSize: 18, color: INK2 })
        }.width(44).height(44).justifyContent(FlexAlign.Center)
          .onClick((): void => { this.onDismiss(); }).accessibilityText('关闭工具详情')
      }
      .width('100%').height(56)
      .padding({ left: 16, right: 4, top: 12 })

      Scroll() {
        Column() {
          // 显示标题之外保留原始工具名(排障/对照日志)
          if (toolDisplayTitle(this.part) !== this.part.toolName) {
            Text(this.part.toolName)
              .fontSize(LABEL).fontColor(INK3).fontFamily('JetBrainsMono')
              .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              .padding({ left: 16, right: 16, bottom: 4 })
              .copyOption(CopyOptions.LocalDevice)
          }
          JevApprovalFacts({ part: this.part, subjectHash: this.jevSubjectHash })
            .padding({ left: 16, right: 16 })
          if (this.part.toolName === 'photos_pick' && this.getImageUrls().length > 0) {
            this.RenderPhotoThumbs()
          }
          if (this.packageApprovalView() !== null) {
            this.RenderRecipePreview(this.packageApprovalView()!)
          } else if (isSSHApprovalTool(this.part.toolName)) {
            this.RenderSSHPreview()
          } else if (this.isSearchWeb()) {
            this.RenderSearchPreview()
          } else if (this.isScrapeWeb()) {
            this.RenderScrapePreview()
          } else {
            this.RenderGenericPreview()
          }
        }
        .alignItems(HorizontalAlign.Start)
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }

  // SSH 审批:展示持久化的批准目标和完整命令，随后保留通用参数/结果区。
  @Builder
  RenderSSHPreview(): void {
    this.RenderSSHTarget(this.part)
    this.RenderGenericPreview()
  }

  @Builder
  private RenderSSHTarget(part: UIMessagePartTool): void {
    Column({ space: 6 }) {
      Text(sshApprovalProtocolLabel(part) + ' · 批准目标').fontSize(META).fontColor(this.sshColors.muted).width('100%')
      Text(sshApprovalTargetLabel(part)).fontSize(META).fontFamily('JetBrainsMono')
        .fontColor(this.sshColors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      Text(part.toolName.endsWith('_session_start') ? '会话操作' : '执行命令')
        .fontSize(META).fontColor(this.sshColors.muted).width('100%').margin({ top: 8 })
      Text(sshApprovalCommand(part)).fontSize(META).fontFamily('JetBrainsMono').lineHeight(19)
        .fontColor(this.sshColors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
    }.width('100%').padding(16)
  }

  @Builder
  private RenderRecipePreview(view: RecipeApprovalView): void {
    Column({ space: 6 }) {
      Text(view.title).fontSize(META).fontColor(this.sshColors.ink).width('100%')
      Text(view.stepLabel).fontSize(META).fontColor(this.sshColors.secondary).width('100%')
      Text(`内容 hash: ${view.hash}`).fontSize(META).fontFamily('JetBrainsMono')
        .fontColor(this.sshColors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      if (view.kind === 'import') {
        Text(`来源: ${view.workspacePath}\n安装基线: ${view.baseHash ?? '首次安装'}`)
          .fontSize(META).fontColor(this.sshColors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
      if (view.canonicalJSON.length > 0) {
        Text('固定审核内容').fontSize(META).fontColor(this.sshColors.muted).width('100%').margin({ top: 8 })
        JsonTree({ json: maskSensitiveJsonText(view.canonicalJSON) }).width('100%')
      }
    }.width('100%').padding(16)
    if (view.pendingStep !== null && isSSHApprovalTool(view.pendingStep.toolName)) {
      this.RenderSSHTarget(view.pendingStep)
    }
    this.RenderGenericPreview()
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
          MarkdownText({ content: this.searchAnswer(), fontSize: BODY2, fontColor: INK2 })
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
          .onClick((): void => { this.openSearchSource(item.url); })
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
      if (this.getOutputText().length > 0) {
        MarkdownText({
          content: this.getOutputText(),
          fontSize: BODY2,
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
        Text(this.packageApprovalView()?.pendingStep ? '当前步骤实际参数' : '输入参数')
          .fontSize(META).fontColor(INK3).layoutWeight(1)
        Text('复制').fontSize(LABEL).fontColor(ACCENT)
          .onClick((): void => { this.copyToClipboard(this.getInputJson()); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 4 })

      Column() {
        JsonTree({ json: this.getInputJson() })
          .width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(8)
      }
      .width('100%').padding({ left: 16, right: 16 }).margin({ bottom: 8 })

      // Output
      Row() {
        Text('输出结果').fontSize(META).fontColor(INK3).layoutWeight(1)
        Text('复制').fontSize(LABEL).fontColor(ACCENT)
          .onClick((): void => { this.copyToClipboard(this.getOutputText()); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 4 })

      Column() {
        if (Object.keys(this.getOutputJson()).length > 0) {
          JsonTree({ json: JSON.stringify(this.getOutputJson(), null, 2) })
            .width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(8)
        } else {
          this.RenderRawOutputText()
        }
      }
      .width('100%').padding({ left: 16, right: 16 }).margin({ bottom: 16 })
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
      Column() {
        this.RenderRawOutputText()
      }
      .width('100%').padding({ left: 16, right: 16 }).margin({ bottom: 16 })
    }
    .width('100%')
  }

  // 原始文本输出:折叠态限字符 + 行数,可展开;展开态仍有硬上限
  @Builder
  RenderRawOutputText(): void {
    Column() {
      Text(this.rawOutputShown())
        .fontSize(LABEL).fontColor(INK2).fontFamily('JetBrainsMono')
        .width('100%')
        .maxLines(this.rawOutputExpanded ? RAW_OUTPUT_EXPANDED_LINES : RAW_OUTPUT_COLLAPSED_LINES)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .wordBreak(WordBreak.BREAK_ALL)
        .copyOption(CopyOptions.LocalDevice)
      if (this.rawOutputCanExpand()) {
        Text(this.rawOutputExpanded ? '收起' : '展开全部')
          .fontSize(LABEL).fontColor(ACCENT)
          .padding({ top: 8, bottom: 4, right: 12 })
          .onClick((): void => { this.rawOutputExpanded = !this.rawOutputExpanded; })
          .accessibilityText(this.rawOutputExpanded ? '收起工具输出' : '展开全部工具输出')
      }
      if (this.rawOutputExpanded && this.rawOutputTruncatedWhenExpanded()) {
        Text('输出过长，已截断显示；点「复制」获取完整内容')
          .fontSize(LABEL).fontColor(INK3).width('100%').padding({ top: 4 })
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE2).borderRadius(8)
  }

  // photos_pick:所选照片缩略图行
  @Builder
  RenderPhotoThumbs(): void {
    Scroll() {
      Row({ space: PHOTO_THUMB_GAP }) {
        ForEach(this.getImageUrls(), (url: string, idx: number): void => {
          Image(url)
            .width(PHOTO_THUMB_SIZE).height(PHOTO_THUMB_SIZE)
            .objectFit(ImageFit.Cover)
            .borderRadius(RADIUS)
            .border({ width: 0.5, color: LINE })
            .clip(true)
            .accessibilityText(`所选照片 ${idx + 1}`)
        }, (url: string, idx: number): string => `sheet-photo-${idx}-${url.length}`)
      }
    }
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .align(Alignment.Start)
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 4 })
  }
}
