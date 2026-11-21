// TopModelMenu — 底部悬浮模型与服务商选择面板
// 对齐 iOS ComposerModelSheet (.amberFloatingSheet(detents: [.medium, .large])):
//   - 宿主 ChatModelMenuOverlay 通过 .bindSheet(..., { detents: [SheetSize.MEDIUM, SheetSize.LARGE], showClose: false, dragBar: true, backgroundColor: BG }) 自下而上弹出
//   - 顶部固定标题栏（选择模型 + 右上角 34×34 圆形关闭钮），正文独立滚动
//   - 单张圆角分组卡承载 Provider 手风琴与缩进模型行（当前 Provider 自动展开、选中态强调色 + 对勾 + 上下文窗令牌）
//   - 支持思考等级（ReasoningPicker）与底部「全部模型 / 管理」入口
import { inputMethod } from '@kit.IMEKit';
import type {
  TopModelMenuGroup,
  TopModelMenuModel,
  ReasoningLevelOption,
} from '@amber/chat-domain';
import { formatContextTokens } from '@amber/chat-domain';
import { ReasoningPicker } from './ReasoningPicker.ets';
import { Icon } from './ui/Icon.ets';
import {
  MOTION_OVERLAY, ACCENT, BG, BODY, INK, INK2, INK3, INK4, LABEL, LINE, META, SURFACE, SURFACE2,
} from '../design/tokens.ets';

@Component
export struct TopModelMenu {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop groups: TopModelMenuGroup[] = [];
  // 当前选中(provider.id + model.modelId 字符串);空串 = 无选中
  @Prop currentProviderId: string = '';
  @Prop currentModelId: string = '';
  // 思考等级段集(off/high/max;空/单档 → ReasoningPicker 不渲染)。
  //   点模型即关(父级 onSelect 关菜单);点等级保持开(仅回调,不关)。
  @Prop reasoningLevels: ReasoningLevelOption[] = [];
  @Prop currentReasoningLevel: string = '';
  onReasoningLevelChange: (level: string) => void = (_l: string): void => {};
  // 选择回调(modelId 为人类可读字符串,与 saveChatModelSelection 契约一致)
  onSelect: (providerId: string, modelId: string) => void = (_p: string, _m: string): void => {};
  onClose: () => void = (): void => {};
  onOpenFullPicker: () => void = (): void => {};
  onOpenProviderSettings: () => void = (): void => {};

  // 展开的 provider 集合(本地态;aboutToAppear 初始化 = 当前 provider,不在则首组)
  @State expandedProviderIds: string[] = [];

  aboutToAppear(): void {
    // 对齐 iOS openComposerModelSheet：打开底部浮层前收起软键盘，避免面板与键盘争夺高度
    try {
      inputMethod.getController().stopInputSession().catch((_e: Error): void => {});
    } catch (_e) {}

    if (this.currentProviderId.length > 0) {
      this.expandedProviderIds = [this.currentProviderId];
      return;
    }
    if (this.groups.length > 0) {
      this.expandedProviderIds = [this.groups[0].providerId];
    }
  }

  private isExpanded(providerId: string): boolean {
    return this.expandedProviderIds.indexOf(providerId) >= 0;
  }

  private toggleExpand(providerId: string): void {
    if (this.isExpanded(providerId)) {
      const next: string[] = [];
      for (const id of this.expandedProviderIds) {
        if (id !== providerId) next.push(id);
      }
      this.expandedProviderIds = next;
    } else {
      const next: string[] = [];
      for (const id of this.expandedProviderIds) next.push(id);
      next.push(providerId);
      this.expandedProviderIds = next;
    }
  }

