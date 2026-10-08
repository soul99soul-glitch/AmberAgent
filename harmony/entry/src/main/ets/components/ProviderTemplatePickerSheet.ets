// ProviderTemplatePickerSheet — 添加提供商(amber-zhenji / Android ProviderTemplatePickerSheet 同构)
// 结构:// OAUTH 区(Codex → 登录配置, Gemini → 暂不支持提示)+
//   // 预置 区(DEFAULT_PROVIDERS 减去已存在的 id),行 = monogram34 + 名称 +
//   subtitle(slug)+ auth 徽标。选择 → onPickPreset(整份 ProviderSetting)。
import {
  ACCENT, BG, INK, INK2, INK3, INK4, LABEL, LINE, META, MOTION_OVERLAY, OVERLAY, SURFACE2,
} from '../design/tokens.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadSettingsSection, DeepReadSettingsGroup, DeepReadSettingsRow, DeepReadSettingsNote } from './deepread/DeepReadSettingsVisual.ets';
import type { ProviderSetting } from '@amber/chat-domain';
import { makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderSettingClaude } from '@amber/chat-domain';
import { DEFAULT_PROVIDERS, toProviderMonogram, providerSlug } from '../di/ProviderDefaults.ets';

export type OAuthTemplateKind = 'codex' | 'gemini';

interface OAuthRow {
  kind: OAuthTemplateKind;
  monogram: string;
  name: string;
  subtitle: string;
}

const OAUTH_ROWS: OAuthRow[] = [
  { kind: 'codex', monogram: 'OC', name: 'OpenAI Codex OAuth', subtitle: 'openai · chatgpt.com 后端' },
  { kind: 'gemini', monogram: 'GO', name: 'Gemini OAuth', subtitle: 'google-gemini · cloudcode-pa' },
];

