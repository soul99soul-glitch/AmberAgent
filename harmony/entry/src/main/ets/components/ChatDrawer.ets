// ChatDrawer — chat navigation drawer aligned with Android ChatDrawer.
// Harmony uses a root Stack overlay instead of ModalNavigationDrawer.
import { INK, INK2, INK3, SURFACE, BG, LINE, ACCENT, BODY, OVERLAY, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { WorkspaceSearchField } from './ui/WorkspaceSearchField.ets';
import { UIAvatar } from './UIAvatar.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatDrawer {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop show: boolean = false;
  @Prop userNickname: string = '用户';
  @Prop userAvatar: string = '';
  onNewChat: () => void = (): void => {};
  onOpenBoard: () => void = (): void => {};
  onOpenSettings: () => void = (): void => {};
  onSearchText: (text: string) => void = (_text: string): void => {};
  onOpenApps: () => void = (): void => {};
  onDismiss: () => void = (): void => {};
  onEditNickname: (name: string) => void = (_name: string): void => {};
  @BuilderParam conversationList: () => void;

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Row() {
        Column() {
          Text('Amber')
            .fontSize(30)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .fontFamily(FONT_SERIF)
            .width('100%')
            .padding({ left: 16, right: 16, top: this.statusBarHeight + 12, bottom: 8 })

          WorkspaceSearchField({
            placeholder: '搜索聊天',
            onSearch: (text: string): void => { this.onSearchText(text); },
          })
            .margin({ left: 16, right: 16, bottom: 8 })

          Column() {
            this.NavRow('plus', '新对话', true, this.onNewChat)
            this.NavRow('book', '今日看板', false, this.onOpenBoard)
            this.NavRow('box', '小应用', false, this.onOpenApps)
          }
          .width('100%')
          .padding({ left: 16, right: 16 })

          Divider()
            .color(LINE)
            .strokeWidth(0.5)
            .margin({ left: 16, right: 16, top: 10, bottom: 8 })

          Row() {
            Icon({ name: 'history', iconSize: 14, color: ACCENT })
              .margin({ right: 6 })
            Text('最近')
              .fontSize(BODY)
              .fontWeight(FontWeight.Medium)
              .fontColor(ACCENT)
          }
          .width('100%')
          .padding({ left: 16, bottom: 4 })

          Column() {
            this.conversationList()
          }
          .width('100%')
          .layoutWeight(1)

          Divider().color(LINE).strokeWidth(0.5)
          Row() {
            Row() {
              UIAvatar({
                name: this.userNickname,
                imageUrl: this.userAvatar,
                avatarSize: 32,
                accentColor: ACCENT,
              })
              Text(this.userNickname.length > 0 ? this.userNickname : '用户')
                .fontSize(BODY)
                .fontColor(INK)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
                .margin({ left: 12 })
            }
            .layoutWeight(1)
            .height(48)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.onEditNickname(this.userNickname); })

            Row() {
              Icon({ name: 'gear', iconSize: 18, color: INK3 })
            }
            .width(44)
            .height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .accessibilityText('打开设置')
            .onClick((): void => { this.onOpenSettings(); })
          }
          .width('100%')
          .padding({ left: 16, right: 8, top: 4, bottom: this.navBarHeight + 8 })
          .alignItems(VerticalAlign.Center)
        }
        // Android 基准 336dp;小屏保持 min(336,86%) 保护。
        .width(336)
        .constraintSize({ maxWidth: '86%' })
        .height('100%')
        .backgroundColor(SURFACE)
        .borderRadius({ topRight: 24, bottomRight: 24 })
        .clip(true)
        .transition(TransitionEffect.translate({ x: -336 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))

        Column()
          .layoutWeight(1)
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onDismiss(); })
          .transition(TransitionEffect.OPACITY
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%')
      .height('100%')
      .backgroundColor(Color.Transparent)
    }
  }

  @Builder
  NavRow(icon: string, label: string, accent: boolean, onClick: () => void): void {
    Row() {
      Icon({ name: icon, iconSize: 18, color: accent ? ACCENT : INK2 })
        .margin({ right: 14 })
      Text(label)
        .fontSize(BODY)
        .fontWeight(accent ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(accent ? ACCENT : INK)
    }
    .width('100%')
    .height(46)
    .padding({ left: 11, right: 11 })
    .borderRadius(8)
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      onClick();
      this.onDismiss();
    })
  }
}
