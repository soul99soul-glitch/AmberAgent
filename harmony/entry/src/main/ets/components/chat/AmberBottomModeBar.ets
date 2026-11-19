// AmberBottomModeBar — 底栏双态原位切换器
// - 静息态：黄金配重 3+1（[ 对话 | 任务 [N] | 中心 ] + [ 本机 ⇄ 云端 ]）
// - 聚焦态：150ms 淡变切为写作元控制栏（[ 模型名 ] + [ ✦ 新话题 | 💭 思考强度 | 上下文环 ]）

import {
  ACCENT, ACCENT_INK, BG_GLASS, ERROR, HOME_GLASS_EDGE, INK, INK2, INK3, LINE,
  STATUS_AMBER, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_MONO, FONT_SANS } from '../../design/fonts.ets';
import { ChatCloudDialogueSession } from '../../platform_impl/chat/ChatCloudDialogueSession.ets';
import { ChatModelContextSession } from '../../platform_impl/chat/ChatModelContextSession.ets';
import { AmberHostSwitcherPill } from './AmberHostSwitcherPill.ets';
import { Icon } from '../ui/Icon.ets';

export type BottomDockTab = 'chat' | 'tasks' | 'hubs';

@Component
export struct AmberBottomModeBar {
  @ObjectLink cloudDialogue: ChatCloudDialogueSession;
  @ObjectLink modelContext: ChatModelContextSession;
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop inputFocused: boolean = false;
  @Prop activeTab: BottomDockTab = 'chat';
  @Prop attentionTaskCount: number = 0;
  @Prop runningTaskCount: number = 0;
  onSelectTab: (tab: BottomDockTab) => void = (_tab: BottomDockTab): void => {};
  onNewTopic: () => void = (): void => {};
  onOpenGatewaySettings: () => void = (): void => {};

  private totalTaskBadge(): number {
    return this.attentionTaskCount + this.runningTaskCount;
  }

  private shortModelTitle(): string {
    const raw: string = this.modelContext.modelLabel.trim();
    if (raw.length === 0) return '选择模型';
    const slashIdx: number = raw.lastIndexOf('/');
    const shortName: string = slashIdx >= 0 ? raw.substring(slashIdx + 1).trim() : raw;
    return shortName.length > 0 ? shortName : raw;
  }

  private reasoningLabel(): string {
    const level: string = this.modelContext.currentReasoningLevel.trim();
    if (level === 'low') return '轻思';
    if (level === 'medium') return '中思';
    if (level === 'high') return '深思';
    if (level === 'xhigh') return '极深';
    if (level === 'none' || level === 'off') return '关闭';
    return level.length > 0 ? level : '思考';
  }

  private cycleReasoningLevel(): void {
    const levels = this.modelContext.reasoningLevels;
    if (levels.length === 0) return;
    const cur: string = this.modelContext.currentReasoningLevel;
    let idx: number = levels.findIndex((item): boolean => item.level === cur);
    idx = (idx + 1) % levels.length;
    this.modelContext.handleReasoningLevelChange(levels[idx].level);
  }

  private contextPercentLabel(): string {
    const pct: number = Math.round(this.modelContext.contextUsageRatio() * 100);
    return `${pct}%`;
  }

  @Builder
  TabChip(tab: BottomDockTab, label: string, badgeCount: number, isAttention: boolean): void {
    Row({ space: 4 }) {
      Text(label)
        .fontSize(12.5)
        .fontFamily(FONT_SANS)
        .fontWeight(this.activeTab === tab ? FontWeight.Bold : FontWeight.Medium)
        .fontColor(this.activeTab === tab ? INK : INK2)
        .maxLines(1)
      if (badgeCount > 0) {
        Text(`${badgeCount}`)
          .fontSize(10)
          .fontFamily(FONT_MONO)
          .fontWeight(FontWeight.Bold)
          .fontColor(ACCENT_INK)
          .padding({ left: 5, right: 5, top: 1, bottom: 1 })
          .borderRadius(999)
          .backgroundColor(isAttention ? ERROR : STATUS_AMBER)
      }
    }
    .height(30)
    .padding({ left: 11, right: 11 })
    .borderRadius(999)
    .backgroundColor(this.activeTab === tab ? SURFACE : Color.Transparent)
    .border({
      width: this.activeTab === tab ? 0.5 : 0,
      color: this.activeTab === tab ? HOME_GLASS_EDGE : Color.Transparent,
    })
    .alignItems(VerticalAlign.Center)
    .justifyContent(FlexAlign.Center)
    .onClick((): void => {
      if (tab !== 'chat' && this.activeTab === tab) {
        this.onSelectTab('chat');
      } else {
        this.onSelectTab(tab);
      }
    })
  }

