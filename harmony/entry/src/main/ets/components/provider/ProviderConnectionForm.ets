

import type { ProviderModel } from '@amber/chat-domain';
import { providerSlug, providerAuthLabel } from '../../di/ProviderDefaults.ets';
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, BODY, BODY2, META, MONO_SMALL, LABEL, ERROR, RADIUS, TEXT_STYLES, lineHeightForSize
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
  @State manualAddOpen: boolean = false;
  @State manualModelInput: string = '';
  @State fetchedSearch: string = '';
  @State tabProgress: number = 0;
  @State swiperWidth: number = 0;
  @State tabBarWidth: number = 0;
  private initialTab: number = 0;
  private swiperController: SwiperController = new SwiperController();

  aboutToAppear(): void {
    this.initialTab = this.tab;
    this.tabProgress = this.tab;
  }

  private switchTab(target: number): void {
    if (target === this.tab) return;
    this.swiperController.changeIndex(target, true);
  }

  private filteredFetchedIds(): string[] {
    const q: string = this.fetchedSearch.trim().toLowerCase();
    if (q.length === 0) return this.catalog.fetchedIds;
    return this.catalog.fetchedIds.filter((id: string): boolean => id.toLowerCase().includes(q));
  }

  private submitManualModels(): void {
    if (this.catalog.addManualIds(this.manualModelInput)) {
      this.manualModelInput = '';
      this.manualAddOpen = false;
    }
  }

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
    Column() {
      Stack({ alignContent: Alignment.BottomStart }) {
        Row() {
          Column() {
            Text('配置')
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(this.tab === 0 ? 600 : TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(TEXT_STYLES.body.family)
              .letterSpacing(1.3)
              .fontColor(this.tab === 0 ? INK : INK3)
          }
          .layoutWeight(1)
          .padding({ top: 4, bottom: 10 })
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .onClick((): void => { this.switchTab(0); })

          Column() {
            Text('模型')
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(this.tab === 1 ? 600 : TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(TEXT_STYLES.body.family)
              .letterSpacing(1.3)
              .fontColor(this.tab === 1 ? INK : INK3)
          }
          .layoutWeight(1)
          .padding({ top: 4, bottom: 10 })
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .onClick((): void => { this.switchTab(1); })
        }
        .width('100%')

        // 单一连续指示条：点击与左右手势滑切时用数值 vp 平滑跟手平移
        Row() {
          Column()
            .width('100%')
            .height(2)
            .borderRadius(1)
            .backgroundColor(this.accentColorStore)
        }
        .width('50%')
        .padding({ left: 12, right: 12 })
        .translate({ x: this.tabBarWidth > 0 ? this.tabProgress * (this.tabBarWidth / 2) : `${this.tabProgress * 100}%` })
        .hitTestBehavior(HitTestMode.None)
      }
      .width('100%')
      .onAreaChange((_old: Area, area: Area): void => {
        const w: number = Number(area.width);
        if (Number.isFinite(w) && w > 0) this.tabBarWidth = w;
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

      Swiper(this.swiperController) {
        Scroll() {
          Column() {
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
          }
          .width('100%')
          .padding({ bottom: 40 })
        }
        .align(Alignment.TopStart)
        .width('100%')
        .height('100%')
        .backgroundColor(BG)

        Scroll() {
          Column() {
            // 顶部快捷操作栏：自动获取 + 手动添加（即点即存，告别逗号文本编辑与底部二次保存）
            Row({ space: 10 }) {
              Row({ space: 6 }) {
                Icon({ name: 'retry', iconSize: 14, color: this.catalog.fetchBusy ? INK3 : this.accentColorStore })
                Text(this.catalog.fetchBusy ? '正在获取…' : '自动获取模型')
                  .fontSize(13.5).fontWeight(FontWeight.Medium)
                  .fontColor(this.catalog.fetchBusy ? INK3 : this.accentColorStore)
              }
              .layoutWeight(1).height(42)
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 0.75, color: LINE })
              .onClick((): void => { this.catalog.fetchModels(); })

              Row({ space: 6 }) {
                Icon({ name: 'plus', iconSize: 14, color: this.accentColorStore })
                Text('手动添加')
                  .fontSize(13.5).fontWeight(FontWeight.Medium)
                  .fontColor(this.accentColorStore)
              }
              .layoutWeight(1).height(42)
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 0.75, color: LINE })
              .onClick((): void => { this.manualAddOpen = !this.manualAddOpen; })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 4 })

            if (this.manualAddOpen) {
              Row({ space: 8 }) {
                TextInput({ text: this.manualModelInput, placeholder: '输入模型 ID（支持逗号分隔多个）' })
                  .layoutWeight(1).height(38)
                  .fontSize(13.5).fontFamily(FONT_MONO).fontColor(INK)
                  .placeholderColor(INK4)
                  .backgroundColor(SURFACE2).borderRadius(10)
                  .padding({ left: 12, right: 12 })
                  .onChange((v: string): void => { this.manualModelInput = v; })
                  .onSubmit((): void => { this.submitManualModels(); })
                Text('添加')
                  .fontSize(13.5).fontWeight(FontWeight.Medium)
                  .fontColor('#FFFFFF')
                  .height(38).padding({ left: 14, right: 14 })
                  .backgroundColor(this.manualModelInput.trim().length > 0 ? this.accentColorStore : INK4)
                  .borderRadius(10)
                  .textAlign(TextAlign.Center)
                  .onClick((): void => { this.submitManualModels(); })
              }
              .width('100%')
              .padding(12)
              .margin({ left: 16, right: 16, top: 8 })
              .backgroundColor(SURFACE).borderRadius(14)
              .border({ width: 0.75, color: LINE })
              .alignItems(VerticalAlign.Center)
            }

            if (this.catalog.fetchMsg.length > 0 || this.edit.savedMsg.length > 0) {
              Text(this.edit.savedMsg.length > 0 ? this.edit.savedMsg : this.catalog.fetchMsg)
                .fontSize(META).fontColor(INK3)
                .width('100%')
                .padding({ left: 18, right: 18, top: 6 })
            }

            SectionHeader({
              label: `已启用模型 (${this.edit.provider !== null ? this.edit.provider.models.length : 0})`,
            })
            Column() {
              if (this.edit.provider !== null && this.edit.provider.models.length > 0) {
                ForEach(this.edit.provider.models,
                  (m: ProviderModel, mIdx: number): void => {
                    Column() {
                      Row({ space: 8 }) {
                        Column() {
                          Text(m.displayName.length > 0 ? m.displayName : m.modelId)
                            .fontSize(15).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
                            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                          if (m.displayName.length > 0 && m.displayName !== m.modelId) {
                            Text(m.modelId)
                              .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                              .fontFamily(FONT_MONO).fontColor(INK3)
                              .margin({ top: 2 })
                              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                          }
                        }
                        .alignItems(HorizontalAlign.Start).layoutWeight(1)
                        .onClick((): void => { this.onOpenModelParameters(m.id); })

                        if (m.type === 'image') {
                          Chip({ text: '生图' })
                        }
                        Row({ space: 4 }) {
                          Icon({ name: 'gear', iconSize: 13, color: INK2 })
                          Text('设置')
                            .fontSize(12).fontWeight(FontWeight.Medium)
                            .fontColor(INK2)
                        }
                        .height(30)
                        .padding({ left: 9, right: 10 })
                        .borderRadius(8)
                        .backgroundColor(BG)
                        .border({ width: 0.75, color: LINE })
                        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                        .accessibilityText(`设置模型 ${m.modelId}`)
                        .onClick((): void => { this.onOpenModelParameters(m.id); })
                        Row() {
                          Icon({ name: 'close', iconSize: 11.5, color: INK3 })
                        }
                        .width(30).height(30).borderRadius(8)
                        .backgroundColor(BG)
                        .border({ width: 0.75, color: LINE })
                        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                        .accessibilityText(`移除模型 ${m.modelId}`)
                        .onClick((): void => { this.catalog.removeModelId(m.modelId); })
                      }
                      .width('100%').alignItems(VerticalAlign.Center)
                      .constraintSize({ minHeight: 52 })
                      .padding({ left: 16, right: 12, top: 10, bottom: 10 })

                      if (mIdx < (this.edit.provider !== null ? this.edit.provider.models.length : 0) - 1) {
                        PaperSeparator()
                      }
                    }
                    .width('100%')
                  }, (m: ProviderModel): string => m.id)
              } else {
                Text('还没有模型，点上方「自动获取模型」或「手动添加」')
                  .fontSize(META).fontColor(INK4)
                  .width('100%').textAlign(TextAlign.Center)
                  .padding({ left: 16, right: 16, top: 20, bottom: 20 })
              }
            }
            .width('calc(100% - 32vp)')
            .margin({ left: 16, right: 16 })
            .backgroundColor(SURFACE)
            .borderRadius(16)
            .border({ width: 0.75, color: LINE })
            .clip(true)

            if (this.catalog.fetchedIds.length > 0) {
              Row() {
                Text(`可添加 (${this.filteredFetchedIds().length})`)
                  .fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3).layoutWeight(1)
                Text('全部添加')
                  .fontSize(META).fontWeight(FontWeight.Medium).fontColor(this.accentColorStore)
                  .padding({ left: 12, top: 6, bottom: 6 })
                  .onClick((): void => { this.catalog.addAllFetched(); })
              }
              .width('100%')
              .padding({ left: 18, right: 18, top: 18, bottom: 6 })

              if (this.catalog.fetchedIds.length >= 6) {
                Row({ space: 6 }) {
                  Icon({ name: 'search', iconSize: 14, color: INK4 })
                  TextInput({ text: this.fetchedSearch, placeholder: '搜索可添加模型…' })
                    .layoutWeight(1).height(34)
                    .fontSize(13).fontFamily(FONT_MONO).fontColor(INK)
                    .placeholderColor(INK4)
                    .backgroundColor(Color.Transparent)
                    .padding({ left: 2, right: 2 })
                    .onChange((v: string): void => { this.fetchedSearch = v; })
                  if (this.fetchedSearch.length > 0) {
                    Row() {
                      Icon({ name: 'close', iconSize: 11, color: INK3 })
                    }
                    .width(24).height(24).borderRadius(12)
                    .backgroundColor(SURFACE2)
                    .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                    .onClick((): void => { this.fetchedSearch = ''; })
                  }
                }
                .width('calc(100% - 32vp)')
                .height(38)
                .padding({ left: 12, right: 10 })
                .margin({ left: 16, right: 16, bottom: 8 })
                .backgroundColor(SURFACE)
                .borderRadius(12)
                .border({ width: 0.75, color: LINE })
                .alignItems(VerticalAlign.Center)
              }

              Column() {
                ForEach(this.filteredFetchedIds(), (id: string, fIdx: number): void => {
                  Column() {
                    Row({ space: 10 }) {
                      Text(id)
                        .fontSize(14).fontFamily(FONT_MONO).fontColor(INK)
                        .layoutWeight(1)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      Row({ space: 4 }) {
                        Icon({ name: 'plus', iconSize: 11, color: this.accentColorStore })
                        Text('添加')
                          .fontSize(12).fontWeight(FontWeight.Medium).fontColor(this.accentColorStore)
                      }
                      .height(30)
                      .padding({ left: 9, right: 10 })
                      .backgroundColor(BG)
                      .borderRadius(8)
                      .border({ width: 0.75, color: LINE })
                      .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                    }
                    .width('100%').constraintSize({ minHeight: 48 })
                    .padding({ left: 16, right: 14, top: 10, bottom: 10 })
                    .alignItems(VerticalAlign.Center)
                    .onClick((): void => { this.catalog.addFetchedId(id); })

                    if (fIdx < this.filteredFetchedIds().length - 1) {
                      PaperSeparator()
                    }
                  }
                  .width('100%')
                }, (id: string): string => id)
                if (this.filteredFetchedIds().length === 0) {
                  Text('没有匹配的模型')
                    .fontSize(META).fontColor(INK4)
                    .width('100%').textAlign(TextAlign.Center)
                    .padding({ top: 16, bottom: 16 })
                }
              }
              .width('calc(100% - 32vp)')
              .margin({ left: 16, right: 16 })
              .backgroundColor(SURFACE)
              .borderRadius(16)
              .border({ width: 0.75, color: LINE })
              .clip(true)
            }
          }
          .width('100%')
          .padding({ bottom: 40 })
        }
        .align(Alignment.TopStart)
        .width('100%')
        .height('100%')
        .backgroundColor(BG)
      }
      .index(this.initialTab)
      .loop(false)
      .indicator(false)
      .duration(280)
      .curve(Curve.FastOutSlowIn)
      .width('100%')
      .layoutWeight(1)
      .onAreaChange((_old: Area, area: Area): void => {
        const w: number = Number(area.width);
        if (Number.isFinite(w) && w > 0) this.swiperWidth = w;
      })
      .onGestureSwipe((index: number, extraInfo: SwiperAnimationEvent): void => {
        if (this.swiperWidth <= 0) return;
        const delta: number = -extraInfo.currentOffset / this.swiperWidth;
        const clamped: number = Math.max(0, Math.min(1, index + delta));
        this.tabProgress = clamped;
      })
      .onAnimationStart((_index: number, targetIndex: number): void => {
        this.tab = targetIndex;
        this.getUIContext().animateTo({ duration: 240, curve: Curve.FastOutSlowIn }, (): void => {
          this.tabProgress = targetIndex;
        });
      })
      .onChange((index: number): void => {
        this.tab = index;
        this.tabProgress = index;
      })
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

  }
}
