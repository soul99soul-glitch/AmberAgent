// ChatProviderSettingsPage — Provider 设置(D-047:三变体列表 + 编辑)
// Android 基准:SettingProviderConfigPage.kt
//   OpenAIEndpointFields(:531-561) / GoogleAuthConsole(:436-487)
//   GoogleEndpointFields(:563-623) / ClaudeEndpointFields(:625-642)
// 持久化:saveProviders → Preferences KV(chat-domain D-016/D-017 线格式)
// 裁剪(登记 D-047):
//   - Harmony 仅支持 API Key;历史 OAuth/token-plan 配置明确提示迁移,
//     不把旧 token 当 API Key 展示或继续执行
//   - Responses API、Google Service Account 当前不支持;保存时迁移到已支持边界
//   - brand 编辑及 openai account 区(余额等)= P1
//   - 编辑中切换选中会丢弃未保存修改(MVP 草稿语义)
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BODY, BODY2, META, LABEL, ERROR, RADIUS,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PageSpacer } from '../components/PageTemplate.ets';
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
import { DEFAULT_PROVIDERS, toProviderMonogram, providerSlug, providerAuthLabel } from '../di/ProviderDefaults.ets';
import { ProviderTemplatePickerSheet } from '../components/ProviderTemplatePickerSheet.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';


interface ProviderRow {
  id: string;
  name: string;
  ptype: string;
  enabled: boolean;
  mono: string;
  slug: string;
  auth: string;
  modelsCount: number;
}

