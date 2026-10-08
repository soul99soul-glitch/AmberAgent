import type { ConversationRecap, ConversationRecapNode, RecapNodeKind } from '@amber/chat-domain';
import { Icon } from './ui/Icon.ets';
import {
  ACCENT, BODY, HEADER, INK, INK2, INK3, LABEL, LINE, META, MOTION_OVERLAY,
  SURFACE, SURFACE2, ERROR, ERROR_BG, INFO_FG, SUCCESS_FG, WARNING_BG, WARNING_FG,
  accentSoftBg,
} from '../design/tokens.ets';

@Component
export struct ChatRecapPanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop recap: ConversationRecap | null = null;
  @Prop loading: boolean = false;
  @Prop error: string = '';
  @Prop stale: boolean = false;
  @Prop topInset: number = 80;
  onClose: () => void = (): void => {};
  onRefresh: () => void = (): void => {};
  onLocate: (node: ConversationRecapNode) => void = (_node: ConversationRecapNode): void => {};
  onNextStep: (text: string) => void = (_text: string): void => {};

  private nodeLabel(kind: RecapNodeKind): string {
    if (kind === 'decision') return '决策';
    if (kind === 'milestone') return '里程碑';
    if (kind === 'failure') return '失败';
    return '产物';
  }

  private nodeColor(kind: RecapNodeKind): string {
    if (kind === 'decision') return this.accentColorStore;
    if (kind === 'milestone') return SUCCESS_FG;
    if (kind === 'failure') return ERROR;
    return INFO_FG;
  }

  @Builder
  StatusContent(): void {
    if (this.loading) {
      Row({ space: 8 }) {
        LoadingProgress().width(18).height(18).color(this.accentColorStore)
        Text(this.recap === null ? '正在整理对话回顾…' : '正在更新回顾…')
          .fontSize(META).fontColor(INK2).layoutWeight(1)
      }
      .width('100%').padding({ top: 8, bottom: 12 })
    }
    if (this.error.length > 0) {
      Column({ space: 8 }) {
        Text(this.error).fontSize(META).fontColor(ERROR).lineHeight(18).width('100%')
        Row({ space: 6 }) {
          Icon({ name: 'retry', iconSize: 16, color: this.accentColorStore })
          Text('重试').fontSize(META).fontColor(this.accentColorStore)
        }
        .height(44).padding({ left: 12, right: 12 })
        .enabled(!this.loading).opacity(this.loading ? 0.45 : 1)
        .onClick((): void => { this.onRefresh(); })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      .padding(12).backgroundColor(ERROR_BG).borderRadius(12).margin({ bottom: 12 })
    }
    if (this.stale && this.recap !== null && !this.loading) {
      Text('对话已更新，点击刷新获取最新回顾。')
        .fontSize(META).fontColor(WARNING_FG).lineHeight(18).width('100%')
        .padding(12).backgroundColor(WARNING_BG).borderRadius(12).margin({ bottom: 12 })
    }
    if (this.recap === null && !this.loading && this.error.length === 0) {
      Text('尚未生成回顾，点击刷新整理这段对话。')
        .fontSize(BODY).fontColor(INK2).lineHeight(22).width('100%')
        .padding({ top: 16, bottom: 16 })
    }
  }

  @Builder
  NodeContent(node: ConversationRecapNode): void {
    Column({ space: 8 }) {
      Row({ space: 6 }) {
        Circle().width(6).height(6).fill(this.nodeColor(node.kind))
        Text(this.nodeLabel(node.kind)).fontSize(LABEL).fontColor(this.nodeColor(node.kind))
      }
      .width('100%')
      Text(node.title).fontSize(BODY).fontColor(INK).lineHeight(22).width('100%')
      if (node.messageId === null) {
        Text('原消息不在当前分支').fontSize(META).fontColor(INK3).width('100%')
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(14).backgroundColor(SURFACE).borderRadius(12)
    .onClick((): void => {
      if (node.messageId !== null) this.onLocate(node);
    })
  }

  @Builder
  RecapContent(recap: ConversationRecap): void {
    Text('概览').fontSize(META).fontColor(INK3).margin({ bottom: 8 })
    Text(recap.overview).fontSize(BODY).fontColor(INK).lineHeight(22).width('100%')
      .margin({ bottom: 20 })
    if (recap.nodes.length > 0) {
      Text('关键节点').fontSize(META).fontColor(INK3).margin({ bottom: 8 })
      Column({ space: 10 }) {
        ForEach(recap.nodes, (node: ConversationRecapNode): void => {
          this.NodeContent(node)
        }, (node: ConversationRecapNode, index: number): string => `${index}:${node.messageRef}:${node.title}`)
      }
      .width('100%').margin({ bottom: 20 })
    }
    if (recap.nextSteps.length > 0) {
      Text('下一步').fontSize(META).fontColor(INK3).margin({ bottom: 4 })
      Text('点击填入输入框').fontSize(META).fontColor(INK3).margin({ bottom: 8 })
      Column({ space: 8 }) {
        ForEach(recap.nextSteps, (text: string): void => {
          Row({ space: 10 }) {
            Text(text).fontSize(BODY).fontColor(INK).lineHeight(22).layoutWeight(1)
            Icon({ name: 'edit', iconSize: 16, color: this.accentColorStore })
          }
          .width('100%').constraintSize({ minHeight: 44 })
          .padding(14).backgroundColor(accentSoftBg(this.accentColorStore)).borderRadius(12)
          .onClick((): void => { this.onNextStep(text); })
        }, (text: string, index: number): string => `${index}:${text}`)
      }
      .width('100%')
    }
  }

  build(): void {
    Stack({ alignContent: Alignment.Top }) {
      Column()
        .width('100%').height('100%').backgroundColor('#14000000')
        .onClick((): void => { this.onClose(); })
        .transition(TransitionEffect.OPACITY
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      Row() {
        Column() {
          Row() {
            Icon({ name: 'history', iconSize: 18, color: this.accentColorStore })
            Text('对话回顾').fontSize(HEADER).fontWeight(FontWeight.Bold).fontColor(INK)
              .layoutWeight(1).margin({ left: 8 })
            Row() {
              Icon({ name: 'retry', iconSize: 18, color: this.loading ? INK3 : this.accentColorStore })
            }
            .width(44).height(44).justifyContent(FlexAlign.Center)
            .enabled(!this.loading).onClick((): void => { this.onRefresh(); })
            .accessibilityText('刷新回顾')
            Row() {
              Icon({ name: 'close', iconSize: 18, color: INK2 })
            }
            .width(44).height(44).justifyContent(FlexAlign.Center)
            .onClick((): void => { this.onClose(); }).accessibilityText('关闭回顾')
          }
          .width('100%').padding({ left: 16, right: 4, top: 8, bottom: 8 })
          Divider().color(LINE).strokeWidth(0.5)
          Scroll() {
            Column() {
              this.StatusContent()
              if (this.recap !== null) {
                this.RecapContent(this.recap)
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(16)
          }
          .width('100%').layoutWeight(1).align(Alignment.Top).scrollBar(BarState.Auto)
        }
        .width('100%').height('100%')
        .backgroundColor(SURFACE2).borderRadius(20).border({ width: 1, color: LINE }).clip(true)
        .onClick((): void => {})
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.scale({ x: 0.96, y: 0.96, centerX: '50%', centerY: '0%' }))
          .combine(TransitionEffect.translate({ y: -3 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('65%').constraintSize({ maxHeight: 520 })
      .padding({ left: 20, right: 20 }).margin({ top: this.topInset })
    }
    .width('100%').height('100%').opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
