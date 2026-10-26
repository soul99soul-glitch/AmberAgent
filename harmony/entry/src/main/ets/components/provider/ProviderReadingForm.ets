import { DeepReadSubpageDock, DeepReadDockLayout } from '../deepread/DeepReadSubpageDock.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

import {
  DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup, DeepReadSettingsRow, DeepReadSettingsToggle, DeepReadSettingsPicker, DeepReadSettingsNote, DeepReadSettingsButton
} from '../deepread/DeepReadSettingsVisual.ets';
import type { ProviderModel } from '@amber/chat-domain';

import { ACCENT } from '../../design/tokens.ets';

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
export struct ProviderReadingForm {
  @ObjectLink edit: ProviderEditSession;
  @ObjectLink login: ProviderLoginSession;
  @ObjectLink catalog: ProviderModelCatalogSession;
  onOpenReadingModel: (id: string) => void = (_id: string): void => {};
  onSaveAndBack: () => void = (): void => {};
  onConfigureReadingGoogle: () => void = (): void => {};
  onRemoveSelf: () => void = (): void => {};
  onExportProvider: () => void = (): void => {};
  onBack: () => void = (): void => {};
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop advancedOpen: boolean = false;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  onToggleReadingAdvanced: () => void = (): void => {};

  @Builder
  readingField(options: ProviderFieldOptions): void {
    Column({ space: 6 }) {
      Text(options.label).fontSize(13).fontColor(D.muted).width('100%')
      TextInput({ text: options.value, placeholder: options.placeholder })
        .type(options.obscure ? InputType.Password : InputType.Normal)
        .fontSize(16).fontColor(D.ink).placeholderColor(D.muted)
        .height(46).backgroundColor(D.paper).borderRadius(10)
        .padding({ left: 12, right: 12 }).enabled(!this.edit.ioBusy)
        .onChange(options.onChange)
    }.width('100%').padding({ left: 16, right: 16, top: 10, bottom: 10 })
  }

  @Builder
  ReadingConnection(): void {
    DeepReadSettingsToggle({ label: '启用服务', checked: this.edit.providerEnabled,
      onChange: (value: boolean): void => { this.edit.providerEnabled = value; } })
    this.readingField({ label: '名称', value: this.edit.name, placeholder: '模型服务名称', obscure: false, mono: false,
      onChange: (value: string): void => { this.edit.name = value; } })
    if (this.edit.provider !== null && this.edit.provider.type === 'openai') {
      DeepReadSettingsPicker({ label: '认证方式', options: this.login.authModeOptions(), selected: this.login.authModeIndex(),
        onSelect: (index: number): void => { this.login.selectAuthMode(index); } })
      if (this.edit.authMode === 'codex_oauth') {
        DeepReadSettingsRow({ label: this.login.codexBusy ? '等待登录…' : this.login.codexAccount.length > 0 ? '重新登录 ChatGPT' : '登录 ChatGPT',
          detail: this.login.codexAccount, valueAccent: true,
          onTap: (): void => { this.login.startCodexLogin(); } })
        if (this.login.codexStatus.length > 0) { DeepReadSettingsNote({ text: this.login.codexStatus }) }
      } else if (this.edit.authMode === 'grok_oauth') {
        DeepReadSettingsRow({ label: this.login.grokBusy ? '等待登录…' : this.login.grokAccount.length > 0 ? '重新登录 Grok' : '登录 Grok',
          detail: this.login.grokAccount, onTap: (): void => { this.login.startGrokLogin(); } })
        if (this.login.grokStatus.length > 0) { DeepReadSettingsNote({ text: this.login.grokStatus }) }
      }
    }
    if ((this.edit.provider?.type !== 'google' || this.edit.isGoogleApiKey())
      && this.edit.authMode !== 'codex_oauth' && this.edit.authMode !== 'grok_oauth') {
      this.readingField({ label: 'API Key', value: this.edit.apiKey, placeholder: '填写服务商提供的密钥', obscure: true, mono: true,
        onChange: (value: string): void => { this.edit.apiKey = value; } })
    }
    if (this.edit.provider?.type !== 'google' || this.edit.isGoogleApiKey()) {
      if (this.login.pinnedBaseUrl().length > 0) {
        Text(`服务地址 · ${this.login.pinnedBaseUrl()}`).fontSize(13).fontColor(D.muted)
          .width('100%').padding(16).wordBreak(WordBreak.BREAK_ALL)
      } else {
        this.readingField({ label: '服务地址', value: this.edit.baseUrl, placeholder: 'https://api.example.com/v1', obscure: false, mono: true,
          onChange: (value: string): void => { this.edit.baseUrl = value; } })
      }
    }
    if (this.edit.provider?.type === 'google') {
      DeepReadSettingsRow({ label: '保存并配置 Google 认证', detail: 'API Key、服务账号或账号授权', last: true,
        onTap: (): void => { this.onConfigureReadingGoogle(); } })
    }
  }

