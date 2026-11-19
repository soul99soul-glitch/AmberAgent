import type { PendingUserMessageMode } from '@amber/chat-domain';
import { ChatComposerSession } from '../../platform_impl/chat/ChatComposerSession.ets';
import type { PendingChipRow } from '../../platform_impl/chat/ChatComposerSession.ets';
import { ChatConversationPresentation } from '../../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatCloudDialogueSession, type CloudDialogueTaskRecord } from '../../platform_impl/chat/ChatCloudDialogueSession.ets';
import { ChatModelContextSession } from '../../platform_impl/chat/ChatModelContextSession.ets';
import { ChatCloudDialogueStrip } from './ChatCloudDialogueStrip.ets';
import { AmberBottomModeBar, type BottomDockTab } from './AmberBottomModeBar.ets';
import { ChatInput } from '../ChatInput.ets';
import { ChatPendingQueueSheet } from '../ChatPendingQueueSheet.ets';
import { JevCompletionNoticeView } from '../JevCompletionNotice.ets';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, BG_FADE_END, BG_FADE_START, ERROR, HOME_GLASS_EDGE, HOME_GLASS_SHADOW, INK, INK2, INK3, META, OVERLAY, SURFACE, SURFACE2 } from '../../design/tokens.ets';

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
  @BuilderParam sandboxContent: () => void;
  @BuilderParam runtimeContent: () => void;
  onCompact: () => void = (): void => {};
  onRetryStorage: () => void = (): void => {};
  onSelectTab: (tab: BottomDockTab) => void = (_tab: BottomDockTab): void => {};
  onNewTopic: () => void = (): void => {};
  onOpenGatewaySettings: () => void = (): void => {};
  onOpenTasksSettings: () => void = (): void => {};
  onOpenHubsPlaceholder: () => void = (): void => {};
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
      Column({ space: 10 }) {
        Row() {
          Text('后台任务')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
          Blank()
          Text('定时与任务页 ›')
            .fontSize(12)
            .fontColor(this.accentColorStore)
            .onClick((): void => { this.onOpenTasksSettings(); })
        }
        .width('100%')

        if (this.cloudDialogue.runningTasks().length === 0) {
          Text(this.cloudDialogue.isActiveMode()
            ? '当前云端主对话暂无运行中任务；回到对话发送消息即可委派。'
            : '当前会话暂无运行中异步任务。')
            .fontSize(13)
            .fontColor(INK3)
            .width('100%')
        } else {
          ForEach(this.cloudDialogue.runningTasks(), (task: CloudDialogueTaskRecord): void => {
            Row({ space: 8 }) {
              Text(this.cloudDialogue.isActiveMode() ? '☁️ 云端' : '📱 本机')
                .fontSize(11)
                .fontColor(this.accentColorStore)
              Text(task.title.length > 0 ? task.title : '运行中任务')
                .fontSize(13)
                .fontColor(INK)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
              Text(task.stopRequested ? '停止中…' : '停止')
                .fontSize(12)
                .fontColor(ERROR)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .borderRadius(999)
                .backgroundColor(SURFACE2)
                .enabled(!task.stopRequested)
                .onClick((): void => {
                  this.cloudDialogue.stopTask(task.workItemId);
                })
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 10, bottom: 10 })
            .borderRadius(16)
            .backgroundColor(SURFACE2)
          }, (task: CloudDialogueTaskRecord): string => `${task.workItemId}-${task.status}-${task.stopRequested}`)
        }
      }
      .width('100%')
      .padding(16)
      .borderRadius(28)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: HOME_GLASS_EDGE })
      .shadow({ radius: 16, color: HOME_GLASS_SHADOW, offsetY: 4 })
      .margin({ left: 16, right: 16, bottom: 8 })
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 20 }))
        .animation({ duration: 200, curve: Curve.EaseOut }))
    } else if (this.activeTab === 'hubs') {
      Column({ space: 8 }) {
        Row() {
          Text('专题中心')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
          Blank()
          Text('返回对话')
            .fontSize(12)
            .fontColor(this.accentColorStore)
            .onClick((): void => { this.onSelectTab('chat'); })
        }
        .width('100%')
        Text('按主题聚合关联对话与专属指令，帮助把长期项目沉淀为专注工作区。')
          .fontSize(13)
          .fontColor(INK3)
          .width('100%')
        Row() {
          Text('管理现有小应用 ›')
            .fontSize(12.5)
            .fontColor(this.accentColorStore)
            .onClick((): void => { this.onOpenHubsPlaceholder(); })
        }
        .width('100%')
        .padding({ top: 4 })
      }
      .width('100%')
      .padding(16)
      .borderRadius(28)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: HOME_GLASS_EDGE })
      .shadow({ radius: 16, color: HOME_GLASS_SHADOW, offsetY: 4 })
      .margin({ left: 16, right: 16, bottom: 8 })
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 20 }))
        .animation({ duration: 200, curve: Curve.EaseOut }))
    }
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
            onTextChange: (v: string): void => {
              animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
                this.composer.setInputText(v);
              });
            },
            onSend: (): void => { this.composer.send(); },
            onStop: (): void => {
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
