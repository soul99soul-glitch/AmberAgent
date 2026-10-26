
import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

import {
  ACCENT, INK, INK3, SURFACE, SURFACE2, META, ERROR, TEXT_STYLES, OVERLAY, MOTION_OVERLAY
} from '../../design/tokens.ets';

import { FONT_MONO } from '../../design/fonts.ets';

import { ProviderLoginSession } from '../../platform_impl/provider/ProviderLoginSession.ets';

@Component
export struct ProviderLoginOverlays {
  @ObjectLink login: ProviderLoginSession;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  private loginOverlayDuration(): number {
    return getProductKind() === 'deepread' && (this.reduceMotion || this.appBackgrounded) ? 0 : MOTION_OVERLAY;
  }

  private loginOverlayScale(): number {
    return getProductKind() === 'deepread' && (this.reduceMotion || this.appBackgrounded) ? 1 : 0.96;
  }

  @Builder
  GrokLoginOverlay(): void {
    Column() {
      Column() {
        Text('登录 xAI(Grok)')
          .fontSize(17).fontWeight(600).fontColor((getProductKind() === 'deepread' ? D.ink : INK)).margin({ bottom: 12 })
        Text('已在浏览器打开授权页。登录完成后页面会跳转到一个打不开的本地地址,请把地址栏里的完整链接复制回来粘贴到下面:')
          .fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontColor((getProductKind() === 'deepread' ? D.muted : INK3)).margin({ bottom: 12 })
        TextArea({ text: this.login.grokPasteUrl, placeholder: 'http://127.0.0.1:8787/callback?code=…' })
          .fontSize(TEXT_STYLES.meta.size).fontFamily(FONT_MONO).fontColor((getProductKind() === 'deepread' ? D.ink : INK))
          .backgroundColor(SURFACE2).borderRadius(8)
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .height(72)
          .onChange((v: string): void => { this.login.grokPasteUrl = v; })
        if (this.login.grokStatus.length > 0) {
          Text(this.login.grokStatus)
            .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 8 })
        }
        Row() {
          Text('取消')
            .fontSize(14).fontColor((getProductKind() === 'deepread' ? D.muted : INK3))
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.login.cancelGrokLogin(); })
          Text(this.login.grokBusy ? '交换中…' : '完成登录')
            .fontSize(14).fontColor(this.login.grokBusy ? (getProductKind() === 'deepread' ? D.muted : INK3) : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.login.confirmGrokLogin(); })
        }
        .width('100%').margin({ top: 12 })
      }
      .width('80%').padding(22).backgroundColor((getProductKind() === 'deepread' ? D.card : SURFACE)).borderRadius(16)
      .alignItems(HorizontalAlign.Start)
      .shadow({ radius: 24, color: OVERLAY, offsetY: 8 })
      .transition(TransitionEffect.scale({ x: this.loginOverlayScale(), y: this.loginOverlayScale() })
        .animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
  }

  @Builder
  CodexLoginOverlay(): void {
    Column() {
      Column() {
        Text('登录 ChatGPT')
          .fontSize(17).fontWeight(600).fontColor((getProductKind() === 'deepread' ? D.ink : INK)).margin({ bottom: 12 })
        Text('在浏览器中打开登录页,输入以下设备码完成授权:')
          .fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontColor((getProductKind() === 'deepread' ? D.muted : INK3)).margin({ bottom: 12 })
        Text(this.login.codexUserCode)
          .fontSize(26).fontWeight(FontWeight.Bold).fontFamily(FONT_MONO)
          .fontColor((getProductKind() === 'deepread' ? D.ink : INK)).letterSpacing(2)
          .margin({ bottom: 18 })
        Row() {
          Text('复制设备码')
            .fontSize(14).fontColor(getProductKind() === 'deepread' ? D.accent : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.login.copyCodexUserCode(); })
          Text('打开登录页')
            .fontSize(14).fontColor(getProductKind() === 'deepread' ? D.accent : this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.login.openCodexVerification(); })
        }
        .width('100%')
        Row() {
          Text('取消登录')
            .fontSize(14).fontColor((getProductKind() === 'deepread' ? D.muted : INK3))
            .layoutWeight(1).textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.login.cancelCodexLogin(); })
        }
        .width('100%').margin({ top: 8 })
      }
      .width('80%').padding(22).backgroundColor((getProductKind() === 'deepread' ? D.card : SURFACE)).borderRadius(16)
      .alignItems(HorizontalAlign.Start)
      .shadow({ radius: 24, color: OVERLAY, offsetY: 8 })
      .transition(TransitionEffect.scale({ x: this.loginOverlayScale(), y: this.loginOverlayScale() })
        .animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: this.loginOverlayDuration(), curve: Curve.EaseOut }))
  }

  build(): void {
    Stack() {
      if (this.login.codexLoginOpen) this.CodexLoginOverlay()
      if (this.login.grokLoginOpen) this.GrokLoginOverlay()
    }.width('100%').height('100%').hitTestBehavior(HitTestMode.Transparent)
  }
}