  @Builder
  ReadingModels(): void {
    ForEach(this.edit.readingModelIds(), (id: string, index: number): void => {
      DeepReadSettingsRow({ label: this.edit.readingModelName(id), detail: id,
        value: this.edit.provider?.models.some((model: ProviderModel): boolean => model.modelId === id) ? '参数' : '待保存',
        last: index === this.edit.readingModelIds().length - 1,
        onTap: (): void => { this.onOpenReadingModel(id); } })
    }, (id: string): string => id)
    if (this.edit.readingModelIds().length === 0) {
      Text('填写模型 ID，或从服务商拉取模型。').fontSize(15).fontColor(D.muted)
        .width('100%').padding(16)
    }
    Column({ space: 6 }) {
      Text('模型 ID').fontSize(13).fontColor(D.muted).width('100%')
      TextArea({ text: this.edit.modelsText, placeholder: '每行一个模型 ID，也可用逗号分隔' })
        .fontSize(15).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(D.paper)
        .borderRadius(10).height(84).padding(12).enabled(!this.edit.ioBusy)
        .onChange((value: string): void => { this.edit.modelsText = value; })
    }.width('100%').padding(16)
    DeepReadSettingsRow({ label: this.catalog.fetchBusy ? '正在拉取模型…' : '拉取可用模型', last: true,
      onTap: (): void => { this.catalog.fetchModels(); } }).enabled(!this.catalog.fetchBusy && !this.edit.ioBusy)
    if (this.catalog.fetchMsg.length > 0) {
      Text(this.catalog.fetchMsg).fontSize(13).fontColor(this.catalog.fetchMsg.startsWith('拉取失败') ? D.danger : D.muted)
        .lineHeight(19).width('100%').padding({ left: 16, right: 16, bottom: 12 })
    }
    if (this.catalog.fetchedIds.length > 0) {
      DeepReadSettingsRow({ label: '添加全部发现的模型', value: `${this.catalog.fetchedIds.length} 个`,
        onTap: (): void => { this.catalog.addAllFetched(); } })
      ForEach(this.catalog.fetchedIds, (id: string, index: number): void => {
        DeepReadSettingsRow({ label: id, value: '添加', valueAccent: true,
          last: index === this.catalog.fetchedIds.length - 1,
          onTap: (): void => { this.catalog.addFetchedId(id); } })
      }, (id: string): string => `found-${id}`)
    }
  }

