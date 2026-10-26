import type { PendingUserMessageMode } from '@amber/chat-domain';
import { ChatComposerSession } from '../../platform_impl/chat/ChatComposerSession.ets';
import type { PendingChipRow } from '../../platform_impl/chat/ChatComposerSession.ets';
import { ChatConversationPresentation } from '../../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatInput } from '../ChatInput.ets';
import { ChatPendingQueueSheet } from '../ChatPendingQueueSheet.ets';
import { JevCompletionNoticeView } from '../JevCompletionNotice.ets';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, BG, INK2, INK3, ERROR, SURFACE2, OVERLAY, LINE, META } from '../../design/tokens.ets';

@Component
export struct ChatComposerPane {
  @ObjectLink composer: ChatComposerSession;
  @ObjectLink presentation: ChatConversationPresentation;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop navBarHeight: number = 0;
  @Prop storageDegraded: boolean = false;
  @Prop retryStatus: string = '';
  @Prop manualCompacting: boolean = false;
  @BuilderParam sandboxContent: () => void;
  @BuilderParam runtimeContent: () => void;
  onCompact: () => void = (): void => {};
  onRetryStorage: () => void = (): void => {};
  @Builder
  PendingQueueSheet(): void {
    ChatPendingQueueSheet({
      messages: this.presentation.pendingQueue,
      onDismiss: (): void => { this.composer.queueSheetOpen = false; },
      onRestore: (id: string): void => { this.composer.restorePendingMessage(id); },
      onCancel: (id: string): void => {
        this.composer.cancelPendingById(id).catch((error: Error): void => { this.presentation.errorMsg = error.message; });
      },
      onClear: (): void => {
        this.composer.clearPending();
      },
    })
  }
  build(): void {
    Column() {
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.composer.queueSheetOpen, this.PendingQueueSheet(), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.composer.queueSheetOpen = false; },
      })
      if (this.presentation.completionNotice !== null && !this.presentation.sending) {
        JevCompletionNoticeView({
          onVerify: (): void => { this.composer.requestCompletionVerification(); },
          onDismiss: (): void => { this.composer.dismissCompletionNotice(); },
        }).margin({ left: 20, right: 20, top: 4, bottom: 4 })
      }

      // P4:错误提示可关闭条(Android ErrorCard 语义 — 紧凑行 + 右侧 ×,
      //   关闭后 errorMsg='',不改变既有 error 语义/设置点)
      if (this.presentation.errorMsg.length > 0) {
        Row() {
          Text(this.presentation.errorMsg)
            .fontSize(12).fontColor(ERROR)
            .layoutWeight(1)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Icon({ name: 'close', iconSize: 14, color: INK3 })
            .padding({ left: 8 })
            .onClick((): void => { this.presentation.errorMsg = ''; })
            .accessibilityText('关闭错误提示')
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 4, bottom: 4 })
      }

      // D-050:重试状态(strings.xml:386 文案,重试等待期间显示)
      if (this.retryStatus.length > 0) {
        Text(this.retryStatus)
          .fontSize(12).fontColor(this.accentColorStore)
          .padding({ left: 20, right: 20, top: 4, bottom: 4 })
      }

      // D-048:排队消息 chips(Android ChatListIndicators pending 区,可逐条取消)
      if (this.composer.pendingRows.length > 0) {
        Scroll() {
          Row() {
            Text(`队列 ${this.presentation.pendingQueue.length}`)
              .fontSize(11).fontColor(INK3)
              .constraintSize({ minHeight: 44 })
              .margin({ right: 8 })
              .onClick((): void => { this.composer.queueSheetOpen = true; })
            ForEach(this.composer.pendingRows, (row: PendingChipRow): void => {
              Row() {
                Text(row.preview)
                  .fontSize(12).fontColor(INK2)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .constraintSize({ maxWidth: 180 })
                Text('✕')
                  .fontSize(11).fontColor(INK3)
                  .padding({ left: 6 })
                  .onClick((): void => {
                    this.composer.cancelPendingById(row.id).catch((e: Error): void => {
                      this.presentation.errorMsg = e.message;
                    });
                  })
              }
              .padding({ left: 10, right: 8, top: 5, bottom: 5 })
              .backgroundColor(SURFACE2)
              .borderRadius(12)
              .margin({ right: 8 })
            }, (row: PendingChipRow): string => row.id)
          }
          .padding({ left: 20, right: 20 })
        }
        .align(Alignment.TopStart)
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-038:待发图片预览(可移除);D-111:点按 = inspectImage 状态检查,
      //   blocked → errorMsg(ChatInputAttachments.kt:onStatusClick,toast 等价面)
      if (this.composer.pendingImages.length > 0) {
        Scroll() {
          Row() {
            ForEach(this.composer.pendingImages, (url: string, i: number): void => {
              Stack() {
                Image(url)
                  .width(64).height(64)
                  .objectFit(ImageFit.Cover)
                  .borderRadius(8)
                  .onClick((): void => { this.composer.inspectPendingImage(url); })
                  .accessibilityText(`查看第 ${i + 1} 张待发图片`)
                Text('✕')
                  .fontSize(10).fontColor('#FFFFFF')
                  .width(28).height(28)
                  .textAlign(TextAlign.Center)
                  .margin({ top: 4, right: 4 })
                  .backgroundColor(OVERLAY)
                  .borderRadius(14)
                  .onClick((): void => { this.composer.removePendingImage(i); })
                  .accessibilityText(`删除第 ${i + 1} 张待发图片`)
              }
              .alignContent(Alignment.TopEnd)
              .margin({ right: 8 })
            }, (url: string, i: number): string => `img-${i}-${url}`)
          }
          .padding({ left: 20, right: 20 })
        }
        .align(Alignment.TopStart)
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-122:沙盒活动 peek(ChatInputSandbox.kt SandboxStepPeek)—
      //   时间线非空才显示(:516-522 模式门已在 sandboxTimeline 内)
      this.sandboxContent()

      // D-110:上下文用量现位于服务商/模型面板顶部。

      // P4:输入区底部安全区(沉浸式全屏下不被导航条遮挡);
      //   Android ChatInput 沉浸式基准 = 安全区底部 + 10vp 呼吸
      Column() {
        // 第二梯队 E:ChatInput 组件替换内联输入行
        if (this.presentation.restoreReloadFailed) {
          Text('恢复后的会话读取失败，点击重试')
            .fontSize(13).fontColor(ERROR).width('100%').padding(12)
            .onClick((): void => {
              this.presentation.refreshRestoredConversation().catch((e: Error): void => { this.presentation.errorMsg = e.message; });
            })
        }
        if (this.storageDegraded) {
          Text('本地数据库暂不可用，无法保存新消息。点击重试。')
            .fontSize(13).fontColor(ERROR).width('100%').padding(12)
            .onClick((): void => {
              this.onRetryStorage();
            })
        }
        // 子代理跨会话活动条(C5-4;有运行中子代理时显示)
        this.runtimeContent()
        if (this.manualCompacting) {
          Text('正在压缩上下文…').fontSize(META).fontColor(INK3)
            .width('100%').padding({ left: 20, right: 20, top: 6, bottom: 4 })
        }
        ChatInput({
          disabled: ((this.storageDegraded || !this.presentation.conversationLoaded) && !this.presentation.sending) || this.manualCompacting,
          text: this.composer.inputText,
          sending: this.presentation.sending,
          queueCount: this.presentation.pendingQueue.length,
          onOpenQueue: (): void => { this.composer.queueSheetOpen = true; },
          onQueueSend: (mode: string): void => { this.composer.send(mode as PendingUserMessageMode); },
          editing: this.composer.editingNodeId.length > 0,
          hasImages: this.composer.pendingImages.length > 0 || this.composer.pendingDocs.length > 0,
          subAgentEnabled: this.composer.subAgentEnabled,
          subAgentMode: this.composer.subAgentMode,
          quickMessages: this.composer.quickMessages,
          mentionRoles: this.composer.mentionRoles,
          sendOnEnter: this.presentation.displaySettings.sendOnEnter,
          pasteLongTextAsFile: this.presentation.displaySettings.pasteLongTextAsFile,
          pasteLongTextThreshold: this.presentation.displaySettings.pasteLongTextThreshold,
          // 1:1 原型 send 键 color/bg .18s ease:输入变化经 animateTo 驱动
          onTextChange: (v: string): void => {
            animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
              this.composer.setInputText(v);
            });
          },
          onSend: (): void => { this.composer.send(); },
          onStop: (): void => {
            // Phase 2:统一停止入口(abort + 收口标记 + steer→followup;
            //   不抢先保存 — 生成完成 .then 单次收口)
            this.presentation.requestStop();
          },
          onSendWithoutAnswer: (): void => { this.composer.sendWithoutAnswer(); },
          onPickImage: (): void => { this.composer.pickImages(); },
          onTakePhoto: (): void => { this.composer.takePhoto(); },
          onPickFile: (): void => { this.composer.pickFiles(); },
          onPasteAsFile: (t: string): void => { this.composer.attachPastedText(t); },
          onCancelEdit: (): void => { this.composer.cancelEdit(); },
          onClearInput: (): void => { this.composer.setInputText(''); },
          onCompactContext: (): void => { this.onCompact(); },
          onInsertText: (t: string): void => { this.composer.setInputText(t); },
        })
      }
      .width('100%')
      .padding({ top: 8, bottom: this.navBarHeight + 10 })
      .backgroundColor(BG)
      .border({ width: { top: 0.5 }, color: LINE })
    }.width('100%')
  }
}
