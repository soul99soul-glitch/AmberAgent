import type { PendingUserMessageMode } from '@amber/chat-domain';
import { ChatComposerSession } from '../../platform_impl/chat/ChatComposerSession.ets';
import type { PendingChipRow } from '../../platform_impl/chat/ChatComposerSession.ets';
import { ChatConversationPresentation } from '../../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatCloudDialogueSession } from '../../platform_impl/chat/ChatCloudDialogueSession.ets';
import { ChatModelContextSession } from '../../platform_impl/chat/ChatModelContextSession.ets';
import { ChatCloudDialogueStrip } from './ChatCloudDialogueStrip.ets';
import { AmberBottomModeBar, type BottomDockTab } from './AmberBottomModeBar.ets';
import { AmberTaskCenterSheet } from './AmberTaskCenterSheet.ets';
import { AmberHubsSheet } from './AmberHubsSheet.ets';
import { ChatInput } from '../ChatInput.ets';
import { ChatPendingQueueSheet } from '../ChatPendingQueueSheet.ets';
import { JevCompletionNoticeView } from '../JevCompletionNotice.ets';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, BG_FADE_END, BG_FADE_START, ERROR, HOME_GLASS_EDGE, HOME_GLASS_SHADOW, INK, INK2, INK3, LINE, META, OVERLAY, SURFACE, SURFACE2 } from '../../design/tokens.ets';

