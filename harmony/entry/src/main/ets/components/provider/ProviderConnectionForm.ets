

import type { ProviderModel } from '@amber/chat-domain';
import { providerSlug, providerAuthLabel } from '../../di/ProviderDefaults.ets';
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, BODY, BODY2, META, MONO_SMALL, LABEL, ERROR, RADIUS, TEXT_STYLES, lineHeightForSize, MOTION_OVERLAY
} from '../../design/tokens.ets';
import { FlatTextField } from '../ui/FlatTextField.ets';
import { Icon } from '../ui/Icon.ets';
import { BtnFill, BtnGhost, Chip, PaperSeparator, ToggleRow, SectionHeader, Seg } from '../PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../../design/fonts.ets';
import { ProviderEditSession } from '../../platform_impl/provider/ProviderEditSession.ets';
import { ProviderLoginSession } from '../../platform_impl/provider/ProviderLoginSession.ets';
import { ProviderModelCatalogSession } from '../../platform_impl/provider/ProviderModelCatalogSession.ets';

interface ProviderFieldOptions {
  label: string;
  placeholder: string;
  value: string;
  onChange: (value: string) => void;
  obscure: boolean;
  mono: boolean;
}

@Component
export struct ProviderConnectionForm {
  @ObjectLink edit: ProviderEditSession;
  @ObjectLink login: ProviderLoginSession;
  @ObjectLink catalog: ProviderModelCatalogSession;
  onOpenGoogleAuth: () => void = (): void => {};
  onOpenModelParameters: (id: string) => void = (_id: string): void => {};
  onRemoveSelf: () => void = (): void => {};
  onExportProvider: () => void = (): void => {};
  onBack: () => void = (): void => {};
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Link tab: number;

