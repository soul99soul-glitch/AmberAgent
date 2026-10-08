import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup, DeepReadSettingsRow,
  DeepReadSettingsNote } from '../components/deepread/DeepReadSettingsVisual.ets';
// ChatProviderSettingsPage — Provider 设置(D-047:三变体列表 + 编辑)
// Android 基准:SettingProviderConfigPage.kt
//   OpenAIEndpointFields(:531-561) / GoogleAuthConsole(:436-487)
//   GoogleEndpointFields(:563-623) / ClaudeEndpointFields(:625-642)
// 持久化:saveProviders → Preferences KV(chat-domain D-016/D-017 线格式)
// Google账号与服务账号认证由独立配置页和安全仓承载；列表从真实状态读取徽标。
// 视觉:2026-09-27 二级页 iOS 对齐(基准截图 IMG_1989)——居中标题 + accent 圆添加、
//   搜索胶囊、PlainSection 组标、r18 发丝描边卡组、圆形字母头像 + StatusChip;
//   逻辑(加载/筛选/跳转/添加)零改动。
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, TEXT_STYLES, lineHeightForSize,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, StatusChip, FooterNote, PageSpacer, CardRow,
} from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, promptAction } from '@kit.ArkUI';
import type { ProviderSetting, ProviderModel } from '@amber/chat-domain';
import { setPendingProvider } from './ChatProviderDetailPage.ets';
import {
  loadProviders, saveProviders, providerFromImportJson, makeProviderSettingOpenAIVariant,
  newId, openAIAuthModeFixedBaseUrl,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadGoogleAuthStatus } from '../platform_impl/GoogleAuthSupport.ets';
