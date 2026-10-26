import { ACCENT, INK, INK3, ERROR, LINE, SURFACE2, MOTION_OVERLAY, lineHeightForSize } from '../../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../../design/fonts.ets';
import { BtnFill } from '../PageTemplate.ets';
import { Icon } from '../ui/Icon.ets';
import type { WebMountOAuthApplication } from '@amber/chat-domain';
import { WebMountOAuthSettingsSession } from '../../platform_impl/webmount_settings/WebMountOAuthSettingsSession.ets';

@Component
export struct WebMountOAuthSettingsView {
  @ObjectLink session: WebMountOAuthSettingsSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  @Builder
  OauthRow(app: WebMountOAuthApplication, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(app.siteId)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(this.session.oauthStatusText(app.siteId))
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Text('编辑')
          .fontSize(14).fontColor(this.accentColorStore)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.session.editOauth(app); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Row({ space: 12 }) {
        BtnFill({
          label: '连接',
          onTap: (): void => { this.session.connectOauth(app.siteId); },
        })
        Text('取消')
          .fontSize(14).fontColor(INK3)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.session.cancelOauth(app.siteId); })
        Text('断开')
          .fontSize(14).fontColor(ERROR)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.session.disconnectOauth(app.siteId); })
      }
      .width('100%')
      .justifyContent(FlexAlign.Start)

      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ top: 8 })
      }
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 10, bottom: 10 })
  }

  @Builder
  OauthCard(): void {
    Column() {
      ForEach(this.session.oauthApps, (app: WebMountOAuthApplication, index: number): void => {
        this.OauthRow(app, index >= this.session.oauthApps.length - 1);
      }, (app: WebMountOAuthApplication): string => app.siteId)

      if (!this.session.oauthFormOpen && this.session.oauthApps.length > 0) {
        Row() {
          Icon({ name: 'plus', iconSize: 14, color: this.accentColorStore })
          Text('添加 OAuth 应用')
            .fontSize(14).fontColor(this.accentColorStore).fontFamily(FONT_SANS_MEDIUM)
            .margin({ left: 6 })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .constraintSize({ minHeight: 44 })
        .padding({ left: 14, right: 14, top: 6, bottom: 6 })
        .onClick((): void => {
          this.session.openAdd();
        })
      }

      if (this.session.oauthFormOpen || this.session.oauthApps.length === 0) {
        Column() {
          Text('站点 ID')
            .fontSize(12).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM)
            .width('100%').padding({ left: 14, right: 14, top: 12 })
          TextInput({ text: this.session.oauthSiteId, placeholder: '如 hackernews(与该站点的注册应用一致)' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 6 })
            .onChange((v: string): void => { this.session.oauthSiteId = v; })
          TextInput({ text: this.session.oauthAuthEndpoint, placeholder: '授权端点 https://…/authorize' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthAuthEndpoint = v; })
          TextInput({ text: this.session.oauthTokenEndpoint, placeholder: '令牌端点 https://…/token' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthTokenEndpoint = v; })
          TextInput({ text: this.session.oauthClientId, placeholder: 'Client ID' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthClientId = v; })
          TextInput({ text: this.session.oauthRedirect, placeholder: '回调地址(默认 http://127.0.0.1:53682/callback)' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthRedirect = v; })
          TextInput({ text: this.session.oauthScope, placeholder: 'Scope(可空)' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthScope = v; })
          Row({ space: 12 }) {
            Text('编码')
              .fontSize(13).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM)
            Text('form')
              .fontSize(14)
              .fontColor(this.session.oauthEncoding === 'form' ? this.accentColorStore : INK3)
              .constraintSize({ minWidth: 44, minHeight: 44 })
              .onClick((): void => { this.session.oauthEncoding = 'form'; })
            Text('json')
              .fontSize(14)
              .fontColor(this.session.oauthEncoding === 'json' ? this.accentColorStore : INK3)
              .constraintSize({ minWidth: 44, minHeight: 44 })
              .onClick((): void => { this.session.oauthEncoding = 'json'; })
            Blank().layoutWeight(1)
            Text('密钥方式')
              .fontSize(13).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM)
            ForEach(['none', 'body', 'basic'], (mode: string): void => {
              Text(mode)
                .fontSize(14)
                .fontColor(this.session.oauthClientAuth === mode ? this.accentColorStore : INK3)
                .constraintSize({ minWidth: 44, minHeight: 44 })
                .onClick((): void => { this.session.oauthClientAuth = mode; })
            }, (mode: string): string => mode)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .padding({ left: 14, right: 14, top: 8 })
          TextInput({ text: this.session.oauthApiOrigins, placeholder: '允许注入 Bearer 的 API origins，逗号分隔(仅 https)' })
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthApiOrigins = v; })
          TextInput({ text: this.session.oauthSecret, placeholder: 'Client Secret(留空保留；none 方式不需要)' })
            .type(InputType.Password)
            .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .margin({ left: 14, right: 14, top: 8 })
            .onChange((v: string): void => { this.session.oauthSecret = v; })
          if (this.session.oauthNote.length > 0) {
            Text(this.session.oauthNote)
              .fontSize(12).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM)
              .width('100%')
              .padding({ left: 14, right: 14, top: 8 })
          }
          Row() {
            Blank().layoutWeight(1)
            BtnFill({ label: '保存配置', onTap: (): void => { this.session.saveOauth(); } })
          }
          .width('100%')
          .padding({ left: 14, right: 14, top: 10, bottom: 14 })
        }
        .width('100%')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
  }

  build(): void { this.OauthCard() }
}
