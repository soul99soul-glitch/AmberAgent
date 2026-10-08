import { router, promptAction, curves, Router } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';
import { DeepReadHaptics as H } from '../../design/DeepReadHaptics.ets';

// Shared by the three standalone roots. Replacing roots keeps article Back local
// to the tab that opened it instead of accumulating a stack of visited tabs.
@Component
export struct DeepReadTabBar {
  @StorageProp('reduceMotion') @Watch('settleMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settleMotion') appBackgrounded: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop @Watch('selectedChanged') selected: number = 0;
  @Prop retainedRoot: boolean = false;
  onBeforeSelect: () => boolean = (): boolean => true;
  onSelect: (index: number) => void = (_index: number): void => {};
  @Prop clearHistoryOnSelect: boolean = false;
  @State private indicator: number = 0;
  @State private navigating: boolean = false;
  private alive: boolean = false;
  private indicatorTimer: number = -1;
  private readonly labels: string[] = ['发现', '阅读库', '设置'];
  private readonly icons: string[] = ['ph_notebook', 'ph_books', 'ph_gear'];
  private readonly routes: string[] = ['pages/BoardPage', 'pages/DeepReadHistoryPage', 'pages/SettingDeepReadPage'];

  aboutToAppear(): void {
    this.alive = true;
    AppStorage.setOrCreate<boolean>('deepreadDockCollapsed', false);
    const previous: number = AppStorage.get<number>('deepreadPreviousTab') ?? this.selected;
    AppStorage.setOrCreate<number>('deepreadPreviousTab', this.selected);
    if (previous === this.selected || this.reduceMotion || this.appBackgrounded) { this.indicator = this.selected; return; }
    this.indicator = previous;
    this.indicatorTimer = setTimeout((): void => {
      this.indicatorTimer = -1;
      if (!this.alive || this.reduceMotion || this.appBackgrounded) return;
      this.getUIContext().animateTo({ duration: 260, curve: curves.springCurve(0, 1, 240, 30) }, (): void => {
        this.indicator = this.selected;
      });
    }, 16);
  }

  aboutToDisappear(): void { this.alive = false; this.settleMotion(); }

  private settleMotion(): void {
    if (!this.alive || this.reduceMotion || this.appBackgrounded) {
      if (this.indicatorTimer >= 0) clearTimeout(this.indicatorTimer);
      this.indicatorTimer = -1;
      this.indicator = this.selected;
      AppStorage.setOrCreate<boolean>('deepreadDockCollapsed', false);
    }
  }

  private selectedChanged(): void {
    if (!this.alive) return;
    if (this.reduceMotion || this.appBackgrounded) { this.indicator = this.selected; return; }
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 100 : 380, curve: Curve.EaseInOut, expectedFrameRateRange: { min: 60, max: 120, expected: 120 } }, (): void => {
      this.indicator = this.selected;
    });
  }

  private selectTab(index: number): void {
    if ((index === this.selected && (this.retainedRoot || !this.clearHistoryOnSelect)) || this.navigating || index < 0 || index >= this.routes.length) return;
    if (!this.onBeforeSelect()) return;
    if (this.retainedRoot) {
      this.onSelect(index);
      H.selection();
      return;
    }
    this.navigating = true;
    AppStorage.setOrCreate<boolean>('deepreadDockCollapsed', false);
    AppStorage.setOrCreate<number>('deepreadPreviousTab', this.selected);
    AppStorage.setOrCreate<number>('deepreadTabDirection', index > this.selected ? 1 : -1);
    AppStorage.setOrCreate<number>('deepreadActiveTab', index);
    const pageRouter: Router = this.getUIContext().getRouter();
    const clearHistory: boolean = this.clearHistoryOnSelect;
    if (clearHistory) {
      const length: number = parseInt(router.getLength(), 10);
      for (let position: number = 1; position <= length; position++) {
        const state: router.RouterState | undefined = pageRouter.getStateByIndex(position);
        if (state?.name !== 'DeepReadRootPage' && state?.path !== 'pages/DeepReadRootPage') continue;
        AppStorage.setOrCreate<number>('deepreadDesiredTab', index);
        pageRouter.back({ url: 'pages/DeepReadRootPage' });
        H.selection();
        return;
      }
    }
    const replacement: Promise<void> = pageRouter.replaceUrl({ url: 'pages/DeepReadRootPage', params: { tab: index } });
    replacement.then((): void => {
      // Capture the bound Router before replacement destroys this detail page.
      if (clearHistory) pageRouter.clear();
      H.selection();
    }).catch((error: Error): void => {
      this.navigating = false;
      AppStorage.setOrCreate<number>('deepreadActiveTab', this.selected);
      promptAction.showToast({ message: '无法切换页面：' + error.message, duration: 1800 });
    });
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Stack() {
        Stack({ alignContent: Alignment.TopStart }) {
          Row().width(86).height(54).borderRadius(27).backgroundColor(D.rule).opacity(0.52)
            .translate({ x: 6 + this.indicator * 86, y: 5 })
          Row() {
            ForEach(this.labels, (label: string, index: number): void => {
              Column({ space: 2 }) {
                Icon({ name: this.icons[index], iconSize: 23, color: this.selected === index ? D.accent : D.ink })
                Text(label).fontSize(10).fontWeight(FontWeight.Medium).fontFamily(D.sans)
                  .fontColor(this.selected === index ? D.accent : D.ink)
              }.width(86).height(54).justifyContent(FlexAlign.Center)
                .accessibilityText(label + (this.selected === index ? '，已选中' : ''))
                .enabled(!this.navigating)
                .onClick((): void => { this.selectTab(index); })
            }, (label: string): string => label)
          }.padding({ left: 6, right: 6, top: 5, bottom: 5 })
        }.width(270).height(64)
      }.width(270).height(64)
        .clip(true).borderRadius(32).backgroundColor(D.card)
        .border({ width: 0.6, color: D.rule })
        .shadow({ radius: 14, color: '#142A2320', offsetY: 5 })
    }.width('100%').height(84 + this.navBarHeight).padding({ bottom: 12 + this.navBarHeight, top: 8 })
      .hitTestBehavior(HitTestMode.Transparent)
  }
}
