import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { url } from '@kit.ArkTS';
import type { DeepReadCacheEntry, DeepReadInputSource } from '@amber/deepread-domain';
import { inputSourceLabel } from '@amber/deepread-domain';
import { getRepository } from '../di/AppContainer.ets';
import { BG, INK, INK3, SURFACE2, ACCENT, ERROR, BODY, META, TITLE } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';

interface SourceParams { topicId: string; }

@Entry
@Component
struct DeepReadSourcesPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
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
    if (!params || !params.topicId) { this.error = '文章参数缺失'; return; }
    const topicId: string = params.topicId;
    let sourceVersion: number = 0;
    this.unsubscribe = getRepository().observe(topicId).subscribe((entry: DeepReadCacheEntry | null): void => {
      if (!this.alive || token !== this.pageToken || entry === null) return;
      sourceVersion++;
      this.sources = entry.output.inputSources ?? [];
      this.inputText = entry.output.inputText ?? '';
    });
    const initialVersion: number = sourceVersion;
    getRepository().get(topicId).then((entry: DeepReadCacheEntry | null): void => {
      if (!this.alive || token !== this.pageToken || sourceVersion !== initialVersion || entry === null) return;
      this.sources = entry.output.inputSources ?? [];
      this.inputText = entry.output.inputText ?? '';
    }).catch((error: Error): void => {
      if (this.alive && token === this.pageToken && sourceVersion === initialVersion) this.error = error.message;
    });
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.pageToken++;
    if (this.unsubscribe !== null) { this.unsubscribe(); this.unsubscribe = null; }
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
    if (!this.alive || uri.length === 0 || this.openingId.length > 0) return;
    const token: number = this.pageToken;
    this.openingId = source.id;
    this.error = '';
    try {
      const context: common.UIAbilityContext | undefined = AppStorage.get<common.UIAbilityContext>('abilityContext');
      if (context === undefined) throw new Error('页面上下文尚未就绪');
      await context.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri });
    } catch (error) {
      if (this.alive && token === this.pageToken) this.error = `无法打开来源：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.alive && token === this.pageToken) this.openingId = '';
    }
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        Row() {
          Row() {
            Icon({ name: 'back', iconSize: 20, color: INK3 })
          }.width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { router.back(); })
          Text(`来源资料 · ${this.sources.length}`).fontSize(TITLE).fontColor(INK).layoutWeight(1)
        }.width('100%').padding({ top: this.statusBarHeight, left: 12, right: 20, bottom: 10 })
        Scroll() {
          Column({ space: 12 }) {
            Text('粘贴原文完整保留，页面最多预览 40,000 字符。已保存全部来源及采集状态；生成最多使用前 10 个可用来源。网页失败可在文章页重试；文件读取失败需重新选择文件。')
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
      }.width('100%').height('100%').backgroundColor(BG)
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
