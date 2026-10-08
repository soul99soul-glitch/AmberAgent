// NovelApprovalShell — 审批/提案卡统一容器(复刻 iOS NovelCreation 审批卡规格):
//   容器:r18 + accent 18% alpha 0.75pt 描边(accentEdge)+ SURFACE 底 + 16 内边距
//   头:36 accent 圆座图标 + 标题 15 semibold + 副标题 12 muted + 可选状态 Label(图标+色字)
//   体:content 槽;底:可选 actions 槽(按钮三档:主=accent 实底胶囊/次=accentSoftBg/弱=SURFACE2)
// 状态 tone:success 绿 / error 红 / warn 琥珀 / accent / muted(iOS Label 形态,无 chip 底)

import {
  ACCENT, INK, INK3, ERROR, SUCCESS_FG, WARNING_FG, SURFACE, accentSoftBg, accentEdge,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct NovelApprovalShell {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop iconName: string = 'info';
  @Prop title: string = '';
  @Prop subtitle: string = '';
  // 状态 Label(右置):check=success / ban|retry=error / warn / info=muted / 其余=accent
  @Prop statusLabel: string = '';
  @Prop statusIcon: string = '';
  @Prop statusTone: string = 'muted';
  // @BuilderParam 无法探测空槽:有动作区的卡显式传 true
  @Prop showActions: boolean = false;
  @BuilderParam content: () => void = this.emptySlot;
  @BuilderParam actions: () => void = this.emptySlot;

  @Builder
  emptySlot(): void {
  }

  private statusColor(): string {
    if (this.statusTone === 'success') return SUCCESS_FG;
    if (this.statusTone === 'error') return ERROR;
    if (this.statusTone === 'warn') return WARNING_FG;
    if (this.statusTone === 'accent') return this.accentColorStore;
    return INK3;
  }

  build(): void {
    Column() {
      Row() {
        Row() {
          Icon({ name: this.iconName, iconSize: 17, color: this.accentColorStore })
        }
        .width(36).height(36).borderRadius(18)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        Column() {
          if (this.title.length > 0) {
            Text(this.title).fontSize(15).fontWeight(FontWeight.Medium)
              .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          if (this.subtitle.length > 0) {
            Text(this.subtitle).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
        if (this.statusLabel.length > 0) {
          Row() {
            if (this.statusIcon.length > 0) {
              Icon({ name: this.statusIcon, iconSize: 14, color: this.statusColor() })
            }
            Text(this.statusLabel).fontSize(12).fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.statusColor()).margin({ left: 5 })
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(VerticalAlign.Center).margin({ left: 8 })
        }
      }
      .width('100%').alignItems(VerticalAlign.Center)
      Column() {
        this.content()
      }
      .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 12 })
      if (this.showActions) {
        Column() {
          this.actions()
        }
        .width('100%').margin({ top: 14 })
      }
    }
    .width('100%').padding(16)
    .backgroundColor(SURFACE)
    .borderRadius(18)
    .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
    .alignItems(HorizontalAlign.Start)
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
