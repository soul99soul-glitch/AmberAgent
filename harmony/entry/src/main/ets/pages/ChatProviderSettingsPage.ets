// ChatProviderSettingsPage — Provider 设置(D-047:三变体列表 + 编辑)
// Android 基准:SettingProviderConfigPage.kt
//   OpenAIEndpointFields(:531-561) / GoogleAuthConsole(:436-487)
//   GoogleEndpointFields(:563-623) / ClaudeEndpointFields(:625-642)
// 持久化:saveProviders → Preferences KV(chat-domain D-016/D-017 线格式)
// 裁剪(登记 D-047):
//   - authMode 选择不呈现:openai 固定 api_key(codex_oauth token store = P1);
//     google 固定 api_key(Code Assist OAuth 传输 = P1,api 层显式抛错);
//     已有数据的 authMode 保存时原样保留
//   - brand 编辑/useResponseApi 以外的 openai account 区(余额等)= P1
//   - service account JSON 导入按钮 = P1(手填 email/privateKey 字段保留)
//   - 编辑中切换选中会丢弃未保存修改(MVP 草稿语义)
import { ACCENT,BG,INK,INK3,SURFACE } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { router } from '@kit.ArkUI';
import type {
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingGoogle,
  ProviderSettingClaude, ProviderModel,
} from '@amber/chat-domain';
import {
  loadProviders, saveProviders, makeProviderSettingOpenAIVariant,
  makeProviderSettingGoogle, makeProviderSettingClaude, makeProviderModel,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';


interface ProviderRow {
  id: string;
  name: string;
  ptype: string;
  enabled: boolean;
}

@Entry
@Component
struct ChatProviderSettingsPage {
  @State loaded: boolean = false;
  @State rows: ProviderRow[] = [];
  @State selectedId: string = '';
  @State selectedType: string = ''; // '' 未选中 | openai | google | claude
  // 公共字段
  @State providerEnabled: boolean = true;
  @State name: string = '';
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State modelsText: string = '';
  // openai 变体字段
  @State chatPath: string = '/chat/completions';
  @State useResponseApi: boolean = false;
  // google 变体字段
  @State vertexAI: boolean = false;
  @State useServiceAccount: boolean = false;
  @State serviceAccountEmail: string = '';
  @State privateKey: string = '';
  @State location: string = 'us-central1';
  @State projectId: string = '';
  // claude 变体字段
  @State promptCaching: boolean = false;
  @State savedMsg: string = '';
  // 选中 provider 的完整快照(保存时合并未编辑字段:authMode/brand/balanceOption 等)
  private selectedRaw: ProviderSetting | null = null;
  private all: ProviderSetting[] = [];

  aboutToAppear(): void {
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      this.all = list ?? [];
      this.rebuildRows();
      // 兼容旧行为(D-020):默认选中首个 openai,否则首个 provider
      const firstOpenai = this.all.find((p: ProviderSetting): boolean => p.type === 'openai');
      const target: ProviderSetting | undefined = firstOpenai ?? (this.all.length > 0 ? this.all[0] : undefined);
      if (target !== undefined) {
        this.selectProvider(target);
      }
      this.loaded = true;
    });
  }

  private rebuildRows(): void {
    this.rows = this.all.map((p: ProviderSetting): ProviderRow => ({
      id: p.id, name: p.name, ptype: p.type, enabled: p.enabled,
    }));
  }

  private selectProvider(p: ProviderSetting): void {
    this.selectedRaw = p;
    this.selectedId = p.id;
    this.selectedType = p.type;
    this.providerEnabled = p.enabled;
    this.name = p.name;
    this.apiKey = p.apiKey;
    this.baseUrl = p.baseUrl;
    this.modelsText = p.models.map((m: ProviderModel): string => m.modelId).join(', ');
    if (p.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
      this.chatPath = v.chatCompletionsPath;
      this.useResponseApi = v.useResponseApi;
    } else if (p.type === 'google') {
      const v: ProviderSettingGoogle = p as ProviderSettingGoogle;
      this.vertexAI = v.vertexAI;
      this.useServiceAccount = v.useServiceAccount;
      this.serviceAccountEmail = v.serviceAccountEmail;
      this.privateKey = v.privateKey;
      this.location = v.location;
      this.projectId = v.projectId;
    } else {
      const v: ProviderSettingClaude = p as ProviderSettingClaude;
      this.promptCaching = v.promptCaching;
    }
  }

  private selectById(id: string): void {
    const target = this.all.find((p: ProviderSetting): boolean => p.id === id);
    if (target !== undefined) {
      this.selectProvider(target);
    }
  }

  private addProvider(ptype: string): void {
    let fresh: ProviderSetting;
    if (ptype === 'google') {
      fresh = makeProviderSettingGoogle({});
    } else if (ptype === 'claude') {
      fresh = makeProviderSettingClaude({});
    } else {
      fresh = makeProviderSettingOpenAIVariant({});
    }
    this.all.push(fresh);
    this.rebuildRows();
    this.selectProvider(fresh);
  }

  private parseModels(): ProviderModel[] {
    // D-109:modelId 命中既有行 → 原样保留(customHeaders/customBodies/
    //   modalities 等不被逗号文本编辑抹掉;Android 模型经独立 sheet 编辑,
    //   不存在整体重建;此前恒新建会丢弃 D-109 参数 → 数据保留修正)
    const existing: ProviderModel[] = this.selectedRaw !== null ? this.selectedRaw.models : [];
    return this.modelsText
      .split(',')
      .map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0)
      .map((id: string): ProviderModel => {
        const hit: ProviderModel | undefined = existing.find(
          (m: ProviderModel): boolean => m.modelId === id);
        return hit !== undefined ? hit : makeProviderModel({ modelId: id, displayName: id });
      });
  }

  private save(): void {
    if (this.selectedRaw === null || this.selectedType.length === 0) return;
    const raw: ProviderSetting = this.selectedRaw;
    const models: ProviderModel[] = this.parseModels();
    const trimmedName: string = this.name.trim();
    let updated: ProviderSetting;
    if (this.selectedType === 'google') {
      const g: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      updated = makeProviderSettingGoogle({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Google',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        models,
        vertexAI: this.vertexAI,
        useServiceAccount: this.useServiceAccount,
        serviceAccountEmail: this.serviceAccountEmail.trim(),
        privateKey: this.privateKey,
        location: this.location.trim(),
        projectId: this.projectId.trim(),
        // 未编辑字段保留(Android 同字段但鸿蒙裁剪编辑面)
        authMode: g.authMode,
        balanceOption: g.balanceOption,
      });
    } else if (this.selectedType === 'claude') {
      const c: ProviderSettingClaude = raw as ProviderSettingClaude;
      updated = makeProviderSettingClaude({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Claude',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        models,
        promptCaching: this.promptCaching,
        balanceOption: c.balanceOption,
      });
    } else {
      const o: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
      updated = makeProviderSettingOpenAIVariant({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'OpenAI',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        chatCompletionsPath: this.chatPath.trim(),
        useResponseApi: this.useResponseApi,
        models,
        // 未编辑字段保留(authMode/brand 编辑面 = P1)
        authMode: o.authMode,
        brand: o.brand,
        balanceOption: o.balanceOption,
      });
    }
    const idx: number = this.all.findIndex((p: ProviderSetting): boolean => p.id === updated.id);
    if (idx >= 0) {
      this.all[idx] = updated;
    } else {
      this.all.push(updated);
    }
    this.selectedRaw = updated;
    saveProviders(getChatKvStore(), this.all).then((): void => {
      this.rebuildRows();
      this.savedMsg = '已保存';
      setTimeout((): void => { this.savedMsg = ''; }, 1500);
    });
  }

  private removeSelected(): void {
    if (this.selectedRaw === null) return;
    const id: string = this.selectedId;
    AlertDialog.show({
      title: '删除 Provider',
      message: `删除「${this.name}」?其 API Key 与模型配置将从本机移除。`,
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: 'ERROR',
        action: (): void => {
          this.all = this.all.filter((p: ProviderSetting): boolean => p.id !== id);
          saveProviders(getChatKvStore(), this.all).then((): void => {
            this.rebuildRows();
            this.selectedRaw = null;
            this.selectedId = '';
            this.selectedType = '';
            if (this.all.length > 0) {
              this.selectProvider(this.all[0]);
            }
          });
        },
      },
    });
  }

  @Builder
  field(label: string, placeholder: string, value: string, onChange: (v: string) => void, obscure: boolean, mono: boolean = false) {
    FlatTextField({
      value: value,
      label: label,
      placeholder: placeholder,
      mono: mono,
      obscure: obscure,
      onChange: onChange,
    })
  }

  @Builder
  switchRow(label: string, isOn: boolean, onChange: (v: boolean) => void) {
    Row() {
      Text(label).fontSize(14).fontColor(INK)
      Blank()
      Toggle({ type: ToggleType.Switch, isOn: isOn })
        .onChange(onChange)
    }.width('100%').margin({ bottom: 14 })
  }

  @Builder
  addButton(label: string, ptype: string) {
    Text(label)
      .fontSize(12).fontColor(ACCENT)
      .padding({ left: 10, right: 10, top: 5, bottom: 5 })
      .backgroundColor(SURFACE).borderRadius(12)
      .onClick((): void => this.addProvider(ptype))
  }

  build() {
    Column() {
      PageTitleBar({
        title: 'Provider 设置',
        actionText: '保存',
        onAction: (): void => { this.save(); },
      })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            // provider 列表 + 新增(Android SettingProviderPage 列表语义)
            Row() {
              Text('Providers').fontSize(12).fontColor(INK3)
              Blank()
              this.addButton('+ OpenAI', 'openai')
              this.addButton('+ Google', 'google')
              this.addButton('+ Claude', 'claude')
            }.width('100%').margin({ bottom: 8 })

            if (this.rows.length === 0) {
              Text('暂无 Provider,点右上角新增').fontSize(13).fontColor(INK3)
                .margin({ bottom: 14 })
            } else {
              Column() {
                ForEach(this.rows, (row: ProviderRow): void => {
                  Row() {
                    Text(row.name).fontSize(14).fontColor(INK)
                    Text(` · ${row.ptype}`).fontSize(12).fontColor(INK3)
                    Blank()
                    if (!row.enabled) {
                      Text('停用').fontSize(11).fontColor(INK3)
                    }
                    if (row.id === this.selectedId) {
                      Text('编辑中').fontSize(11).fontColor(ACCENT).margin({ left: 8 })
                    }
                  }
                  .width('100%')
                  .padding({ left: 12, right: 12, top: 10, bottom: 10 })
                  .backgroundColor(row.id === this.selectedId ? SURFACE : Color.Transparent)
                  .borderRadius(8)
                  .onClick((): void => this.selectById(row.id))
                }, (row: ProviderRow): string => row.id)
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .margin({ bottom: 14 })
            }

            if (this.selectedType.length > 0) {
              this.switchRow('启用', this.providerEnabled,
                (v: boolean): void => { this.providerEnabled = v; })
              this.field('名称', 'Provider', this.name,
                (v: string): void => { this.name = v; }, false, false)

              // apiKey:google vertex+serviceAccount 时隐藏(Android :456)
              if (!(this.selectedType === 'google' && this.vertexAI && this.useServiceAccount)) {
                this.field('API Key',
                  this.selectedType === 'claude' ? 'sk-ant-…' : 'sk-…',
                  this.apiKey, (v: string): void => { this.apiKey = v; }, true, true)
              }

              if (this.selectedType === 'openai') {
                // OpenAIEndpointFields(:531-561)
                this.field('Base URL', 'https://api.openai.com/v1', this.baseUrl,
                  (v: string): void => { this.baseUrl = v; }, false, true)
                if (!this.useResponseApi) {
                  this.field('API Path', '/chat/completions', this.chatPath,
                    (v: string): void => { this.chatPath = v; }, false, true)
                }
                this.switchRow('使用 Response API', this.useResponseApi,
                  (v: boolean): void => { this.useResponseApi = v; })
              } else if (this.selectedType === 'google') {
                // GoogleAuthConsole(:436-487) + GoogleEndpointFields(:563-623)
                this.switchRow('Vertex AI', this.vertexAI,
                  (v: boolean): void => { this.vertexAI = v; })
                if (this.vertexAI) {
                  this.switchRow('使用 Service Account', this.useServiceAccount,
                    (v: boolean): void => { this.useServiceAccount = v; })
                }
                if (!this.vertexAI) {
                  this.field('Base URL', 'https://generativelanguage.googleapis.com/v1beta',
                    this.baseUrl, (v: string): void => { this.baseUrl = v; }, false, true)
                } else if (this.useServiceAccount) {
                  this.field('Service Account Email', 'name@project.iam.gserviceaccount.com',
                    this.serviceAccountEmail,
                    (v: string): void => { this.serviceAccountEmail = v; }, false, true)
                  this.field('Private Key', '-----BEGIN PRIVATE KEY-----…',
                    this.privateKey, (v: string): void => { this.privateKey = v; }, true, true)
                  this.field('Location', 'us-central1', this.location,
                    (v: string): void => { this.location = v; }, false, true)
                  this.field('Project ID', 'my-gcp-project', this.projectId,
                    (v: string): void => { this.projectId = v; }, false, true)
                }
              } else if (this.selectedType === 'claude') {
                // ClaudeEndpointFields(:625-642)
                this.field('Base URL', 'https://api.anthropic.com/v1', this.baseUrl,
                  (v: string): void => { this.baseUrl = v; }, false, true)
                this.switchRow('Prompt Caching', this.promptCaching,
                  (v: boolean): void => { this.promptCaching = v; })
              }

              Column() {
                Text('模型(逗号分隔 modelId)').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
                TextArea({ text: this.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                  .fontSize(14).padding(10)
                  .backgroundColor(SURFACE).borderRadius(8)
                  .height(80)
                  .onChange((v: string): void => { this.modelsText = v; })
              }.width('100%').alignItems(HorizontalAlign.Start)

              // D-109:模型级自定义参数入口(Android 模型行 → 编辑 sheet;
              //   已持久化模型逐行列出,'参数' → ChatModelParamsPage)
              if (this.selectedRaw !== null && this.selectedRaw.models.length > 0) {
                Column() {
                  ForEach(this.selectedRaw.models, (m: ProviderModel) => {
                    Row() {
                      Text(m.displayName.length > 0 ? m.displayName : m.modelId)
                        .fontSize(13).fontColor(INK)
                        .layoutWeight(1)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      Text('参数')
                        .fontSize(12).fontColor(ACCENT)
                        .padding({ left: 12, right: 4, top: 4, bottom: 4 })
                        .onClick((): void => {
                          const params: Record<string, string> = {};
                          params['providerId'] = this.selectedId;
                          params['modelId'] = m.id;
                          router.pushUrl({ url: 'pages/ChatModelParamsPage', params: params });
                        })
                    }
                    .width('100%').padding({ top: 8, bottom: 8 })
                  }, (m: ProviderModel): string => m.id)
                }
                .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 4 })
              }

              Row() {
                Text('删除此 Provider').fontSize(13).fontColor('ERROR')
                  .onClick((): void => this.removeSelected())
                Blank()
                if (this.savedMsg.length > 0) {
                  Text(this.savedMsg).fontSize(13).fontColor(ACCENT)
                }
              }.width('100%').margin({ top: 16 })
            }
          }
          .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 32 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
