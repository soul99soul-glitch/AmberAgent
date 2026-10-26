import { ChatModelContextSession } from '../../platform_impl/chat/ChatModelContextSession.ets';
import { TopModelMenu } from '../TopModelMenu.ets';
import { MarkdownText } from '../MarkdownText.ets';
import { formatContextTokens } from '@amber/chat-domain';
import { BODY, INK, INK2, INK3, LINE, META, STATUS_BLUE, SURFACE, MOTION_OVERLAY } from '../../design/tokens.ets';

@Component
export struct ChatModelMenuOverlay {
  @ObjectLink session: ChatModelContextSession;
  @Prop topInset: number = 0;
  onOpenFullPicker: (assistantId: string) => void = (_assistantId: string): void => {};
  onOpenProviderSettings: () => void = (): void => {};

  build(): void {
    if (this.session.modelMenuOpen) {
      TopModelMenu({
        topInset: this.topInset,
        contextUsedTokens: this.session.contextUsedTokens,
        contextWindowTokens: this.session.contextWindowTokens,
        contextColor: this.session.contextRingColor(),
        contextCompactActive: this.session.compactActiveCount > 0,
        turnTotal: this.session.turnTotalText,
        turnCache: this.session.turnCacheText,
        turnSpeed: this.session.turnSpeedText,
        groups: this.session.modelMenuGroups,
        currentProviderId: this.session.modelMenuCurrentProvider,
        currentModelId: this.session.modelMenuCurrentModel,
        reasoningLevels: this.session.reasoningLevels,
        currentReasoningLevel: this.session.currentReasoningLevel,
        onReasoningLevelChange: (level: string): void => { this.session.handleReasoningLevelChange(level); },
        onSelect: (providerId: string, modelId: string): void => {
          this.session.selectModelFromMenu(providerId, modelId);
        },
        onClose: (): void => { this.session.closeModelMenu(); },
        onOpenFullPicker: (): void => {
          this.session.closeModelMenu();
          this.onOpenFullPicker(this.session.currentAssistantId());
        },
        onOpenProviderSettings: (): void => {
          this.session.closeModelMenu();
          this.onOpenProviderSettings();
        },
      })
    }
  }
}

@Component
export struct ChatContextMeterOverlay {
  @ObjectLink session: ChatModelContextSession;

  build(): void {
    if (this.session.contextMeterOpen) {
      Stack({ alignContent: Alignment.TopStart }) {
        Column()
          .width('100%').height('100%')
          .hitTestBehavior(HitTestMode.Block)
          .onClick((): void => { this.session.contextMeterOpen = false; })
        Column() {
          ChatContextUsagePanel({ session: this.session })
        }
        .position({ top: 0, right: 8 })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }.width('100%').height('100%')
    }
  }
}

@Component
export struct ChatCompactSummaryHost {
  @ObjectLink session: ChatModelContextSession;

  @Builder
  CompactSummarySheet(): void {
    Column() {
      Text('上下文摘要').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ top: 12, bottom: 16 })
      Scroll() {
        MarkdownText({ content: this.session.compactSummary, fontSize: BODY, fontColor: INK2 })
          .width('100%').padding({ bottom: 20 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
    }.width('100%').height('100%').padding({ left: 20, right: 20 }).backgroundColor(SURFACE)
  }

  build(): void {
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet($$this.session.compactSummaryOpen, this.CompactSummarySheet(), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.session.compactSummaryOpen = false; },
      })
  }
}

@Component
export struct ChatContextUsagePanel {
  @ObjectLink session: ChatModelContextSession;

  @Builder
  metaStat(label: string, value: string) {
    Column() {
      Text(label).fontSize(10.5).fontColor(INK2).letterSpacing(0.3)
      Text(value).fontSize(12).fontColor(INK).fontWeight(FontWeight.Medium)
        .letterSpacing(0.2).margin({ top: 2 })
    }
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  contextUsagePanel() {
    Column() {
      Text('用量与上下文')
        .fontSize(13).fontColor(INK2).letterSpacing(0.3)
        .width('100%')
        .margin({ bottom: 10 })

      Row() {
        Text('Context')
          .fontSize(13).fontColor(INK).fontWeight(FontWeight.Medium).letterSpacing(0.2)
        Blank()
        Text(`${this.session.contextUsedTokens > 0
          ? formatContextTokens(this.session.contextUsedTokens) : '0'} / ${this.session.contextWindowTokens !== null
          ? formatContextTokens(this.session.contextWindowTokens) : '--'}`)
          .fontSize(11.5).fontColor(INK2).letterSpacing(0.3)
      }
      .width('100%')

      Stack({ alignContent: Alignment.Start }) {
        Column()
          .width('100%').height(5).borderRadius(999)
          .backgroundColor('#141B1A17')
        Column()
          .height(5).borderRadius(999)
          .backgroundColor(this.session.contextRingColor())
          .width(`${Math.max(0, Math.min(1, this.session.contextUsageRatio())) * 100}%`)
      }
      .width('100%')
      .margin({ top: 6 })

      Column()
        .width('100%').height(1)
        .backgroundColor(LINE)
        .margin({ top: 14 })

      Row() {
        this.metaStat('本次', this.session.turnTotalText)
        Blank()
        this.metaStat('缓存命中', this.session.turnCacheText)
        Blank()
        this.metaStat('速度', this.session.turnSpeedText)
      }
      .width('100%')
      .margin({ top: 12 })
      .alignItems(VerticalAlign.Top)
    }
    .width(260)
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .borderRadius(18)
    .border({ width: 1, color: LINE })
    .shadow({ radius: 36, color: '#33404238', offsetY: 6 })
    .alignItems(HorizontalAlign.Start)
  }

  build(): void {
    this.contextUsagePanel()
  }
}

// Retained compact meter presentation; current usage entry opens the model menu.
@Component
export struct ChatContextMeterSheet {
  @ObjectLink session: ChatModelContextSession;

  build(): void {
    Column() {
      Row() {
        Text('Context').fontSize(14).fontColor(INK).fontWeight(FontWeight.Medium)
        Blank()
        Text(this.session.contextUsageText()).fontSize(META).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 16, bottom: 8 })

      // 5 段柱(放大版)
      Row() {
        ForEach([0, 1, 2, 3, 4], (i: number): void => {
          Text('')
            .layoutWeight(1).height(10).borderRadius(3)
            .backgroundColor(i < this.session.contextFilledBars() ? this.session.contextRingColor() : LINE)
            .margin({ right: 4 })
        }, (i: number): string => `sheetbar-${i}`)
      }
      .width('100%')
      .padding({ left: 16, right: 16 })

      Row() {
        Text(`${Math.round(this.session.contextUsageRatio() * 100)}%`)
          .fontSize(14).fontColor(this.session.contextRingColor()).fontWeight(FontWeight.Medium)
        Text('已用').fontSize(META).fontColor(INK3).margin({ left: 6 })
        Blank()
        if (this.session.compactActiveCount > 0) {
          Text(`压缩中 ${this.session.compactActiveCount} 项`).fontSize(META).fontColor(STATUS_BLUE)
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 16 })
    }
    .width('100%')
    .backgroundColor(SURFACE)
  }
}
