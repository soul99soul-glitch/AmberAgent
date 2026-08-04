// SettingProviderConfigPage — Provider 配置页
// 编辑单个 Provider:name / baseUrl / apiKey(掩码) / enabled / 模型列表
// 测试连接(占位) + 保存。占位持久化,接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface ProviderConfig {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  enabled: boolean;
  models: string[];
}

function makeProvider(id: string, name: string, baseUrl: string, apiKey: string, enabled: boolean, models: string[]): ProviderConfig {
  return { id: id, name: name, baseUrl: baseUrl, apiKey: apiKey, enabled: enabled, models: models };
}

function placeholderLoadProvider(): Promise<ProviderConfig> {
  const seed: ProviderConfig = makeProvider(
    'p-1', 'OpenAI', 'https://api.openai.com/v1', 'sk-************', true,
    ['gpt-4o', 'gpt-4o-mini', 'o1-mini']);
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingProviderConfigPage {
  @State config: ProviderConfig = makeProvider('', '', '', '', true, []);
  @State newModel: string = '';
  @State testStatus: string = '';
  @State savedMsg: string = '';
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadProvider().then((p: ProviderConfig): void => {
      this.config = p;
      this.loaded = true;
    });
  }

  private setName(v: string): void {
    const c: ProviderConfig = this.config;
    this.config = makeProvider(c.id, v, c.baseUrl, c.apiKey, c.enabled, c.models);
  }

  private setBaseUrl(v: string): void {
    const c: ProviderConfig = this.config;
    this.config = makeProvider(c.id, c.name, v, c.apiKey, c.enabled, c.models);
  }

  private setApiKey(v: string): void {
    const c: ProviderConfig = this.config;
    this.config = makeProvider(c.id, c.name, c.baseUrl, v, c.enabled, c.models);
  }

  private setEnabled(v: boolean): void {
    const c: ProviderConfig = this.config;
    this.config = makeProvider(c.id, c.name, c.baseUrl, c.apiKey, v, c.models);
  }

  private addModel(): void {
    const id: string = this.newModel.trim();
    if (id.length === 0) return;
    const c: ProviderConfig = this.config;
    if (c.models.indexOf(id) >= 0) {
      this.newModel = '';
      return;
    }
    this.config = makeProvider(c.id, c.name, c.baseUrl, c.apiKey, c.enabled, [...c.models, id]);
    this.newModel = '';
  }

  private removeModel(i: number): void {
    const c: ProviderConfig = this.config;
    this.config = makeProvider(c.id, c.name, c.baseUrl, c.apiKey, c.enabled,
      c.models.filter((_v: string, idx: number): boolean => idx !== i));
  }

  private testConnection(): void {
    this.testStatus = '测试中…';
    setTimeout((): void => {
      const ok: boolean = this.config.baseUrl.length > 0 && this.config.apiKey.length > 0;
      this.testStatus = ok ? '连接成功' : '连接失败:缺少 baseUrl 或 apiKey';
    }, 600);
  }

  private save(): void {
    // 占位保存:接入 chat-domain 后替换为 saveProviders(getChatKvStore(), [this.config])
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: (v: boolean) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked, switchSize: SwitchSize.Small,
        onCheckedChange: (v: boolean): void => { onToggle(v); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('Provider 配置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('基本')

            this.toggleRow('启用', '停用后该 Provider 不参与模型调度', this.config.enabled,
              (v: boolean): void => { this.setEnabled(v); })

            Column() {
              FlatTextField({
                value: this.config.name, label: '名称', placeholder: 'OpenAI',
                onChange: (v: string): void => { this.setName(v); },
              })
              FlatTextField({
                value: this.config.baseUrl, label: 'Base URL', placeholder: 'https://api.openai.com/v1',
                mono: true,
                onChange: (v: string): void => { this.setBaseUrl(v); },
              }).margin({ top: 8 })
              FlatTextField({
                value: this.config.apiKey, label: 'API Key', placeholder: 'sk-…',
                mono: true, obscure: true,
                onChange: (v: string): void => { this.setApiKey(v); },
              }).margin({ top: 8 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('模型列表')

            Column() {
              if (this.config.models.length === 0) {
                Text('暂无模型,在下方输入 modelId 添加').fontSize(META).fontColor(INK3)
                  .padding({ top: 4, bottom: 8 })
              } else {
                ForEach(this.config.models, (id: string): void => {
                  Row() {
                    Text(id).fontSize(BODY).fontColor(INK).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text('移除').fontSize(META).fontColor('ERROR')
                      .padding({ left: 8, right: 4, top: 4, bottom: 4 })
                      .onClick((): void => {
                        const idx: number = this.config.models.indexOf(id);
                        if (idx >= 0) this.removeModel(idx);
                      })
                  }
                  .width('100%').padding({ top: 8, bottom: 8 })
                  .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                }, (id: string): string => `model-${id}`)
              }

              Row() {
                TextInput({ text: this.newModel, placeholder: 'modelId, 如 gpt-4o' })
                  .fontSize(BODY).fontColor(INK)
                  .backgroundColor(SURFACE2).borderRadius(8)
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.newModel = v; })
                Text('添加').fontSize(BODY).fontColor(ACCENT)
                  .padding({ left: 12, right: 12, top: 10, bottom: 10 })
                  .onClick((): void => { this.addModel(); })
              }
              .width('100%').margin({ top: 8 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('连接')

            Row() {
              Text('测试连接').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.testConnection(); })
              Blank()
              if (this.testStatus.length > 0) {
                Text(this.testStatus).fontSize(META).fontColor(INK3)
              }
            }
            .width('100%').margin({ top: 4, bottom: 24 })

            if (this.savedMsg.length > 0) {
              Text(this.savedMsg).fontSize(META).fontColor(ACCENT).margin({ bottom: 24 })
            }
            Column() {}.height(16)
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