@Entry
@Component
struct ChatProviderSettingsPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State loaded: boolean = false;
  @State filterQuery: string = '';
  @State sheetShow: boolean = false;
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
  // Responses API is intentionally unsupported; retain stale value only for migration input.
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
  @State unsupportedAuthMsg: string = '';
  // 选中 provider 的完整快照(保存时合并未编辑字段:authMode/brand/balanceOption 等)
  private selectedRaw: ProviderSetting | null = null;
  private all: ProviderSetting[] = [];

  aboutToAppear(): void {
    this.load();
  }

  // 子页(模型参数页)保存返回后重读,避免陈旧 models 合并覆盖新参数
  onPageShow(): void {
    this.load();
    // 进入页面不弹键盘、不自动滚到编辑区:延迟清掉首帧抢到的焦点
    setTimeout((): void => {
      try {
        this.getUIContext().getFocusController().clearFocus();
      } catch (_e) {
      }
    }, 250);
  }

  // 加载序号:aboutToAppear + onPageShow 双触发时,迟到的旧加载不得覆盖新数据/选中态
  private loadSeq: number = 0;

  private load(): void {
    const seq: number = ++this.loadSeq;
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      if (seq !== this.loadSeq) return;
      if (list === null) {
        // 首装播种:Android DEFAULT_PROVIDERS 1:1(9 家预置)
        this.all = DEFAULT_PROVIDERS;
        saveProviders(getChatKvStore(), this.all).catch((): void => {});
      } else {
        this.all = list;
      }
      this.rebuildRows();
      // 兼容旧行为(D-020):默认选中首个 openai,否则首个 provider
      const firstOpenai = this.all.find((p: ProviderSetting): boolean => p.type === 'openai');
      const target: ProviderSetting | undefined = firstOpenai ?? (this.all.length > 0 ? this.all[0] : undefined);
      if (target !== undefined) {
        this.selectProvider(target);
      }
      this.loaded = true;
    }).catch((): void => {
      // 读取失败也要退出加载态(否则页面永远 loading)
      if (seq === this.loadSeq) this.loaded = true;
    });
  }

  private rebuildRows(): void {
    this.rows = this.all.map((p: ProviderSetting): ProviderRow => ({
      id: p.id, name: p.name, ptype: p.type, enabled: p.enabled,
      mono: toProviderMonogram(p.name),
      slug: providerSlug(p),
      auth: providerAuthLabel(p),
      modelsCount: p.models.length,
    }));
  }

  private filteredRows(): ProviderRow[] {
    const q: string = this.filterQuery.trim().toLowerCase();
    if (q.length === 0) return this.rows;
    return this.rows.filter((r: ProviderRow): boolean =>
      r.name.toLowerCase().indexOf(q) >= 0 || r.slug.toLowerCase().indexOf(q) >= 0);
  }

  // 预置添加(Android AddButton:模板原 id 直接入列并置顶)
  private addPreset(p: ProviderSetting): void {
    if (this.all.find((x: ProviderSetting): boolean => x.id === p.id) !== undefined) return;
    const next: ProviderSetting[] = [p, ...this.all];
    saveProviders(getChatKvStore(), next).then((): void => {
      this.all = next;
      this.rebuildRows();
      this.selectProvider(p);
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `添加失败：${e.message}`, duration: 1800 });
    });
  }

  private selectProvider(p: ProviderSetting): void {
    this.selectedRaw = p;
    this.selectedId = p.id;
    this.selectedType = p.type;
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
    if (this.unsupportedAuthMsg.length > 0 && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      return;
    }
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
        // Vertex service-account transport is unsupported on Harmony.
        useServiceAccount: false,
        serviceAccountEmail: '',
        privateKey: '',
        location: this.location.trim(),
        projectId: this.projectId.trim(),
        // 未编辑字段保留(Android 同字段但鸿蒙裁剪编辑面)
        authMode: 'api_key',
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
        // Responses API is unsupported; always save the chat-completions boundary.
        useResponseApi: false,
        models,
        // 未编辑字段保留(authMode/brand 编辑面 = P1)
        authMode: 'api_key',
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
      this.unsupportedAuthMsg = '';
      this.useResponseApi = false;
      this.useServiceAccount = false;
      this.rebuildRows();
      this.savedMsg = '已保存';
      setTimeout((): void => { this.savedMsg = ''; }, 1500);
    }).catch((e: Error): void => {
      this.savedMsg = `保存失败：${e.message}`;
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
        fontColor: ERROR,
        action: (): void => {
          // 先持久化候选列表,成功后再更新 UI;失败保留原状(避免「UI 已删、存储还在」)
          const next: ProviderSetting[] = this.all.filter((p: ProviderSetting): boolean => p.id !== id);
          saveProviders(getChatKvStore(), next).then((): void => {
            this.all = next;
            this.rebuildRows();
            this.selectedRaw = null;
            this.selectedId = '';
            this.selectedType = '';
            if (this.all.length > 0) {
              this.selectProvider(this.all[0]);
            }
          }).catch((error: Error): void => {
            this.savedMsg = `删除失败：${error.message}`;
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
      Text(label).fontSize(BODY).fontColor(INK)
      Blank()
      Toggle({ type: ToggleType.Switch, isOn: isOn })
        .selectedColor(isOn ? this.accentColorStore : LINE)
        .onChange(onChange)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
  }

  @Builder
  addProviderButton(label: string, ptype: string) {
    Text(label)
      .fontSize(BODY2)
      .fontColor(this.accentColorStore)
      .padding({ left: 14, right: 14, top: 9, bottom: 9 })
      .constraintSize({ minHeight: 44 })
      .backgroundColor(SURFACE2)
      .borderRadius(RADIUS)
      .onClick((): void => this.addProvider(ptype))
  }

  @Builder
  providerTemplateCard(name: string, desc: string, ptype: string) {
    Row() {
      Column() {
        Text(name)
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
        Text(desc)
          .fontSize(LABEL)
          .fontColor(INK3)
          .margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text('+')
        .fontSize(20)
        .fontColor(this.accentColorStore)
        .fontWeight(FontWeight.Bold)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 15, bottom: 15 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
    .onClick((): void => this.addProvider(ptype))
  }

  private totalModelCount(): number {
    let n: number = 0;
    for (const p of this.all) n += p.models.length;
    return n;
  }

  private onlineCount(): number {
    let n: number = 0;
    for (const p of this.all) {
      if (p.enabled) n++;
    }
    return n;
  }

  @Builder
  registryStat(value: number, label: string, accent: boolean) {
    Row() {
      Text(`${value}`).fontSize(12).fontWeight(FontWeight.Bold)
        .fontFamily('JetBrainsMono')
        .fontColor(accent ? this.accentColorStore : INK)
      Text(` ${label}`).fontSize(11.5).fontColor(INK3)
    }
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  registrySep() {
    Column()
      .width(1).height(11)
      .backgroundColor(LINE2)
      .margin({ left: 13, right: 13 })
  }

  @Builder
  providerRegistryRow(row: ProviderRow) {
    Column() {
      Row() {
        Text(row.mono)
          .fontSize(10.5).fontWeight(FontWeight.Bold)
          .fontFamily('JetBrainsMono')
          .fontColor(row.enabled ? INK2 : INK4)
          .width(32).height(32)
          .borderRadius(9)
          .backgroundColor(SURFACE2)
          .border({ width: 1, color: LINE })
          .textAlign(TextAlign.Center)
        Column() {
          Row() {
            Text(row.name)
              .fontSize(15).fontWeight(FontWeight.Medium)
              .fontColor(row.enabled ? INK : INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (row.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore).margin({ left: 7 })
            }
          }
          .alignItems(VerticalAlign.Center)
          Text(row.slug)
            .fontSize(11).fontFamily('JetBrainsMono').fontColor(INK4)
            .margin({ top: 3 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })

        Column() {
          Text(row.auth)
            .fontSize(10).fontWeight(FontWeight.Medium)
            .fontFamily('JetBrainsMono')
            .fontColor(row.auth === '—' ? INK4 : INK3)
            .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            .backgroundColor(SURFACE2)
            .borderRadius(3)
            .border({ width: 1, color: LINE })
          Text(`${row.modelsCount} 个模型`)
            .fontSize(11)
            .fontColor(row.modelsCount > 0 ? INK2 : INK4)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.End)
      }
      .width('100%')
      .padding({ left: 4, right: 4, top: 11, bottom: 11 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .onClick((): void => {
      const target = this.all.find((p: ProviderSetting): boolean => p.id === row.id);
      if (target !== undefined) this.selectProvider(target);
    })
  }

  build() {
    Stack() {
    Column() {
      PageTitleBar({
        title: '服务商与密钥',
        actionText: '保存',
        onAction: (): void => { this.save(); },
      })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY2).fontColor(INK3)
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .padding({ top: 48 })
      } else {
        Scroll() {
          Column() {
            // ── Provider 注册表(amber-zhenji / Android SettingProviderPage 1:1)──
            Row() {
              Row({ space: 1 }) {
                Column().width(3).height(5).borderRadius(1).backgroundColor(this.accentColorStore)
                Column().width(3).height(9).borderRadius(1).backgroundColor(this.accentColorStore)
                Column().width(3).height(13).borderRadius(1).backgroundColor(this.accentColorStore)
              }
              .alignItems(VerticalAlign.Bottom)
              .margin({ right: 9 })
              this.registryStat(this.all.length, '提供商', false)
              this.registrySep()
              this.registryStat(this.totalModelCount(), '模型', false)
              this.registrySep()
              this.registryStat(this.onlineCount(), '在线', true)
              Blank()
              // + 添加(ghost,accent 描边)
              Row() {
                Text('+').fontSize(13).fontColor(this.accentColorStore).margin({ right: 6 })
                Text('添加').fontSize(11).fontWeight(FontWeight.Bold)
                  .fontFamily('JetBrainsMono').fontColor(this.accentColorStore)
              }
              .height(32)
              .padding({ left: 11, right: 11 })
              .borderRadius(3)
              .border({ width: 1, color: this.accentColorStore })
              .alignItems(VerticalAlign.Center)
              .onClick((): void => { this.sheetShow = true; })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .margin({ top: 12, bottom: 2 })

            // 终端风筛选(▸ + mono 输入 + line2 底线)
            Column() {
              Row() {
                Text('▸').fontSize(12).fontWeight(FontWeight.Medium).fontColor(this.accentColorStore)
                TextInput({ text: this.filterQuery, placeholder: '筛选提供商' })
                  .fontSize(12).fontColor(INK)
                  .fontFamily('JetBrainsMono')
                  .placeholderColor(INK4)
                  .placeholderFont({ size: 12 })
                  .backgroundColor(Color.Transparent)
                  .padding({ top: 0, bottom: 0, left: 7, right: 0 })
                  .constraintSize({ minHeight: 40 })
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.filterQuery = v; })
                if (this.filterQuery.length > 0) {
                  Text('✕').fontSize(12).fontColor(INK3)
                    .width(24).height(24).textAlign(TextAlign.Center)
                    .onClick((): void => { this.filterQuery = ''; })
                }
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
              Column().width('100%').height(1).backgroundColor(LINE2)
            }
            .width('100%')
            .margin({ top: 8, bottom: 2 })

            Row() {
              Text('//').fontSize(11).fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore).fontFamily('JetBrainsMono')
              Text(` 在线`).fontSize(11).fontWeight(FontWeight.Medium)
                .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
              Text(`· ${this.filteredRows().filter((r: ProviderRow): boolean => r.enabled).length}`)
                .fontSize(11).fontColor(INK4).margin({ left: 6 })
              Blank()
              Column().width(1).height(1)
            }
            .width('100%')
            .padding({ top: 18, bottom: 11, left: 4, right: 4 })
            .alignItems(VerticalAlign.Center)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => r.enabled),
              (row: ProviderRow): void => {
                this.providerRegistryRow(row)
              }, (row: ProviderRow): string => `on-${row.id}`)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled),
              (row: ProviderRow, idx: number): void => {
                if (idx === 0) {
                  Row() {
                    Text('//').fontSize(11).fontWeight(FontWeight.Medium)
                      .fontColor(this.accentColorStore).fontFamily('JetBrainsMono')
                    Text(` 未启用`).fontSize(11).fontWeight(FontWeight.Medium)
                      .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
                    Text(`· ${this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length}`)
                      .fontSize(11).fontColor(INK4).margin({ left: 6 })
                    Blank()
                  }
                  .width('100%')
                  .padding({ top: 22, bottom: 11, left: 4, right: 4 })
                  .alignItems(VerticalAlign.Center)
                }
                Column() {
                  this.providerRegistryRow(row)
                }
                .opacity(0.62)
              }, (row: ProviderRow): string => `off-${row.id}`)

            if (this.filteredRows().length === 0) {
              Text('没有匹配的提供商')
                .fontSize(13).fontColor(INK3)
                .width('100%')
                .padding({ top: 28, bottom: 28 })
                .textAlign(TextAlign.Center)
            }

            Column().height(18)

            // ── 编辑区 ──
            if (this.selectedType.length > 0) {
              SectionHeader({ label: '编辑' })

              // 基本信息卡
              Column() {
                // 启用开关
                Row() {
                  Text('启用').fontSize(BODY).fontColor(INK).layoutWeight(1)
                  Toggle({ type: ToggleType.Switch, isOn: this.providerEnabled })
                    .selectedColor(this.providerEnabled ? this.accentColorStore : LINE)
                    .onChange((v: boolean): void => { this.providerEnabled = v; })
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 14, bottom: 14 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              }
              .width('100%').backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)
              .margin({ bottom: 8 })

              // 名称
              this.field('名称', 'Provider 名称', this.name,
                (v: string): void => { this.name = v; }, false, false)

              // 不支持的旧配置提示
              if (this.unsupportedAuthMsg.length > 0) {
                Column() {
                  Text(this.unsupportedAuthMsg)
                    .fontSize(LABEL).fontColor(ERROR).lineHeight(18)
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                .backgroundColor(SURFACE)
                .border({ width: 1, color: ERROR, radius: RADIUS })
                .margin({ top: 8 })
              }

              // API Key
              this.field('API Key',
                this.selectedType === 'claude' ? 'sk-ant-…' : 'sk-…',
                this.apiKey, (v: string): void => { this.apiKey = v; }, true, true)

              // 类型相关字段
              if (this.selectedType === 'openai') {
                this.field('Base URL', 'https://api.openai.com/v1', this.baseUrl,
                  (v: string): void => { this.baseUrl = v; }, false, true)
                this.field('API Path', '/chat/completions', this.chatPath,
                  (v: string): void => { this.chatPath = v; }, false, true)
              } else if (this.selectedType === 'google') {
                // Vertex AI 开关
                Column() {
                  Row() {
                    Text('Vertex AI').fontSize(BODY).fontColor(INK).layoutWeight(1)
                    Toggle({ type: ToggleType.Switch, isOn: this.vertexAI })
                      .selectedColor(this.vertexAI ? this.accentColorStore : LINE)
                      .onChange((v: boolean): void => { this.vertexAI = v; })
                  }
                  .width('100%')
                  .padding({ left: 16, right: 16, top: 14, bottom: 14 })
                }
                .width('100%').backgroundColor(SURFACE)
                .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)
                .margin({ top: 8 })

                if (!this.vertexAI) {
                  this.field('Base URL', 'https://generativelanguage.googleapis.com/v1beta',
                    this.baseUrl, (v: string): void => { this.baseUrl = v; }, false, true)
                }
              } else if (this.selectedType === 'claude') {
                this.field('Base URL', 'https://api.anthropic.com/v1', this.baseUrl,
                  (v: string): void => { this.baseUrl = v; }, false, true)

                // Prompt Caching 开关
                Column() {
                  Row() {
                    Text('Prompt Caching').fontSize(BODY).fontColor(INK).layoutWeight(1)
                    Toggle({ type: ToggleType.Switch, isOn: this.promptCaching })
                      .selectedColor(this.promptCaching ? this.accentColorStore : LINE)
                      .onChange((v: boolean): void => { this.promptCaching = v; })
                  }
                  .width('100%')
                  .padding({ left: 16, right: 16, top: 14, bottom: 14 })
                }
                .width('100%').backgroundColor(SURFACE)
                .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)
                .margin({ top: 8 })
              }

              // 模型列表(逗号分隔,用 FlatTextField 多行模式统一风格)
              Column() {
                Text('模型')
                  .fontSize(META).fontColor(INK3)
                  .margin({ left: 2, bottom: 8 })
                TextArea({ text: this.modelsText, placeholder: 'gpt-4o, gpt-4o-mini' })
                  .fontSize(BODY).fontColor(INK)
                  .backgroundColor(SURFACE)
                  .borderRadius(12)
                  .border({ width: 1, color: LINE })
                  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                  .height(80)
                  .onChange((v: string): void => { this.modelsText = v; })
                Text('逗号分隔 modelId')
                  .fontSize(LABEL).fontColor(INK4)
                  .margin({ left: 2, top: 6 })
              }
              .width('100%')
              .alignItems(HorizontalAlign.Start)
              .margin({ top: 8 })

              // D-109:模型级自定义参数入口
              if (this.selectedRaw !== null && this.selectedRaw.models.length > 0) {
                SectionHeader({ label: '模型参数' })
                Column() {
                  ForEach(this.selectedRaw.models, (m: ProviderModel, mIdx: number): void => {
                    Row() {
                      Text(m.displayName.length > 0 ? m.displayName : m.modelId)
                        .fontSize(BODY2).fontColor(INK)
                        .layoutWeight(1)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      Text('参数')
                        .fontSize(BODY2).fontColor(this.accentColorStore)
                    }
                    .width('100%')
                    .padding({ left: 16, right: 16, top: 13, bottom: 13 })
                    .backgroundColor(SURFACE)
                    .border(mIdx === this.selectedRaw!.models.length - 1
                      ? { width: 0 }
                      : { width: { bottom: 0.5 }, color: { bottom: LINE } })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['providerId'] = this.selectedId;
                      params['modelId'] = m.id;
                      router.pushUrl({ url: 'pages/ChatModelParamsPage', params: params });
                    })
                  }, (m: ProviderModel): string => m.id)
                }
                .width('100%').backgroundColor(SURFACE)
                .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)
              }

              // 删除 + 保存状态
              Row() {
                Text('删除此 Provider')
                  .fontSize(BODY2).fontColor(ERROR)
                  .padding({ top: 10, bottom: 10 })
                  .onClick((): void => this.removeSelected())
                Blank()
                if (this.savedMsg.length > 0) {
                  Text(this.savedMsg)
                    .fontSize(BODY2)
                    .fontColor(this.savedMsg.startsWith('保存失败') || this.savedMsg.startsWith('删除失败') ? ERROR : this.accentColorStore)
                }
              }
              .width('100%')
              .margin({ top: 16 })
            }

            PageSpacer()
          }
          .width('100%')
          .padding({ left: 18, right: 18 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    ProviderTemplatePickerSheet({
            show: this.sheetShow,
            existingIds: JSON.stringify(this.all.map((p: ProviderSetting): string => p.id)),
            onPickPreset: (p: ProviderSetting): void => {
              this.sheetShow = false;
              this.addPreset(p);
            },
            onOauthPick: (): void => {
              this.sheetShow = false;
              promptAction.showToast({ message: '鸿蒙版暂不支持 OAuth 登录，请使用 API Key 预置', duration: 2200 });
            },
            onDismiss: (): void => { this.sheetShow = false; },
          })
    }
  }
}
