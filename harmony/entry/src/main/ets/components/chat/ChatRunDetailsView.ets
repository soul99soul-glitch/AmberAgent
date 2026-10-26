import { ACCENT, BG, ERROR, INK, INK2, INK3, INK4, LINE, META, MOTION_OVERLAY,
  OVERLAY, SURFACE, SURFACE2 } from '../../design/tokens.ets';
import { FONT_MONO } from '../../design/fonts.ets';
import type { SandboxActivityUiState } from '@amber/chat-domain';
import { ChatRunDetailsSession } from '../../platform_impl/chat/ChatRunDetailsSession.ets';
import { WebMountHost, WebMountSurface } from '../../platform_impl/WebMountSession.ets';
import { JsSandboxHost } from '../../platform_impl/JsSandboxTool.ets';
import { SubAgentRunSheet } from '../SubAgentRunSheet.ets';
import { ChatCouncilRunSheet } from '../ChatCouncilRunSheet.ets';
import { ChatMessageToolPreviewSheet } from '../ChatMessageToolPreviewSheet.ets';
import { SubAgentActivityStrip } from '../SubAgentActivityStrip.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct ChatRunDetailsView {
  @ObjectLink session: ChatRunDetailsSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  @Builder
  sandboxStatusIcon(status: string) {
    if (status === 'failed') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(ERROR).borderRadius(8)
    } else if (status === 'cancelled') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(INK3).borderRadius(8)
    } else {
      Text('✓').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(this.accentColorStore).borderRadius(8)
    }
  }

  @Builder
  sandboxArrow(left: boolean, enabled: boolean, onTap: () => void) {
    Text(left ? '‹' : '›')
      .fontSize(13)
      .fontColor(enabled ? INK : INK4)
      .padding({ left: 4, right: 4 })
      .onClick((): void => {
        if (enabled) onTap();
      })
  }

  @Builder
  sandboxSheet(timeline: SandboxActivityUiState[]) {
    Column() {
      // 头部
      Row() {
        this.sandboxStatusIcon(this.session.currentSandboxActivity(timeline)?.status ?? 'running')
        Column() {
          Text(this.session.currentSandboxActivity(timeline)?.title ?? '')
            .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.session.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.runtime.trim().length > 0 ? a.runtime : a.toolName;
          })())
            .fontSize(11).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
        if ((this.session.currentSandboxActivity(timeline)?.canCancel ?? false) && this.session.sending()) {
          Text('中断')
            .fontSize(11).fontColor(this.accentColorStore)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .backgroundColor(SURFACE2)
            .borderRadius(999)
            .onClick((): void => { this.session.requestStop(); })
        }
        this.sandboxArrow(true, this.session.currentSandboxIndexOf(timeline) > 0, (): void => {
          this.session.selectedSandboxIndex = this.session.currentSandboxIndexOf(timeline) - 1;
        })
        Text(this.session.sandboxStepText(this.session.currentSandboxActivity(timeline) ??
          timeline[timeline.length - 1]))
          .fontSize(13).fontColor(INK3).maxLines(1)
        this.sandboxArrow(false,
          this.session.currentSandboxIndexOf(timeline) >= 0 &&
          this.session.currentSandboxIndexOf(timeline) < timeline.length - 1,
          (): void => {
            this.session.selectedSandboxIndex = this.session.currentSandboxIndexOf(timeline) + 1;
          })
      }
      .width('100%').padding({ left: 12, right: 12, top: 9, bottom: 9 })
      .backgroundColor(SURFACE).borderRadius(22)
      .border({ width: 1, color: LINE })

      // 内容(:873-899)— 调用内容/调用结果两块
      Scroll() {
        Column() {
          Text('调用内容')
            .fontSize(11).fontColor(INK3).width('100%')
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.session.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.inputPreview.trim().length > 0 ? a.inputPreview : a.title;
          })())
            .fontSize(12).fontColor(INK).fontFamily(FONT_MONO)
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(SURFACE2).borderRadius(10)
          Text('调用结果')
            .fontSize(11).fontColor(INK3).width('100%').margin({ top: 12 })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.session.currentSandboxActivity(timeline);
            if (a === null) return '';
            if (a.outputTail.trim().length > 0) return a.outputTail;
            if (a.status === 'running' || a.status === 'waiting_for_permission') {
              return '等待工具返回输出...';
            }
            return '无输出';
          })())
            .fontSize(12).fontColor(INK).fontFamily(FONT_MONO)
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(SURFACE2).borderRadius(10)
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }
        .align(Alignment.TopStart)
      .width('100%').layoutWeight(1).margin({ top: 12 })
  }
    .width('100%').height('100%').padding(16)
  }

  @Builder
  subAgentSheetBuilder() {
    SubAgentRunSheet({
      runId: this.session.subAgentSheetRunId,
      tools: this.session.subAgentSheetTools,
      liveText: this.session.subAgentLiveText,
      onDismiss: (): void => { this.session.closeSubAgentSheet(); },
    })
  }

  @Builder
  CouncilSheet(): void {
    ChatCouncilRunSheet({ runId: this.session.councilSheetRunId, tools: this.session.councilSheetTools,
      onDismiss: (): void => { this.session.closeCouncilSheet(); } })
  }

  @Builder
  toolPreviewSheetBuilder(_version: number) {
    if (this.session.toolPreviewPart !== null) {
      ChatMessageToolPreviewSheet({
        part: this.session.currentToolPreviewPart(),
        jevSubjectHash: this.session.jevSubjectHash(this.session.currentToolPreviewPart(), this.session.toolPreviewLocator ?? undefined),
        onDismiss: (): void => { this.session.closeToolPreview(); },
      })
    }
  }

  @Builder
  webmountSheet(): void {
    ChatRunBrowserSheet({ session: this.session })
  }

  private timeline(_version: number): SandboxActivityUiState[] { return this.session.sandboxTimeline(); }

  build(): void {
    Stack() {
      Column() {}.width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet($$this.session.webmountSheetOpen, this.webmountSheet(), {
          height: '92%', showClose: false,
          onDisappear: (): void => { this.session.webmountSheetDidDisappear(); },
        })
      Column() {}.width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.sandboxSheetOpen, this.sandboxSheet(this.timeline(this.session.snapshotVersion)), {
          height: SheetSize.LARGE,
          onDisappear: (): void => { this.session.closeSandboxSheet(); },
        })
      Column() {}.width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.subAgentSheetOpen, this.subAgentSheetBuilder(), {
          height: SheetSize.LARGE, showClose: false, backgroundColor: SURFACE,
          onDisappear: (): void => { this.session.finishSubAgentSheet(); },
        })
      Column() {}.width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.councilSheetOpen, this.CouncilSheet(), {
          height: SheetSize.LARGE, showClose: false, backgroundColor: SURFACE,
          onDisappear: (): void => { this.session.closeCouncilSheet(); },
        })
      Column() {}.width(0).height(0).hitTestBehavior(HitTestMode.None)
        .bindSheet(this.session.toolPreviewSheetOpen, this.toolPreviewSheetBuilder(this.session.snapshotVersion), {
          height: SheetSize.LARGE, showClose: false, backgroundColor: SURFACE,
          onDisappear: (): void => { this.session.finishToolPreview(); },
        })
      WebMountHost({ visible: this.session.webmountHostVisible })
      if (this.session.jsSandboxNeeded) { JsSandboxHost() }
    }
  }
}