@Component
export struct ChatComposerPane {
  @ObjectLink composer: ChatComposerSession;
  @ObjectLink presentation: ChatConversationPresentation;
  @ObjectLink cloudDialogue: ChatCloudDialogueSession;
  @ObjectLink modelContext: ChatModelContextSession;
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop navBarHeight: number = 0;
  @Prop storageDegraded: boolean = false;
  @Prop retryStatus: string = '';
  @Prop manualCompacting: boolean = false;
  @Prop activeTab: BottomDockTab = 'chat';
  @Prop attentionTaskCount: number = 0;
  @Prop runningTaskCount: number = 0;
  @Prop pendingApprovalCount: number = 0;
  @Prop consecutiveErrorCount: number = 0;
  @Prop hasUnrecoveredError: boolean = false;
  @BuilderParam sandboxContent: () => void;
  @BuilderParam runtimeContent: () => void;
  onCompact: () => void = (): void => {};
  onRetryStorage: () => void = (): void => {};
  onSelectTab: (tab: BottomDockTab) => void = (_tab: BottomDockTab): void => {};
  onNewTopic: () => void = (): void => {};
  onOpenGatewaySettings: () => void = (): void => {};
  onOpenTasksSettings: () => void = (): void => {};
  onOpenHubsPlaceholder: () => void = (): void => {};
  onOpenConversation: (conversationId: string) => void = (_id: string): void => {};
  onFocusApproval: () => void = (): void => {};
  onStartWithHubInstruction: (hubId: string, instruction: string, title: string) => void =
    (_id: string, _inst: string, _title: string): void => {};
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
  @Builder
  InlineDockPanel(): void {
    if (this.activeTab === 'tasks') {
      Column() {
        AmberTaskCenterSheet({
          cloudDialogue: this.cloudDialogue,
          currentConversationId: this.presentation.conversation?.id ?? '',
          pendingApprovalCount: this.pendingApprovalCount,
          consecutiveErrorCount: this.consecutiveErrorCount,
          hasUnrecoveredError: this.hasUnrecoveredError,
          onBackToChat: (): void => { this.onSelectTab('chat'); },
          onOpenTasksSettings: (): void => { this.onOpenTasksSettings(); },
          onOpenGatewaySettings: (): void => { this.onOpenGatewaySettings(); },
          onJumpToConversation: (id: string): void => {
            this.onSelectTab('chat');
            this.onOpenConversation(id);
          },
          onFocusApproval: (): void => { this.onFocusApproval(); },
          onStatusMessage: (msg: string): void => { this.presentation.errorMsg = msg; },
        })
      }
      .width('100%')
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 20 }))
        .animation({ duration: 200, curve: Curve.EaseOut }))
    } else if (this.activeTab === 'hubs') {
      Column() {
        AmberHubsSheet({
          currentConversationId: this.presentation.conversation?.id ?? '',
          onBackToChat: (): void => { this.onSelectTab('chat'); },
          onOpenConversation: (id: string): void => {
            this.onSelectTab('chat');
            this.onOpenConversation(id);
          },
          onStartWithInstruction: (hubId: string, instruction: string, title: string): void => {
            this.onStartWithHubInstruction(hubId, instruction, title);
          },
          onOpenMiniApps: (): void => { this.onOpenHubsPlaceholder(); },
        })
      }
      .width('100%')
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 20 }))
        .animation({ duration: 200, curve: Curve.EaseOut }))
    }
  }

  private effectiveSending(): boolean {
    return this.presentation.sending || (!this.cloudDialogue.isActiveMode() && this.cloudDialogue.runningTasks().length > 0);
  }

  private stopActiveGeneration(): void {
    if (this.presentation.sending) {
      this.presentation.requestStop();
      return;
    }
    if (!this.cloudDialogue.isActiveMode()) {
      const running = this.cloudDialogue.runningTasks();
      if (running.length > 0) {
        this.cloudDialogue.stopTask(running[running.length - 1].workItemId);
      }
    }
  }

  @Builder
  private SuggestionStrip(): void {
    Scroll() {
      Row({ space: 8 }) {
        ForEach(this.presentation.suggestions.slice(0, 3), (item: string): void => {
          Text(item)
            .fontSize(12.5)
            .fontColor(INK2)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ maxWidth: 240 })
            .padding({ left: 12, right: 12, top: 7, bottom: 7 })
            .backgroundColor(SURFACE2)
            .borderRadius(999)
            .border({ width: 0.75, color: LINE })
            .onClick((): void => { this.composer.fillSuggestion(item); })
            .accessibilityText(`填入建议：${item}`)
        }, (item: string, idx: number): string => `comp-sg-${idx}-${item}`)
      }
      .padding({ left: 20, right: 20 })
    }
    .align(Alignment.TopStart)
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
    .margin({ bottom: 4 })
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
      // 悬浮输入与原位双态底栏（去通栏实色底与顶硬线，底部柔和渐隐衬底）
      Column() {
        this.InlineDockPanel()

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
        this.runtimeContent()
        ChatCloudDialogueStrip({ session: this.cloudDialogue })
        if (this.manualCompacting) {
          Text('正在压缩上下文…').fontSize(META).fontColor(INK3)
            .width('100%').padding({ left: 20, right: 20, top: 6, bottom: 4 })
        }

        // 点「任务」或「中心」时临时收起输入框，保持组件挂载以保留草稿不丢失
        Column() {
          if (this.presentation.suggestions.length > 0
            && this.composer.inputText.trim().length === 0
            && !this.effectiveSending()
            && this.composer.editingNodeId.length === 0) {
            this.SuggestionStrip()
          }
          ChatInput({
            disabled: ((this.storageDegraded || !this.presentation.conversationLoaded) && !this.effectiveSending()) || this.manualCompacting,
            text: this.composer.inputText,
            sending: this.effectiveSending(),
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
            onTextChange: (v: string): void => {
              this.composer.setInputText(v);
            },
            onSend: (): void => { this.composer.send(); },
            onStop: (): void => {
              this.stopActiveGeneration();
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
            onFocusChange: (focused: boolean): void => { this.composer.setInputFocused(focused); },
          })
        }
        .width('100%')
        .visibility(this.activeTab === 'chat' ? Visibility.Visible : Visibility.None)

        AmberBottomModeBar({
          cloudDialogue: this.cloudDialogue,
          modelContext: this.modelContext,
          inputFocused: this.composer.inputFocused,
          activeTab: this.activeTab,
          attentionTaskCount: this.attentionTaskCount,
          runningTaskCount: this.runningTaskCount,
          onSelectTab: (tab: BottomDockTab): void => { this.onSelectTab(tab); },
          onNewTopic: (): void => { this.onNewTopic(); },
          onOpenGatewaySettings: (): void => { this.onOpenGatewaySettings(); },
        })
      }
      .width('100%')
      .padding({ top: 10, bottom: this.navBarHeight + 10 })
      .linearGradient({
        direction: GradientDirection.Bottom,
        colors: [[BG_FADE_START, 0], [BG_FADE_END, 0.36], [BG_FADE_END, 1]],
      })
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }
}