  build(): void {
    Column() {
      // 顶栏（对齐 iOS ComposerModelSheet：左标题 + 右圆形关闭钮；宿主 bindSheet 已设 showClose: false）
      Row() {
        Text('选择模型')
          .fontSize(17)
          .fontWeight(600)
          .fontColor(INK)
          .layoutWeight(1)
        Row() {
          Icon({ name: 'close', iconSize: 13, color: INK2 })
        }
        .width(34)
        .height(34)
        .borderRadius(17)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText('关闭模型选择')
        .onClick((): void => { this.onClose(); })
      }
      .width('100%')
      .padding({ left: 20, right: 16, top: 8, bottom: 12 })
      .alignItems(VerticalAlign.Center)

      Divider().color(LINE).strokeWidth(0.5)

      Scroll() {
        Column({ space: 14 }) {
          if (this.groups.length === 0) {
            Column() {
              Text('没有可用的 AI Provider')
                .fontSize(BODY)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK)
              Text('请在设置中添加 baseUrl、API Key 和模型')
                .fontSize(META)
                .fontColor(INK3)
                .margin({ top: 6 })
              Text('去 Provider 设置')
                .fontSize(META)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .margin({ top: 14 })
                .backgroundColor(SURFACE2)
                .borderRadius(999)
                .border({ width: 0.75, color: LINE })
                .onClick((): void => { this.onOpenProviderSettings(); })
            }
            .width('100%')
            .padding(20)
            .backgroundColor(SURFACE)
            .borderRadius(18)
            .border({ width: 0.75, color: LINE })
            .alignItems(HorizontalAlign.Start)
          } else {
            Column() {
              ForEach(this.groups, (group: TopModelMenuGroup, index: number): void => {
                if (index > 0) {
                  Divider().color(LINE).strokeWidth(0.5)
                }
                this.ProviderGroupBuilder(group)
              }, (group: TopModelMenuGroup): string => group.providerId)
            }
            .width('100%')
            .backgroundColor(SURFACE)
            .borderRadius(18)
            .border({ width: 0.75, color: LINE })
            .clip(true)
          }

          if (this.reasoningLevels.length > 1) {
            Column() {
              Text('思考等级')
                .fontSize(META)
                .fontColor(INK3)
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 4 })
              ReasoningPicker({
                levels: this.reasoningLevels,
                currentLevel: this.currentReasoningLevel,
                onReasoningLevelChange: (level: string): void => { this.onReasoningLevelChange(level); },
              })
            }
            .width('100%')
            .padding({ bottom: 4 })
            .backgroundColor(SURFACE)
            .borderRadius(18)
            .border({ width: 0.75, color: LINE })
          }

          Row() {
            Text('全部模型 / 管理')
              .fontSize(14)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.accentColorStore)
              .layoutWeight(1)
            Icon({ name: 'chevron_right', iconSize: 14, color: INK4 })
          }
          .width('100%')
          .height(48)
          .padding({ left: 16, right: 16 })
          .backgroundColor(SURFACE)
          .borderRadius(16)
          .border({ width: 0.75, color: LINE })
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { this.onOpenFullPicker(); })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 14, bottom: 28 })
      }
      .width('100%')
      .layoutWeight(1)
      .align(Alignment.Top)
      .scrollBar(BarState.Off)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }

  @Builder ProviderGroupBuilder(group: TopModelMenuGroup): void {
    Column() {
      Row() {
        Text(group.providerName)
          .fontSize(15)
          .fontWeight(group.providerId === this.currentProviderId ? FontWeight.Medium : FontWeight.Regular)
          .fontColor(group.providerId === this.currentProviderId ? this.accentColorStore : INK)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Icon({
          name: 'chevron_down',
          iconSize: 13,
          color: this.isExpanded(group.providerId) ? INK2 : INK4,
        })
          .rotate({ angle: this.isExpanded(group.providerId) ? 180 : 0 })
      }
      .width('100%')
      .height(50)
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => {
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.toggleExpand(group.providerId);
        });
      })

      if (this.isExpanded(group.providerId)) {
        Column() {
          ForEach(group.models, (model: TopModelMenuModel): void => {
            Divider().color(LINE).strokeWidth(0.5).margin({ left: 16 })
            Row() {
              Text(model.displayName.length > 0 ? model.displayName : model.modelId)
                .fontSize(14.5)
                .fontWeight(model.selected ? FontWeight.Medium : FontWeight.Regular)
                .fontColor(model.selected ? this.accentColorStore : INK2)
                .layoutWeight(1)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              if (model.contextWindowTokens !== null) {
                Text(formatContextTokens(model.contextWindowTokens))
                  .fontSize(LABEL)
                  .fontColor(INK4)
                  .fontFamily('JetBrainsMono')
                  .margin({ left: 8 })
              }
              if (model.selected) {
                Icon({ name: 'check', iconSize: 14, color: this.accentColorStore })
                  .margin({ left: 8 })
              }
            }
            .width('100%')
            .constraintSize({ minHeight: 46 })
            .padding({ left: 26, right: 16, top: 11, bottom: 11 })
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.onSelect(group.providerId, model.modelId); })
          }, (model: TopModelMenuModel): string => `${group.providerId}/${model.modelId}`)
        }
        .width('100%')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -6 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
    .clip(true)
  }
}