@Component
struct ChatRunBrowserSheet {
  @ObjectLink session: ChatRunDetailsSession;
  @StorageProp('webmountUrl') webmountUrl: string = '';
  @StorageProp('webmountLease') webmountLease: string = 'bot';

  build(): void {

    Column() {
      Row() {
        Text(this.session.webmountHostOf(this.webmountUrl))
          .fontSize(16).fontColor(INK).layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.webmountLease === 'hand' ? '交还控制' : '接管')
          .fontSize(14).fontColor(ACCENT)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.session.toggleWebmountLease(this.webmountLease); })
        Text('关闭')
          .fontSize(14).fontColor(INK3)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.session.closeWebmountSheet(); })
      }
      .width('100%')
      if (this.webmountLease === 'hand') {
        Text('你正在手动控制 — Agent 的点击/输入/脚本已暂停,交还后恢复')
          .fontSize(META).fontColor(INK3)
          .width('100%')
          .padding({ top: 6, bottom: 2 })
      }
      WebMountSurface()
        .layoutWeight(1)
        .width('100%')
        .margin({ top: 8 })
    }
    .width('100%').height('100%').padding(16).backgroundColor(BG)
  
  }
}

@Component
export struct ChatSandboxStrip {
  @ObjectLink session: ChatRunDetailsSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  @Builder
  sandboxStatusIcon(status: string) {
    if (status === 'failed') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(ERROR).borderRadius(8)
    } else if (status === 'cancelled') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(INK3).borderRadius(8)
    } else {
      Text('✓').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(this.accentColorStore).borderRadius(8)
    }
  }

  @Builder
  sandboxArrow(left: boolean, enabled: boolean, onTap: () => void) {
    Text(left ? '‹' : '›')
      .fontSize(13)
      .fontColor(enabled ? INK : INK4)
      .padding({ left: 4, right: 4 })
      .onClick((): void => {
        if (enabled) onTap();
      })
  }

  @Builder
  sandboxStrip(timeline: SandboxActivityUiState[]) {
    Row() {
      this.sandboxStatusIcon(this.session.currentSandboxActivity(timeline)?.status ?? 'running')
      Text(this.session.currentSandboxActivity(timeline)?.title ?? '')
        .fontSize(10.5).fontWeight(FontWeight.Medium).fontColor(INK)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1).margin({ left: 8 })
      if ((this.session.currentSandboxActivity(timeline)?.canCancel ?? false) && this.session.sending()) {
        Text('✕')
          .fontSize(10).fontColor(this.accentColorStore)
          .padding({ left: 4, right: 4 })
          .onClick((): void => {
            // onCancelClick → vm.stopGeneration(:640-642;同输入区停止按钮)。
            // Phase 2:统一走 requestStop(收口 + 不抢先保存)
            this.session.requestStop();
          })
      }
      this.sandboxArrow(true, this.session.currentSandboxIndexOf(timeline) > 0, (): void => {
        this.session.selectedSandboxIndex = this.session.currentSandboxIndexOf(timeline) - 1;
      })
      Text(this.session.sandboxStepText(this.session.currentSandboxActivity(timeline) ??
        timeline[timeline.length - 1]))
        .fontSize(9.5).fontColor(INK3).maxLines(1)
      this.sandboxArrow(false,
        this.session.currentSandboxIndexOf(timeline) >= 0 &&
        this.session.currentSandboxIndexOf(timeline) < timeline.length - 1,
        (): void => {
          this.session.selectedSandboxIndex = this.session.currentSandboxIndexOf(timeline) + 1;
        })
    }
    .width('calc(100% - 40vp)')
    .padding({ left: 6, right: 10, top: 3, bottom: 3 })
    .backgroundColor(SURFACE)
    .borderRadius(999)
    .border({ width: 1, color: LINE })
    .margin({ left: 20, right: 20, bottom: 4 })
    .onClick((): void => { this.session.sandboxSheetOpen = true; })
  }

  private timeline(_version: number): SandboxActivityUiState[] { return this.session.sandboxTimeline(); }

  build(): void {
    Column() {
      if (this.timeline(this.session.snapshotVersion).length > 0) {
        this.sandboxStrip(this.timeline(this.session.snapshotVersion))
      }
    }.width('100%')
  }
}

