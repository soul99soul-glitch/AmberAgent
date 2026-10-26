import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
// SettingDeepReadPage — 深度阅读设置(AI 配置 + 热榜源开关 + 搜索源配置面板)
//   原误置于 HistoryPage,归位独立页(首页功能区/设置入口可达)。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup(r18+描边)+ h16 /
//   表单控件装入卡片(标题 16 medium sans、字段标 mono meta、值 12 muted)/
//   FooterNote 组下脚注,逻辑零改动(读写/测试连通/保存/模型库增删全原样)。
import { ACCENT, INK, INK3, SURFACE, ERROR, MOTION_OVERLAY, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, FooterNote } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router, curves } from '@kit.ArkUI';
import { getAppContainer, getChatKvStore, getModelRegistry, resolveChatProviderChoice } from '../di/AppContainer.ets';
import type { DeepReadModelOption } from '../di/AppContainer.ets';
import { loadDeepReadModelSelection, saveDeepReadModelSelection } from '../platform_impl/DeepReadModelSelection.ets';
import type { Storage, ModelConfig } from '@amber/deepread-domain';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
import { DeepReadTabBar } from '../components/deepread/DeepReadTabBar.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadSettingsHeader } from '../components/deepread/DeepReadSettingsVisual.ets';
import { loadDeepReadAppearance } from '../platform_impl/DeepReadAppearance.ets';
import { DeepReadDockScroll } from '../components/deepread/DeepReadDockScroll.ets';
import { DeepReadSettingsForm } from '../components/deepread/DeepReadSettingsForm.ets';
import { DeepReadSettingsSession } from '../platform_impl/deepread_settings/DeepReadSettingsSession.ets';
import { DeepReadHostSettings } from '../platform_impl/deepread_settings/DeepReadHostSettings.ets';
import { DeepReadHostSettingsView } from '../components/deepread/DeepReadHostSettingsView.ets';
interface DeepReadSettingsRouteParams { standaloneTabRoot?: boolean; }

@Component
export struct SettingDeepReadPageContent {
  onBackHandler: (handler: () => boolean) => void = (_handler: () => boolean): void => {};
  @Prop embeddedRoot: boolean = false;
  @Prop @Watch('rootVisibilityChanged') rootVisible: boolean = false;

  private rootVisibilityChanged(): void {
    if (this.rootVisible) this.onPageShow(); else this.onPageHide();
  }

  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') @Watch('motionPolicyChanged') appBackgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('motionPolicyChanged') reduceMotion: boolean = false;
  @StorageProp('deepreadActiveTab') @Watch('motionPolicyChanged') activeTab: number = 2;
  @State arrivalOffset: number = 0;

  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private readonly settingsScroller: Scroller = new Scroller();
  private readonly dockScroll: DeepReadDockScroll = new DeepReadDockScroll();
  private firstArrival: boolean = true;
  private arrivalTimer: number = -1;
  private restoreScrollPending: boolean = true;

  @State settings: DeepReadSettingsSession = new DeepReadSettingsSession();
  @State host: DeepReadHostSettings = new DeepReadHostSettings();
  private startSessions(): void {
    const storage: Storage = getAppContainer().storage;
    this.host.start({ storage, isAlive: (): boolean => this.settings.isAlive(),
      isCurrent: (token: number): boolean => this.settings.isCurrent(token),
      waitForLoad: (): Promise<void> => this.settings.pageLoad,
      getNotice: (): string => this.settings.saved,
      setNotice: (message: string): void => { this.settings.saved = message; },
      listModels: (): Promise<ModelConfig[]> => getModelRegistry().list(),
      saveModels: (models: ModelConfig[]): Promise<void> => getModelRegistry().save(models),
      resolveChatChoice: resolveChatProviderChoice });
    this.settings.start({ storage, kv: getChatKvStore(), standalone: getProductKind() === 'deepread',
      reduceMotion: (): boolean => this.reduceMotion, uiContext: () => this.getUIContext(),
      loadAppearance: loadDeepReadAppearance, loadHost: (token: number): Promise<void> => this.host.loadPage(token),
      loadModelSelection: loadDeepReadModelSelection, saveModelSelection: saveDeepReadModelSelection,
      openSearchEditor: (serviceId: string): Promise<void> => router.pushUrl({ url: 'pages/DeepReadSearchServiceEditorPage', params: { serviceId } }) });
  }
  onBackPress(): boolean {
    if (!this.settings.modelSheetOpen) return false;
    this.settings.modelSheetOpen = false;
    return true;
  }
  aboutToAppear(): void {
    this.onBackHandler((): boolean => this.onBackPress());
    this.pageAlive = true;
    this.startSessions();
    if (getProductKind() === 'deepread') {
      this.settings.standaloneRoot = this.readStandaloneRoot();
      if (this.settings.standaloneRoot) this.settings.restoreSessionDraft();
    }
    this.settings.load();
    if (this.rootVisible) this.onPageShow();
  }
  aboutToDisappear(): void {
    this.dockScroll.reset();
    this.settings.dispose();
    this.pageAlive = false;
    this.pageVisible = false;
    this.settleArrival();
  }
  onPageShow(): void {
    this.pageVisible = true;
    this.dockScroll.reset();
    if (getProductKind() === 'deepread') {
      this.settings.standaloneRoot = this.readStandaloneRoot();
      if (!this.embeddedRoot) markWantRoutingReady(this.getUIContext().getRouter());
      if (this.settings.standaloneRoot && !this.embeddedRoot) AppStorage.setOrCreate<number>('deepreadActiveTab', 2);
      if (!this.embeddedRoot) this.arrive();
    }
    this.settings.show();
  }
  private readStandaloneRoot(): boolean {
    const params: DeepReadSettingsRouteParams | null = router.getParams() as DeepReadSettingsRouteParams | null;
    return this.embeddedRoot || parseInt(router.getLength(), 10) <= 1 || params?.standaloneTabRoot === true;
  }
  onPageHide(): void {
    this.dockScroll.reset();
    this.settings.hide();
    this.pageVisible = false;
    this.settleArrival();
    if (getProductKind() === 'deepread' && this.settings.standaloneRoot) {
      AppStorage.setOrCreate<number>('deepreadSettingsOffset', this.settingsScroller.currentOffset().yOffset);
    }
  }

