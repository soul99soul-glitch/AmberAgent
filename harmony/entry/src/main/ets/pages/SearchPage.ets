// SearchPage — 全局搜索页
// 搜索框 + 真实网络搜索结果;网络不可用时降级为本地会话标题搜索。

import { router, promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import type { ConversationSummary, JsonObject, JsonValue, SearchSettings } from '@amber/chat-domain';
import { loadSearchPrefs, searchOrchestratorSearch } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { DotLoading } from '../components/ui/DotLoading.ets';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, TITLE } from '../design/tokens.ets';

interface SearchResult {
  id: string;
  title: string;
  snippet: string;
  source: string;
  url: string;
  domain: string;
  updatedAt: string;
  isConversation: boolean;
}

interface SearchExecution {
  results: SearchResult[];
  errorMsg: string;
}

const stringValue = (value: JsonValue | undefined): string =>
  typeof value === 'string' ? value : '';

const errorMessage = (error: Error): string => {
  const message: string = error.message;
  return message.length > 0 ? message : String(error);
};

const webResultsFrom = (payload: JsonObject): SearchResult[] => {
  const rawItems: JsonValue | undefined = payload['items'];
  if (!Array.isArray(rawItems)) return [];
  const results: SearchResult[] = [];
  rawItems.forEach((raw: JsonValue, index: number): void => {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return;
    const item: JsonObject = raw as JsonObject;
    const title: string = stringValue(item['title']).trim();
    const url: string = stringValue(item['url']).trim();
    if (title.length === 0 || url.length === 0) return;
    const domain: string = stringValue(item['domain']).trim();
    const source: string = domain.length > 0 ? domain : stringValue(item['source_service']).trim();
    results.push({
      id: stringValue(item['id']).trim().length > 0 ? stringValue(item['id']).trim() : `web-${index}`,
      title,
      snippet: stringValue(item['text']).trim(),
      source: source.length > 0 ? source : '网络',
      url,
      domain,
      updatedAt: '',
      isConversation: false,
    });
  });
  return results;
};

const localResultsFrom = (items: ConversationSummary[]): SearchResult[] =>
  items.map((item: ConversationSummary): SearchResult => ({
    id: `conversation-${item.id}`,
    title: item.title.length > 0 ? item.title : '未命名对话',
    snippet: '',
    source: '会话',
    url: '',
    domain: '',
    updatedAt: item.updateAt,
    isConversation: true,
  }));