  @Builder
  field(options: ProviderFieldOptions) {
    Column() {
      Text(options.label)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(TEXT_STYLES.meta.weight)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(TEXT_STYLES.meta.family)
        .fontColor(INK3)
        .margin({ left: 2, bottom: 6 })
      FlatTextField({
        value: options.value,
        placeholder: options.placeholder,
        mono: options.mono,
        obscure: options.obscure,
        onChange: options.onChange,
      })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16 })
    .margin({ bottom: 12 })
  }

  @Builder
  tabHeader() {
    Row() {
      Column() {
        Text('配置')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(this.tab === 0 ? 600 : TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .letterSpacing(1.3)
          .fontColor(this.tab === 0 ? INK : INK3)
        Column()
          .width('100%').height(2).borderRadius(1)
          .backgroundColor(this.tab === 0 ? this.accentColorStore : Color.Transparent)
          .margin({ top: 6 })
      }
      .layoutWeight(1)
      .padding({ left: 12, right: 12 })
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.tab = 0; });
      })

      Column() {
        Text('模型')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(this.tab === 1 ? 600 : TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .letterSpacing(1.3)
          .fontColor(this.tab === 1 ? INK : INK3)
        Column()
          .width('100%').height(2).borderRadius(1)
          .backgroundColor(this.tab === 1 ? this.accentColorStore : Color.Transparent)
          .margin({ top: 6 })
      }
      .layoutWeight(1)
      .padding({ left: 12, right: 12 })
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.tab = 1; });
      })
    }
    .width('100%')
    .padding({ left: 18, right: 18 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
  }

  build(): void {
      Column() {
      // 头部:back + // PROVIDERS + slug16 Bold/点/摘要 + (占位)
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 24, color: INK })
        }
        .width(48).height(48)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ left: -10 })
        .onClick((): void => { this.onBack(); })
        .accessibilityText('返回')

        Column() {
          Row() {
            Text('//').fontSize(11).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(11))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(this.accentColorStore)
            Text(' 提供商').fontSize(11).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(11))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).letterSpacing(1.3)
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 7 }) {
            Text(this.edit.provider !== null ? providerSlug(this.edit.provider) : '--')
              .fontSize(16).fontWeight(FontWeight.Bold).lineHeight(lineHeightForSize(16)).fontFamily(FONT_MONO)
              .fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.edit.provider !== null && this.edit.provider.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore)
            }
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 6 }) {
            Text(`${this.edit.name} · ${(this.edit.provider !== null ? providerAuthLabel(this.edit.provider) : '—')} · ${this.edit.provider !== null ? this.edit.provider.models.length : 0} 个模型`)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(MONO_SMALL))
              .fontFamily(FONT_MONO).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(VerticalAlign.Center)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 8 })

        // 导出脱敏 JSON(不含 API Key)
        BtnGhost({
          label: '导出',
          onTap: (): void => { this.onExportProvider(); },
        })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 20, right: 14, top: this.statusBarHeight + 8, bottom: 8 })
      .alignItems(VerticalAlign.Center)

      this.tabHeader()

      Scroll() {
        Column() {
          if (this.tab === 0) {
            SectionHeader({ label: '认证与端点' })
            ToggleRow({
              label: '启用', checked: this.edit.providerEnabled, isLast: false,
              onToggle: (): void => { this.edit.providerEnabled = !this.edit.providerEnabled; },
            })

            if (this.edit.provider !== null && this.edit.provider.type === 'openai') {
              ToggleRow({
                label: '使用 Responses API',
                checked: this.edit.useResponseApi,
                isLast: false,
                onToggle: (): void => { this.edit.useResponseApi = !this.edit.useResponseApi; },
              })
              // 认证方式(brand 决定可选:API Key / Codex 登录 / Coding Plan)
              Column() {
                Text('认证方式')
                  .fontSize(TEXT_STYLES.meta.size)
                  .fontWeight(TEXT_STYLES.meta.weight)
                  .lineHeight(TEXT_STYLES.meta.lineHeight)
                  .fontFamily(TEXT_STYLES.meta.family)
                  .fontColor(INK3)
                  .margin({ left: 2, bottom: 6 })
                Seg({
                  options: this.login.authModeOptions(),
                  selected: this.login.authModeIndex(),
                  onSelect: (idx: number): void => { this.login.selectAuthMode(idx); },
                })
                if (this.edit.authMode === 'codex_oauth') {
                  Row() {
                    Text(this.login.codexAccount.length > 0 ? `已登录:${this.login.codexAccount}` : '设备码授权,免 API Key')
                      .fontSize(LABEL).fontColor(INK3).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(this.login.codexBusy ? '等待登录…' : (this.login.codexAccount.length > 0 ? '重新登录' : '登录 ChatGPT'))
                      .fontSize(META).fontColor(this.login.codexBusy ? INK3 : this.accentColorStore)
                      .padding({ left: 12, top: 8, bottom: 8 })
                      .onClick((): void => { this.login.startCodexLogin(); })
                  }
                  .width('100%').margin({ top: 10 })
                  if (this.login.codexStatus.length > 0) {
                    Text(this.login.codexStatus)
                      .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 4 })
                  }
                }
                if (this.edit.authMode === 'grok_oauth') {
                  Row() {
                    Text(this.login.grokAccount.length > 0 ? `已登录:${this.login.grokAccount}` : '浏览器授权,免 API Key')
                      .fontSize(LABEL).fontColor(INK3).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(this.login.grokAccount.length > 0 ? '重新登录' : '登录 xAI')
                      .fontSize(META).fontColor(this.accentColorStore)
                      .padding({ left: 12, top: 8, bottom: 8 })
                      .onClick((): void => { this.login.startGrokLogin(); })
                  }
                  .width('100%').margin({ top: 10 })
                  if (this.login.grokStatus.length > 0) {
                    Text(this.login.grokStatus)
                      .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 4 })
                  }
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12 })
            }

            this.field({
              label: '名称', placeholder: 'Provider 名称', value: this.edit.name,
              onChange: (v: string): void => { this.edit.name = v; }, obscure: false, mono: false,
            })

            if (this.edit.unsupportedAuthMsg.length > 0) {
              Column() {
                Column() {
                  Text(this.edit.unsupportedAuthMsg)
                    .fontSize(LABEL).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(LABEL))
                      .fontFamily(TEXT_STYLES.eyebrow.family).fontColor(ERROR)
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                .backgroundColor(SURFACE)
                .border({ width: 1, color: ERROR, radius: RADIUS })
              }
              .width('100%')
              .padding({ left: 16, right: 16 })
              .margin({ bottom: 12 })
            }

            if (this.edit.provider === null || this.edit.provider.type !== 'google' || this.edit.isGoogleApiKey()) {
              this.field({
                label: 'API Key',
                placeholder: this.edit.provider !== null && this.edit.provider.type === 'claude' ? 'sk-ant-…' : 'sk-…',
                value: this.edit.apiKey, onChange: (v: string): void => { this.edit.apiKey = v; },
                obscure: true, mono: true,
              })
            }

            if (this.edit.provider !== null && this.edit.provider.type === 'openai') {
              if (this.login.pinnedBaseUrl().length > 0) {
                // 只读展示当前认证模式要求的固定官方地址
                Column() {
                  Text('Base URL')
                    .fontSize(TEXT_STYLES.meta.size)
                    .fontWeight(TEXT_STYLES.meta.weight)
                    .lineHeight(TEXT_STYLES.meta.lineHeight)
                    .fontFamily(TEXT_STYLES.meta.family)
                    .fontColor(INK3)
                    .margin({ left: 2, bottom: 6 })
                  Row() {
                    Text(this.login.pinnedBaseUrl())
                      .fontSize(BODY).fontFamily(FONT_MONO).fontColor(INK2)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .width('100%')
                  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                  .backgroundColor(SURFACE2)
                  .borderRadius(12)
                  .border({ width: 1, color: LINE })
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                .padding({ left: 16, right: 16 })
                .margin({ bottom: 12 })
              } else {
                this.field({
                  label: 'Base URL', placeholder: 'https://api.openai.com/v1', value: this.edit.baseUrl,
                  onChange: (v: string): void => { this.edit.baseUrl = v; }, obscure: false, mono: true,
                })
              }
              this.field({
                label: 'API Path', placeholder: '/chat/completions', value: this.edit.chatPath,
                onChange: (v: string): void => { this.edit.chatPath = v; }, obscure: false, mono: true,
              })
            } else if (this.edit.provider !== null && this.edit.provider.type === 'google') {
              Column() {
                BtnGhost({ label: 'Google 认证与服务账号', onTap: (): void => { this.onOpenGoogleAuth(); } })
                  .width('100%')
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ bottom: 12 })
              if (this.edit.isGoogleApiKey()) {
                ToggleRow({
                label: 'Vertex AI', checked: this.edit.vertexAI, isLast: this.edit.vertexAI,
                onToggle: (): void => { this.edit.vertexAI = !this.edit.vertexAI; },
                })
                if (!this.edit.vertexAI) {
                  this.field({
                    label: 'Base URL', placeholder: 'https://generativelanguage.googleapis.com/v1beta',
                    value: this.edit.baseUrl, onChange: (v: string): void => { this.edit.baseUrl = v; },
                    obscure: false, mono: true,
                  })
                }
              }
            } else if (this.edit.provider !== null && this.edit.provider.type === 'claude') {
              this.field({
                label: 'Base URL', placeholder: 'https://api.anthropic.com/v1', value: this.edit.baseUrl,
                onChange: (v: string): void => { this.edit.baseUrl = v; }, obscure: false, mono: true,
              })
              ToggleRow({
                label: 'Prompt Caching', checked: this.edit.promptCaching, isLast: true,
                onToggle: (): void => { this.edit.promptCaching = !this.edit.promptCaching; },
              })
            }

            // 保存(Android ProviderSplitBar 语义:显式提交)
            Row() {
              Text(this.edit.savedMsg.length > 0 ? this.edit.savedMsg : ' ')
                .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(this.edit.savedMsg.startsWith('保存失败') ? ERROR : this.accentColorStore)
                .layoutWeight(1)
              BtnFill({ label: '保存', onTap: (): void => { this.edit.save(); } })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .padding({ left: 16, right: 16 })
            .margin({ top: 8, bottom: 20 })

            // 删除置底
            Row() {
              Text('删除此 Provider')
                .fontSize(BODY2).fontColor(ERROR).lineHeight(lineHeightForSize(BODY2)).fontFamily(TEXT_STYLES.secondary.family)
                .padding({ top: 12, bottom: 12 })
                .onClick((): void => this.onRemoveSelf())
              Blank()
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .padding({ left: 16, right: 16 })
          } else {
            SectionHeader({ label: '模型列表' })
            Column() {
              ForEach(this.edit.provider !== null ? this.edit.provider.models : [],
                (m: ProviderModel, mIdx: number): void => {
                  Column() {
                    Row() {
                      Column() {
                        Text(m.displayName.length > 0 ? m.displayName : m.modelId)
                          .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
                          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                        Text(m.modelId)
                          .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                          .fontFamily(FONT_MONO).fontColor(INK3)
                          .margin({ top: 3 })
                          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                      .alignItems(HorizontalAlign.Start).layoutWeight(1)
                      if (m.type === 'image') {
                        Chip({ text: '生图' }).margin({ right: 8 })
                      }
                      Text('参数')
                        .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                        .fontFamily(FONT_MONO_MEDIUM).fontColor(this.accentColorStore)
                    }
                    .width('100%').alignItems(VerticalAlign.Center)
                    .constraintSize({ minHeight: 64 })
                    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                    .onClick((): void => {
                      this.onOpenModelParameters(m.id);
                    })
                    if (mIdx < (this.edit.provider !== null ? this.edit.provider.models.length : 0) - 1) {
                      PaperSeparator()
                    }
                  }
                  .width('100%')
                }, (m: ProviderModel): string => m.id)
              if (this.edit.provider !== null && this.edit.provider.models.length === 0) {
                Text('暂无模型 — 在下方以逗号分隔添加 modelId')
                  .fontSize(LABEL).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                  .fontFamily(TEXT_STYLES.eyebrow.family).fontColor(INK4)
                  .width('100%').textAlign(TextAlign.Center)
                  .padding({ top: 16, bottom: 16 })
              }
            }
            .width('100%')

            Column() {
              Text('添加模型(逗号分隔 modelId,保存后生效)')
                .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                .margin({ left: 16, bottom: 8 })
              Column() {
                TextArea({ text: this.edit.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                  .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                  .fontFamily(FONT_MONO).fontColor(INK)
                  .backgroundColor(SURFACE2).borderRadius(8)
                  .border({ width: 1, color: LINE })
                  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                  .height(80)
                  .onChange((v: string): void => { this.edit.modelsText = v; })
              }.width('100%').padding({ left: 16, right: 16 })
              Row() {
                Text(this.catalog.fetchMsg)
                  .fontSize(META).fontColor(INK3).layoutWeight(1)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(this.catalog.fetchBusy ? '拉取中…' : '拉取可用模型')
                  .fontSize(META).fontColor(this.catalog.fetchBusy ? INK3 : this.accentColorStore)
                  .padding({ left: 12, top: 6, bottom: 6 })
                  .onClick((): void => { this.catalog.fetchModels(); })
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ top: 10 })
              if (this.catalog.fetchedIds.length > 0) {
                Column() {
                  Row() {
                    Text('可添加')
                      .fontSize(META).fontColor(INK3).layoutWeight(1)
                    Text('全部添加')
                      .fontSize(META).fontColor(this.accentColorStore)
                      .padding({ left: 12, top: 6, bottom: 6 })
                      .onClick((): void => { this.catalog.addAllFetched(); })
                  }
                  .width('100%')
                  Flex({ wrap: FlexWrap.Wrap }) {
                    ForEach(this.catalog.fetchedIds, (id: string): void => {
                      Text(id)
                        .fontSize(META).fontFamily(FONT_MONO).fontColor(INK)
                        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                        .backgroundColor(RAISED)
                        .border({ width: 1, color: LINE, radius: 8 })
                        .margin({ right: 8, top: 8 })
                        .onClick((): void => { this.catalog.addFetchedId(id); })
                    }, (id: string): string => id)
                  }
                  .width('100%')
                }
                .width('100%').alignItems(HorizontalAlign.Start)
                .padding({ left: 16, right: 16, top: 4, bottom: 4 })
              }
              Row() {
                Blank()
                BtnFill({ label: '保存模型', onTap: (): void => { this.edit.save(); } })
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ top: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 12 })
          }
        }
        .width('100%')
        .padding({ bottom: 40 })
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

  }
}