  private arrive(): void {
    if (!this.firstArrival || !this.settings.standaloneRoot) return;
    this.firstArrival = false;
    const direction: number = AppStorage.get<number>('deepreadTabDirection') ?? 0;
    if (direction === 0 || this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) return;
    this.arrivalOffset = direction * 30;
    // Commit the starting position before the first animation transaction.
    this.arrivalTimer = setTimeout((): void => {
      if (this.arrivalTimer < 0) return;
      this.arrivalTimer = -1;
      if (!this.pageAlive || !this.pageVisible || this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) {
        this.arrivalOffset = 0;
        return;
      }
      this.getUIContext().animateTo({ duration: 260, curve: curves.springCurve(0, 1, 240, 30) }, (): void => {
        this.arrivalOffset = 0;
      });
    }, 16);
  }

  private motionPolicyChanged(): void {
    if (this.reduceMotion || this.appBackgrounded || this.activeTab !== 2) this.settleArrival();
  }

  private settleArrival(): void {
    if (this.arrivalTimer >= 0) clearTimeout(this.arrivalTimer);
    this.arrivalTimer = -1;
    this.arrivalOffset = 0;
  }

  private restoreSettingsScroll(): void {
    if (!this.restoreScrollPending || !this.settings.standaloneRoot) return;
    this.restoreScrollPending = false;
    const offset: number = AppStorage.get<number>('deepreadSettingsOffset') ?? 0;
    this.settingsScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false });
  }

  private modelSheetTransition(card: boolean): TransitionEffect {
    if (getProductKind() === 'deepread') {
      if (this.appBackgrounded) return TransitionEffect.IDENTITY;
      if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseOut });
    }
    const effect: TransitionEffect = card ? TransitionEffect.translate({ y: 160 }) : TransitionEffect.OPACITY;
    return effect.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut });
  }

  @Builder
  ModelSheet(): void {
    Column() {
      Blank()
        .onClick((): void => { this.settings.modelSheetOpen = false; })
      Column() {
        Text('深度阅读模型')
          .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .width('100%').padding({ left: 20, right: 20, top: 18, bottom: 10 })
        Scroll() {
          Column() {
            this.ModelSheetRow('跟随当前模型', this.settings.taskModelReference?.kind === 'auto', (): void => {
              this.settings.selectTaskModel(null);
            })
            ForEach(this.settings.taskModelOptions, (option: DeepReadModelOption): void => {
              this.ModelSheetRow(`${option.providerLabel} / ${option.label}${option.available ? '' : '（不可用）'}`,
                this.settings.taskModelSelected(option), (): void => { this.settings.selectTaskModel(option); }, option.available)
            }, (option: DeepReadModelOption): string => `${option.providerId}:${option.modelId}`)
            if (this.settings.taskModelOptions.length === 0) {
              Text('暂无已配置模型，请先添加模型服务商。')
                .fontSize(12).lineHeight(17).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
            }
            if (this.settings.modelError.length > 0) {
              Text(this.settings.modelError).fontSize(13).lineHeight(18).fontFamily(FONT_SANS_MEDIUM).fontColor(ERROR)
                .width('100%').padding({ left: 20, right: 20, top: 8, bottom: 8 })
            }
            this.ModelSheetRow('配置模型服务商', false, (): void => {
              this.settings.modelSheetOpen = false;
              router.pushUrl({ url: 'pages/ChatProviderSettingsPage' });
            })
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 420 })
        .scrollBar(BarState.Auto)
      }
      .width('100%')
      .padding({ bottom: 12 })
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .transition(this.modelSheetTransition(true))
    }
    .width('100%').height('100%')
    .backgroundColor('#66000000')
    .justifyContent(FlexAlign.End)
    .transition(this.modelSheetTransition(false))
  }

  @Builder
  ModelSheetRow(label: string, selected: boolean, onTap: () => void, available: boolean = true): void {
    Row() {
      Text(label)
        .fontSize(15).lineHeight(lineHeightForSize(15)).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(!available ? INK3 : selected ? this.accentColorStore : INK)
        .layoutWeight(1).maxLines(3).wordBreak(WordBreak.BREAK_ALL)
      if (selected) {
        Icon({ name: 'check', iconSize: 16, color: this.accentColorStore })
      }
    }
    .width('100%')
    .constraintSize({ minHeight: 48 })
    .padding({ left: 20, right: 20, top: 10, bottom: 10 })
    .alignItems(VerticalAlign.Center)
    .enabled(available && !this.settings.savingModel)
    .onClick((): void => { onTap(); })
  }

  @Builder
  StandaloneSettings(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      DeepReadPaperBackground({ nightReading: true })
      Column() {
        DeepReadSettingsHeader({ title: '设置', root: this.settings.standaloneRoot })
        Scroll(this.settingsScroller) {
          DeepReadSettingsForm({ settings: this.settings, modelLabel: this.settings.taskModelLabel,
            modelDetail: this.settings.taskModelDetail, modelOptions: this.settings.taskModelOptions,
            modelError: this.settings.modelError, templates: this.settings.allTemplateChips(),
            onModel: (): void => { this.settings.showModelMenu(); },
            onSearchEdit: (id: string): void => { this.settings.openSearchServiceEditor(id); } })
            .width('100%').padding({ left: D.gutter, right: D.gutter,
              bottom: this.settings.standaloneRoot ? this.navBarHeight + 104 : this.navBarHeight + 28 })
            .onAreaChange((_oldArea: Area, _newArea: Area): void => { this.restoreSettingsScroll(); })
        }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
          .onDidScroll((_x: number, y: number, state: ScrollState): void => {
            if (this.settings.standaloneRoot && this.pageVisible && this.activeTab === 2) {
              if (!this.embeddedRoot) this.dockScroll.update(y, this.settingsScroller.currentOffset().yOffset, state !== ScrollState.Idle);
            }
          })
      }.width('100%').height('100%').translate({ x: this.reduceMotion ? 0 : this.arrivalOffset })
        .opacity(this.embeddedRoot || !this.settings.standaloneRoot || this.activeTab === 2 ? 1 : 0)
      if (this.settings.standaloneRoot && !this.embeddedRoot) { DeepReadTabBar({ selected: 2 }) }
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
  build(): void {
    Stack() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') { this.StandaloneSettings(); }
        else { DeepReadHostSettingsView({ settings: this.settings, host: this.host,
          onModel: (): void => { this.settings.showModelMenu(); } }) }
        if (this.settings.modelSheetOpen) { this.ModelSheet(); }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
  }
}

