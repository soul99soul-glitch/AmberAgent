// ChatDrawer — Muse 风格精简导航与历史抽屉
// 顶部 Agent 形象头卡（+ 新话题）、中部「任务看板 / 设置 / 最近 5 条会话 + 全部历史」、底部可折叠「更多工具与主题配色」

import {
  ACCENT, ACCENT_INK, BG, BODY, INK, INK2, INK3, LINE, META, MOTION_OVERLAY,
  OVERLAY, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../design/tokens.ets';
import { BUILTIN_PACKS, applyThemePack, resolveThemeDark, themePackStore, type ThemePack } from '../design/theme_packs.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';
import { WorkspaceSearchField } from './ui/WorkspaceSearchField.ets';
import { AmberPersonaMedallion } from './chat/AmberPersonaMedallion.ets';
import { UIAvatar } from './UIAvatar.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatDrawer {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('agentSoulDisplayName') agentSoulDisplayName: string = 'Amber';
  @StorageProp('agentSoulIsDefault') agentSoulIsDefault: boolean = true;
  @Prop show: boolean = false;
  @Prop userNickname: string = '用户';
  @Prop userAvatar: string = '';
  @State private moreToolsExpanded: boolean = false;
  onNewChat: () => void = (): void => {};
  onNewTopic: () => void = (): void => {};
  onOpenPersona: () => void = (): void => {};
  onOpenTasks: () => void = (): void => {};
  onOpenHistory: () => void = (): void => {};
  onOpenSettings: () => void = (): void => {};
  onSearchText: (text: string) => void = (_text: string): void => {};
  onOpenCouncil: () => void = (): void => {};
  onOpenApps: () => void = (): void => {};
  onOpenWebMount: () => void = (): void => {};
  onOpenAppearance: () => void = (): void => {};
  onDismiss: () => void = (): void => {};
  onEditNickname: (name: string) => void = (_name: string): void => {};
  @BuilderParam conversationList: () => void;

  private applyQuickThemePack(pack: ThemePack): void {
    const resolvedDark: boolean = resolveThemeDark(pack, this.darkMode || this.systemDarkMode);
    themePackStore.select(getChatKvStore(), pack).then((): void => {
      applyThemePack(pack, resolvedDark);
      applyWindowTheme(resolvedDark);
    }).catch((): void => {
      applyThemePack(pack, resolvedDark);
      applyWindowTheme(resolvedDark);
    });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Row() {
        Column() {
          // 顶部 Agent 形象头卡（点按进人设，右侧 + 新话题胶囊）
          Row({ space: 10 }) {
            Row({ space: 10 }) {
              AmberPersonaMedallion({
                medallionSize: 44,
                displayName: this.agentSoulDisplayName,
                isDefault: this.agentSoulIsDefault,
                sending: false,
              })
              Column({ space: 2 }) {
                Text(this.agentSoulDisplayName.length > 0 ? this.agentSoulDisplayName : 'Amber')
                  .fontSize(17)
                  .fontWeight(FontWeight.Medium)
                  .fontFamily(FONT_SANS)
                  .fontColor(INK)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                Text('形象与记忆 ›')
                  .fontSize(11.5)
                  .fontColor(INK3)
              }
              .layoutWeight(1)
              .alignItems(HorizontalAlign.Start)
            }
            .layoutWeight(1)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              this.onOpenPersona();
              this.onDismiss();
            })

            Row({ space: 4 }) {
              Icon({ name: 'plus', iconSize: 13, color: ACCENT_INK })
              Text('新话题')
                .fontSize(12)
                .fontWeight(FontWeight.Medium)
                .fontColor(ACCENT_INK)
            }
            .height(32)
            .padding({ left: 10, right: 11 })
            .borderRadius(999)
            .backgroundColor(this.accentColorStore)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              this.onNewTopic();
              this.onDismiss();
            })
            .gesture(
              LongPressGesture({ duration: 420 })
                .onAction((): void => {
                  this.onNewChat();
                  this.onDismiss();
                }),
            )
            .accessibilityText('新话题，长按新建空白会话')
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: this.statusBarHeight + 12, bottom: 10 })
          .alignItems(VerticalAlign.Center)

          WorkspaceSearchField({
            placeholder: '搜索历史对话',
            onSearch: (text: string): void => { this.onSearchText(text); },
          })
            .margin({ left: 16, right: 16, bottom: 8 })

          Column() {
            this.NavRow('ph_listChecks', '任务看板', false, this.onOpenTasks)
            this.NavRow('gear', '设置', false, this.onOpenSettings)
          }
          .width('100%')
          .padding({ left: 16, right: 16 })

          Divider()
            .color(LINE)
            .strokeWidth(0.5)
            .margin({ left: 16, right: 16, top: 8, bottom: 8 })

          Row() {
            Icon({ name: 'history', iconSize: 14, color: this.accentColorStore })
              .margin({ right: 6 })
            Text('最近会话')
              .fontSize(BODY)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.accentColorStore)
            Blank()
            Text('全部历史 ›')
              .fontSize(12)
              .fontColor(INK3)
              .padding({ left: 8, right: 4, top: 4, bottom: 4 })
              .onClick((): void => {
                this.onOpenHistory();
                this.onDismiss();
              })
          }
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 4 })
          .alignItems(VerticalAlign.Center)

          Column() {
            this.conversationList()
          }
          .width('100%')
          .layoutWeight(1)

          // 底部默认折叠「更多工具与主题配色」
          Divider().color(LINE).strokeWidth(0.5)
          Column() {
            Row() {
              Icon({ name: 'ph_palette', iconSize: 16, color: INK2 }).margin({ right: 10 })
              Text('更多工具与主题配色')
                .fontSize(13.5)
                .fontColor(INK2)
                .layoutWeight(1)
              Icon({ name: 'chevron_down', iconSize: 14, color: INK3 })
                .rotate({ angle: this.moreToolsExpanded ? 180 : 0 })
            }
            .width('100%')
            .height(44)
            .padding({ left: 16, right: 16 })
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
                this.moreToolsExpanded = !this.moreToolsExpanded;
              });
            })

            if (this.moreToolsExpanded) {
              Column({ space: 8 }) {
                Scroll() {
                  Row({ space: 6 }) {
                    ForEach(BUILTIN_PACKS, (pack: ThemePack): void => {
                      Row({ space: 5 }) {
                        Column()
                          .width(10)
                          .height(10)
                          .borderRadius(999)
                          .backgroundColor(pack.accent)
                        Text(pack.displayName)
                          .fontSize(11.5)
                          .fontColor(themePackStore.current().id === pack.id ? this.accentColorStore : INK2)
                      }
                      .height(28)
                      .padding({ left: 8, right: 9 })
                      .borderRadius(999)
                      .backgroundColor(themePackStore.current().id === pack.id ? accentSoftBg(this.accentColorStore) : SURFACE2)
                      .border({
                        width: 0.75,
                        color: themePackStore.current().id === pack.id ? accentEdge(this.accentColorStore) : LINE,
                      })
                      .onClick((): void => { this.applyQuickThemePack(pack); })
                    }, (pack: ThemePack): string => pack.id)
                  }
                  .padding({ left: 16, right: 16 })
                }
                .scrollable(ScrollDirection.Horizontal)
                .scrollBar(BarState.Off)
                .width('100%')

                Column() {
                  this.NavRow('ph_quotes', '模型议会', false, this.onOpenCouncil)
                  this.NavRow('ph_squaresFour', '小应用', false, this.onOpenApps)
                  this.NavRow('ph_globe', '站点', false, this.onOpenWebMount)
                  this.NavRow('palette', '外观与主题包…', false, this.onOpenAppearance)
                }
                .width('100%')
                .padding({ left: 16, right: 16 })
              }
              .width('100%')
              .padding({ bottom: 6 })
            }
          }
          .width('100%')
          .backgroundColor(BG)

          Divider().color(LINE).strokeWidth(0.5)
          Row() {
            Row() {
              UIAvatar({
                name: this.userNickname,
                imageUrl: this.userAvatar,
                avatarSize: 32,
                accentColor: this.accentColorStore,
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
            .accessibilityText('账户与昵称设置')

            Row() {
              Icon({ name: 'plus', iconSize: 18, color: INK3 })
            }
            .width(44)
            .height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .accessibilityText('新建空白对话')
            .onClick((): void => {
              this.onNewChat();
              this.onDismiss();
            })
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
      Icon({ name: icon, iconSize: 18, color: accent ? this.accentColorStore : INK2 })
        .margin({ right: 14 })
      Text(label)
        .fontSize(BODY)
        .fontWeight(accent ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(accent ? this.accentColorStore : INK)
    }
    .width('100%')
    .height(44)
    .padding({ left: 11, right: 11 })
    .borderRadius(8)
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      onClick();
      this.onDismiss();
    })
  }
}
