// ChatProviderDetailPage — 提供商详情(Android SettingProviderDetailPage 1:1 结构)
// 头部:// PROVIDERS eyebrow + slug 16 Bold + 在线点 + name·auth·N 个模型;
// 配置 / 模型 双 tab(accent 2dp 下划线);配置 tab = 启用/名称/API Key/端点等;
// 模型 tab = 模型列表 + 参数入口 + 逗号编辑;保存显式按钮;删除置底。
// 新增流:列表页模板选择后经 PENDING_PROVIDER 暂存对象进入本页,保存时插入。
import { router, promptAction } from '@kit.ArkUI';
import type {
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingGoogle,
  ProviderSettingClaude, ProviderModel,
} from '@amber/chat-domain';
import {
  loadProviders, saveProviders, makeProviderSettingOpenAIVariant,
  makeProviderSettingGoogle, makeProviderSettingClaude, makeProviderModel,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { toProviderMonogram, providerSlug, providerAuthLabel } from '../di/ProviderDefaults.ets';
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BODY, BODY2, META, MONO_SMALL, LABEL, ERROR, RADIUS,
  TEXT_STYLES,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { Icon } from '../components/ui/Icon.ets';
import { BtnFill, BtnGhost, Chip, PaperSeparator, ToggleRow, SectionHeader } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';

// 模板新增流:列表页 → 本页 的对象暂存(单线程 UI,模块级安全)
export let PENDING_PROVIDER: ProviderSetting | null = null;

export const setPendingProvider = (p: ProviderSetting | null): void => {
  PENDING_PROVIDER = p;
};

@Entry
@Component
struct ChatProviderDetailPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State provider: ProviderSetting | null = null;
  @State tab: number = 0; // 0 配置 / 1 模型
  // 表单字段
  @State providerEnabled: boolean = true;
  @State name: string = '';
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State modelsText: string = '';
  @State chatPath: string = '/chat/completions';
  @State useResponseApi: boolean = false;
  @State vertexAI: boolean = false;
  @State useServiceAccount: boolean = false;
  @State serviceAccountEmail: string = '';
  @State privateKey: string = '';
  @State location: string = 'us-central1';
  @State projectId: string = '';
  @State promptCaching: boolean = false;
  @State savedMsg: string = '';
  @State unsupportedAuthMsg: string = '';
  private isNew: boolean = false;

  aboutToAppear(): void {
    const params: Record<string, string> | undefined =
      router.getParams() as Record<string, string> | undefined;
    const id: string = params !== undefined && params['providerId'] !== undefined ? params['providerId'] : '';
    this.isNew = params !== undefined && params['isNew'] === '1';
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      let target: ProviderSetting | null = null;
      if (id.length > 0) {
        const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === id);
        if (hit !== undefined) target = hit;
      }
      if (target === null && this.isNew && PENDING_PROVIDER !== null) {
        target = PENDING_PROVIDER;
      }
      if (target !== null) {
        this.applyProvider(target);
      }
      setPendingProvider(null);
    }).catch((): void => {
      setPendingProvider(null);
    });
  }

  // 返回自模型参数页等场景:仅刷新 provider 引用(携带最新 models 参数),
  // 不重置用户正在编辑的表单草稿
  onPageShow(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null) return;
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === raw.id);
      if (hit !== undefined && this.provider !== null && this.provider.id === hit.id) {
        this.provider = hit;
      }
    }).catch((): void => {});
  }

  private applyProvider(p: ProviderSetting): void {
    this.provider = p;
    this.providerEnabled = p.enabled;
    this.name = p.name;
    this.baseUrl = p.baseUrl;
    this.modelsText = p.models.map((m: ProviderModel): string => m.modelId).join(', ');
    this.unsupportedAuthMsg = '';
    this.apiKey = p.apiKey;
    if (p.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
      this.chatPath = v.chatCompletionsPath;
      this.useResponseApi = v.useResponseApi;
      if (v.authMode !== 'api_key' || v.useResponseApi) {
        this.apiKey = '';
        this.unsupportedAuthMsg = v.useResponseApi
          ? '历史配置启用了 Responses API，当前 Harmony 版本不支持。填写 API Key 并保存可迁移为 Chat Completions。'
          : '历史 OAuth/Token Plan 当前 Harmony 版本不支持。填写 API Key 并保存可迁移。';
      }
    } else if (p.type === 'google') {
      const v: ProviderSettingGoogle = p as ProviderSettingGoogle;
      this.vertexAI = v.vertexAI;
      this.useServiceAccount = v.useServiceAccount;
      this.serviceAccountEmail = '';
      this.privateKey = '';
      if (v.authMode !== 'api_key' || v.useServiceAccount) {
        this.apiKey = '';
        this.unsupportedAuthMsg = '历史 Google OAuth/Service Account 当前 Harmony 版本不支持。填写 API Key 并保存可迁移。';
      }
      this.location = v.location;
      this.projectId = v.projectId;
    } else {
      const c: ProviderSettingClaude = p as ProviderSettingClaude;
      this.promptCaching = c.promptCaching;
    }
  }

  private parseModels(existing: ProviderModel[]): ProviderModel[] {
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

  private ioBusy: boolean = false;

  private save(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return;
    this.ioBusy = true;
    if (this.unsupportedAuthMsg.length > 0 && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      return;
    }
    const models: ProviderModel[] = this.parseModels(raw.models);
    const trimmedName: string = this.name.trim();
    let updated: ProviderSetting;
    if (raw.type === 'google') {
      const g: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      updated = makeProviderSettingGoogle({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Google',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        models,
        vertexAI: this.vertexAI,
        useServiceAccount: false,
        serviceAccountEmail: '',
        privateKey: '',
        location: this.location.trim(),
        projectId: this.projectId.trim(),
        authMode: 'api_key',
        balanceOption: g.balanceOption,
      });
    } else if (raw.type === 'claude') {
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
        useResponseApi: false,
        models,
        authMode: 'api_key',
        brand: o.brand,
        balanceOption: o.balanceOption,
      });
    }
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): Promise<void> => {
      const all: ProviderSetting[] = list ?? [];
      const idx: number = all.findIndex((p: ProviderSetting): boolean => p.id === updated.id);
      if (idx >= 0) {
        all[idx] = updated;
      } else {
        all.unshift(updated);
      }
      return saveProviders(getChatKvStore(), all).then((): void => {
        this.provider = updated;
        this.unsupportedAuthMsg = '';
        this.savedMsg = '已保存';
        setTimeout((): void => { this.savedMsg = ''; }, 1500);
      });
    }).catch((e: Error): void => {
      this.savedMsg = `保存失败：${e.message}`;
    }).finally((): void => {
      this.ioBusy = false;
    });
  }

  private removeSelf(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return;
    this.ioBusy = true;
    AlertDialog.show({
      title: '删除 Provider',
      message: `删除「${this.name}」?其 API Key 与模型配置将从本机移除。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => {
          loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): Promise<void> => {
            const next: ProviderSetting[] = (list ?? []).filter(
              (p: ProviderSetting): boolean => p.id !== raw.id);
            return saveProviders(getChatKvStore(), next);
          }).then((): void => {
            router.back();
          }).catch((e: Error): void => {
            this.ioBusy = false;
            this.savedMsg = `删除失败：${e.message}`;
          });
        },
      },
    });
  }

  @Builder
  field(label: string, placeholder: string, value: string, onChange: (v: string) => void,
    obscure: boolean, mono: boolean = false) {
    Column() {
      Text(label)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(TEXT_STYLES.meta.weight)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(TEXT_STYLES.meta.family)
        .fontColor(INK3)
        .margin({ left: 2, bottom: 6 })
      FlatTextField({
        value: value,
        placeholder: placeholder,
        mono: mono,
        obscure: obscure,
        onChange: onChange,
      })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
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
          .margin({ top: 6, left: 12, right: 12 })
      }
      .layoutWeight(1)
      .onClick((): void => { this.tab = 0; })

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
          .margin({ top: 6, left: 12, right: 12 })
      }
      .layoutWeight(1)
      .onClick((): void => { this.tab = 1; })
    }
    .width('100%')
    .padding({ left: 18, right: 18 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
  }

  build() {
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
        .onClick((): void => { router.back(); })
        .accessibilityText('返回')

        Column() {
          Row() {
            Text('//').fontSize(11).fontWeight(FontWeight.Medium)
              .fontColor(this.accentColorStore).fontFamily(FONT_MONO_MEDIUM)
            Text(' 提供商').fontSize(11).fontWeight(FontWeight.Medium)
              .fontColor(INK3).fontFamily(FONT_MONO_MEDIUM).letterSpacing(1.3)
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 7 }) {
            Text(this.provider !== null ? providerSlug(this.provider) : '--')
              .fontSize(16).fontWeight(FontWeight.Bold).fontColor(INK)
              .fontFamily(FONT_MONO)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.provider !== null && this.provider.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore)
            }
          }
          .alignItems(VerticalAlign.Center)
          Row({ space: 6 }) {
            Text(`${this.name} · ${(this.provider !== null ? providerAuthLabel(this.provider) : '—')} · ${this.provider !== null ? this.provider.models.length : 0} 个模型`)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(VerticalAlign.Center)
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 8 })

        // 导出/分享为 Android 侧能力,鸿蒙暂无 ShareSheet — 占位保持布局对称
        BtnGhost({
          label: '导出',
          onTap: (): void => {
            promptAction.showToast({ message: '鸿蒙版暂不支持导出分享', duration: 1800 });
          },
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
              label: '启用', checked: this.providerEnabled, isLast: false,
              onToggle: (): void => { this.providerEnabled = !this.providerEnabled; },
            })

            this.field('名称', 'Provider 名称', this.name,
              (v: string): void => { this.name = v; }, false, false)

            if (this.unsupportedAuthMsg.length > 0) {
              Column() {
                Text(this.unsupportedAuthMsg)
                  .fontSize(LABEL).fontColor(ERROR).lineHeight(18)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .border({ width: 1, color: ERROR, radius: RADIUS })
              .margin({ bottom: 12 })
            }

            this.field('API Key',
              this.provider !== null && this.provider.type === 'claude' ? 'sk-ant-…' : 'sk-…',
              this.apiKey, (v: string): void => { this.apiKey = v; }, true, true)

            if (this.provider !== null && this.provider.type === 'openai') {
              this.field('Base URL', 'https://api.openai.com/v1', this.baseUrl,
                (v: string): void => { this.baseUrl = v; }, false, true)
              this.field('API Path', '/chat/completions', this.chatPath,
                (v: string): void => { this.chatPath = v; }, false, true)
            } else if (this.provider !== null && this.provider.type === 'google') {
              ToggleRow({
                label: 'Vertex AI', checked: this.vertexAI, isLast: this.vertexAI,
                onToggle: (): void => { this.vertexAI = !this.vertexAI; },
              })
              if (!this.vertexAI) {
                this.field('Base URL', 'https://generativelanguage.googleapis.com/v1beta',
                  this.baseUrl, (v: string): void => { this.baseUrl = v; }, false, true)
              }
            } else if (this.provider !== null && this.provider.type === 'claude') {
              this.field('Base URL', 'https://api.anthropic.com/v1', this.baseUrl,
                (v: string): void => { this.baseUrl = v; }, false, true)
              ToggleRow({
                label: 'Prompt Caching', checked: this.promptCaching, isLast: true,
                onToggle: (): void => { this.promptCaching = !this.promptCaching; },
              })
            }

            // 保存(Android ProviderSplitBar 语义:显式提交)
            Row() {
              Text(this.savedMsg.length > 0 ? this.savedMsg : ' ')
                .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(this.savedMsg.startsWith('保存失败') ? ERROR : this.accentColorStore)
                .layoutWeight(1)
              BtnFill({ label: '保存', onTap: (): void => { this.save(); } })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .margin({ top: 8, bottom: 20 })

            // 删除置底
            Row() {
              Text('删除此 Provider')
                .fontSize(BODY2).fontColor(ERROR)
                .padding({ top: 12, bottom: 12 })
                .onClick((): void => this.removeSelf())
              Blank()
            }
            .width('100%')
          } else {
            SectionHeader({ label: '模型列表' })
            Column() {
              ForEach(this.provider !== null ? this.provider.models : [],
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
                      const params: Record<string, string> = {};
                      params['providerId'] = this.provider !== null ? this.provider.id : '';
                      params['modelId'] = m.id;
                      router.pushUrl({ url: 'pages/ChatModelParamsPage', params: params });
                    })
                    if (mIdx < (this.provider !== null ? this.provider.models.length : 0) - 1) {
                      PaperSeparator()
                    }
                  }
                  .width('100%')
                }, (m: ProviderModel): string => m.id)
              if (this.provider !== null && this.provider.models.length === 0) {
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
              TextArea({ text: this.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                .fontSize(BODY2).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(FONT_MONO).fontColor(INK)
                .backgroundColor(SURFACE2).borderRadius(8)
                .border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                .height(80)
                .onChange((v: string): void => { this.modelsText = v; })
              Row() {
                Blank()
                BtnFill({ label: '保存模型', onTap: (): void => { this.save(); } })
              }
              .width('100%').padding({ left: 16, right: 16 }).margin({ top: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 12 })
          }
        }
        .width('100%')
        .padding({ left: 18, right: 18, bottom: 40 })
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