@Entry
@Component
struct SettingDeepReadPage {
  @State private visible: boolean = false;
  private backHandler: () => boolean = (): boolean => false;
  onBackPress(): boolean { return this.backHandler(); }
  onPageShow(): void { this.visible = true; }
  onPageHide(): void { this.visible = false; }

  aboutToAppear(): void {
    if (getProductKind() === 'deepread') {
      const boundRouter = this.getUIContext().getRouter();
      for (let index: number = 1; index <= parseInt(router.getLength(), 10); index++) {
        const state: router.RouterState | undefined = boundRouter.getStateByIndex(index);
        if (state?.name !== 'DeepReadRootPage' && state?.path !== 'pages/DeepReadRootPage') continue;
        AppStorage.setOrCreate<number>('deepreadDesiredTab', 2);
        boundRouter.back({ url: 'pages/DeepReadRootPage' });
        return;
      }
      boundRouter.replaceUrl({ url: 'pages/DeepReadRootPage', params: { tab: 2 } }).then((): void => { boundRouter.clear(); });
    }
  }

  build(): void {
    if (getProductKind() !== 'deepread') { SettingDeepReadPageContent({ rootVisible: this.visible, onBackHandler: (handler: () => boolean): void => { this.backHandler = handler; } }) }
    else { Column().width('100%').height('100%').backgroundColor(D.paper) }
  }
}