@Component
export struct ProviderTemplatePickerSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop show: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop existingIds: string = ''; // JSON 数组字符串(ArkTS @Prop 传数组受限,序列化传递)
  onPickPreset: (p: ProviderSetting) => void = (_p: ProviderSetting): void => {};
  onOauthPick: (kind: OAuthTemplateKind) => void = (_kind: OAuthTemplateKind): void => {};
  onDismiss: () => void = (): void => {};

  private pickerTransition(card: boolean): TransitionEffect {
    if (getProductKind() === 'deepread') {
      if (this.appBackgrounded) return TransitionEffect.IDENTITY;
      if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseOut });
    }
    const effect: TransitionEffect = card
      ? TransitionEffect.translate({ y: 80 }).combine(TransitionEffect.OPACITY) : TransitionEffect.OPACITY;
    return effect.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut });
  }

  private bundled(): ProviderSetting[] {
    if (getProductKind() === 'deepread') return DEFAULT_PROVIDERS;
    const exist: string = this.existingIds;
    return DEFAULT_PROVIDERS.filter((p: ProviderSetting): boolean => exist.indexOf(`"${p.id}"`) < 0);
  }

  @Builder
  sectionLabel(text: string) {
    Row() {
      Text('//').fontSize(11).fontWeight(FontWeight.Medium).fontColor(ACCENT)
        .fontFamily('JetBrainsMono')
      Text(` ${text.toUpperCase()}`).fontSize(11).fontWeight(FontWeight.Medium)
        .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
      Blank()
    }
    .width('100%')
    .padding({ top: 18, bottom: 10 })
  }

  @Builder
  tagBadge(text: string) {
    Text(text)
      .fontSize(10)
      .fontWeight(FontWeight.Medium)
      .fontFamily('JetBrainsMono')
      .fontColor(text === '—' ? INK4 : INK3)
      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
      .backgroundColor(SURFACE2)
      .borderRadius(3)
      .border({ width: 1, color: LINE })
  }

  @Builder
  ReadingPresets(): void {
    ForEach(this.bundled(), (provider: ProviderSetting, index: number): void => {
      DeepReadSettingsRow({ label: provider.name, detail: providerSlug(provider),
        last: index === this.bundled().length - 1,
        onTap: (): void => { this.onPickPreset(provider); } })
    }, (provider: ProviderSetting): string => provider.id)
  }

  private pickProtocol(protocol: string): void {
    if (protocol === 'claude') {
      this.onPickPreset(makeProviderSettingClaude({ name: 'Claude' }));
    } else if (protocol === 'google') {
      this.onPickPreset(makeProviderSettingGoogle({ name: 'Google Gemini' }));
    } else {
      this.onPickPreset(makeProviderSettingOpenAIVariant({ name: 'OpenAI 兼容 API' }));
    }
  }

  @Builder
  ReadingProtocols(): void {
    DeepReadSettingsRow({ label: 'OpenAI 兼容 API',
      onTap: (): void => { this.pickProtocol('openai'); } })
    DeepReadSettingsRow({ label: 'Google Gemini',
      onTap: (): void => { this.pickProtocol('google'); } })
    DeepReadSettingsRow({ label: 'Claude', last: true,
      onTap: (): void => { this.pickProtocol('claude'); } })
  }

  @Builder
  ReadingLogin(): void {
    DeepReadSettingsRow({ label: '使用 ChatGPT 登录', detail: '通过设备码连接 Codex', last: true,
      onTap: (): void => { this.onOauthPick('codex'); } })
  }

  @Builder
  ReadingSheet(): void {
    Column() {
      Row().width(42).height(4).backgroundColor(D.rule).borderRadius(2).margin({ top: 10, bottom: 12 })
      Row() {
        Text('添加模型服务').fontSize(22).fontWeight(FontWeight.Bold).fontColor(D.ink).layoutWeight(1)
        Text('取消').fontSize(17).fontColor(D.accent).height(44)
          .onClick((): void => { this.onDismiss(); })
      }.width('100%').padding({ left: D.gutter, right: D.gutter })
      Scroll() {
        Column() {
          DeepReadSettingsSection({ title: '自定义服务' })
          DeepReadSettingsGroup({ content: (): void => { this.ReadingProtocols(); } })
          DeepReadSettingsSection({ title: '品牌预置' })
          DeepReadSettingsGroup({ content: (): void => { this.ReadingPresets(); } })
          DeepReadSettingsNote({ text: '选择预置后，可以修改名称和服务地址。同一服务支持添加多份配置。' })
          DeepReadSettingsSection({ title: '账号连接' })
          DeepReadSettingsGroup({ content: (): void => { this.ReadingLogin(); } })
        }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: 24 + this.navBarHeight })
      }.layoutWeight(1).scrollBar(BarState.Off)
    }.width('100%').height('88%').backgroundColor(D.paper)
      .borderRadius({ topLeft: 24, topRight: 24 }).onClick((): void => {})
      .transition(this.pickerTransition(true))
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack({ alignContent: Alignment.Bottom }) {
        Column()
          .width('100%')
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onDismiss(); })
          .transition(this.pickerTransition(false))

        if (getProductKind() === 'deepread') {
          this.ReadingSheet()
        } else {
        Column() {
          Text('')
            .width(42).height(4).borderRadius(2)
            .backgroundColor(LINE)
            .margin({ top: 10, bottom: 6 })

          Scroll() {
            Column() {
              Text('添加提供商')
                .fontSize(22)
                .fontWeight(FontWeight.Bold)
                .fontColor(INK)
                .width('100%')
                .margin({ top: 6, bottom: 4 })

              this.sectionLabel('OAUTH')
              ForEach(OAUTH_ROWS, (row: OAuthRow): void => {
                Row() {
                  Text(row.monogram)
                    .fontSize(11).fontWeight(FontWeight.Bold)
                    .fontFamily('JetBrainsMono')
                    .fontColor(INK2)
                    .width(34).height(34)
                    .borderRadius(9)
                    .backgroundColor(SURFACE2)
                    .border({ width: 1, color: LINE })
                    .textAlign(TextAlign.Center)
                  Column() {
                    Text(row.name)
                      .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(row.subtitle)
                      .fontSize(12).fontColor(INK4)
                      .fontFamily('JetBrainsMono')
                      .margin({ top: 2 })
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  .margin({ left: 12 })

                  this.tagBadge('oauth')
                }
                .width('100%')
                .padding({ left: 4, right: 4, top: 10, bottom: 10 })
                .onClick((): void => { this.onOauthPick(row.kind); })
              }, (row: OAuthRow): string => `oa-${row.monogram}`)

              this.sectionLabel('预置')
              ForEach(this.bundled(), (p: ProviderSetting): void => {
                Row() {
                  Text(toProviderMonogram(p.name))
                    .fontSize(11).fontWeight(FontWeight.Bold)
                    .fontFamily('JetBrainsMono')
                    .fontColor(INK2)
                    .width(34).height(34)
                    .borderRadius(9)
                    .backgroundColor(SURFACE2)
                    .border({ width: 1, color: LINE })
                    .textAlign(TextAlign.Center)
                  Column() {
                    Text(p.name)
                      .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(providerSlug(p))
                      .fontSize(12).fontColor(INK4)
                      .fontFamily('JetBrainsMono')
                      .margin({ top: 2 })
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  .margin({ left: 12 })

                  this.tagBadge('key')
                }
                .width('100%')
                .padding({ left: 4, right: 4, top: 10, bottom: 10 })
                .onClick((): void => { this.onPickPreset(p); })
              }, (p: ProviderSetting): string => `pre-${p.id}`)

              Text('列表里没有的?选一个相近的预置,进详情改 Base URL 即可。')
                .fontSize(12).fontColor(INK3)
                .width('100%')
                .margin({ top: 8, bottom: 16 })
            }
            .width('100%')
            .padding({ left: 18, right: 18, bottom: 12 })
          }
          .align(Alignment.TopStart)
          .layoutWeight(1)
          .scrollBar(BarState.Off)

          Text('关闭')
            .fontSize(14).fontColor(INK3)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 14 })
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%')
        .height('88%')
        .backgroundColor(BG)
        .borderRadius({ topLeft: 16, topRight: 16 })
        .onClick((): void => {})
        .transition(TransitionEffect.translate({ y: 80 }).combine(TransitionEffect.OPACITY)
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%')
      .height('100%')
    }
  }
}
