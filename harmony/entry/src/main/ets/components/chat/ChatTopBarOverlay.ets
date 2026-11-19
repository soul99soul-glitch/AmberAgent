// ChatTopBarOverlay — Muse 风格顶部悬垂岛与左右玻璃圆钮
// - 外置于 54vp 窄渐进模糊带之上，容器 hitTestBehavior(Transparent) 确保正文触控 100% 穿透
// - 左侧 ☰ 呼出抽屉（两栏折叠时可隐藏或保持对齐），右侧 ChatTopBarTrailingDock 常态产物架/长按菜单，有提醒变卫星胶囊
// - 居中 76vp 动态形象徽章（点按打开人设面板）+ 重叠 8vp 的 32vp 高、最大 268vp 活动/标题胶囊（长按 0.45s 停止生成）

import {
  ACCENT, ACCENT_INK, BG_GLASS, ERROR, HOME_GLASS_EDGE, HOME_GLASS_SHADOW,
  INK, INK2, INK3, LINE, META, SIGNAL, STATUS_AMBER, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_MONO, FONT_SANS } from '../../design/fonts.ets';
import { AmberPersonaMedallion } from './AmberPersonaMedallion.ets';
import { Icon } from '../ui/Icon.ets';

export interface TopBarNoticeItem {
  id: string;
  kind: 'awaitingUser' | 'failed' | 'completed' | 'running';
  title: string;
  subtitle: string;
  conversationId: string;
}

@Component
export struct ChatTopBarOverlay {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop twoPane: boolean = false;
  @Prop title: string = '新对话';
  @Prop episodeTitle: string = '';
  @Prop editingTitle: boolean = false;
  @Prop titleInput: string = '';
  @Prop sending: boolean = false;
  @Prop recapLoading: boolean = false;
  @Prop activityLabel: string = '';
  @Prop agentSoulDisplayName: string = 'Amber';
  @Prop agentSoulIsDefault: boolean = true;
  @Prop artifactCount: number = 0;
  @Prop notices: TopBarNoticeItem[] = [];
  @State private noticesExpanded: boolean = false;
  onOpenDrawer: () => void = (): void => {};
  onOpenPersona: () => void = (): void => {};
  onOpenRecap: () => void = (): void => {};
  onStopGeneration: () => void = (): void => {};
  onTitleInputChange: (v: string) => void = (_v: string): void => {};
  onSaveTitle: () => void = (): void => {};
  onCancelEditTitle: () => void = (): void => {};
  onOpenArtifactShelf: () => void = (): void => {};
  onNewTopic: () => void = (): void => {};
  onNewChat: () => void = (): void => {};
  onExportChat: () => void = (): void => {};
  onOpenNotice: (notice: TopBarNoticeItem) => void = (_n: TopBarNoticeItem): void => {};
  onClearNotices: () => void = (): void => {};
  onControlsHeightChange: (height: number) => void = (_h: number): void => {};

  private capsuleDisplayText(): string {
    if (this.activityLabel.trim().length > 0) return this.activityLabel.trim();
    if (this.sending) return '正在思考与生成…';
    if (this.recapLoading) return '正在整理对话回顾…';
    if (this.episodeTitle.trim().length > 0) return this.episodeTitle.trim();
    return this.title.trim().length > 0 ? this.title.trim() : '新对话';
  }

  private primaryNotice(): TopBarNoticeItem | null {
    if (this.notices.length === 0) return null;
    const awaiting = this.notices.find((n: TopBarNoticeItem): boolean => n.kind === 'awaitingUser');
    if (awaiting !== undefined) return awaiting;
    const failed = this.notices.find((n: TopBarNoticeItem): boolean => n.kind === 'failed');
    if (failed !== undefined) return failed;
    return this.notices[0];
  }

  private noticeTint(kind: string): string {
    if (kind === 'awaitingUser') return STATUS_AMBER;
    if (kind === 'failed') return ERROR;
    if (kind === 'completed') return SIGNAL;
    return this.accentColorStore;
  }

  @Builder
  TrailingDockMenu(): void {
    Menu() {
      MenuItem({ content: '✦ 新话题（当前对话分段）' })
        .onClick((): void => { this.onNewTopic(); })
      MenuItem({ content: '导出当前对话…' })
        .onClick((): void => { this.onExportChat(); })
      MenuItem({ content: '新建空白对话' })
        .onClick((): void => { this.onNewChat(); })
    }
  }