@Component
export struct ChatRunActivityStrip {
  @ObjectLink session: ChatRunDetailsSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('webmountUrl') webmountUrl: string = '';
  @StorageProp('webmountReady') webmountReady: boolean = false;
  @StorageProp('webmountLease') webmountLease: string = 'bot';

  build(): void {
    Column() {
      SubAgentActivityStrip()
        if (this.webmountUrl.length > 0 && this.webmountReady && !this.session.webmountSheetOpen) {
          Row() {
            Icon({ name: 'globe', iconSize: 14, color: this.accentColorStore })
            Text(`WebMount · ${this.session.webmountHostOf(this.webmountUrl)}`)
              .fontSize(META).fontColor(INK2)
              .margin({ left: 6 })
              .layoutWeight(1)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(this.webmountLease === 'hand' ? '手动控制中' : '查看 / 接管')
              .fontSize(META).fontColor(this.accentColorStore)
          }
          .width('calc(100% - 40vp)')
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .margin({ left: 20, right: 20, bottom: 2 })
          .backgroundColor(SURFACE)
          .borderRadius(10)
          .border({ width: 1, color: LINE })
          .onClick((): void => { this.session.openWebmountSheet(); })
          .accessibilityText('打开 WebMount 浏览器面板')
        }
    }.width('100%')
  }
}

@Component
export struct ChatToolDenyOverlay {
  @ObjectLink session: ChatRunDetailsSession;

  build(): void {
    if (this.session.denyDialogOpen) {
      Column() {
        Column() {
          Text('拒绝工具执行')
            .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
            .width('100%').margin({ bottom: 10 })
          TextArea({ text: this.session.denyReasonText, placeholder: '原因（可选）' })
            .width('100%').fontSize(13).fontColor(INK)
            .constraintSize({ minHeight: 88, maxHeight: 160 })
            .backgroundColor(SURFACE2).borderRadius(10)
            .onChange((v: string): void => { this.session.denyReasonText = v; })
          Row() {
            Blank()
            Text('取消')
              .fontSize(14).fontColor(INK3)
              .padding({ left: 20, right: 20, top: 8, bottom: 8 })
              .onClick((): void => { this.session.dismissDenyDialog(); })
            Text('拒绝')
              .fontSize(14).fontColor(ERROR)
              .padding({ left: 20, right: 8, top: 8, bottom: 8 })
              .onClick((): void => { this.session.confirmDenyDialog(); })
          }
          .width('100%').margin({ top: 12 })
        }
        .width('86%')
        .padding(18)
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .border({ width: 1, color: LINE })
        // 消费点击,防穿透到遮罩 onClick(遮罩点击 = onDismissRequest)
        .onClick((): void => {})
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.session.dismissDenyDialog(); })
      .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }

  }
}