  @Builder
  RestingSwitcherRow(): void {
    Row({ space: 8 }) {
      // 左侧 3 段胶囊：[ 对话 | 任务 [N] | 中心 ]
      Row({ space: 2 }) {
        this.TabChip('chat', '对话', 0, false)
        this.TabChip('tasks', '任务', this.totalTaskBadge(), this.attentionTaskCount > 0)
        this.TabChip('hubs', '中心', 0, false)
      }
      .height(36)
      .padding({ left: 3, right: 3 })
      .borderRadius(999)
      .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE2)
      .backgroundBlurStyle(
        this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
        { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
      )
      .border({ width: 0.75, color: HOME_GLASS_EDGE })
      .alignItems(VerticalAlign.Center)

      Blank()

      // 右侧对位宿主胶囊：[ 📱 本机 ⇄ ☁️ 云端 ]
      AmberHostSwitcherPill({
        cloudDialogue: this.cloudDialogue,
        onOpenGatewaySettings: (): void => { this.onOpenGatewaySettings(); },
      })
    }
    .width('100%')
    .height(38)
    .alignItems(VerticalAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: 150, curve: Curve.EaseOut }))
  }

  @Builder
  FocusedComposerBar(): void {
    Row({ space: 6 }) {
      // 左：[ 当前模型名 ] 快捷切换胶囊
      Row({ space: 4 }) {
        Text(this.shortModelTitle())
          .fontSize(12)
          .fontFamily(FONT_MONO)
          .fontColor(this.modelContext.modelLabel.length > 0 ? INK : this.accentColorStore)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: 132 })
        Icon({ name: 'chevron_down', iconSize: 11, color: INK3 })
      }
      .height(34)
      .padding({ left: 11, right: 9 })
      .borderRadius(999)
      .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE2)
      .border({ width: 0.75, color: LINE })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => {
        this.modelContext.toggleModelMenu();
      })
      .accessibilityText('切换当前模型')

      Blank()

      // 右：[ ✦ 新话题 ] + [ 💭 思考强度 ] + [ 上下文环 ]
      Row({ space: 6 }) {
        Row({ space: 3 }) {
          Icon({ name: 'spark', iconSize: 12, color: this.accentColorStore })
          Text('新话题')
            .fontSize(12)
            .fontFamily(FONT_SANS)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.accentColorStore)
        }
        .height(34)
        .padding({ left: 10, right: 10 })
        .borderRadius(999)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
        .alignItems(VerticalAlign.Center)
        .onClick((): void => {
          this.onNewTopic();
        })
        .accessibilityText('新话题分段')

        if (this.modelContext.reasoningLevels.length > 0) {
          Row({ space: 3 }) {
            Icon({ name: 'brain', iconSize: 12, color: INK2 })
            Text(this.reasoningLabel())
              .fontSize(11.5)
              .fontFamily(FONT_SANS)
              .fontColor(INK2)
          }
          .height(34)
          .padding({ left: 9, right: 9 })
          .borderRadius(999)
          .backgroundColor(SURFACE2)
          .border({ width: 0.75, color: LINE })
          .alignItems(VerticalAlign.Center)
          .onClick((): void => {
            this.cycleReasoningLevel();
          })
          .accessibilityText('切换思考强度')
        }

        Row({ space: 4 }) {
          Column()
            .width(8)
            .height(8)
            .borderRadius(999)
            .border({ width: 2, color: this.modelContext.contextRingColor() })
          Text(this.contextPercentLabel())
            .fontSize(11)
            .fontFamily(FONT_MONO)
            .fontColor(INK3)
        }
        .height(34)
        .padding({ left: 8, right: 9 })
        .borderRadius(999)
        .backgroundColor(SURFACE2)
        .border({ width: 0.75, color: LINE })
        .alignItems(VerticalAlign.Center)
        .onClick((): void => {
          this.modelContext.contextMeterOpen = !this.modelContext.contextMeterOpen;
        })
        .accessibilityText('查看上下文用量')
      }
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height(38)
    .alignItems(VerticalAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: 150, curve: Curve.EaseOut }))
  }

  build(): void {
    Column() {
      if (this.inputFocused || this.modelContext.modelMenuOpen || this.modelContext.contextMeterOpen) {
        this.FocusedComposerBar()
      } else {
        this.RestingSwitcherRow()
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 20, right: 20 })
  }
}
