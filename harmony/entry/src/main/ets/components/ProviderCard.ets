// ProviderCard — Provider 列表卡片(对齐 Android ProviderCard.kt)
// 一行式卡片:名称 + baseUrl 预览 + 模型计数徽章 + 启用开关 + chevron + 删除
// 整卡可点(进入配置);开关与删除独立点击,不冒泡到卡片
//
// 设计 token:见 design/tokens.ets(SURFACE/LINE/INK 系)
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;@Prop 避免保留字;
//   ForEach/@Builder 内不出现 const/let 声明(UI 树优先)
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL,
} from '../design/tokens.ets';
import { AmberSwitch, SwitchSize } from './ui/Switch.ets';

@Component
export struct ProviderCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop providerName: string = '';
  @Prop baseUrl: string = '';
  @Prop modelCount: number = 0;
  @Prop providerEnabled: boolean = false;
  // 回调(避免与 CustomComponent 基类的 enabled/onClick 保留字冲突)
  onCardClick: () => void = (): void => {};
  onToggle: (next: boolean) => void = (_n: boolean): void => {};
  onDelete: () => void = (): void => {};

  // baseUrl 仅显示 host(去掉 scheme 与 path,降低视觉噪音)
  private hostPreview(): string {
    const raw: string = this.baseUrl.trim();
    if (raw.length === 0) return '未配置 Base URL';
    let rest: string = raw;
    if (rest.startsWith('https://')) {
      rest = rest.substring(8);
    } else if (rest.startsWith('http://')) {
      rest = rest.substring(7);
    }
    const slash: number = rest.indexOf('/');
    if (slash >= 0) rest = rest.substring(0, slash);
    return rest.length > 0 ? rest : raw;
  }

  // 模型计数徽章:0 时弱化(INK4),>0 时 ACCENT_LIGHT 背景
  @Builder countBadge(): void {
    Text(`${this.modelCount} 模型`)
      .fontSize(LABEL)
      .fontColor(this.modelCount > 0 ? ACCENT : INK4)
      .padding({ left: 8, right: 8, top: 2, bottom: 2 })
      .backgroundColor(this.modelCount > 0 ? '#FFF5F0' : SURFACE2)
      .borderRadius(50)
      .maxLines(1)
  }

  build(): void {
    Row() {
      // 左:启用状态色条(1.5dp 宽,启用=ACCENT,停用=LINE)
      Column()
        .width(3)
        .height(48)
        .backgroundColor(this.providerEnabled ? ACCENT : LINE)
        .borderRadius(2)
        .margin({ right: 12 })

      // 中:名称 + baseUrl 预览 + 模型计数
      Column() {
        Row() {
          Text(this.providerName.length > 0 ? this.providerName : '未命名 Provider')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
          this.countBadge()
        }
        .width('100%')

        Text(this.hostPreview())
          .fontSize(META)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      // 右:启用开关(独立点击)
      AmberSwitch({
        checked: this.providerEnabled,
        switchSize: SwitchSize.Small,
        onCheckedChange: (next: boolean): void => { this.onToggle(next); },
      })
        .margin({ left: 10 })

      // chevron(整卡点击进入配置的视觉提示)
      Text('›')
        .fontSize(20)
        .fontColor(INK4)
        .margin({ left: 6 })

      // 删除按钮(独立点击)
      Text('删除')
        .fontSize(META)
        .fontColor('#C0392B')
        .padding({ left: 8, right: 4, top: 8, bottom: 8 })
        .onClick((): void => { this.onDelete(); })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 14, right: 10, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .borderRadius(14)
    .border({ width: 1, color: LINE })
    .alignItems(VerticalAlign.Center)
    // 整卡点击进入配置(开关与删除在子节点已 stopPropagation 等价:
    //   ArkUI 子节点 onClick 消费事件后不冒泡到父级 onClick)
    .onClick((): void => { this.onCardClick(); })
  }
}
