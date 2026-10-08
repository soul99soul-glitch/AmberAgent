import { router, curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { BoardPageContent } from './BoardPage.ets';
import { DeepReadHistoryPageContent } from './DeepReadHistoryPage.ets';
import { SettingDeepReadPageContent } from './SettingDeepReadPage.ets';
import { DeepReadTabBar } from '../components/deepread/DeepReadTabBar.ets';
import { DeepReadEdgeFade } from '../components/deepread/DeepReadEdgeFade.ets';
import { Icon } from '../components/ui/Icon.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';

interface DeepReadRootRouteParams { tab?: number; }

@Entry
@Component
struct DeepReadRootPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('reduceMotion') @Watch('motionPolicyChanged') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('motionPolicyChanged') appBackgrounded: boolean = false;
  @State private selected: number = 0;
  @State private outgoing: number = -1;
  @State private offsets: number[] = [0, 1, 1];
  @State private visible: boolean = false;
  @State private transitioning: boolean = false;
  @State private pageWidth: number = 0;
  @State private createTrigger: number = 0;
  @State private fade: number = 1;
  private queuedTab: number = -1;
  private motionId: number = 0;
  private startTimer: number = -1;
  private alive: boolean = false;
  private discoveryBack: () => boolean = (): boolean => false;
  private libraryBack: () => boolean = (): boolean => false;
  private settingsBack: () => boolean = (): boolean => false;

  aboutToAppear(): void {
    this.alive = true;
    const params: DeepReadRootRouteParams | null = router.getParams() as DeepReadRootRouteParams | null;
    const initial: number = params?.tab ?? 0;
    this.selected = initial >= 0 && initial <= 2 ? initial : 0;
    this.settleContent();
    AppStorage.setOrCreate<number>('deepreadActiveTab', this.selected);
  }

  onPageShow(): void {
    this.visible = true;
    markWantRoutingReady(this.getUIContext().getRouter());
    const desired: number | undefined = AppStorage.get<number>('deepreadDesiredTab');
    AppStorage.delete('deepreadDesiredTab');
    if (desired !== undefined) this.selectTab(desired);
  }

  onPageHide(): void { this.visible = false; this.settleContent(); }
  aboutToDisappear(): void { this.alive = false; this.visible = false; this.settleContent(); }

  onBackPress(): boolean {
    if (this.transitioning) return true;
    if (this.selected === 0) return this.discoveryBack();
    if (this.selected === 1) return this.libraryBack();
    return this.settingsBack();
  }

  private motionPolicyChanged(): void {
    if (this.reduceMotion || this.appBackgrounded) this.settleContent();
  }

  private settleContent(): void {
    this.motionId++;
    if (this.startTimer >= 0) clearTimeout(this.startTimer);
    this.startTimer = -1;
    this.outgoing = -1;
    this.transitioning = false;
    this.queuedTab = -1;
    this.fade = 1;
    this.offsets = [0, 1, 2].map((index: number): number => index === this.selected ? 0 : index < this.selected ? -1 : 1);
  }

  private selectTab(index: number): void {
    if (index < 0 || index > 2) return;
    if (this.transitioning) { this.queuedTab = index; return; }
    if (index === this.selected) return;
    const previous: number = this.selected;
    const direction: number = index > previous ? 1 : -1;
    this.selected = index;
    AppStorage.setOrCreate<number>('deepreadActiveTab', index);
    AppStorage.setOrCreate<boolean>('deepreadDockCollapsed', false);
    this.outgoing = previous;
    this.transitioning = true;
    this.offsets = [0, 1, 2].map((tab: number): number => tab === previous ? 0 : direction);
    this.fade = this.reduceMotion ? 0 : 1;
    const motion: number = ++this.motionId;
    // Establish both full-width layers before entering the animation transaction.
    this.startTimer = setTimeout((): void => {
      this.startTimer = -1;
      if (!this.alive || motion !== this.motionId) return;
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 120 : 380, curve: Curve.EaseInOut,
        expectedFrameRateRange: { min: 60, max: 120, expected: 120 },
        onFinish: (): void => {
          if (!this.alive || motion !== this.motionId) return;
          const queued: number = this.queuedTab;
          this.settleContent();
          if (queued >= 0) this.selectTab(queued);
        } }, (): void => {
        this.offsets = [0, 1, 2].map((tab: number): number => tab === index ? 0 : -direction);
        this.fade = 1;
      });
    }, 16);
  }

  private layerOpacity(index: number): number {
    if (index !== this.selected && index !== this.outgoing) return 0;
    if (this.reduceMotion) return index === this.selected ? this.fade : 1 - this.fade;
    return 1;
  }

  private dockTransition(): TransitionEffect {
    if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 160 });
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 48 }))
        .combine(TransitionEffect.scale({ x: 0.82, y: 0.82 }))
        .animation({ duration: 500, curve: curves.springCurve(0, 1, 240, 28),
          expectedFrameRateRange: { min: 60, max: 120, expected: 120 } }),
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 48 }))
        .combine(TransitionEffect.scale({ x: 0.92, y: 0.92 }))
        .animation({ duration: 180, curve: Curve.EaseInOut,
          expectedFrameRateRange: { min: 60, max: 120, expected: 120 } }));
  }

  @Builder
  RootActions(): void {
    Stack({ alignContent: Alignment.End }) {
      Row() { Icon({ name: 'menu', iconSize: 19, color: D.accent }) }
        .width(44).height(44).justifyContent(FlexAlign.Center).position({ left: 4, top: 0 })
        .opacity(this.selected === 0 ? 1 : 0).hitTestBehavior(this.selected === 0 ? HitTestMode.Default : HitTestMode.None)
        .accessibilityText('热点来源设置')
        .onClick((): void => { router.pushUrl({ url: 'pages/DeepReadDiscoverySettingsPage' }); })
      Row() { Icon({ name: 'edit', iconSize: 19, color: D.accent }) }
        .width(44).height(44).justifyContent(FlexAlign.Center).accessibilityText('创建阅读')
        .onClick((): void => { this.createTrigger++; })
    }.width(this.selected === 0 ? 96 : 44).height(44).borderRadius(22).backgroundColor(D.card).clip(true)
      .opacity(this.selected < 2 ? 1 : 0).hitTestBehavior(this.selected < 2 ? HitTestMode.Default : HitTestMode.None)
      .animation({ duration: this.reduceMotion ? 160 : 400, curve: curves.springCurve(0, 1, 240, 28),
        expectedFrameRateRange: { min: 60, max: 120, expected: 120 } })
      .position({ top: this.statusBarHeight + 8, right: D.gutter }).enabled(!this.transitioning)
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Stack() {
        BoardPageContent({ embeddedRoot: true, rootVisible: this.visible && this.selected === 0,
          rootCreate: this.createTrigger, onBackHandler: (handler: () => boolean): void => { this.discoveryBack = handler; } })
          .translate({ x: this.reduceMotion ? 0 : this.offsets[0] * this.pageWidth }).opacity(this.layerOpacity(0))
          .enabled(this.selected === 0 && !this.transitioning).hitTestBehavior(this.selected === 0 && !this.transitioning ? HitTestMode.Default : HitTestMode.None)
        DeepReadHistoryPageContent({ embeddedRoot: true, rootVisible: this.visible && this.selected === 1,
          rootCreate: this.createTrigger, onBackHandler: (handler: () => boolean): void => { this.libraryBack = handler; } })
          .translate({ x: this.reduceMotion ? 0 : this.offsets[1] * this.pageWidth }).opacity(this.layerOpacity(1))
          .enabled(this.selected === 1 && !this.transitioning).hitTestBehavior(this.selected === 1 && !this.transitioning ? HitTestMode.Default : HitTestMode.None)
        SettingDeepReadPageContent({ embeddedRoot: true, rootVisible: this.visible && this.selected === 2,
          onBackHandler: (handler: () => boolean): void => { this.settingsBack = handler; } })
          .translate({ x: this.reduceMotion ? 0 : this.offsets[2] * this.pageWidth }).opacity(this.layerOpacity(2))
          .enabled(this.selected === 2 && !this.transitioning).hitTestBehavior(this.selected === 2 && !this.transitioning ? HitTestMode.Default : HitTestMode.None)
      }.width('100%').height('100%').clip(true)
      if (this.visible) {
        DeepReadEdgeFade({ top: false, bandHeight: 104 + this.navBarHeight, active: true })
          .transition(TransitionEffect.OPACITY.animation({ duration: 180 }))
      }
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (this.visible) {
          DeepReadTabBar({ selected: this.selected, retainedRoot: true, onSelect: (index: number): void => { this.selectTab(index); } })
            .transition(this.dockTransition())
        }
        this.RootActions()
      }, (epoch: number): string => 'root-controls-' + epoch)
    }.width('100%').height('100%').backgroundColor(this.themeEpoch >= 0 ? D.paper : D.paper)
      .onAreaChange((_old: Area, area: Area): void => { this.pageWidth = Number(area.width); })
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