  build(): void {
    Stack({ alignContent: Alignment.Top }) {
      // ── 1. 左右单行轻按钮层（54vp 窄顶栏内：水平边距 18vp，顶内距 statusBarHeight + 10vp）──
      Row() {
        if (this.twoPane) {
          Row().width(44).height(44)
        } else {
          Row() {
            Row() {
              Icon({ name: 'menu', iconSize: 19, color: INK })
            }
            .width(40)
            .height(40)
            .borderRadius(999)
            .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE2)
            .backgroundBlurStyle(
              this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
              { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
            )
            .border({ width: 0.75, color: HOME_GLASS_EDGE })
            .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 2 })
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
          }
          .width(44)
          .height(44)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .hitTestBehavior(HitTestMode.Default)
          .onClick((): void => { this.onOpenDrawer(); })
          .accessibilityText('打开导航与历史抽屉')
        }

        Blank()

        // 右侧 ChatTopBarTrailingDock：有跨会话提醒时变形为染色卫星胶囊，常态为产物架圆钮
        if (this.editingTitle) {
          Row({ space: 8 }) {
            Text('保存')
              .fontSize(META)
              .fontColor(this.accentColorStore)
              .padding({ left: 10, right: 10, top: 6, bottom: 6 })
              .backgroundColor(SURFACE)
              .borderRadius(999)
              .onClick((): void => { this.onSaveTitle(); })
            Text('取消')
              .fontSize(META)
              .fontColor(INK3)
              .padding({ left: 10, right: 10, top: 6, bottom: 6 })
              .backgroundColor(SURFACE)
              .borderRadius(999)
              .onClick((): void => { this.onCancelEditTitle(); })
          }
          .hitTestBehavior(HitTestMode.Default)
        } else if (this.primaryNotice() !== null) {
          Row({ space: 5 }) {
            Column()
              .width(7)
              .height(7)
              .borderRadius(999)
              .backgroundColor(this.noticeTint(this.primaryNotice()!.kind))
            Text(`${this.notices.length}`)
              .fontSize(12)
              .fontFamily(FONT_MONO)
              .fontWeight(FontWeight.Bold)
              .fontColor(INK)
            Icon({ name: 'bell', iconSize: 14, color: this.noticeTint(this.primaryNotice()!.kind) })
          }
          .height(40)
          .padding({ left: 11, right: 11 })
          .borderRadius(999)
          .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE)
          .backgroundBlurStyle(
            this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
            { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
          )
          .border({ width: 1, color: accentEdge(this.noticeTint(this.primaryNotice()!.kind)) })
          .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 2 })
          .hitTestBehavior(HitTestMode.Default)
          .onClick((): void => {
            if (this.notices.length === 1) {
              this.onOpenNotice(this.notices[0]);
            } else {
              this.noticesExpanded = !this.noticesExpanded;
            }
          })
          .gesture(
            PanGesture({ direction: PanDirection.Up, distance: 12 })
              .onActionEnd((): void => {
                this.noticesExpanded = false;
                this.onClearNotices();
              }),
          )
          .bindContextMenu(this.TrailingDockMenu(), ResponseType.LongPress)
          .accessibilityText('查看跨会话提醒，长按打开更多菜单')
        } else {
          Row() {
            Stack({ alignContent: Alignment.TopEnd }) {
              Row() {
                Icon({ name: 'box', iconSize: 18, color: INK })
              }
              .width(40)
              .height(40)
              .borderRadius(999)
              .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE2)
              .backgroundBlurStyle(
                this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
                { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
              )
              .border({ width: 0.75, color: HOME_GLASS_EDGE })
              .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 2 })
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)

              if (this.artifactCount > 0) {
                Text(`${this.artifactCount > 99 ? '99+' : this.artifactCount}`)
                  .fontSize(9)
                  .fontFamily(FONT_MONO)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(ACCENT_INK)
                  .padding({ left: 4, right: 4, top: 1, bottom: 1 })
                  .borderRadius(999)
                  .backgroundColor(this.accentColorStore)
              }
            }
          }
          .width(44)
          .height(44)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .hitTestBehavior(HitTestMode.Default)
          .onClick((): void => { this.onOpenArtifactShelf(); })
          .bindContextMenu(this.TrailingDockMenu(), ResponseType.LongPress)
          .accessibilityText('打开产物架，长按打开新话题与导出菜单')
        }
      }
      .width('100%')
      .padding({ left: 18, right: 18, top: this.statusBarHeight + 10 })
      .alignItems(VerticalAlign.Center)
      .hitTestBehavior(HitTestMode.Transparent)

      // ── 2. 上层居中悬垂形象/活动胶囊岛（外置于模糊带之上，总控制高 108vp）──
      Column() {
        Column() {
          AmberPersonaMedallion({
            medallionSize: 76,
            displayName: this.agentSoulDisplayName,
            isDefault: this.agentSoulIsDefault,
            sending: this.sending,
          })
        }
        .margin({ top: this.statusBarHeight + 4 })
        .hitTestBehavior(HitTestMode.Default)
        .onClick((): void => { this.onOpenPersona(); })
        .accessibilityText('打开形象人设与记忆面板')

        // 下方重叠 8vp 悬垂 32vp 高、最大宽 268vp 胶囊（两侧各留 >=56vp 净空）
        Column() {
          if (this.editingTitle) {
            TextInput({ text: this.titleInput, placeholder: '会话标题' })
              .fontSize(13)
              .fontFamily(FONT_SANS)
              .fontColor(INK)
              .height(32)
              .constraintSize({ maxWidth: 240 })
              .padding({ left: 12, right: 12 })
              .backgroundColor(SURFACE)
              .borderRadius(999)
              .border({ width: 0.75, color: this.accentColorStore })
              .onChange((v: string): void => { this.onTitleInputChange(v); })
              .hitTestBehavior(HitTestMode.Default)
          } else {
            Row({ space: 6 }) {
              if (this.sending || this.recapLoading) {
                LoadingProgress()
                  .width(13)
                  .height(13)
                  .color(this.accentColorStore)
              } else {
                Column()
                  .width(6)
                  .height(6)
                  .borderRadius(999)
                  .backgroundColor(this.agentSoulIsDefault ? this.accentColorStore : SIGNAL)
              }

              Text(this.capsuleDisplayText())
                .fontSize(13)
                .fontFamily(FONT_SANS)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .flexShrink(1)
            }
            .height(32)
            .constraintSize({ minWidth: 96, maxWidth: 268 })
            .padding({ left: 12, right: 14 })
            .borderRadius(999)
            .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE)
            .backgroundBlurStyle(
              this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
              { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
            )
            .border({
              width: 0.75,
              color: this.sending ? accentEdge(this.accentColorStore) : HOME_GLASS_EDGE,
            })
            .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 2 })
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .hitTestBehavior(HitTestMode.Default)
            .onClick((): void => { this.onOpenRecap(); })
            .gesture(
              LongPressGesture({ duration: 450 })
                .onAction((): void => {
                  if (this.sending) this.onStopGeneration();
                }),
            )
            .accessibilityText(this.sending
              ? '正在生成，轻点查看对话回顾，长按停止生成'
              : `当前话题：${this.capsuleDisplayText()}，轻点查看对话回顾`)
          }
        }
        .margin({ top: -16 })
      }
      .padding({ left: 56, right: 56 })
      .alignItems(HorizontalAlign.Center)
      .hitTestBehavior(HitTestMode.Transparent)
      .onAreaChange((_old: Area, area: Area): void => {
        const h: number = Number(area.height);
        if (Number.isFinite(h) && h > 0) this.onControlsHeightChange(h);
      })

      // ── 3. 卫星提醒展开浮层（同挂 overlay 层，圆角 30vp）──
      if (this.noticesExpanded && this.notices.length > 0) {
        Column({ space: 8 }) {
          Row() {
            Text(`后台与提醒 · ${this.notices.length}`)
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK)
            Blank()
            Text('全部清除')
              .fontSize(12)
              .fontColor(INK3)
              .onClick((): void => {
                this.noticesExpanded = false;
                this.onClearNotices();
              })
          }
          .width('100%')

          ForEach(this.notices, (notice: TopBarNoticeItem): void => {
            Row({ space: 10 }) {
              Column()
                .width(8)
                .height(8)
                .borderRadius(999)
                .backgroundColor(this.noticeTint(notice.kind))
              Column({ space: 2 }) {
                Text(notice.title)
                  .fontSize(13)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(INK)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                if (notice.subtitle.length > 0) {
                  Text(notice.subtitle)
                    .fontSize(11.5)
                    .fontColor(INK3)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                }
              }
              .layoutWeight(1)
              .alignItems(HorizontalAlign.Start)
              Icon({ name: 'chevron_right', iconSize: 14, color: INK3 })
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 10, bottom: 10 })
            .borderRadius(16)
            .backgroundColor(SURFACE2)
            .onClick((): void => {
              this.noticesExpanded = false;
              this.onOpenNotice(notice);
            })
          }, (n: TopBarNoticeItem): string => n.id)
        }
        .width('90%')
        .constraintSize({ maxWidth: 360 })
        .margin({ top: this.statusBarHeight + 114 })
        .padding(16)
        .borderRadius(30)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: HOME_GLASS_EDGE })
        .shadow({ radius: 18, color: HOME_GLASS_SHADOW, offsetY: 6 })
        .hitTestBehavior(HitTestMode.Default)
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .hitTestBehavior(HitTestMode.Transparent)
  }
}