  @Builder
  ReadingRequestOptions(): void {
    if (this.edit.provider?.type === 'openai') {
      DeepReadSettingsToggle({ label: 'Responses API', checked: this.edit.useResponseApi,
        onChange: (value: boolean): void => { this.edit.useResponseApi = value; } })
      this.readingField({ label: '请求路径', value: this.edit.chatPath, placeholder: '/chat/completions', obscure: false, mono: true,
        onChange: (value: string): void => { this.edit.chatPath = value; } })
    } else if (this.edit.provider?.type === 'google' && this.edit.isGoogleApiKey()) {
      DeepReadSettingsToggle({ label: 'Vertex AI', checked: this.edit.vertexAI,
        onChange: (value: boolean): void => { this.edit.vertexAI = value; } })
      if (this.edit.vertexAI) {
        this.readingField({ label: 'Google Cloud 项目', value: this.edit.projectId, placeholder: 'project-id', obscure: false, mono: true,
          onChange: (value: string): void => { this.edit.projectId = value; } })
        this.readingField({ label: '区域', value: this.edit.location, placeholder: 'us-central1 或 global', obscure: false, mono: true,
          onChange: (value: string): void => { this.edit.location = value; } })
      }
    } else if (this.edit.provider?.type === 'claude') {
      DeepReadSettingsToggle({ label: '提示词缓存', checked: this.edit.promptCaching, last: true,
        onChange: (value: boolean): void => { this.edit.promptCaching = value; } })
    }
    DeepReadSettingsRow({ label: '导出已保存配置', detail: '不包含 API Key 或账号令牌', last: true,
      onTap: (): void => { this.onExportProvider(); } })
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        DeepReadSettingsHeader({ title: '服务配置', onBack: (): void => { if (!this.edit.ioBusy) this.onBack(); } })
        Scroll() {
          Column() {
            if (this.edit.loading || this.edit.provider === null) {
              DeepReadSettingsNote({ text: this.edit.loading ? '正在读取…' : this.edit.loadError })
            } else {
              DeepReadSettingsSection({ title: '连接信息' })
              DeepReadSettingsGroup({ content: (): void => { this.ReadingConnection(); } }).enabled(!this.edit.ioBusy)
              if (this.edit.unsupportedAuthMsg.length > 0) { DeepReadSettingsNote({ text: this.edit.unsupportedAuthMsg }) }
              DeepReadSettingsSection({ title: '模型' })
              DeepReadSettingsGroup({ content: (): void => { this.ReadingModels(); } }).enabled(!this.edit.ioBusy)
              DeepReadSettingsNote({ text: '模型及连接信息统一保存。选择文章生成使用的模型，请返回设置页。' })
              DeepReadSettingsSection({ title: '其他选项' })
              DeepReadSettingsRow({ label: '高级设置', value: this.advancedOpen ? '收起' : '展开', last: true,
                onTap: (): void => { this.onToggleReadingAdvanced(); } })
                .backgroundColor(D.card).borderRadius(D.radius)
              if (this.advancedOpen) {
                DeepReadSettingsGroup({ content: (): void => { this.ReadingRequestOptions(); } })
                  .margin({ top: 8 }).enabled(!this.edit.ioBusy)
                  .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 160 }))
              }
              if (this.edit.savedMsg.length > 0) {
                Text(this.edit.savedMsg).fontSize(13).lineHeight(19).fontColor(this.edit.savedMsg === '已保存' ? D.done : D.danger)
                  .width('100%').padding({ left: 16, right: 16, top: 16 })
              }
              DeepReadSettingsButton({ label: this.edit.ioBusy ? '正在保存…' : '保存并返回', icon: 'check', disabled: this.edit.ioBusy,
                onTap: (): void => { this.onSaveAndBack(); } }).margin({ top: 24 })
              DeepReadSettingsRow({ label: '删除模型服务', destructive: true, chevron: false, last: true,
                onTap: (): void => { this.onRemoveSelf(); } }).margin({ top: 16 }).enabled(!this.edit.ioBusy)
            }
          }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        }.align(Alignment.TopStart).layoutWeight(1).scrollBar(BarState.Off)
      }.width('100%').height('100%').backgroundColor(D.paper)
      DeepReadSubpageDock({ onBeforeLeave: (): boolean => { return !this.edit.ioBusy; } }).align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
