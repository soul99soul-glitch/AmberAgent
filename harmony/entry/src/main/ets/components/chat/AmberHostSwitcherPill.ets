// AmberHostSwitcherPill — 底栏右侧「📱 本机 ⇄ ☁️ 云端」一键切换胶囊
// 严格直连现有 ChatCloudDialogueSession（不新建并行状态机）。

import {
  ACCENT, BG_GLASS, HOME_GLASS_EDGE, INK, INK2, INK3, LINE, SIGNAL, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_SANS } from '../../design/fonts.ets';
import { ChatCloudDialogueSession } from '../../platform_impl/chat/ChatCloudDialogueSession.ets';
import { Icon } from '../ui/Icon.ets';

@Component
export struct AmberHostSwitcherPill {
  @ObjectLink cloudDialogue: ChatCloudDialogueSession;
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  onOpenGatewaySettings: () => void = (): void => {};

  private isCloudActive(): boolean {
    return this.cloudDialogue.uiTick >= 0 && this.cloudDialogue.isActiveMode();
  }

  private handlePrimaryTap(): void {
    if (!this.cloudDialogue.paired) {
      this.onOpenGatewaySettings();
      return;
    }
    this.cloudDialogue.setCloudModeEnabled(!this.cloudDialogue.enabled).catch((): void => {});
  }

  @Builder
  HostMenu(): void {
    Menu() {
      MenuItem({ content: this.isCloudActive() ? '✓ 云端主对话' : '切换到云端主对话' })
        .enabled(this.cloudDialogue.paired)
        .onClick((): void => {
          if (this.cloudDialogue.paired) {
            this.cloudDialogue.setCloudModeEnabled(true).catch((): void => {});
          }
        })
      MenuItem({ content: !this.isCloudActive() ? '✓ 本机直连对话' : '切换到本机直连' })
        .onClick((): void => {
          this.cloudDialogue.setCloudModeEnabled(false).catch((): void => {});
        })
      MenuItem({ content: this.cloudDialogue.paired ? 'Mac Gateway 设置…' : '配对 Mac Gateway…' })
        .onClick((): void => {
          this.onOpenGatewaySettings();
        })
    }
  }

  build(): void {
    Row({ space: 6 }) {
      Column()
        .width(7)
        .height(7)
        .borderRadius(999)
        .backgroundColor(
          this.isCloudActive()
            ? SIGNAL
            : (this.cloudDialogue.paired ? this.accentColorStore : INK3),
        )

      Icon({
        name: this.isCloudActive() ? 'ph_globe' : 'ph_laptop',
        iconSize: 15,
        color: this.isCloudActive() ? this.accentColorStore : INK2,
      })

      Text(this.isCloudActive() ? '云端' : '本机')
        .fontSize(12)
        .fontFamily(FONT_SANS)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.isCloudActive() ? INK : INK2)
        .maxLines(1)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .height(36)
    .constraintSize({ minWidth: 74 })
    .padding({ left: 11, right: 12 })
    .borderRadius(999)
    .backgroundColor(this.isCloudActive()
      ? accentSoftBg(this.accentColorStore)
      : (this.appBackgrounded ? BG_GLASS : SURFACE2))
    .backgroundBlurStyle(
      this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
      { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
    )
    .border({
      width: 0.75,
      color: this.isCloudActive() ? accentEdge(this.accentColorStore) : (this.cloudDialogue.paired ? HOME_GLASS_EDGE : LINE),
    })
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      this.handlePrimaryTap();
    })
    .bindContextMenu(this.HostMenu(), ResponseType.LongPress)
    .accessibilityText(this.isCloudActive()
      ? '当前为云端主对话，点按切换本机，长按打开宿主菜单'
      : (this.cloudDialogue.paired ? '当前为本机对话，点按切换云端，长按打开宿主菜单' : '未配对云端网关，点按前往配对'))
  }
}
