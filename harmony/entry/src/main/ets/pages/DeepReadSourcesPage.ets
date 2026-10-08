import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { url } from '@kit.ArkTS';
import type { DeepReadCacheEntry, DeepReadInputSource } from '@amber/deepread-domain';
import { inputSourceLabel } from '@amber/deepread-domain';
import { getRepository } from '../di/AppContainer.ets';
import { BG, INK, INK3, SURFACE2, ACCENT, ERROR, BODY, META, TITLE } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';

interface SourceParams { topicId: string; }

@Component
export struct DeepReadSourcesContent {
  @Prop topicId: string = '';
  onClose: () => void = (): void => { router.back(); };
  isActive: () => boolean = (): boolean => true;
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @State sources: DeepReadInputSource[] = [];
  @State inputText: string = '';
  @State expanded: string = '';
  @State error: string = '';
  @State openingId: string = '';
  private alive: boolean = false;
  private pageToken: number = 0;
  private unsubscribe: (() => void) | null = null;

  aboutToAppear(): void {
    this.alive = true;
    const token: number = ++this.pageToken;
    this.sources = [];
    this.inputText = '';
    this.expanded = '';
    this.error = '';
    this.openingId = '';
    const params: SourceParams = router.getParams() as SourceParams;
    const topicId: string = this.topicId.length > 0 ? this.topicId : params?.topicId ?? '';
    if (topicId.length === 0) { this.error = '文章参数缺失'; return; }
    let sourceVersion: number = 0;
    this.unsubscribe = getRepository().observe(topicId).subscribe((entry: DeepReadCacheEntry | null): void => {
      if (!this.currentActive(token) || entry === null) return;
      sourceVersion++;
      this.sources = entry.output.inputSources ?? [];
      this.inputText = entry.output.inputText ?? '';
    });
    const initialVersion: number = sourceVersion;
    getRepository().get(topicId).then((entry: DeepReadCacheEntry | null): void => {
      if (!this.currentActive(token) || sourceVersion !== initialVersion || entry === null) return;
      this.sources = entry.output.inputSources ?? [];
      this.inputText = entry.output.inputText ?? '';
    }).catch((error: Error): void => {
      if (this.currentActive(token) && sourceVersion === initialVersion) this.error = error.message;
    });
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.pageToken++;
    if (this.unsubscribe !== null) { this.unsubscribe(); this.unsubscribe = null; }
  }

  private currentActive(token: number = this.pageToken): boolean {
    return this.alive && token === this.pageToken && this.isActive();
  }

  private finish(): void {
    if (!this.currentActive()) return;
    this.aboutToDisappear();
    this.onClose();
  }

  private kindLabel(source: DeepReadInputSource): string {
    return source.kind === 'file' ? '文件' : source.kind === 'text' ? '文本' : source.kind === 'search' ? '搜索' : '网页';
  }

