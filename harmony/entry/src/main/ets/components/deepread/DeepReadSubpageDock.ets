import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
import { DeepReadTabBar } from './DeepReadTabBar.ets';
import { DeepReadEdgeFade } from './DeepReadEdgeFade.ets';

export class DeepReadDockLayout {
  static bottomInset(navBarHeight: number): number { return navBarHeight + 104; }
}

// Routed subpages return to the retained root and keep its selected tab.
@Component
export struct DeepReadSubpageDock {
  onBeforeLeave: () => boolean = (): boolean => true;
  @StorageProp('deepreadActiveTab') activeTab: number = 2;
  @StorageProp('navBarHeight') navBarHeight: number = 0;

  build(): void {
    if (getProductKind() === 'deepread') {
      Stack({ alignContent: Alignment.Bottom }) {
        DeepReadEdgeFade({ top: false, bandHeight: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        DeepReadTabBar({ selected: this.activeTab, clearHistoryOnSelect: true, onBeforeSelect: this.onBeforeLeave })
      }.width('100%').height('100%')
        .align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }
  }
}
