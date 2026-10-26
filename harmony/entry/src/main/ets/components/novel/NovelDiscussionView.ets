// NovelWorkspacePage — 小说工作区:三页签(聊天写作 / 章节正文 / 活资料)
// 单文件承载三个 pane(@Builder)与对话框(Stack 叠层),状态集中于本页 @State。
import { ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, LINE, MOTION_PRESS, SUCCESS_FG, SURFACE2 } from '../../design/tokens.ets';
import { FONT_MONO, FONT_SANS_MEDIUM } from '../../design/fonts.ets';
import { Icon } from '../../components/ui/Icon.ets';
import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
import { ChatInput } from '../../components/ChatInput.ets';
import { AgentMessageRow, NO_AGENT_MESSAGE_ROW_CAPABILITIES } from '../../components/AgentMessageRow.ets';
import type { AgentMessageRowHostCapabilities } from '../../components/AgentMessageRow.ets';
import { InteractiveMessageTimeline } from '../../components/InteractiveMessageTimeline.ets';
import { NovelDiscussionArchiveCard } from '../../components/NovelDiscussionArchiveCard.ets';
import { NovelRunRecoveryCard } from '../../components/NovelRunRecoveryCard.ets';
import { discussionArchiveAtMessage, discussionMessageVisible } from '../../components/NovelArchivePresentation.ets';
import { NovelSettingProposalCard } from '../../components/NovelSettingProposalCard.ets';
import { orphanSettingProposals } from '../../platform_impl/NovelSettingProposalProjection.ets';
import { NovelGhostwriteReportCard } from '../../components/NovelGhostwriteReportCard.ets';
import { NovelGhostwriteCard } from '../../components/NovelGhostwriteCard.ets';
import { MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import { NovelProject, NovelMessage, NovelSettingProposal, canCloneCollectedMessage, NovelWorkspaceStatus, UIMessage, makeUIMessage, toText, UIMessagePart, novelMessageText, DEFAULT_GHOSTWRITE_CHAPTER_COUNT } from '@amber/deepread-domain';
import type { ToolApprovalPartLocator } from '@amber/chat-domain';


import { NovelDiscussionSession } from '../../platform_impl/novel/NovelDiscussionSession.ets';
import { NovelJobSession } from '../../platform_impl/novel/NovelJobSession.ets';
const NOVEL_AGENT_MESSAGE_CAPABILITIES: AgentMessageRowHostCapabilities = {
  copy: false,
  fork: false,
  branchSwitch: false,
  edit: false,
  regenerate: false,
  favorite: false,
  openActions: false,
  approveTool: true,
  denyTool: true,
  answerTool: true,
  openToolPreview: false,
  openSubAgentRun: false,
  runMiniApp: false,
  modifyMiniApp: false,
  openMiniAppList: false,
  openDocument: false,
};

@Component
export struct NovelDiscussionView {
  @ObjectLink session: NovelDiscussionSession;
  @ObjectLink jobs: NovelJobSession;
  @Prop project: NovelProject | null = null;
  @Prop workspaceStatus: NovelWorkspaceStatus | null = null;
  @Prop busy: boolean = false;
  @Prop blocking: boolean = false;
  @Prop navBarHeight: number = 0;
  @Prop writingLabel: string = '';
  onOpenModel: () => void = (): void => {};
  onCollect: (id: string) => void = (): void => {};
  onCloneCollected: (message: NovelMessage) => void = (): void => {};
  onResolveSettingProposal: (proposal: NovelSettingProposal, accept: boolean) => void = (): void => {};
  onReload: () => void = (): void => {};
  @Builder
  novelMessageHeader(message: NovelMessage): void {
    if (message.role === 'assistant') {
      Text(this.session.assistantLabel(message))
        .fontSize(12)
        .fontColor(INK3)
        .height(18)
        .margin({ bottom: 4 })
    }
    if (message.clonedFromMessageId !== undefined) {
      Text('再次收录候选 · 来自已收录回复').fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
    }
  }

  @Builder
  novelMessageFooter(message: NovelMessage): void {
    if (this.session.canRetryQuickStart(message)) {
      Text('重试快速开始').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .enabled(!this.busy && !this.blocking)
        .onClick((): void => { this.session.retryQuickStartSeed(this.session.quickStartSeedFor(message)); })
    }
    if (message.role === 'assistant' && message.mode === 'write' && message.collectedChapterId === null &&
      (message.candidate === undefined || message.candidate.kind !== 'polish' || message.candidate.complete) &&
      novelMessageText(message).trim().length > 0) {
      Row() {
        Icon({ name: 'ph_bookOpen', iconSize: 14, color: ACCENT_INK })
        Text(message.interrupted ? '收进章节（部分）' : '收进章节')
          .fontSize(12).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(ACCENT_INK).margin({ left: 6 })
      }
      .height(44)
      .padding({ left: 16, right: 16 })
      .borderRadius(22)
      .backgroundColor(ACCENT)
      .margin({ top: 6 })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => { this.onCollect(message.id); })
      .accessibilityText(message.interrupted ? '收进章节，部分内容' : '收进章节')
    } else if (message.collectedChapterId !== null) {
      Row() {
        Icon({ name: 'check', iconSize: 13, color: SUCCESS_FG })
        Text('已收进章节').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(SUCCESS_FG)
          .margin({ left: 5 })
      }
      .alignItems(VerticalAlign.Center).margin({ top: 10 })
      if (this.project !== null && canCloneCollectedMessage(this.project, message, this.workspaceStatus?.activeBranchId ?? '')) {
        Text('再次收录').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
          .enabled(!this.busy && !this.blocking)
          .onClick((): void => { this.onCloneCollected(message); })
      }
    }
  }

  @Builder
  activeRunHeader(show: boolean = true): void {
    if (show) {
      Row() {
        Text(this.session.activeMessages.length > 0 ? '正在写' : '正在思考')
          .fontSize(12)
          .fontColor(ACCENT)
        if (this.session.activeFrameSeen) {
          Text(this.session.activeTransport === 'live' ? '实时' :
            (this.session.activeTransport === 'buffered' ? '缓冲' : '非流式'))
            .fontSize(10)
            .fontColor(INK3)
            .margin({ left: 8 })
        }
      }.height(18).alignItems(VerticalAlign.Center).margin({ bottom: 4 })
    }
  }

  @Builder
  activeRunFooter(message: UIMessage): void {
    if (message.role === 'assistant' && this.session.activeRunKind !== 'discussion' && !this.session.activeQuickStart &&
      toText(message).trim().length > 0) {
      // The saved collection button is44vp plus6vp margin; reserve it while writing.
      Column() {}.height(50)
    }
  }

  @Builder
  emptyAgentRowSlot(): void {}

  @Builder
  novelTimelineMessageHeader(message: NovelMessage): void {
    if (this.session.novelTimelineMessageLive(message)) this.activeRunHeader(message.role === 'assistant');
    else this.novelMessageHeader(this.session.savedTimelineMessage(message, this.project));
  }

  @Builder
  novelTimelineMessageFooter(message: NovelMessage): void {
    if (this.session.novelTimelineMessageLive(message)) this.activeRunFooter(this.session.novelTimelineMessageUi(message, this.project));
    // Clone eligibility and actions require the canonical message: @Prop snapshots omit its derived content getter.
    else this.novelMessageFooter(this.session.savedTimelineMessage(message));
  }

  @Builder
  novelTimelineRows(): void {
    if (this.project !== null) {
      if (this.session.ordinaryRecoveryRecord() !== null && this.session.activeRunId.length === 0) {
        ListItem() {
          NovelRunRecoveryCard({
            recordKey: this.session.ordinaryRecoveryRecord()!.id,
            heading: this.session.ordinaryRecoveryRecord()!.status === 'failed' ? '上次生成失败' : '上次生成已中断',
            detail: this.session.ordinaryRecoveryDetail(this.session.ordinaryRecoveryRecord()!),
            userText: this.session.ordinaryRecoveryRecord()!.userText,
            partialText: this.session.ordinaryPartialText(this.session.ordinaryRecoveryRecord()!),
            busy: this.busy || this.blocking,
            retryAllowed: this.session.ordinaryRecovery?.retryAllowed ?? false,
            resumeAllowed: this.session.ordinaryRecovery?.resumeAllowed ?? false,
            blockedReason: this.session.ordinaryRecovery?.blockedReason ?? '',
            errorMessage: this.session.ordinaryRecoveryRecord()!.error ?? '',
            retryLabel: this.session.ordinaryRecoveryRecord()!.originalRequest.operation.kind === 'tool_continuation' ? '重试工具续接' : '重试原请求',
            onRetry: (recordId: string): void => { this.session.recoverOrdinaryRun(recordId, 'retry'); },
            onResume: (recordId: string): void => { this.session.recoverOrdinaryRun(recordId, 'resume'); },
            onCopyPartial: (recordId: string, text: string): void => { this.session.copyOrdinaryPartial(recordId, text); },
          }).margin({ bottom: 10 })
        }
      }
      if (this.jobs.ghostwriteJob !== null) {
        ListItem() {
          NovelGhostwriteCard({
            job: this.jobs.ghostwriteJob,
            suggestedChapterCount: this.project?.branchSettings.suggestedChapterCount ?? DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
            planReady: this.jobs.ghostwritePlanReady(),
            modelReady: this.jobs.ghostwriteModelsReady(),
            planDisabledReason: '请先在项目控制中保存本章计划。',
            modelDisabledReason: this.jobs.ghostwriteModelReason(),
            busy: this.jobs.ghostwriteBusy,
            onStart: (targetChapterCount: number): void => { this.jobs.startGhostwrite(targetChapterCount); },
            onPause: (): void => { this.jobs.pauseGhostwrite(); },
            onResume: (): void => { this.jobs.resumeGhostwrite(); },
            onRetry: (): void => { this.jobs.retryGhostwrite(); },
            onCancel: (): void => { this.jobs.cancelGhostwrite(); },
          })
          .margin({ bottom: 8 }).borderRadius(14).clip(true)
        }
      }
      if (this.project.messages.length === 0 && this.session.activeRunId.length === 0) {
        // 空态(iOS 纯文字):快速开始已前置到列表页创建 sheet
        ListItem() {
          Column() {
            Text('新的创作对话').fontSize(15).fontColor(INK2).width('100%')
            Text('在下方写下灵感，先梳理设定与开篇方向。')
              .fontSize(12).fontColor(INK3).margin({ top: 6 }).width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
        }
      }
      if (this.jobs.ghostwriteJob === null && this.jobs.ghostwriteReport !== null) {
        ListItem() { NovelGhostwriteReportCard({ report: this.jobs.ghostwriteReport }) }
      }
      ForEach(this.session.novelTimelineMessages(this.project), (message: NovelMessage): void => {
        if (discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message) !== null) {
          ListItem() {
            NovelDiscussionArchiveCard({
              archive: discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message)!,
              expanded: this.session.expandedArchiveIds.includes(discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message)!.id),
              onToggle: (archiveId: string): void => { this.session.toggleDiscussionArchive(archiveId); },
            }).margin({ bottom: 8 })
          }
        }
        if (discussionMessageVisible(this.project?.discussionArchives ?? [], this.session.expandedArchiveIds, message)) {
          ListItem() {
            AgentMessageRow({
              message: this.session.novelTimelineMessageUi(message, this.project),
              capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
              hostHeader: (): void => { this.novelTimelineMessageHeader(message); },
              hostFooter: (): void => { this.novelTimelineMessageFooter(message); },
              streaming: this.session.novelTimelineMessageLive(message),
              textDeltasLive: this.session.activeTextDeltasLive,
              isLastAssistant: this.session.novelTimelineMessageLive(message) &&
                message.uiMessage.id === this.session.activeRunTimelineMessages()[this.session.activeRunTimelineMessages().length - 1]?.id,
              animateEntrance: false,
              onTextGrow: (): void => {
                if (this.session.novelTimelineMessageLive(message)) this.session.followNovelStreamingUpdate();
                else this.session.requestNovelNativeFollow();
              },
              onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'approved' }, locator);
              },
              onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'denied', reason: '用户拒绝了这次操作' }, locator);
              },
              onAnswerTool: (toolCallId: string, answer: string,
                locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'answered', answer }, locator);
              },
            })
          }
        }
        ForEach(this.session.settingProposalsAt(message, this.project),
          (proposal: NovelSettingProposal): void => {
            this.novelSettingProposalRow(proposal)
          }, (proposal: NovelSettingProposal): string => proposal.id)
      }, (message: NovelMessage): string => `${message.id}:${message.collectedChapterId ?? ''}:${discussionMessageVisible(this.project?.discussionArchives ?? [], this.session.expandedArchiveIds, message)}`)
      ForEach(orphanSettingProposals(this.project.settingProposals, this.project.messages),
        (proposal: NovelSettingProposal): void => {
          this.novelSettingProposalRow(proposal)
        }, (proposal: NovelSettingProposal): string => proposal.id)
    }
    if (this.session.pendingUserText.length > 0 && this.session.activeMessages.length === 0) {
      ListItem() {
        AgentMessageRow({
          message: this.session.pendingUserUi(),
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.emptyAgentRowSlot(); },
          hostFooter: (): void => { this.emptyAgentRowSlot(); },
          streaming: false,
        })
      }
    }
  }

  @Builder
  novelSettingProposalRow(proposal: NovelSettingProposal): void {
    ListItem() {
      NovelSettingProposalCard({
        title: proposal.title,
        content: proposal.content,
        kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
        status: proposal.status,
        onResolve: (accept: boolean): void => { this.onResolveSettingProposal(proposal, accept); },
      })
    }
  }

  @Builder
  novelTimelineActiveTail(): void {
    ForEach(this.session.completedPreview, (message: UIMessage): void => {
      ListItem() {
        AgentMessageRow({ message,
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.completedPreviewHeader(); },
          hostFooter: (): void => { this.completedPreviewFooter(); },
          streaming: false, animateEntrance: false,
        })
      }
    }, (message: UIMessage): string => `completed-preview-${message.id}`)
    ForEach(this.session.activeRunId.length > 0 ? [] : this.session.failedMessages, (message: UIMessage): void => {
      ListItem() {
        AgentMessageRow({
          message: this.session.unsavedDisplayMessage(message),
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.unsavedMessageHeader(); },
          hostFooter: (): void => { this.unsavedMessageFooter(message); },
          streaming: false,
        })
      }
    }, (message: UIMessage): string => `unsaved-${message.id}`)
    if (this.session.activeRunId.length > 0) {
      if (this.session.activeQuickStart) {
        ListItem() {
          Text('正在整理角色、设定与开篇方向；完成后可逐项确认。')
            .fontSize(14).fontColor(INK3).lineHeight(21).width('100%').padding(16)
        }
      }
      if (this.session.activeRunTimelineMessages().length > 0) {
        ForEach(this.session.activeQuickStart ? this.session.activeRunTimelineMessages() : [], (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({
              message: this.session.activeRunMessage(message),
              capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
              hostHeader: (): void => { this.activeRunHeader(message.role === 'assistant'); },
              hostFooter: (): void => { this.activeRunFooter(this.session.activeRunMessage(message)); },
              streaming: true,
              textDeltasLive: this.session.activeTextDeltasLive,
              isLastAssistant: index === this.session.activeRunTimelineMessages().length - 1,
              onTextGrow: (): void => { this.session.followNovelStreamingUpdate(); },
              onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'approved' }, locator);
              },
              onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'denied', reason: '用户拒绝了这次操作' }, locator);
              },
              onAnswerTool: (toolCallId: string, answer: string,
                locator?: ToolApprovalPartLocator): void => {
                this.session.continueNovelTool(toolCallId, { kind: 'answered', answer }, locator);
              },
            })
          }
        }, (message: UIMessage): string => message.id)
      } else if (!this.session.activeQuickStart) {
        ListItem() {
          AgentMessageRow({
            message: this.session.activeStatusUi(),
            capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
            hostHeader: (): void => { this.activeRunHeader(); },
            hostFooter: (): void => { this.emptyAgentRowSlot(); },
            streaming: true,
            textDeltasLive: false,
            isLastAssistant: true,
            onTextGrow: (): void => { this.session.followNovelStreamingUpdate(); },
          })
        }
      }
    }
  }

  @Builder
  completedPreviewHeader(): void {
    Text('生成内容 · 项目尚未刷新').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
  }

  @Builder
  completedPreviewFooter(): void {
    Text('重新加载项目').fontSize(12).fontColor(ACCENT).constraintSize({ minHeight: 44 })
      .enabled(!this.busy && !this.blocking)
      .onClick((): void => { this.onReload(); })
  }

  @Builder
  unsavedMessageHeader(): void {
    Text('未保存的生成内容').fontSize(12).fontColor(ERROR).margin({ bottom: 4 })
  }

  @Builder
  unsavedMessageFooter(message: UIMessage): void {
    if (toText(message).trim().length > 0) {
      Text('复制未保存内容').fontSize(12).fontColor(ACCENT).fontFamily(FONT_SANS_MEDIUM)
        .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
        .onClick((): void => { this.session.copyUnsavedMessage(message); })
    }
  }

  @Builder
  emptyTimelineSlot(): void {}

  @Builder
  chatPane(): void {
    Column() {
    Column() {
      Stack({ alignContent: Alignment.BottomEnd }) {
      // Chat 与 Novel 共享同一 Timeline/AgentMessageRow；Timeline 独占 16vp 水平 gutter。
      InteractiveMessageTimeline({
        scroller: this.session.chatScroller,
        horizontalGutter: 16,
        topPadding: 8,
        bottomPadding: 0,
        cachedItems: 5,
        showOlderLoader: false,
        showDivider: false,
        showScrollBar: false,
        rows: (): void => { this.novelTimelineRows(); },
        activeTail: (): void => { this.novelTimelineActiveTail(); },
        hostTail: (): void => { this.emptyTimelineSlot(); },
        onTimelineAppear: (): void => {
          if (this.session.initialScrollArmed) {
            this.session.initialScrollArmed = false;
            this.session.scheduleNovelScrollToBottom();
          }
        },
        onViewportHeightChange: (height: number): void => {
          this.session.novelViewportHeight = height;
          if (!this.session.initialScrollArmed) this.session.requestNovelNativeFollow();
        },
        onVisibleRangeChange: (_start: number, _end: number): void => {
          this.session.novelLastVisibleIndex = _end;
          if (this.session.initialScrollArmed) {
            this.session.initialScrollArmed = false;
            this.session.scheduleNovelScrollToBottom();
          }
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) {
            this.session.userTouchActive = true;
            this.session.novelScrollToken++;
            this.session.nativeFollow?.cancel();
          }
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.session.userTouchActive = false;
            if (this.session.novelUserScrolling) this.session.reconcileNovelUserScroll();
            else this.session.requestNovelNativeFollow();
          }
        },
        onTimelineWillScroll: (offset: number, _state: ScrollState, source: ScrollSource): void => {
          if (offset !== 0 && (source === ScrollSource.DRAG || source === ScrollSource.FLING)) {
            this.session.novelUserScrolling = true;
            this.session.nativeFollow?.cancel();
          }
        },
        onTimelineScroll: (_offset: number, state: ScrollState): void => {
          if (this.session.novelUserScrolling &&
            (state === ScrollState.Scroll || state === ScrollState.Fling)) {
            this.session.reconcileNovelUserScroll();
          }
        },
        onTimelineScrollStop: (): void => {
          if (this.session.novelUserScrolling) {
            this.session.novelUserScrolling = false;
            this.session.reconcileNovelUserScroll();
          }
        },
      })
      .width('100%')
      .height('100%')
      .clip(true)
      this.novelJumpButton()
      }
      .layoutWeight(1)
      .width('100%')

      // 输入器(复用聊天页 ChatInput;meta 行 = 模型 chip + 创作方式 menu,iOS 同款)
      Column() {
        Row() {
          Text(this.writingLabel)
            .fontSize(11).fontFamily(FONT_MONO).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ minHeight: 44, maxWidth: '50%' })
            .padding({ left: 10, right: 10 })
            .backgroundColor(SURFACE2).borderRadius(12)
            .enabled(this.project !== null && !this.busy)
            .onClick((): void => { this.onOpenModel(); })
            .accessibilityText(getProductKind() === 'novel' ? '创作模型，选择模型' : '写作模型，打开项目控制')
          Blank()
          Text(`${this.session.composeModeLabel()} ▾`)
            .fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2)
            .maxLines(1)
            .constraintSize({ minHeight: 44 })
            .padding({ left: 12, right: 12 })
            .backgroundColor(SURFACE2).borderRadius(12)
            .opacity(this.blocking ? 0.5 : 1)
            .onClick((): void => { this.session.showComposeModeMenu(); })
            .accessibilityText('创作方式')
        }
        .width('100%').padding({ left: 20, right: 20 }).margin({ bottom: 2 }).alignItems(VerticalAlign.Center)

        if (this.session.failedInput.length > 0) {
          Row({ space: 8 }) {
            Text('可恢复本次输入，重新发送时会读取当前书稿。')
              .fontSize(11).fontColor(INK3).layoutWeight(1).lineHeight(16)
            Text(this.session.failedInputRunKind === 'quick_start' ? '重试快速开始' : '恢复输入').fontSize(12).fontColor(ACCENT).fontFamily(FONT_SANS_MEDIUM)
              .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
              .enabled(!this.blocking)
              .onClick((): void => { this.session.restoreFailedInput(); })
          }.width('100%').padding({ left: 20, right: 20 }).alignItems(VerticalAlign.Center)
        }
        ChatInput({
          text: this.session.draft,
          placeholder: this.session.composerPlaceholder(),
          showAttachments: false,
          showCoreSlashCommands: false,
          sending: this.session.activeRunId.length > 0 || this.jobs.polishRuns() || this.jobs.ghostwriteRuns(),
          disabled: this.busy || this.session.sendStarting || this.session.composerRestoring ||
            (this.jobs.novelJobLocksWorkspace() && !this.jobs.ghostwriteRuns() && !this.jobs.polishRuns()),
          onTextChange: (v: string): void => { this.session.setComposerDraft(v); },
          onSend: (): void => { this.session.send(); },
          onStop: (): void => {
            if (this.jobs.polishRuns()) this.jobs.pausePolish();
            else if (this.jobs.ghostwriteRuns()) this.jobs.pauseGhostwrite();
            else this.session.interrupt();
          },
          onClearInput: (): void => { this.session.setComposerDraft(''); },
        })
      }
      // 全屏布局下手势条悬浮(bottomRect=0 → navBarHeight=0),须自留 ≥24vp 安全区
      .width('100%').padding({ top: 8,
        bottom: Math.max(this.navBarHeight, 24) })
      .border({ width: { top: 0.5 }, color: { top: LINE } })
    }
    .layoutWeight(1)
    .width('100%')
    }
    .layoutWeight(1)
    .width('100%')
  }

  @Builder
  novelJumpButton(): void {
    if (this.session.jumpButtonVisible) {
      Stack() {
        Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
      }
      .width(44)
      .height(44)
      .margin({ right: 16, bottom: 12 })
      .backgroundColor(SURFACE2)
      .borderRadius(22)
      .border({ width: 1, color: LINE })
      .alignContent(Alignment.Center)
      .onClick((): void => { this.session.jumpNovelToBottom(); })
      .accessibilityText('回到消息底部')
      .transition(TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: 8 }))
        .animation({ duration: MOTION_PRESS, curve: Curve.EaseOut }))
    }
  }

  build(): void {
    this.chatPane()
  }
}
