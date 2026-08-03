// TopModelMenu — 顶部模型快速切换菜单(第二梯队)
// Android 基准: feature/ui/components/ai/TopModelMenu.kt(275 行)
//   "roller/accordion" 下拉:provider 分组(可折叠)+ 组内 model 行,
//   当前 provider 自动展开,选中态仅用 accent 色(无加粗/对勾/signal 绿)。
//   结构:Box { scrim(点击=onClose); AnimatedVisibility(panel) }。无 ViewModel 注入。
//
// 鸿蒙对应:
//   - 组件自含全屏 dimmed scrim(28% 黑 ≈ Android sheetBackdrop)+ 贴顶面板
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
} from '@amber/chat-domain';
import {
  formatNumberInt,
} from '@amber/chat-domain';
import {
  INK2, INK3, INK4, ACCENT, SURFACE2, LINE, BODY, META, LABEL,
} from '../design/tokens.ets';

@Component
export struct TopModelMenu {
  // 预装配数据:每 provider 一组,组内仅目标类型模型(调用方决定 type,通常 chat)
  @Prop groups: TopModelMenuGroup[] = [];
  // 当前选中(provider.id + model.modelId 字符串);空串 = 无选中
  @Prop currentProviderId: string = '';
  @Prop currentModelId: string = '';
  // 选择回调(modelId 为人类可读字符串,与 saveChatModelSelection 契约一致)
  onSelect: (providerId: string, modelId: string) => void = (_p: string, _m: string): void => {};
  onClose: () => void = (): void => {};
  onOpenFullPicker: () => void = (): void => {};
  onOpenProviderSettings: () => void = (): void => {};

  // 展开的 provider 集合(本地态;aboutToAppear 初始化 = 当前 provider,不在则首组)
  @State expandedProviderIds: string[] = [];

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
    //   scrim:28% 黑(Android sheetBackdrop ~0.28 alpha),点击 = onClose
    //   面板:贴顶,SURFACE2 底 + 底部 hairline, maxHeight 420 可滚
    Column() {
      // 面板(provider 分组手风琴 + 底部全页入口)
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
                .fontSize(BODY).fontColor(ACCENT).margin({ top: 12 })
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
          // 底部「全部模型 / 管理」入口(全页选择器,既有 ChatModelPickerPage)
          Divider().color(LINE).strokeWidth(0.5)
          Row() {
            Text('全部模型 / 管理')
              .fontSize(META).fontColor(ACCENT).layoutWeight(1)
            Text('▸').fontSize(LABEL).fontColor(INK4)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .onClick((): void => { this.onOpenFullPicker(); })
        }
        .width('100%')
      }
      .align(Alignment.Top)
      .constraintSize({ maxHeight: 420 })
      .backgroundColor(SURFACE2)
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      // 消费面板点击,防穿透到 backdrop(否则点面板空白会误关)
      .onClick((): void => {})
      // 透明 backdrop:占满剩余高度,点击 = 关闭(对齐 Android scrim)
      Column()
        .width('100%')
        .layoutWeight(1)
        .onClick((): void => { this.onClose(); })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#47000000')
  }

  @Builder ProviderGroupBuilder(group: TopModelMenuGroup): void {
    Column() {
      // 分组 header:provider 名 + 展开/收起 glyph
      Row() {
        Text(group.providerName)
          .fontSize(BODY).fontColor(INK2).layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.isExpanded(group.providerId) ? '▾' : '▸')
          .fontSize(LABEL).fontColor(INK4)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .onClick((): void => { this.toggleExpand(group.providerId); })

      if (this.isExpanded(group.providerId)) {
        ForEach(group.models, (model: TopModelMenuModel): void => {
          Row() {
            Text(model.displayName.length > 0 ? model.displayName : model.modelId)
              .fontSize(META).fontColor(model.selected ? ACCENT : INK3).layoutWeight(1)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (model.contextWindowTokens !== null) {
              Text(formatNumberInt(model.contextWindowTokens))
                .fontSize(LABEL).fontColor(INK4)
                .margin({ left: 8 })
            }
          }
          .width('100%')
          .padding({ left: 24, right: 16, top: 9, bottom: 9 })
          // 消费点击防穿透到 backdrop(已选中也允许重选 = 等价 nop,对齐 Android)
          .onClick((): void => { this.onSelect(group.providerId, model.modelId); })
        }, (model: TopModelMenuModel): string => `${group.providerId}/${model.modelId}`)
      }
    }
    .width('100%')
  }
}
