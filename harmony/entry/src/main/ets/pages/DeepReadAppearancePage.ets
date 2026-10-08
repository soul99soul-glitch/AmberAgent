import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { curves } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadHaptics } from '../design/DeepReadHaptics.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup,
  DeepReadSettingsNote } from '../components/deepread/DeepReadSettingsVisual.ets';
import { DeepReadAppearanceChoice, DEEPREAD_READER_LAYOUTS, DEEPREAD_READER_STYLES,
  loadDeepReadAppearance, saveDeepReadReaderLayout, saveDeepReadReaderStyle, readerCanvas } from '../platform_impl/DeepReadAppearance.ets';

@Entry
@Component
struct DeepReadAppearancePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State layout: string = 'classic';
  @State style: string = 'classic';
  @State loading: boolean = true;
  @State saving: boolean = false;
  @State error: string = '';
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void { this.alive = false; }

  private async load(): Promise<void> {
    try {
      const appearance = await loadDeepReadAppearance(getAppContainer().storage);
      if (!this.alive) return;
      this.layout = appearance.readerLayout;
      this.style = appearance.readerStyle;
      this.error = '';
    } catch (error) {
      if (this.alive) this.error = '读取阅读外观失败：' + String(error);
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private async select(id: string, isLayout: boolean): Promise<void> {
    if (this.loading || this.saving || (isLayout ? this.layout : this.style) === id) return;
    const previous: string = isLayout ? this.layout : this.style;
    this.saving = true;
    this.error = '';
    DeepReadHaptics.selection();
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 250,
      curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.28, 0.7) }, (): void => {
      if (isLayout) this.layout = id;
      else this.style = id;
    });
    try {
      if (isLayout) await saveDeepReadReaderLayout(getAppContainer().storage, id);
      else await saveDeepReadReaderStyle(getAppContainer().storage, id);
    } catch (error) {
      if (!this.alive) return;
      if (isLayout) this.layout = previous;
      else this.style = previous;
      this.error = '保存阅读外观失败：' + String(error);
    } finally {
      if (this.alive) this.saving = false;
    }
  }

  private selected(choice: DeepReadAppearanceChoice, isLayout: boolean): boolean {
    return (isLayout ? this.layout : this.style) === choice.id;
  }

  private layoutSymbol(id: string): string {
    if (id === 'brief') return 'ϟ';
    if (id === 'timeline') return '◷';
    if (id === 'debate') return '◉◉';
    return '文';
  }

  @Builder
  Preview(choice: DeepReadAppearanceChoice, isLayout: boolean): void {
    Column({ space: 4 }) {
      Text(isLayout ? this.layoutSymbol(choice.id) : '文')
        .maxLines(1)
        .fontSize(isLayout && choice.id === 'debate' ? 14 : 17)
        .fontWeight(choice.id === 'briefing' || choice.id === 'broadsheet' ? FontWeight.Bold : FontWeight.Medium)
        .fontFamily(choice.id === 'briefing' || choice.id === 'minimal' ? D.sans : D.readingSerif)
        .fontColor(isLayout ? D.accent : readerCanvas(choice.id, this.darkMode).foreground)
      Row().width(18).height(3).backgroundColor(D.accent).borderRadius(2)
      Row().width(30).height(2).backgroundColor(isLayout ? D.rule : readerCanvas(choice.id, this.darkMode).border)
      Row().width(24).height(2).backgroundColor(isLayout ? D.rule : readerCanvas(choice.id, this.darkMode).border)
    }.width(48).height(60).justifyContent(FlexAlign.Center)
      .alignItems(choice.id === 'scroll' || choice.id === 'broadsheet' ? HorizontalAlign.Center : HorizontalAlign.Start)
      .padding(8).borderRadius(8)
      .backgroundColor(isLayout ? D.paper : readerCanvas(choice.id, this.darkMode).background)
      .border({ width: 0.5, color: isLayout ? D.rule : readerCanvas(choice.id, this.darkMode).border })
  }

  @Builder
  Choices(choices: DeepReadAppearanceChoice[], isLayout: boolean): void {
    ForEach(choices, (choice: DeepReadAppearanceChoice, index: number): void => {
      Column() {
        Row({ space: 14 }) {
          this.Preview(choice, isLayout)
          Column({ space: 4 }) {
            Text(choice.name).fontSize(17).fontColor(D.ink).width('100%')
            Text(choice.detail).fontSize(12).lineHeight(17).fontColor(D.muted).width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Text('✓').fontSize(19).fontColor(D.accent).width(22)
            .opacity(this.selected(choice, isLayout) ? 1 : 0)
            .scale({ x: this.selected(choice, isLayout) ? 1 : 0.5, y: this.selected(choice, isLayout) ? 1 : 0.5 })
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .enabled(!this.loading && !this.saving)
          .accessibilityText(choice.name + (this.selected(choice, isLayout) ? '，已选择' : ''))
          .onClick((): void => { this.select(choice.id, isLayout); })
        if (index < choices.length - 1) {
          Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 78 })
        }
      }.width('100%')
    }, (choice: DeepReadAppearanceChoice): string => choice.id)
  }

  build(): void {
    Stack() {
      DeepReadPaperBackground()
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          DeepReadSettingsHeader({ title: '版式与样式' })
          Scroll() {
            Column() {
              DeepReadSettingsSection({ title: '版式编排' })
              DeepReadSettingsGroup({ content: (): void => { this.Choices(DEEPREAD_READER_LAYOUTS, true); } })
              DeepReadSettingsNote({ text: '编排用于默认杂志和斜切图文。简报、问答、观点交锋、时间线报道与评测汇总保留各自的内容结构；阅读样式可自由切换。' })
              DeepReadSettingsSection({ title: '阅读样式' })
              DeepReadSettingsGroup({ content: (): void => { this.Choices(DEEPREAD_READER_STYLES, false); } })
              DeepReadSettingsNote({ text: '选择后立即保存，作用于所有使用内置版式的文章。自定义 HTML 模板保留自己的版式。' })
              if (this.loading) { Text('正在读取…').fontSize(13).fontColor(D.muted).padding(16) }
              if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(D.danger).lineHeight(19).padding(16) }
            }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
          }.width('100%').layoutWeight(1).align(Alignment.TopStart)
        }.width('100%').height('100%')
      }, (epoch: number): string => epoch.toString())
      DeepReadSubpageDock().align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
