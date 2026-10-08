// TopModelMenu — 顶部模型快速切换菜单(第二梯队)
// Android 基准: feature/ui/components/ai/TopModelMenu.kt(275 行)
//   "roller/accordion" 下拉:provider 分组(可折叠)+ 组内 model 行,
//   当前 provider 自动展开,选中态仅用 accent 色(无加粗/对勾/signal 绿)。
//   结构:Box { scrim(点击=onClose); AnimatedVisibility(panel) }。无 ViewModel 注入。
//
// 鸿蒙对应:
//   - 组件自含全屏 dimmed scrim(40% 黑(统一 OVERLAY) ≈ Android sheetBackdrop)+ 贴顶面板
//     (对齐 Android scrim 由组件内部拥有,非父级)
//   - 选择层非 Uuid:currentProviderId/modelId 为字符串(provider.id + model.modelId)
//   - groups 由域纯逻辑 buildTopModelMenuGroups() 预装配(过滤 hasUsableAuth + ModelType)
//   - 组件每次打开由父级条件渲染重建 → aboutToAppear 初始化展开集合,等价
//     Android LaunchedEffect(open, currentProviderId) 的重置语义
//
// 不做(明确边界):
//   - 动画曲线精确复刻(Android CubicBezierEasing 340ms)— 设备验证动画手感项
//   - icon 资源 — 用文字箭头 ▾/▸(对齐 ToggleSurface 既有惯例)
import type {
  TopModelMenuGroup,
  TopModelMenuModel,
  ReasoningLevelOption,
} from '@amber/chat-domain';
import { ReasoningPicker } from './ReasoningPicker.ets';
import {
  formatNumberInt, formatContextTokens,
} from '@amber/chat-domain';
import { MOTION_OVERLAY, ACCENT, BODY, HEADER, INK, INK2, INK3, INK4, LABEL, LINE, META, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';

@Component
export struct TopModelMenu {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  // 预装配数据:每 provider 一组,组内仅目标类型模型(调用方决定 type,通常 chat)
  // 宿主实测的顶栏下边界，已包含状态栏和内边距。
  @Prop topInset: number = 0;
  @Prop contextUsedTokens: number = 0;
  @Prop contextWindowTokens: number | null = null;
  @Prop contextColor: string = INK3;
  @Prop contextCompactActive: boolean = false;
  @Prop turnTotal: string = '—';
  @Prop turnCache: string = '—';
  @Prop turnSpeed: string = '—';
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
  @StorageProp('darkMode') darkMode: boolean = false;

  aboutToAppear(): void {
    if (this.currentProviderId.length > 0) {
      this.expandedProviderIds = [this.currentProviderId];
      return;
    }
    // 无当前选中 → 展开首组(若有)
    if (this.groups.length > 0) {
      this.expandedProviderIds = [this.groups[0].providerId];
    }
  }

  private isExpanded(providerId: string): boolean {
    return this.expandedProviderIds.indexOf(providerId) >= 0;
  }

  // 切换某 provider 的展开态(不依赖原数组,重建新数组 → 触发 @State 更新)
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
    // 全屏遮罩 + 面板(对齐 Android TopModelMenu.kt:Box { scrim; AnimatedVisibility(panel) })
    //   scrim:40% 黑(统一 OVERLAY)(Android sheetBackdrop ~0.28 alpha),点击 = onClose
    //   面板:贴顶,SURFACE2 底 + 底部 hairline, maxHeight 420 可滚
    Column() {
      // 顶部留白(头部高度, 透明+不拦截点击 → 头部仍可见可点, 对齐参考图面板在头部下方)
      Column()
        .width('100%')
        .height(this.topInset)
        .onClick((): void => { this.onClose(); })
        .accessibilityText('收起模型与服务商')
      Column() {
      // 固定上下文和底部思考等级，服务商/模型列表独立滚动。
      Column() {
        this.ContextUsageBuilder()
        Divider().color(LINE).strokeWidth(0.5)
        Scroll() {
          Column() {
          if (this.groups.length === 0) {
            // 空态(对齐 Android R.string.model_list_no_providers)
            Column() {
              Text('没有可用的 AI Provider')
                .fontSize(BODY).fontColor(INK2)
              Text('请在设置中添加 baseUrl、API Key 和模型')
                .fontSize(META).fontColor(INK3).margin({ top: 4 })
              Text('去 Provider 设置')
                .fontSize(BODY).fontColor(this.accentColorStore).margin({ top: 12 })
                .onClick((): void => { this.onOpenProviderSettings(); })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 20, bottom: 20 })
            .alignItems(HorizontalAlign.Start)
          } else {
            ForEach(this.groups, (group: TopModelMenuGroup): void => {
              this.ProviderGroupBuilder(group)
            }, (group: TopModelMenuGroup): string => group.providerId)
          }
          }
          .width('100%')
        }
        .width('100%').align(Alignment.Top)
        .constraintSize({ maxHeight: 420 })
          // 思考等级段集(模型感知;点等级保持菜单开)
          if (this.reasoningLevels.length > 1) {
            Divider().color(LINE).strokeWidth(0.5)
            Text('思考等级').fontSize(META).fontColor(INK3).width('100%')
              .padding({ left: 16, right: 16, top: 10 })
            ReasoningPicker({
              levels: this.reasoningLevels,
              currentLevel: this.currentReasoningLevel,
              onReasoningLevelChange: (level: string): void => { this.onReasoningLevelChange(level); },
            })
          }
          // 底部「全部模型 / 管理」入口(全页选择器,既有 ChatModelPickerPage)
          Divider().color(LINE).strokeWidth(0.5)
          Row() {
            Text('全部模型 / 管理')
              .fontSize(META).fontColor(this.accentColorStore).layoutWeight(1)
            Text('▸').fontSize(LABEL).fontColor(INK4)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .onClick((): void => { this.onOpenFullPicker(); })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .onClick((): void => {})
      .transition(
        TransitionEffect.translate({ y: '-100%' })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }),
      )
      // 灰色 scrim:占满剩余高度(头部下方),点击 = 关闭(对齐参考图)
      Column()
        .width('100%')
        .layoutWeight(1)
        .backgroundColor(OVERLAY)
        .onClick((): void => { this.onClose(); })
        .transition(TransitionEffect.OPACITY
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').layoutWeight(1).clip(true).transition(TransitionEffect.IDENTITY)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    // 官方父子转场示例：保留根节点至子面板滑出完成，避免默认整层淡出。
    .transition(TransitionEffect.opacity(0.99).animation({ duration: MOTION_OVERLAY }))
  }

  @Builder ContextUsageBuilder(): void {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text('上下文占用量').fontSize(META).fontColor(INK2)
        Stack({ alignContent: Alignment.Start }) {
          Column().width('100%').height(3).backgroundColor(LINE).borderRadius(2)
          Column().height(3).backgroundColor(this.contextColor).borderRadius(2)
            .width(`${this.contextWindowTokens !== null && this.contextWindowTokens > 0
              ? Math.min(1, this.contextUsedTokens / this.contextWindowTokens) * 100 : 0}%`)
        }
        .layoutWeight(1).height(3)
        Text(`${formatContextTokens(this.contextUsedTokens)} / ${this.contextWindowTokens !== null
          ? formatContextTokens(this.contextWindowTokens) : '--'}`)
          .fontSize(META).fontColor(INK).fontFamily('JetBrainsMono')
      }
      .width('100%')
      Text(this.contextCompactActive ? '自动压缩中'
        : `本次 ${this.turnTotal} · 缓存命中 ${this.turnCache} · ${this.turnSpeed}`)
        .fontSize(LABEL).fontColor(INK3).width('100%')
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
  }

  @Builder ProviderGroupBuilder(group: TopModelMenuGroup): void {
    Column() {
      // 分组 header(1:1 参考图): provider 名(mono) + 「+/−」
      Row() {
        Text(group.providerName)
          .fontSize(15).fontFamily('JetBrainsMono')
          .fontColor(this.isExpanded(group.providerId) ? this.accentColorStore : INK2)
          .layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.isExpanded(group.providerId) ? '−' : '+')
          .fontSize(18).fontColor(INK4)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 14, bottom: 14 })
      .onClick((): void => {
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.toggleExpand(group.providerId);
        });
      })

      if (this.isExpanded(group.providerId)) {
        Column() {
          ForEach(group.models, (model: TopModelMenuModel): void => {
            Row() {
              Text(model.displayName.length > 0 ? model.displayName : model.modelId)
                .fontSize(14).fontFamily('JetBrainsMono')
                .fontColor(model.selected ? this.accentColorStore : INK3).layoutWeight(1)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              if (model.contextWindowTokens !== null) {
                Text(formatNumberInt(model.contextWindowTokens))
                  .fontSize(LABEL).fontColor(INK4)
                  .margin({ left: 8 })
              }
            }
            .width('100%')
            .padding({ left: 36, right: 16, top: 12, bottom: 12 })
            .onClick((): void => { this.onSelect(group.providerId, model.modelId); })
          }, (model: TopModelMenuModel): string => `${group.providerId}/${model.modelId}`)
        }
        .width('100%')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
    .clip(true)
  }
}