import { DEFAULT_PROVIDERS, toProviderMonogram, providerSlug, providerAuthLabel } from '../di/ProviderDefaults.ets';
import { ProviderTemplatePickerSheet } from '../components/ProviderTemplatePickerSheet.ets';
import type { OAuthTemplateKind } from '../components/ProviderTemplatePickerSheet.ets';
import { ProviderBalanceText } from '../components/ui/ProviderBalanceText.ets';
import { picker, fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';

interface ProviderRow {
  id: string;
  name: string;
  ptype: string;
  enabled: boolean;
  mono: string;
  slug: string;
  auth: string;
  modelsCount: number;
  viewVersion: number;
}

@Entry
@Component
struct ChatProviderSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State loaded: boolean = false;
  @State loadError: string = '';
  @State unconfiguredExpanded: boolean = false;
  @State filterQuery: string = '';
  @State sheetShow: boolean = false;
  @State rows: ProviderRow[] = [];
  @State all: ProviderSetting[] = [];

  aboutToAppear(): void {
    this.load();
  }

  onBackPress(): boolean {
    if (!this.sheetShow) return false;
    this.sheetShow = false;
    return true;
  }

  private addOAuthProvider(kind: OAuthTemplateKind): void {
    this.sheetShow = false;
    if (kind === 'gemini') {
      promptAction.showToast({ message: '鸿蒙版暂不支持 Gemini OAuth 登录，请使用 API Key 预置', duration: 2200 });
      return;
    }
    const provider: ProviderSetting = makeProviderSettingOpenAIVariant({
      id: newId(), name: 'OpenAI Codex OAuth', brand: 'openai', authMode: 'codex_oauth',
      baseUrl: openAIAuthModeFixedBaseUrl('codex_oauth') as string, useResponseApi: true,
    });
    setPendingProvider(provider);
    const params: Record<string, string> = {};
    params['isNew'] = '1';
    router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
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
    this.loaded = false;
    this.loadError = '';
    loadProviders(getChatKvStore()).then(async (list: ProviderSetting[] | null): Promise<void> => {
      if (seq !== this.loadSeq) return;
      if (list === null) {
        await saveProviders(getChatKvStore(), DEFAULT_PROVIDERS);
        if (seq !== this.loadSeq) return;
        this.all = DEFAULT_PROVIDERS;
      } else {
        this.all = list;
      }
      this.rebuildRows();
      this.loaded = true;
    }).catch((error: Error): void => {
      if (seq !== this.loadSeq) return;
      this.loadError = `无法读取模型服务：${error.message}`;
    });
  }

  private rowsSeq: number = 0;
  private providerRow(p: ProviderSetting, auth: string, viewVersion: number): ProviderRow {
    return {
      id: p.id, name: p.name, ptype: p.type, enabled: p.enabled,
      mono: toProviderMonogram(p.name), slug: providerSlug(p), auth,
      modelsCount: p.models.length, viewVersion,
    };
  }
  private rebuildRows(): void {
    const seq: number = ++this.rowsSeq;
    const providers: ProviderSetting[] = this.all;
    this.rows = providers.map((p: ProviderSetting): ProviderRow => this.providerRow(p, providerAuthLabel(p), seq * 2));
    if (!providers.some((p: ProviderSetting): boolean =>
      p.type === 'google' && (p.useServiceAccount || p.authMode !== 'api_key'))) return;
    Promise.all(providers.map(async (p: ProviderSetting): Promise<ProviderRow> => {
      let auth: string = providerAuthLabel(p);
      if (p.type === 'google' && (p.useServiceAccount || p.authMode !== 'api_key')) {
        try {
          const status = await loadGoogleAuthStatus(p);
          auth = status.phase === 'configured' && status.mode === 'service_account'
            ? 'google_service_account_configured' : `google_${status.phase}`;
        }
        catch (_) { auth = 'google_error'; }
      }
      return this.providerRow(p, auth, seq * 2 + 1);
    })).then((rows: ProviderRow[]): void => { if (seq === this.rowsSeq) this.rows = rows; });
  }

  private settingFor(id: string): ProviderSetting | null {
    const found: ProviderSetting | undefined = this.all.find(
      (p: ProviderSetting): boolean => p.id === id);
    return found === undefined ? null : found;
  }

  // D3-2:从文件导入 provider(详情页导出 JSON 的逆通道;新 id + 空 apiKey)
  private async importProvider(): Promise<void> {
    if (!this.loaded) return;
    try {
      const ctx: common.UIAbilityContext =
        AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.json'];
      const uris: string[] = await new picker.DocumentViewPicker(ctx).select(options);
      if (uris.length === 0) return;
      const p: ProviderSetting = providerFromImportJson(fileIo.readTextSync(uris[0]));
      const latest: ProviderSetting[] | null = await loadProviders(getChatKvStore());
      const next: ProviderSetting[] = [...(latest ?? []), p];
      await saveProviders(getChatKvStore(), next);
      this.all = next;
      this.rebuildRows();
      promptAction.showToast({ message: '已导入，请在详情页重新配置凭据或账号授权。', duration: 2000 });
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      promptAction.showToast({ message: `导入失败:${msg}`, duration: 2000 });
    }
  }

  private filteredRows(): ProviderRow[] {
    const q: string = this.filterQuery.trim().toLowerCase();
    if (q.length === 0) return this.rows;
    return this.rows.filter((r: ProviderRow): boolean =>
      r.name.toLowerCase().indexOf(q) >= 0 || r.slug.toLowerCase().indexOf(q) >= 0);
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

  // 配置与授权状态分开，不把安全仓引用或客户端配置当作成功授权。
  private chipLabel(auth: string): string {
    if (auth === 'key') return '已配置';
    if (auth === 'google_ready') return '已授权';
    if (auth === 'google_service_account_configured') return 'SA 已配置';
    if (auth === 'google_configured') return '待刷新';
    if (auth === 'google_authorizing') return '授权中';
    if (auth === 'google_onboarding_required') return '待接入';
    if (auth === 'google_error') return '读取失败';
    if (auth === 'google_signed_out') return '待登录';
    if (auth === 'service_account' || auth === 'google_oauth') return '读取中';
    return '未配置';
  }
  private chipTone(auth: string): string {
    if (auth === 'key' || auth === 'google_ready') return 'success';
    if (auth === 'google_error') return 'error';
    if (auth === 'google_service_account_configured' || auth === 'google_configured'
      || auth === 'google_authorizing') return 'accent';
    if (auth === 'google_onboarding_required') return 'warn';
    return 'muted';
  }

  // iOS 圆形字母头像:40 圆 SURFACE2 底 + 名称首字(单字符,对齐 iOS)+ 右下 8 状态点(启用=accent,否则灰)
  @Builder
  providerAvatar(row: ProviderRow) {
    Stack({ alignContent: Alignment.BottomEnd }) {
      Text(row.name.charAt(0).toUpperCase())
        .fontSize(18).fontWeight(600).lineHeight(lineHeightForSize(18))
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(row.enabled ? INK2 : INK4)
        .width(40).height(40)
        .borderRadius(20)
        .backgroundColor(SURFACE2)
        .textAlign(TextAlign.Center)
      Column()
        .width(8).height(8).borderRadius(4)
        .backgroundColor(row.enabled ? this.accentColorStore : INK4)
    }
    .width(40).height(40)
  }

  @Builder
  providerCardRow(row: ProviderRow, isLast: boolean) {
    Column() {
      Row() {
        this.providerAvatar(row)
        Column() {
          Text(row.name)
            .fontSize(17).fontWeight(600).lineHeight(lineHeightForSize(17))
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(row.slug)
            .fontSize(12).lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_MONO).fontColor(INK3)
            .margin({ top: 3 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          ProviderBalanceText({ setting: this.settingFor(row.id) })
            .margin({ top: 3 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })

        StatusChip({ text: this.chipLabel(row.auth), tone: this.chipTone(row.auth) })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 6, bottom: 6 })
      .onClick((): void => {
        const params: Record<string, string> = {};
        params['providerId'] = row.id;
        router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
      })
      if (!isLast) {
        // 分隔线内缩对齐文字起点(行 padding 14 + 头像 40 + 间距 12)
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 66, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  enabledCards(): void {
    ForEach(this.filteredRows().filter((r: ProviderRow): boolean => r.enabled),
      (row: ProviderRow, idx: number): void => {
        this.providerCardRow(row,
          idx === this.filteredRows().filter((r: ProviderRow): boolean => r.enabled).length - 1)
      }, (row: ProviderRow): string => `on-${row.id}-${row.viewVersion}`)
  }

  @Builder
  disabledCards(): void {
    ForEach(this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled),
      (row: ProviderRow, idx: number): void => {
        Column() {
          this.providerCardRow(row,
            idx === this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length - 1)
        }
        .opacity(0.42)
      }, (row: ProviderRow): string => `off-${row.id}-${row.viewVersion}`)
  }

  @Builder
  ConfigurationLinks(): void {
    if (getProductKind() === 'novel') {
      CardRow({ label: '小说模型设置', desc: '写作、审稿和剧情同步的默认模型', isLast: true,
        onRowClick: (): void => { router.pushUrl({ url: 'pages/NovelStandaloneSettingsPage' }); } })
    } else if (getProductKind() === 'deepread') {
      CardRow({ label: '阅读模型设置', desc: '选择深度阅读和模板生成使用的模型', isLast: true,
        onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingDeepReadPage' }); } })
    } else {
    CardRow({
      label: '模型与提示词',
      desc: '聊天模型与标题、建议、压缩等任务模型可单独选择服务商与模型',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatTaskModelsPage' }); },
    })
    CardRow({
      label: '深度阅读配置',
      desc: '深度阅读模型可跟随聊天配置，也可从模型库单独选择',
      isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingDeepReadPage' }); },
    })
      }
  }

  @Builder
  ImportCard(): void {
    CardRow({
      label: '从文件导入',
      desc: '导入导出的 provider JSON(不含 API Key,导入后请补填)',
      iconName: 'upload', plateSize: 28, isLast: true,
      onRowClick: (): void => { this.importProvider().catch((): void => {}); },
    })
  }

  private configured(row: ProviderRow): boolean {
    return row.auth === 'key' || row.auth === 'google_ready'
      || row.auth === 'google_service_account_configured' || row.auth === 'google_configured'
      || row.auth === 'google_authorizing';
  }

  private readingRows(configured: boolean): ProviderRow[] {
    return this.filteredRows().filter((row: ProviderRow): boolean => this.configured(row) === configured);
  }

  @Builder
  DeepReadRows(configured: boolean): void {
    ForEach(this.readingRows(configured), (row: ProviderRow, index: number): void => {
      DeepReadSettingsRow({ label: row.name,
        detail: `${row.modelsCount} 个模型 · ${row.enabled ? '已启用' : '已停用'}`,
        value: this.chipLabel(row.auth), last: index === this.readingRows(configured).length - 1,
        onTap: (): void => { router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: { providerId: row.id } }); } })
    }, (row: ProviderRow): string => `${row.id}:${row.viewVersion}`)
  }

  @Builder
  DeepReadPage(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        DeepReadSettingsHeader({ title: '模型服务' })
        Scroll() {
          Column() {
            if (!this.loaded) {
              DeepReadSettingsNote({ text: this.loadError.length > 0 ? this.loadError : '正在读取模型服务…' })
              if (this.loadError.length > 0) {
                DeepReadSettingsGroup({ content: (): void => { this.DeepReadRetry(); } })
              }
            } else {
              Row({ space: 8 }) {
                Icon({ name: 'search', iconSize: 17, color: D.muted })
                TextInput({ text: this.filterQuery, placeholder: '搜索模型服务' })
                  .fontSize(17).fontColor(D.ink).placeholderColor(D.muted)
                  .backgroundColor(Color.Transparent).padding(0).layoutWeight(1)
                  .onChange((value: string): void => { this.filterQuery = value; })
              }.width('100%').height(48).padding({ left: 16, right: 16 }).backgroundColor(D.card).borderRadius(D.radius)
              DeepReadSettingsNote({ text: '配置服务地址与凭据，再添加需要的模型。' })
              DeepReadSettingsSection({ title: '已配置' })
              if (this.readingRows(true).length > 0) {
                DeepReadSettingsGroup({ content: (): void => { this.DeepReadRows(true); } })
              } else {
                DeepReadSettingsNote({ text: this.filterQuery.trim().length > 0 ? '没有匹配的已配置服务。' : '还没有配置服务。' })
              }
              if (this.readingRows(false).length > 0) {
                DeepReadSettingsSection({ title: '待配置' })
                DeepReadSettingsGroup({ content: (): void => { this.DeepReadUnconfigured(); } })
              }
              DeepReadSettingsSection({ title: '添加服务' })
              DeepReadSettingsGroup({ content: (): void => { this.DeepReadAdd(); } })
              DeepReadSettingsNote({ text: `${this.all.length} 个服务 · ${this.totalModelCount()} 个模型 · ${this.onlineCount()} 个已启用` })
            }
          }.width('100%').padding({ left: D.gutter, right: D.gutter, top: 12, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        }.align(Alignment.TopStart).layoutWeight(1).scrollBar(BarState.Off)
      }.width('100%').height('100%').backgroundColor(D.paper)
      DeepReadSubpageDock().align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  DeepReadRetry(): void {
    DeepReadSettingsRow({ label: '重新读取', valueAccent: true, last: true,
      onTap: (): void => { this.load(); } })
  }

  private toggleUnconfigured(): void {
    if (!this.loaded) return;
    if (this.reduceMotion || this.appBackgrounded) {
      this.unconfiguredExpanded = !this.unconfiguredExpanded;
      return;
    }
    this.getUIContext().animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.unconfiguredExpanded = !this.unconfiguredExpanded;
    });
  }

  @Builder
  DeepReadUnconfigured(): void {
    DeepReadSettingsRow({ label: `${this.readingRows(false).length} 个待配置服务`,
      value: this.unconfiguredExpanded ? '收起' : '展开', picker: true, last: !this.unconfiguredExpanded,
      onTap: (): void => { this.toggleUnconfigured(); } })
    if (this.unconfiguredExpanded) {
      Column() { this.DeepReadRows(false); }.width('100%')
        .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 160 }))
    }
  }

  @Builder
  DeepReadAdd(): void {
    DeepReadSettingsRow({ label: '添加模型服务', action: true,
      onTap: (): void => { this.sheetShow = true; } })
    DeepReadSettingsRow({ label: '从文件导入', last: true,
      onTap: (): void => { this.importProvider().catch((): void => {}); } })
  }

  build() {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
    if (getProductKind() === 'deepread') {
      this.DeepReadPage()
    } else {
    Column() {
      CenteredTitleBar({
        title: '服务商',
        actionIcon: 'plus',
        onAction: (): void => { if (this.loaded) this.sheetShow = true; },
      })

      if (!this.loaded) {
        Column() {
          Text(this.loadError.length > 0 ? this.loadError : '加载中…')
            .onClick((): void => { if (this.loadError.length > 0) this.load(); })
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK3)
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .padding({ top: 48 })
      } else {
        Scroll() {
          Column() {
            FooterNote({
              text: getProductKind() === 'deepread'
                ? '这里配置本应用的服务商与模型。到阅读模型设置选择文章和模板生成使用的模型。'
                : getProductKind() === 'novel'
                  ? '这里配置本应用的服务商与模型。项目设置可以指定创作模型，代笔和润色任务可单独选择模型。'
                  : '聊天和小说共用这里的服务商与模型。小说默认跟随聊天，也可在项目设置中指定。任务模型可单独选择服务商；深度阅读可跟随聊天或从模型库单独选择。',
            })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ConfigurationLinks(); } })
            }
            .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 })

            // ── 搜索胶囊(iOS IMG_1989:圆角输入条 + 放大镜)──
            Column() {
              Row() {
                Icon({ name: 'search', iconSize: 16, color: INK4 })
                TextInput({ text: this.filterQuery, placeholder: '搜索服务商' })
                  .fontSize(15)
                  .fontColor(INK)
                  .placeholderColor(INK4)
                  .placeholderFont({ size: 15 })
                  .backgroundColor(Color.Transparent)
                  .padding({ top: 0, bottom: 0, left: 9, right: 0 })
                  .constraintSize({ minHeight: 44 })
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.filterQuery = v; })
                if (this.filterQuery.length > 0) {
                  Row() {
                    Icon({ name: 'close', iconSize: 13, color: INK3 })
                  }
                  .width(26).height(26)
                  .justifyContent(FlexAlign.Center)
                  .alignItems(VerticalAlign.Center)
                  .onClick((): void => { this.filterQuery = ''; })
                }
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
              .constraintSize({ minHeight: 46 })
              .padding({ left: 14, right: 9 })
              .borderRadius(23)
              .backgroundColor(SURFACE)
            }
            .width('100%')
            .padding({ left: 16, right: 16 })

            // ── 启用中的服务商(iOS 卡组:r18 + 发丝描边)──
            PlainSection({ label: '服务商' })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.enabledCards(); } })
            }
            .width('100%')
            .padding({ left: 16, right: 16 })

            // ── 未启用的服务商(保留鸿蒙分组,按规范排布)──
            if (this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length > 0) {
              PlainSection({ label: '未启用' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.disabledCards(); } })
              }
              .width('100%')
              .padding({ left: 16, right: 16 })
            }

            // ── D3-2:从文件导入(与详情页导出 JSON 互通)──
            PlainSection({ label: '其他' })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ImportCard(); } })
            }
            .width('100%')
            .padding({ left: 16, right: 16 })

            if (this.filteredRows().length === 0) {
              Text('没有匹配的服务商')
                .fontSize(TEXT_STYLES.body.size)
                .fontWeight(TEXT_STYLES.body.weight)
                .lineHeight(TEXT_STYLES.body.lineHeight)
                .fontFamily(TEXT_STYLES.body.family)
                .fontColor(INK3)
                .width('100%')
                .padding({ top: 28, bottom: 28 })
                .textAlign(TextAlign.Center)
            }

            FooterNote({
              text: `${this.all.length} 个服务商 · ${this.totalModelCount()} 个模型 · ${this.onlineCount()} 个已启用`,
            })

            Column().height(18)

            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    }
    ProviderTemplatePickerSheet({
            show: this.sheetShow,
            existingIds: JSON.stringify(this.all.map((p: ProviderSetting): string => p.id)),
            onPickPreset: (p: ProviderSetting): void => {
              this.sheetShow = false;
              if (getProductKind() === 'deepread') {
                const draft: ProviderSetting = JSON.parse(JSON.stringify(p)) as ProviderSetting;
                draft.id = newId();
                draft.models = draft.models.map((model: ProviderModel): ProviderModel => { model.id = newId(); return model; });
                setPendingProvider(draft);
              } else {
                setPendingProvider(p);
              }
              const params: Record<string, string> = {};
              params['isNew'] = '1';
              router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
            },
            onOauthPick: (kind: OAuthTemplateKind): void => {
              this.addOAuthProvider(kind);
            },
            onDismiss: (): void => { this.sheetShow = false; },
          })
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