  private sourceUrl(source: DeepReadInputSource): string {
    const value: string = (source.url ?? '').trim();
    if (!/^https?:\/\//i.test(value) || /\s/.test(value)) return '';
    try {
      const parsed: url.URL = url.URL.parseURL(value);
      return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && parsed.hostname.length > 0 ? value : '';
    } catch (_) { return ''; }
  }

  private async openSource(source: DeepReadInputSource): Promise<void> {
    const uri: string = this.sourceUrl(source);
    if (!this.currentActive() || uri.length === 0 || this.openingId.length > 0) return;
    const token: number = this.pageToken;
    this.openingId = source.id;
    this.error = '';
    try {
      const context: common.UIAbilityContext | undefined = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('页面上下文尚未就绪');
      await context.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri });
    } catch (error) {
      if (this.currentActive(token)) this.error = `无法打开来源：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.currentActive(token)) this.openingId = '';
    }
  }

  private toggleSource(id: string): void {
    if (!this.currentActive()) return;
    const expanded: string = this.expanded === id ? '' : id;
    if (this.reduceMotion || this.appBackgrounded || getProductKind() !== 'deepread') {
      this.expanded = expanded;
      return;
    }
    const token: number = this.pageToken;
    this.getUIContext().animateTo({ duration: 200, curve: Curve.EaseOut }, (): void => {
      if (this.currentActive(token)) this.expanded = expanded;
    });
  }

  build(): void {
    Stack() {
      if (getProductKind() === 'deepread') { DeepReadPaperBackground() }
      Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        if (getProductKind() === 'deepread') {
          this.StandaloneHeader()
          Scroll() {
            this.StandaloneSources()
          }.layoutWeight(1).width('100%').align(Alignment.TopStart)
        } else {
        Row() {
          Row() {
            Icon({ name: 'back', iconSize: 20, color: INK3 })
          }.width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { router.back(); })
          Text(`来源资料 · ${this.sources.length}`).fontSize(TITLE).fontColor(INK).layoutWeight(1)
        }.width('100%').padding({ top: this.statusBarHeight, left: 12, right: 20, bottom: 10 })
        Scroll() {
          Column({ space: 12 }) {
            Text('粘贴原文完整保留，页面最多预览 40,000 字符。已保存全部来源及采集状态；生成最多使用前 10 个可用来源。网页来源需重新采集时，可在文章页选择“重新生成”；文件读取失败需重新选择文件。')
              .fontSize(META).fontColor(INK3).width('100%')
            if (this.error.length > 0) { Text(this.error).fontSize(META).fontColor(ERROR).width('100%').wordBreak(WordBreak.BREAK_ALL) }
            ForEach(this.sources, (source: DeepReadInputSource): void => {
              Column({ space: 8 }) {
                Text(`${this.kindLabel(source)} · ${source.title}`).fontSize(BODY).fontColor(INK).width('100%').wordBreak(WordBreak.BREAK_ALL)
                Text(inputSourceLabel(source)).fontSize(META).fontColor(source.status === 'failed' ? ERROR : INK3).width('100%')
                if (source.url !== null) {
                  Text(source.url).fontSize(META).fontColor(INK3).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.InApp)
                  if (this.sourceUrl(source).length > 0) {
                    Button(this.openingId === source.id ? '正在打开…' : '在浏览器中打开').fontSize(META).fontColor(ACCENT)
                      .backgroundColor(Color.Transparent).constraintSize({ minHeight: 44 }).width('100%')
                      .enabled(this.openingId.length === 0).onClick((): void => { this.openSource(source); })
                  }
                }
                if (source.content.length > 0) {
                  Text(this.expanded === source.id ? '收起正文' : '查看正文（最多 40,000 字符预览）').fontSize(META).fontColor(ACCENT).height(44).width('100%')
                    .onClick((): void => { this.expanded = this.expanded === source.id ? '' : source.id; })
                  if (this.expanded === source.id) {
                    Text(source.kind === 'text' && this.inputText.length > 0 ? this.inputText.substring(0, 40000) : source.content)
                      .fontSize(BODY).fontColor(INK).width('100%').copyOption(CopyOptions.InApp)
                    if (source.note !== null) { Text(source.note).fontSize(META).fontColor(INK3).width('100%') }
                  }
                }
              }.width('100%').alignItems(HorizontalAlign.Start).padding(16).backgroundColor(SURFACE2).borderRadius(12)
            }, (source: DeepReadInputSource): string => `${source.id}-${source.status}-${source.content.length}-${source.error ?? ''}`)
          }.width('100%').padding({ left: 20, right: 20, bottom: this.navBarHeight + 28 })
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
        }
      }.width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? Color.Transparent : BG)
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
      }.width('100%').height('100%')
    }.width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
  }

  @Builder
  StandaloneHeader(): void {
    Column() {
      Row() {
        Blank().layoutWeight(1)
        Text('完成').fontSize(17).fontColor(D.accent).constraintSize({ minWidth: 64, minHeight: 44 })
          .textAlign(TextAlign.Center).onClick((): void => { this.finish(); })
      }.width('100%').padding({ left: 16, right: 16 })
      Text('原始来源').fontSize(34).fontWeight(FontWeight.Bold).fontColor(D.ink).width('100%')
        .padding({ left: D.gutter, right: D.gutter, top: 2, bottom: 12 })
    }.width('100%').padding({ top: this.statusBarHeight }).backgroundColor(D.paper)
  }

  @Builder
  SourceRule(): void {
    Row().width('100%').height(0.5).backgroundColor(D.rule)
  }

  @Builder
  SourceSection(source: DeepReadInputSource): void {
    Column() {
      Text(source.title).fontSize(17).fontWeight(600).fontColor(D.ink).width('100%')
        .wordBreak(WordBreak.BREAK_ALL).padding({ left: 14, right: 14, top: 12, bottom: 12 })
      this.SourceRule()
      Text(this.kindLabel(source)).fontSize(12).fontColor(D.muted).width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
      if (source.url !== null && source.url.length > 0) {
        this.SourceRule()
        Text(source.url).fontSize(13).fontColor(this.sourceUrl(source).length > 0 ? D.accent : D.muted)
          .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.InApp)
          .constraintSize({ minHeight: 44 }).padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .enabled(this.openingId.length === 0)
          .onClick((): void => { this.openSource(source); })
        if (this.openingId === source.id) {
          Text('正在打开…').fontSize(12).fontColor(D.muted).width('100%')
            .padding({ left: 14, right: 14, bottom: 12 })
        }
      }
      this.SourceRule()
      Text(source.status === 'ready' ? `已取得正文 · ${inputSourceLabel(source)}` :
        source.status === 'pending' ? '等待采集' : source.kind === 'search' ? '补充搜索失败' : '正文采集失败')
        .fontSize(12).fontColor(D.muted).width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
      if (source.status === 'failed') {
        this.SourceRule()
        Text(inputSourceLabel(source)).fontSize(13).fontColor(D.danger).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.InApp)
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
      }
      this.SourceRule()
      Row() {
        Text('查看采集内容').fontSize(17).fontColor(D.accent).layoutWeight(1)
        Icon({ name: 'chevron_right', iconSize: 13, color: D.muted })
          .rotate({ angle: this.expanded === source.id ? 90 : 0 })
      }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 14, right: 14 })
        .onClick((): void => { this.toggleSource(source.id); })
      if (this.expanded === source.id) {
        Text(source.content.length > 0 ?
          (source.kind === 'text' && this.inputText.length > 0 ? this.inputText.substring(0, 40000) : source.content) : '尚未取得正文。')
          .fontSize(13).fontColor(D.ink).lineHeight(21).width('100%').copyOption(CopyOptions.InApp)
          .padding({ left: 14, right: 14, bottom: 14 })
          .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 160 }))
        if (source.note !== null) {
          Text(source.note).fontSize(12).fontColor(D.muted).width('100%')
            .padding({ left: 14, right: 14, bottom: 14 })
        }
      }
    }.width('100%').alignItems(HorizontalAlign.Start).backgroundColor(D.card).borderRadius(D.radius).clip(true)
  }

  @Builder
  StandaloneSources(): void {
    Column({ space: 22 }) {
      if (this.error.length > 0) {
        Text(this.error).fontSize(13).fontColor(D.danger).width('100%').wordBreak(WordBreak.BREAK_ALL)
          .padding(14).backgroundColor(D.card).borderRadius(D.radius)
      }
      ForEach(this.sources, (source: DeepReadInputSource): void => {
        this.SourceSection(source)
      }, (source: DeepReadInputSource): string => `${source.id}-${source.status}-${source.content.length}-${source.error ?? ''}`)
      if (this.sources.length === 0 && this.error.length === 0) {
        Column({ space: 10 }) {
          Icon({ name: 'link', iconSize: 32, color: D.muted })
          Text('暂无来源资料').fontSize(20).fontFamily(D.serif).fontColor(D.ink)
          Text('文章保存的原始资料将显示在这里。').fontSize(13).fontColor(D.muted).textAlign(TextAlign.Center)
        }.width('100%').padding({ top: 40, bottom: 24 })
      }
      Text('粘贴原文完整保留，最多预览 40,000 字符。已保存全部来源及采集状态；' +
        '生成最多使用前 10 个可用来源。网页来源需重新采集时，可在文章页选择“重新生成”；文件读取失败需重新选择文件。')
        .fontSize(12).fontColor(D.muted).width('100%').padding({ left: 14, right: 14 })
    }.width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: D.gutter, right: D.gutter, top: 12, bottom: this.navBarHeight + 28 })
  }
}

@Entry
@Component
struct DeepReadSourcesPage {
  build(): void {
    Column() {
      DeepReadSourcesContent()
    }.width('100%').height('100%')
  }
}
