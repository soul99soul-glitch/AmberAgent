// ChatExportSheet — 导出格式选择 sheet(G4-S4)
// Android 基准: Export.kt(823 行)
// 必要: Markdown 导出(一键) + 图片导出选项(展开推理开关)
// 所选消息的 Markdown 文件保存/分享/复制与最近 40 条富文本图片相册导出。
import type { UIMessage, UIMessagePart, Conversation, ImageExportPage, MessageNode } from '@amber/chat-domain';
import { exportConversationMarkdown, exportFileNameFor, messageImageExportText, imageExportPages } from '@amber/chat-domain';
import { ACCENT, BODY, INK, INK2, INK3, INK4, LABEL, LINE, META, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2, BG, USER_BG, USER_INK } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { display, promptAction } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { packPixelMapToCache, saveImageFilesToAlbum, removeCachedImageFiles } from '../platform_impl/ImageSaveSupport.ets';
import { saveMarkdownFile, shareMarkdownFile } from '../platform_impl/ChatExportFiles.ets';
import { common } from '@kit.AbilityKit';
import { MarkdownText } from './MarkdownText.ets';

// D7-4:长图渲染条数上限(超出提示仅最近 N 条)
const IMAGE_EXPORT_MAX_MESSAGES: number = 40;

@Component
export struct ChatExportSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop conversation: Conversation | null = null;
  @Prop messages: UIMessage[] = [];
  @Prop initialSelectedMessageIds: string[] = [];
  onDismiss: () => void = (): void => {};
  @State expandReasoning: boolean = false;
  @State renderImageMode: boolean = false;
  @State exportMessages: UIMessage[] = [];
  @State exportTitle: string = '';
  @State exportSubtitle: string = '';
  @State exportWidth: number = 360;
  @State exportPageHeight: number = 600;
  @State exportProgress: string = '';
  @State selectedMessageIds: string[] = [];
  @State choosingMessages: boolean = false;
  @State exportingFile: boolean = false;
  private imageIncludeReasoning: boolean = false;
  private screenHeightPx: number = 0;
  private captureStarted: boolean = false;
  private pendingImageCount: number = 0;
  private exportContentHeight: number = 0;
  private scroller: Scroller = new Scroller();
  private renderTimer: number = -1;
  private finishFrame: (() => void) | null = null;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.selectedMessageIds = this.initialSelectedMessageIds.length > 0
      ? this.messages.filter((message: UIMessage): boolean => this.initialSelectedMessageIds.includes(message.id))
        .map((message: UIMessage): string => message.id)
      : this.messages.map((message: UIMessage): string => message.id);
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.renderTimer !== -1) clearTimeout(this.renderTimer);
    this.renderTimer = -1;
    if (this.finishFrame !== null) this.finishFrame();
    this.finishFrame = null;
  }

  // 导出为 Markdown 文本
  private exportToMarkdown(): string {
    const selected: string[] = this.selectedMessageIds;
    const nodeIds: string[] = this.conversation === null ? [] : this.conversation.messageNodes
      .filter((node: MessageNode): boolean => node.messages.some((message: UIMessage): boolean => selected.includes(message.id)))
      .map((node: MessageNode): string => node.id);
    return this.conversation === null ? '' : exportConversationMarkdown(
      this.conversation, { selectedNodeIds: nodeIds, includeReasoning: this.expandReasoning });
  }

  private selectedMessages(): UIMessage[] {
    return this.messages.filter((message: UIMessage): boolean => this.selectedMessageIds.includes(message.id));
  }

  private toggleMessage(id: string): void {
    if (this.renderImageMode || this.exportingFile) return;
    this.selectedMessageIds = this.selectedMessageIds.includes(id)
      ? this.selectedMessageIds.filter((selected: string): boolean => selected !== id)
      : [...this.selectedMessageIds, id];
  }

  private hasExportSelection(): boolean {
    if (this.selectedMessageIds.length > 0) return true;
    promptAction.showToast({ message: '请选择要导出的消息', duration: 1500 });
    return false;
  }

  private async exportMarkdownFile(share: boolean): Promise<void> {
    if (this.exportingFile || this.renderImageMode || this.conversation === null || !this.hasExportSelection()) return;
    this.exportingFile = true;
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const fileName: string = exportFileNameFor(this.conversation, 'md');
      const content: string = this.exportToMarkdown();
      if (share) {
        await shareMarkdownFile(context, fileName, content);
      } else if (await saveMarkdownFile(context, fileName, content)) {
        promptAction.showToast({ message: '已导出 Markdown 文件', duration: 1500 });
      }
    } catch (error) {
      if (this.alive) promptAction.showToast({ message: `导出失败:${String(error)}`, duration: 2000 });
    } finally {
      if (this.alive) this.exportingFile = false;
    }
  }

  private copyMarkdown(): void {
    if (this.renderImageMode || this.exportingFile || !this.hasExportSelection()) return;
    const md: string = this.exportToMarkdown();
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md));
    promptAction.showToast({ message: '已复制 Markdown 到剪贴板', duration: 2000 });
    this.onDismiss();
  }

  // 等待状态对应的一帧；截图再强制等待绘制完成，不保留常驻定时器。
  private waitRenderFrame(): Promise<void> {
    return new Promise<void>((resolve: () => void): void => {
      this.finishFrame = resolve;
      this.renderTimer = setTimeout((): void => {
        this.renderTimer = -1;
        this.finishFrame = null;
        resolve();
      }, 32);
    });
  }

  // D7-4:实测内容高度后按屏幕尺寸截图；普通内容仍是一张。
  private startImageExport(): void {
    if (this.renderImageMode || this.exportingFile || !this.hasExportSelection()) return;
    try {
      const screen: display.Display = display.getDefaultDisplaySync();
      this.screenHeightPx = screen.height;
      this.exportWidth = px2vp(Math.min(screen.width, Math.floor(vp2px(720))));
      this.exportPageHeight = px2vp(screen.height);
      const selected: UIMessage[] = this.selectedMessages();
      this.exportMessages = selected.slice(-IMAGE_EXPORT_MAX_MESSAGES);
      this.pendingImageCount = 0;
      this.exportContentHeight = 0;
      for (const message of this.exportMessages) {
        for (const part of message.parts) {
          if (part.type === 'image') this.pendingImageCount++;
        }
      }
      this.imageIncludeReasoning = this.expandReasoning;
      this.exportTitle = this.conversation !== null && this.conversation.title.length > 0
        ? this.conversation.title : '未命名对话';
      this.exportSubtitle = selected.length > IMAGE_EXPORT_MAX_MESSAGES
        ? `仅最近 ${IMAGE_EXPORT_MAX_MESSAGES} 条 · ${new Date().toLocaleDateString()}`
        : new Date().toLocaleDateString();
      this.captureStarted = false;
      this.exportProgress = '正在测量内容…';
      this.renderImageMode = true;
    } catch (error) {
      promptAction.showToast({ message: '导出失败:无法读取屏幕尺寸', duration: 1500 });
    }
  }

  private onExportContentMeasured(height: number): void {
    this.exportContentHeight = height;
    if (!this.renderImageMode || this.captureStarted || height <= 0 || this.pendingImageCount > 0) return;
    this.captureStarted = true;
    this.captureImagePages(height);
  }

  private async captureImagePages(contentHeight: number): Promise<void> {
    const files: string[] = [];
    try {
      const pages: ImageExportPage[] = imageExportPages(vp2px(contentHeight), this.screenHeightPx);
      for (let index: number = 0; index < pages.length; index++) {
        if (!this.alive || !this.show) return;
        const page: ImageExportPage = pages[index];
        this.exportProgress = pages.length === 1 ? '正在生成图片…'
          : `正在生成第 ${index + 1}/${pages.length} 张图片…`;
        this.exportPageHeight = px2vp(page.height);
        await this.waitRenderFrame();
        if (!this.alive || !this.show) return;
        this.scroller.scrollTo({ xOffset: 0, yOffset: px2vp(page.top), animation: false });
        await this.waitRenderFrame();
        if (!this.alive || !this.show) return;
        if (Math.abs(vp2px(this.scroller.currentOffset().yOffset) - page.top) > 1) {
          throw new Error('内容滚动尚未就绪');
        }
        const pm: image.PixelMap = await this.getUIContext().getComponentSnapshot()
          .get('chat_export_image', { scale: 1, waitUntilRenderFinished: true });
        try {
          files.push(await packPixelMapToCache(pm));
        } finally {
          await pm.release();
        }
      }
      if (!this.alive || !this.show) return;
      this.exportProgress = files.length === 1 ? '请确认保存图片'
        : `内容较长，已分为 ${files.length} 张，请确认保存`;
      const saved: boolean = await saveImageFilesToAlbum(files);
      if (saved && this.alive && this.show) this.onDismiss();
    } catch (error) {
      if (this.alive && this.show) {
        const message: string = error instanceof Error ? error.message : String(error);
        promptAction.showToast({ message: `导出失败:${message}`, duration: 2000 });
      }
    } finally {
      removeCachedImageFiles(files);
      if (this.alive) this.renderImageMode = false;
    }
  }

  private messagePlainText(msg: UIMessage): string {
    return messageImageExportText(msg, this.imageIncludeReasoning);
  }

  private onExportImageLoaded(): void {
    if (!this.alive || !this.renderImageMode || this.pendingImageCount <= 0) return;
    this.pendingImageCount--;
    if (this.pendingImageCount === 0) this.onExportContentMeasured(this.exportContentHeight);
  }

  private onExportImageError(): void {
    if (!this.alive || !this.renderImageMode) return;
    this.renderImageMode = false;
    promptAction.showToast({ message: '图片加载失败，未导出；请加载原消息中的图片后重试', duration: 2000 });
  }

  @Builder
  ExportImageRender(): void {
    Scroll(this.scroller) {
      Column() {
        Text(this.exportTitle)
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
          .width('100%').margin({ bottom: 6 })
        Text(this.exportSubtitle)
          .fontSize(11).fontColor(INK3)
          .width('100%').margin({ bottom: 14 })
        ForEach(this.exportMessages, (msg: UIMessage): void => {
          Column() {
            Text(msg.role === 'user' ? '用户' : '助手')
              .fontSize(11).fontColor(INK3).margin({ bottom: 4 })
            Column() {
              if (this.messagePlainText(msg) !== '[非文本内容]' || !msg.parts.some((part: UIMessagePart): boolean => part.type === 'image')) {
                MarkdownText({ content: this.messagePlainText(msg), fontSize: 14,
                  fontColor: msg.role === 'user' ? USER_INK : INK })
              }
              ForEach(msg.parts, (part: UIMessagePart, index: number): void => {
                if (part.type === 'image') {
                  Image(part.url).width('100%').height(240).objectFit(ImageFit.Contain)
                    .margin({ top: 6 }).borderRadius(8)
                    .onComplete((event): void => {
                      if (event !== undefined && event.loadingStatus === 1) this.onExportImageLoaded();
                    })
                    .onError((): void => { this.onExportImageError(); })
                }
              }, (_part: UIMessagePart, index: number): string => `${msg.id}-image-${index}`)
            }
            .padding(12)
            .backgroundColor(msg.role === 'user' ? USER_BG : SURFACE)
            .borderRadius(12)
            .border({ width: msg.role === 'user' ? 0 : 0.5, color: LINE })
            .constraintSize({ maxWidth: '85%' })
          }
          .width('100%')
          .alignItems(msg.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
          .margin({ bottom: 10 })
        }, (msg: UIMessage): string => msg.id)
      }
      .width('100%')
      .padding(20)
      .onAreaChange((_old: Area, area: Area): void => {
        this.onExportContentMeasured(Number(area.height));
      })
    }
    .width(this.exportWidth)
    .height(this.exportPageHeight)
    .scrollable(ScrollDirection.Vertical)
    .scrollBar(BarState.Off)
    .enableScrollInteraction(false)
    .backgroundColor(BG)
    .id('chat_export_image')
    .position({ x: -this.exportWidth - 100, y: 0 })
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack() {
        Scroll() {
        Column() {
          // 标题
          Text('导出格式')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 16 })

          Row() {
            Text(`已选 ${this.selectedMessageIds.length}/${this.messages.length} 条消息`)
              .fontSize(META).fontColor(INK2).layoutWeight(1)
            Text(this.choosingMessages ? '收起' : '选择范围')
              .fontSize(LABEL).fontColor(ACCENT)
              .onClick((): void => { if (!this.renderImageMode && !this.exportingFile) this.choosingMessages = !this.choosingMessages; })
          }.width('100%').margin({ bottom: 12 })
          if (this.choosingMessages) {
            Row() {
              Text('全选').fontSize(LABEL).fontColor(ACCENT)
                .onClick((): void => { this.selectedMessageIds = this.messages.map((message: UIMessage): string => message.id); })
              Text('清空').fontSize(LABEL).fontColor(INK3).margin({ left: 20 })
                .onClick((): void => { this.selectedMessageIds = []; })
            }.width('100%').margin({ bottom: 8 }).enabled(!this.renderImageMode && !this.exportingFile)
            Scroll() {
              Column() {
                ForEach(this.messages, (message: UIMessage, index: number): void => {
                  Row({ space: 8 }) {
                    Icon({ name: this.selectedMessageIds.includes(message.id) ? 'check' : 'circle', iconSize: 16,
                      color: this.selectedMessageIds.includes(message.id) ? ACCENT : INK3 })
                    Text(`${index + 1}. ${message.role === 'user' ? '用户' : '助手'} · ${messageImageExportText(message, false)}`)
                      .fontSize(LABEL).fontColor(INK2).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
                  }.width('100%').padding({ top: 8, bottom: 8 })
                  .onClick((): void => { this.toggleMessage(message.id); })
                }, (message: UIMessage): string => message.id)
              }.width('100%')
            }.width('100%').constraintSize({ maxHeight: 180 }).margin({ bottom: 12 })
              .enabled(!this.renderImageMode && !this.exportingFile)
          }

          // Markdown 导出卡片
          Column() {
            Row() {
            Icon({ name: 'doc', iconSize: 20, color: INK2 }).margin({ right: 12 })
            Column() {
              Text(this.exportingFile ? '正在导出…' : 'Markdown').fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
              Text('导出所选消息为 .md 文件').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
            }.width('100%')
            Divider().color(LINE).strokeWidth(0.5).margin({ top: 12, bottom: 12 })
            Row() {
              Text('保存文件').fontSize(LABEL).fontColor(ACCENT)
                .onClick((): void => { this.exportMarkdownFile(false); })
              Text('分享文件').fontSize(LABEL).fontColor(ACCENT).margin({ left: 20 })
                .onClick((): void => { this.exportMarkdownFile(true); })
              Text('复制').fontSize(LABEL).fontColor(INK3).margin({ left: 20 })
                .onClick((): void => { this.copyMarkdown(); })
            }.width('100%')
          }
          .width('100%')
          .padding(16)
          .backgroundColor(SURFACE2)
          .borderRadius(12)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 8 })

          // 图片导出卡片(D7-4:长图 → 相册)
          Column() {
            Row() {
              Icon({ name: 'image', iconSize: 20, color: INK2 }).margin({ right: 12 })
              Column() {
                Text(this.renderImageMode ? this.exportProgress : '图片')
                  .fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
                Text(this.selectedMessageIds.length > IMAGE_EXPORT_MAX_MESSAGES
                  ? `导出最近 ${IMAGE_EXPORT_MAX_MESSAGES} 条；内容较长时分为多张图片`
                  : '导出到相册；内容较长时分为多张图片')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
            }
            .width('100%')
            .onClick((): void => { this.startImageExport(); })

            Divider().color(LINE).strokeWidth(0.5).margin({ top: 12, bottom: 12 })

            // 展开推理开关
            Row() {
              Text('展开推理过程').fontSize(BODY).fontColor(INK2).layoutWeight(1)
              Text(this.expandReasoning ? '开启' : '关闭')
                .fontSize(LABEL)
                .fontColor(this.expandReasoning ? ACCENT : INK3)
                .onClick((): void => {
                  if (!this.renderImageMode && !this.exportingFile) this.expandReasoning = !this.expandReasoning;
                })
            }
            .width('100%')
          }
          .width('100%')
          .padding(16)
          .backgroundColor(SURFACE2)
          .borderRadius(12)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 16 })

          // 关闭按钮
          Text('关闭')
            .fontSize(BODY)
            .fontColor(INK3)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%')
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .padding({ left: 20, right: 20, top: 24, bottom: 12 })
        }
        .width('85%')
        .constraintSize({ maxHeight: '85%' })
        .scrollBar(BarState.Off)
        .borderRadius(16)
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        if (this.renderImageMode) {
          this.ExportImageRender()
        }
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }
}
