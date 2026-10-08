import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { router } from '@kit.ArkUI';
import { BG, INK3 } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote } from '../components/PageTemplate.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs, SEARCH_SERVICE_TYPES } from '@amber/chat-domain';
import type { SearchSettings, SearchServiceOptions } from '@amber/chat-domain';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup,
  DeepReadSettingsNote, DeepReadSettingsPicker, DeepReadSettingsRow, DeepReadSettingsToggle } from '../components/deepread/DeepReadSettingsVisual.ets';

@Entry
@Component
struct DeepReadSearchSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State settings: SearchSettings = defaultSearchPrefs();
  @State busy: boolean = false;
  @State loading: boolean = true;
  @State error: string = '';
  private alive: boolean = false;
  private loadToken: number = 0;

  onPageShow(): void {
    this.alive = true;
    this.refresh();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.loadToken++;
  }

  private async refresh(): Promise<void> {
    const token: number = ++this.loadToken;
    this.loading = true;
    try {
      const settings: SearchSettings = await loadSearchPrefs(getChatKvStore());
      if (!this.alive || token !== this.loadToken) return;
      this.settings = settings;
      this.error = '';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = `读取搜索设置失败：${String(error)}`;
    } finally {
      if (this.alive && token === this.loadToken) this.loading = false;
    }
  }

  private async saveChoice(serviceId?: string, googleEnabled?: boolean): Promise<void> {
    if (this.busy || this.loading) return;
    this.busy = true;
    this.error = '';
    try {
      await updateSearchPrefs(getChatKvStore(), (current: SearchSettings): SearchSettings => {
        const next: SearchSettings = JSON.parse(JSON.stringify(current)) as SearchSettings;
        if (serviceId !== undefined) {
          const index: number = next.searchServices.findIndex((service: SearchServiceOptions): boolean => service.id === serviceId);
          if (index < 0) throw new Error('该搜索服务已删除，请重新选择');
          next.searchServiceSelected = index;
        }
        if (googleEnabled !== undefined) next.searchGoogleWebViewFallbackEnabled = googleEnabled;
        return next;
      });
      await this.refresh();
    } catch (error) {
      if (this.alive) this.error = `保存搜索设置失败：${String(error)}`;
    } finally {
      this.busy = false;
    }
  }

  private serviceLabels(): string[] {
    return this.settings.searchServices.map((service: SearchServiceOptions): string =>
      `${SEARCH_SERVICE_TYPES[service.type]}${this.settings.searchEnabledServiceIds.includes(service.id) ? '' : '（未启用）'}`);
  }

  @Builder
  private GoogleCard() {
    CardToggleRow({ label: 'Google 网页补充搜索', desc: '搜索结果不足时使用网页搜索，取消任务会停止该请求。',
      checked: this.settings.searchGoogleWebViewFallbackEnabled, isLast: true,
      onToggle: (): void => { this.saveChoice(undefined, !this.settings.searchGoogleWebViewFallbackEnabled); } })
  }

  @Builder
  private ManageCard() {
    CardRow({ label: '管理搜索服务与免费来源', desc: '添加服务、认证、结果数量及来源开关', isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); } })
  }

  @Builder
  HostSearchSettings(): void {
    Column() {
      CenteredTitleBar({ title: '深读搜索', onBack: (): void => { router.back(); } })
      Scroll() {
        Column({ space: 12 }) {
          PlainSection({ label: '首选搜索服务' })
          if (this.settings.searchServices.length > 0) {
            PillSelect({ options: this.serviceLabels(), selectedIndex: this.settings.searchServiceSelected,
              triggerMinHeight: 44,
              onSelect: (index: number): void => {
                const service: SearchServiceOptions | undefined = this.settings.searchServices[index];
                if (service !== undefined) this.saveChoice(service.id);
              } }).width('100%').enabled(!this.busy && !this.loading)
          }
          FooterNote({ text: '优先使用选中的可用服务；不可用时选择其他已启用的可用服务。免费来源按各自开关补充。' })
          CardGroup({ content: (): void => { this.GoogleCard(); } }).enabled(!this.busy && !this.loading)
          CardGroup({ content: (): void => { this.ManageCard(); } })
          if (this.error.length > 0) {
            Text(this.error).fontSize(14).fontColor(INK3).width('100%')
          }
        }
        .width('100%').padding({ left: 20, right: 20, top: 18, bottom: 32 })
        .alignItems(HorizontalAlign.Start)
      }
      .width('100%').layoutWeight(1).align(Alignment.TopStart)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }

  @Builder
  PreferredService(): void {
    if (this.settings.searchServices.length > 0) {
      DeepReadSettingsPicker({ label: '首选搜索服务', options: this.serviceLabels(),
        selected: this.settings.searchServiceSelected, last: true,
        onSelect: (index: number): void => {
          const service: SearchServiceOptions | undefined = this.settings.searchServices[index];
          if (service !== undefined) this.saveChoice(service.id);
        } })
    } else {
      DeepReadSettingsRow({ label: '首选搜索服务', value: '免费引擎聚合', last: true,
        onTap: (): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); } })
    }
  }

  @Builder
  Supplement(): void {
    DeepReadSettingsToggle({ label: 'Google 网页补充搜索', checked: this.settings.searchGoogleWebViewFallbackEnabled,
      last: true, onChange: (value: boolean): void => { this.saveChoice(undefined, value); } })
  }

  @Builder
  Services(): void {
    DeepReadSettingsRow({ label: '管理搜索服务与免费来源', detail: '添加服务、认证、结果数量及来源开关', last: true,
      onTap: (): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); } })
  }

  @Builder
  StandaloneSearchSettings(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      DeepReadPaperBackground()
      Column() {
        DeepReadSettingsHeader({ title: '多来源搜索' })
        Scroll() {
          Column() {
            DeepReadSettingsSection({ title: '多来源搜索' })
            DeepReadSettingsGroup({ content: (): void => { this.PreferredService(); } }).enabled(!this.busy && !this.loading)
            DeepReadSettingsNote({ text: '优先使用选中的搜索服务；不可用时，选择其他已启用的可用服务。' })
            DeepReadSettingsSection({ title: '免费聚合与正文读取' })
            DeepReadSettingsGroup({ content: (): void => { this.Supplement(); } }).enabled(!this.busy && !this.loading)
            DeepReadSettingsSection({ title: '服务配置' })
            DeepReadSettingsGroup({ content: (): void => { this.Services(); } })
            if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(D.danger).width('100%').padding({ top: 12 }) }
          }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
      }.width('100%').height('100%')
      DeepReadSubpageDock().align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') { this.StandaloneSearchSettings(); } else { this.HostSearchSettings(); }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
  }
}
