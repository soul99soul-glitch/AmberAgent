// ChatDrawer — 对齐 iOS AmberDuetWorkspaceDrawerSheet 的底部浮层导航与历史面板
// 全宽圆角分组卡片：顶栏（左搜索 / 中「导航与历史」/ 右设置）、Agent 形象头卡、常用功能、最近话题、可折叠更多工具与主题配色

import {
  ACCENT, ACCENT_INK, BG, BODY, INK, INK2, INK3, LINE,
  SURFACE, accentEdge, accentSoftBg,
} from '../design/tokens.ets';
import { BUILTIN_PACKS, applyThemePack, resolveThemeDark, themePackStore, type ThemePack } from '../design/theme_packs.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';
import { AmberPersonaMedallion } from './chat/AmberPersonaMedallion.ets';
import { UIAvatar } from './UIAvatar.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatDrawer {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
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
  @State private searchExpanded: boolean = false;
  @State private searchText: string = '';
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

  private resolvedUserLabel(): string {
    const trimmed: string = this.userNickname.trim();
    return trimmed.length > 0 ? trimmed : '用户';
  }

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

  @Builder
  private SpaceCardRow(icon: string, title: string, subtitle: string, onClick: () => void): void {
    Row({ space: 12 }) {
      Icon({ name: icon, iconSize: 17, color: INK2 })
      Text(title)
        .fontSize(15)
        .fontFamily(FONT_SANS)
        .fontColor(INK)
      Blank()
      if (subtitle.length > 0) {
        Text(subtitle)
          .fontSize(12.5)
          .fontColor(INK3)
      }
      Icon({ name: 'chevron_right', iconSize: 12, color: INK3 })
    }
    .width('100%')
    .height(48)
    .padding({ left: 16, right: 16 })
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      this.onDismiss();
      onClick();
    })
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Column() {
        // 顶栏（对齐 iOS NavigationStack inline toolbar：左 搜索 / 中 导航与历史 / 右 设置）
        Stack({ alignContent: Alignment.Center }) {
          Text('导航与历史')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontFamily(FONT_SANS)
            .fontColor(INK)

          Row() {
            Row({ space: 4 }) {
              Icon({ name: 'search', iconSize: 14, color: this.searchExpanded ? this.accentColorStore : INK })
              Text('搜索')
                .fontSize(14)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.searchExpanded ? this.accentColorStore : INK)
            }
            .height(34)
            .padding({ left: 4, right: 10 })
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              animateTo({ duration: 180, curve: Curve.FastOutSlowIn }, (): void => {
                this.searchExpanded = !this.searchExpanded;
                if (!this.searchExpanded && this.searchText.length > 0) {
                  this.searchText = '';
                  this.onSearchText('');
                }
              });
            })

            Blank()

            Row({ space: 4 }) {
              Icon({ name: 'gear', iconSize: 14, color: this.accentColorStore })
              Text('设置')
                .fontSize(14)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore)
            }
            .height(34)
            .padding({ left: 10, right: 4 })
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              this.onDismiss();
              this.onOpenSettings();
            })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .height(44)
        .padding({ left: 18, right: 18, top: 4 })

        Scroll() {
          Column({ space: 20 }) {
            if (this.searchExpanded) {
              Row({ space: 8 }) {
                Icon({ name: 'search', iconSize: 14, color: INK3 })
                TextInput({ text: this.searchText, placeholder: '搜索历史对话与消息' })
                  .fontSize(14)
                  .fontColor(INK)
                  .placeholderColor(INK3)
                  .backgroundColor(Color.Transparent)
                  .padding(0)
                  .layoutWeight(1)
                  .onChange((value: string): void => {
                    this.searchText = value;
                    this.onSearchText(value);
                  })
                if (this.searchText.length > 0) {
                  Icon({ name: 'close', iconSize: 12, color: INK3 })
                    .padding(4)
                    .onClick((): void => {
                      this.searchText = '';
                      this.onSearchText('');
                    })
                }
              }
              .width('100%')
              .height(40)
              .padding({ left: 14, right: 10 })
              .borderRadius(14)
              .backgroundColor(SURFACE)
              .border({ width: 0.75, color: LINE })
              .alignItems(VerticalAlign.Center)
            }

            // 1. Agent 主形象卡（对齐 iOS agentPrimaryCard）
            Row({ space: 12 }) {
              Row({ space: 12 }) {
                Stack({ alignContent: Alignment.BottomEnd }) {
                  AmberPersonaMedallion({
                    medallionSize: 48,
                    displayName: this.agentSoulDisplayName,
                    isDefault: this.agentSoulIsDefault,
                    sending: false,
                    sheetOpen: true,
                  })
                  Stack({ alignContent: Alignment.Center }) {
                    Icon({ name: 'spark', iconSize: 9, color: ACCENT_INK })
                  }
                  .width(17)
                  .height(17)
                  .borderRadius(999)
                  .backgroundColor(this.accentColorStore)
                  .border({ width: 1.5, color: SURFACE })
                }

                Column({ space: 3 }) {
                  Row({ space: 6 }) {
                    Text(this.agentSoulDisplayName.length > 0 ? this.agentSoulDisplayName : 'Amber')
                      .fontSize(16)
                      .fontWeight(FontWeight.Bold)
                      .fontFamily(FONT_SANS)
                      .fontColor(INK)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text('定制形象')
                      .fontSize(10.5)
                      .fontWeight(FontWeight.Medium)
                      .fontColor(this.accentColorStore)
                      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                      .borderRadius(999)
                      .backgroundColor(accentSoftBg(this.accentColorStore))
                  }
                  .alignItems(VerticalAlign.Center)

                  Text('点按查看或自定义形象人设与记忆')
                    .fontSize(12)
                    .fontColor(INK3)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
              }
              .layoutWeight(1)
              .alignItems(VerticalAlign.Center)
              .onClick((): void => {
                this.onDismiss();
                this.onOpenPersona();
              })

              Row({ space: 4 }) {
                Icon({ name: 'plus', iconSize: 12, color: ACCENT_INK })
                Text('新话题')
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(ACCENT_INK)
              }
              .height(32)
              .padding({ left: 11, right: 12 })
              .borderRadius(999)
              .backgroundColor(this.accentColorStore)
              .alignItems(VerticalAlign.Center)
              .onClick((): void => {
                this.onDismiss();
                this.onNewTopic();
              })
              .gesture(
                LongPressGesture({ duration: 420 })
                  .onAction((): void => {
                    this.onDismiss();
                    this.onNewChat();
                  }),
              )
              .accessibilityText('新话题，长按新建空白会话')
            }
            .width('100%')
            .padding(14)
            .borderRadius(16)
            .backgroundColor(SURFACE)
            .border({ width: 0.75, color: LINE })
            .alignItems(VerticalAlign.Center)

            // 2. 常用功能与设置（对齐 iOS primarySpacesListSection）
            Column({ space: 8 }) {
              Text('常用功能与设置')
                .fontSize(12.5)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK3)
                .padding({ left: 4 })
                .width('100%')

              Column() {
                this.SpaceCardRow('ph_listChecks', '任务看板', '后台与云端任务', this.onOpenTasks)
                Divider().color(LINE).strokeWidth(0.5).margin({ left: 46 })
                this.SpaceCardRow('gear', '设置', '连接、模型与偏好', this.onOpenSettings)
              }
              .width('100%')
              .borderRadius(16)
              .backgroundColor(SURFACE)
              .border({ width: 0.75, color: LINE })
              .clip(true)
            }
            .width('100%')

            // 3. 历史话题（对齐 iOS recentHistorySection）
            Column({ space: 8 }) {
              Row() {
                Text('历史话题')
                  .fontSize(12.5)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(INK3)
                Blank()
                Row({ space: 2 }) {
                  Text('管理全部历史')
                    .fontSize(12)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(this.accentColorStore)
                  Icon({ name: 'chevron_right', iconSize: 11, color: this.accentColorStore })
                }
                .padding({ left: 6, top: 2, bottom: 2 })
                .onClick((): void => {
                  this.onDismiss();
                  this.onOpenHistory();
                })
              }
              .width('100%')
              .padding({ left: 4, right: 2 })
              .alignItems(VerticalAlign.Center)

              this.conversationList()
            }
            .width('100%')

            // 4. 可折叠「更多工具与主题配色」（对齐 iOS moreSpacesDisclosureSection）
            Column({ space: 10 }) {
              Row() {
                Text('更多工具与主题配色')
                  .fontSize(12.5)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(INK3)
                  .layoutWeight(1)
                Icon({ name: 'chevron_down', iconSize: 13, color: INK3 })
                  .rotate({ angle: this.moreToolsExpanded ? 180 : 0 })
              }
              .width('100%')
              .padding({ left: 4, right: 4, top: 4, bottom: 4 })
              .alignItems(VerticalAlign.Center)
              .onClick((): void => {
                animateTo({ duration: 200, curve: Curve.FastOutSlowIn }, (): void => {
                  this.moreToolsExpanded = !this.moreToolsExpanded;
                });
              })

              if (this.moreToolsExpanded) {
                Scroll() {
                  Row({ space: 8 }) {
                    ForEach(BUILTIN_PACKS, (pack: ThemePack): void => {
                      Row({ space: 6 }) {
                        Column()
                          .width(9)
                          .height(9)
                          .borderRadius(999)
                          .backgroundColor(pack.accent)
                        Text(pack.displayName)
                          .fontSize(12)
                          .fontWeight(themePackStore.current().id === pack.id ? FontWeight.Medium : FontWeight.Normal)
                          .fontColor(themePackStore.current().id === pack.id ? this.accentColorStore : INK)
                      }
                      .height(32)
                      .padding({ left: 11, right: 11 })
                      .borderRadius(999)
                      .backgroundColor(themePackStore.current().id === pack.id ? accentSoftBg(this.accentColorStore) : SURFACE)
                      .border({
                        width: 0.75,
                        color: themePackStore.current().id === pack.id ? accentEdge(this.accentColorStore) : LINE,
                      })
                      .onClick((): void => { this.applyQuickThemePack(pack); })
                    }, (pack: ThemePack): string => pack.id)
                  }
                }
                .scrollable(ScrollDirection.Horizontal)
                .scrollBar(BarState.Off)
                .width('100%')

                Column() {
                  this.SpaceCardRow('ph_quotes', '模型议会', '', this.onOpenCouncil)
                  Divider().color(LINE).strokeWidth(0.5).margin({ left: 46 })
                  this.SpaceCardRow('ph_squaresFour', '小应用', '', this.onOpenApps)
                  Divider().color(LINE).strokeWidth(0.5).margin({ left: 46 })
                  this.SpaceCardRow('ph_globe', '站点', '', this.onOpenWebMount)
                  Divider().color(LINE).strokeWidth(0.5).margin({ left: 46 })
                  this.SpaceCardRow('palette', '外观与主题包', '', this.onOpenAppearance)
                }
                .width('100%')
                .borderRadius(16)
                .backgroundColor(SURFACE)
                .border({ width: 0.75, color: LINE })
                .clip(true)
              }
            }
            .width('100%')

            // 5. 账户与新建空白对话轻量卡
            Row({ space: 10 }) {
              Row({ space: 10 }) {
                UIAvatar({
                  name: this.resolvedUserLabel(),
                  imageUrl: this.userAvatar,
                  avatarSize: 32,
                  accentColor: this.accentColorStore,
                })
                Text(this.resolvedUserLabel())
                  .fontSize(BODY)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(INK)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .layoutWeight(1)
              }
              .layoutWeight(1)
              .alignItems(VerticalAlign.Center)
              .onClick((): void => {
                this.onDismiss();
                this.onEditNickname(this.resolvedUserLabel());
              })
              .accessibilityText('账户与昵称设置')

              Row({ space: 4 }) {
                Icon({ name: 'plus', iconSize: 12, color: INK2 })
                Text('空白对话')
                  .fontSize(12)
                  .fontColor(INK2)
              }
              .height(30)
              .padding({ left: 10, right: 11 })
              .borderRadius(999)
              .backgroundColor(BG)
              .border({ width: 0.75, color: LINE })
              .alignItems(VerticalAlign.Center)
              .accessibilityText('新建空白对话')
              .onClick((): void => {
                this.onDismiss();
                this.onNewChat();
              })
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .borderRadius(16)
            .backgroundColor(SURFACE)
            .border({ width: 0.75, color: LINE })
            .alignItems(VerticalAlign.Center)
          }
          .width('100%')
          .padding({ left: 20, right: 20, top: 10, bottom: this.navBarHeight + 28 })
        }
        .layoutWeight(1)
        .scrollBar(BarState.Off)
      }
      .width('100%')
      .height('100%')
      .backgroundColor(BG)
    }
  }
}
