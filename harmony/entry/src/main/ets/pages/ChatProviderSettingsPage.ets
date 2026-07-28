// ChatProviderSettingsPage — Provider 设置(MVP:单 OpenAI 兼容 provider 编辑)
// 持久化:saveProviders → Preferences KV(chat-domain D-016/D-017 线格式)
// 裁剪(D-020):多 provider 管理/brand/authMode/Google/Claude 编辑 = P1;
//   模型编辑 = 逗号分隔 modelId(生成 ProviderModel 条目)
import { router } from '@kit.ArkUI';
import type { ProviderSetting, ProviderSettingOpenAIVariant, ProviderModel } from '@amber/chat-domain';
import {
  loadProviders, saveProviders, makeProviderSettingOpenAIVariant, makeProviderModel,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const ACCENT: string = '#B8623A';

@Entry
@Component
struct ChatProviderSettingsPage {
  @State loaded: boolean = false;
  @State providerEnabled: boolean = true;
  @State name: string = 'OpenAI';
  @State baseUrl: string = 'https://api.openai.com/v1';
  @State apiKey: string = '';
  @State chatPath: string = '/chat/completions';
  @State modelsText: string = '';
  @State savedMsg: string = '';
  private providerId: string = '';
  private others: ProviderSetting[] = [];

  aboutToAppear(): void {
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const all: ProviderSetting[] = list ?? [];
      const first = all.find((p: ProviderSetting): boolean => p.type === 'openai');
      this.others = all.filter((p: ProviderSetting): boolean => p !== first);
      if (first !== undefined && first.type === 'openai') {
        this.providerId = first.id;
        this.providerEnabled = first.enabled;
        this.name = first.name;
        this.baseUrl = first.baseUrl;
        this.apiKey = first.apiKey;
        this.chatPath = first.chatCompletionsPath;
        this.modelsText = first.models.map((m: ProviderModel): string => m.modelId).join(', ');
      }
      this.loaded = true;
    });
  }

  private save(): void {
    const models: ProviderModel[] = this.modelsText
      .split(',')
      .map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0)
      .map((id: string): ProviderModel => makeProviderModel({ modelId: id, displayName: id }));
    const variant: ProviderSettingOpenAIVariant = makeProviderSettingOpenAIVariant({
      id: this.providerId.length > 0 ? this.providerId : undefined,
      enabled: this.providerEnabled,
      name: this.name.trim().length > 0 ? this.name.trim() : 'OpenAI',
      baseUrl: this.baseUrl.trim(),
      apiKey: this.apiKey.trim(),
      chatCompletionsPath: this.chatPath.trim(),
      models,
    });
    this.providerId = variant.id;
    const list: ProviderSetting[] = [variant, ...this.others];
    saveProviders(getChatKvStore(), list).then((): void => {
      this.savedMsg = '已保存';
      setTimeout((): void => { this.savedMsg = ''; }, 1500);
    });
  }

  @Builder
  field(label: string, placeholder: string, value: string, onChange: (v: string) => void, obscure: boolean) {
    Column() {
      Text(label).fontSize(12).fontColor(INK3).margin({ bottom: 4 })
      TextInput({ text: value, placeholder: placeholder })
        .fontSize(14).padding(10)
        .backgroundColor(SURFACE).borderRadius(8)
        .type(obscure ? InputType.Password : InputType.Normal)
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })
  }

  build() {
    Column() {
      Row() {
        Text('‹ 返回').fontSize(14).fontColor(INK2)
          .onClick((): void => { router.back(); })
        Blank()
        Text('Provider 设置').fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
        Text('保存').fontSize(14).fontColor(ACCENT)
          .onClick((): void => this.save())
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 12 })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            Row() {
              Text('启用').fontSize(14).fontColor(INK)
              Blank()
              Toggle({ type: ToggleType.Switch, isOn: this.providerEnabled })
                .onChange((v: boolean): void => { this.providerEnabled = v; })
            }.width('100%').margin({ bottom: 14 })

            this.field('名称', 'OpenAI', this.name,
              (v: string): void => { this.name = v; }, false)
            this.field('Base URL', 'https://api.openai.com/v1', this.baseUrl,
              (v: string): void => { this.baseUrl = v; }, false)
            this.field('API Key', 'sk-…', this.apiKey,
              (v: string): void => { this.apiKey = v; }, true)
            this.field('Chat Completions Path', '/chat/completions', this.chatPath,
              (v: string): void => { this.chatPath = v; }, false)

            Column() {
              Text('模型(逗号分隔 modelId)').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
              TextArea({ text: this.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                .fontSize(14).padding(10)
                .backgroundColor(SURFACE).borderRadius(8)
                .height(80)
                .onChange((v: string): void => { this.modelsText = v; })
            }.width('100%').alignItems(HorizontalAlign.Start)

            if (this.savedMsg.length > 0) {
              Text(this.savedMsg).fontSize(13).fontColor(ACCENT).margin({ top: 12 })
            }
          }
          .width('100%').padding({ left: 16, right: 16, top: 8 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