@Entry
@Component
struct SearchPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State searchText: string = '';
  @State results: SearchResult[] = [];
  @State loading: boolean = false;
  @State searched: boolean = false;
  @State errorMsg: string = '';
  private pageAlive: boolean = false;
  private searchSeq: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    const params = router.getParams() as Record<string, string>;
    const initialQuery: string = params ? (params['query'] ?? '') : '';
    if (initialQuery.trim().length > 0) this.onSearch(initialQuery);
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.searchSeq++;
  }

  private formatDate(iso: string): string {
    const date: Date = new Date(iso);
    if (isNaN(date.getTime())) return iso.length > 10 ? iso.substring(0, 10) : iso;
    return `${date.getMonth() + 1}月${date.getDate()}日 ${date.getHours()}:${date.getMinutes() < 10 ? '0' : ''}${date.getMinutes()}`;
  }

  private async executeSearch(query: string): Promise<SearchExecution> {
    let webError: string = '';
    try {
      const settings: SearchSettings = await loadSearchPrefs(getChatKvStore());
      ensureSearchSdk();
      const params: JsonObject = { query: query, max_results: 20 };
      const payload: JsonObject = await searchOrchestratorSearch(settings, params);
      const webResults: SearchResult[] = webResultsFrom(payload);
      if (webResults.length > 0) return { results: webResults, errorMsg: '' };
      webError = stringValue(payload['error']).trim();
      if (webError.length === 0) webError = stringValue(payload['message']).trim();
    } catch (error) {
      webError = errorMessage(error as Error);
    }

    try {
      const sessions: ConversationSummary[] = await getChatRepository().search(query);
      const localResults: SearchResult[] = localResultsFrom(sessions);
      // 网络失败但本地也无结果时保留可诊断错误;有本地结果则不打扰用户。
      return {
        results: localResults,
        errorMsg: localResults.length === 0 ? webError : '',
      };
    } catch (error) {
      const localError: string = errorMessage(error as Error);
      return {
        results: [],
        errorMsg: webError.length > 0 ? `${webError}；本地会话搜索失败：${localError}` : localError,
      };
    }
  }

  private onSearch(text: string): void {
    this.searchText = text;
    const query: string = text.trim();
    const seq: number = ++this.searchSeq;
    this.errorMsg = '';
    if (query.length === 0) {
      this.results = [];
      this.searched = false;
      this.loading = false;
      return;
    }
    this.loading = true;
    this.searched = true;
    this.executeSearch(query).then((outcome: SearchExecution): void => {
      if (!this.pageAlive || seq !== this.searchSeq) return;
      this.results = outcome.results;
      this.errorMsg = outcome.errorMsg;
      this.loading = false;
    }).catch((error: Error): void => {
      if (!this.pageAlive || seq !== this.searchSeq) return;
      this.results = [];
      this.errorMsg = errorMessage(error);
      this.loading = false;
    });
  }

  private openResult(item: SearchResult): void {
    if (item.isConversation) {
      const params: Record<string, string> = {};
      params['conversationId'] = item.id.substring('conversation-'.length);
      router.pushUrl({ url: 'pages/ChatPage', params: params });
      return;
    }
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, item.url));
      promptAction.showToast({ message: '已复制链接', duration: 1400 });
    } catch (error) {
      this.errorMsg = `复制链接失败：${errorMessage(error as Error)}`;
    }
  }

  build(): void {
    Column() {
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 }).onClick((): void => { router.back(); })
        Text('搜索').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 8 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      Row() {
        Icon({ name: 'search', iconSize: 14, color: INK3 }).margin({ right: 8 })
        TextInput({ text: this.searchText, placeholder: '搜索话题、文章、关键词…' })
          .fontSize(BODY).fontColor(INK).backgroundColor(Color.Transparent).layoutWeight(1)
          .onChange((value: string): void => { this.onSearch(value); })
        if (this.searchText.length > 0) {
          Text('✕').fontSize(12).fontColor(INK3)
            .padding({ left: 8, right: 4 })
            .onClick((): void => { this.onSearch(''); })
        }
      }
      .width('100%').padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .backgroundColor(SURFACE2).borderRadius(8)
      .margin({ left: 16, right: 16, top: 12, bottom: 8 })

      if (this.loading) {
        Column() {
          DotLoading({ dotSize: 12 })
          Text('搜索中…').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center).padding({ top: 48 })
      } else if (this.errorMsg.length > 0) {
        Column() {
          Text('搜索失败').fontSize(BODY).fontColor(INK2)
          Text(this.errorMsg).fontSize(META).fontColor(INK3).margin({ top: 8 })
            .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
        }.width('100%').margin({ top: 64 }).padding({ left: 24, right: 24 })
      } else if (this.searched && this.results.length === 0) {
        Column() {
          Text('没有找到结果').fontSize(BODY).fontColor(INK2)
          Text('换个关键词试试').fontSize(META).fontColor(INK3).margin({ top: 8 })
        }.margin({ top: 64 })
      } else if (!this.searched) {
        Column() {
          Icon({ name: 'search', iconSize: 34, color: INK4 })
          Text('输入关键词开始搜索').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.results, (item: SearchResult): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                if (item.snippet.length > 0) {
                  Text(item.snippet).fontSize(META).fontColor(INK3).margin({ top: 4 })
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                if (item.isConversation) {
                  Text(`会话 · ${this.formatDate(item.updatedAt)}`)
                    .fontSize(META).fontColor(ACCENT).margin({ top: 6 })
                } else {
                  Text(item.url).fontSize(META).fontColor(INK3).margin({ top: 6 })
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(item.source).fontSize(META).fontColor(ACCENT).margin({ top: 4 })
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.openResult(item); })
            }
          }, (item: SearchResult): string => item.id)
        }
        .width('100%').layoutWeight(1).divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
