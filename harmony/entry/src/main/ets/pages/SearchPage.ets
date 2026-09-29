// SearchPage — 全局搜索页
import { CenteredTitleBar } from '../components/PageTemplate.ets';
// 搜索框 + 真实网络搜索结果;网络不可用时降级为本地会话标题搜索。

import { router, promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import type {
  Conversation, ConversationSummary, JsonObject, JsonValue, SearchSettings, UIMessage,
  UIMessagePartText,
} from '@amber/chat-domain';
import { currentMessages, loadSearchPrefs, searchOrchestratorSearch } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { DotLoading } from '../components/ui/DotLoading.ets';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, TITLE } from '../design/tokens.ets';

type SearchResultKind = 'message' | 'conversation' | 'web';

interface SearchResult {
  id: string;
  title: string;
  snippet: string;
  source: string;
  url: string;
  domain: string;
  updatedAt: string;
  kind: SearchResultKind;
  conversationId: string;
  nodeId: string;
  messageId: string;
}

interface SearchExecution {
  results: SearchResult[];
  errorMsg: string;
}

const MAX_MESSAGE_SCAN_SESSIONS: number = 50;
const MAX_MESSAGE_HITS_PER_SESSION: number = 3;
const MESSAGE_PREVIEW_LENGTH: number = 80;
const SEARCH_DEBOUNCE_MS: number = 300;
const MAX_TITLE_RESULTS: number = 20;

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
      kind: 'web',
      conversationId: '',
      nodeId: '',
      messageId: '',
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
    kind: 'conversation',
    conversationId: item.id,
    nodeId: '',
    messageId: '',
  }));

const messagePreview = (text: string): string => {
  const compact: string = text.replace(/\s+/g, ' ').trim();
  return compact.length > MESSAGE_PREVIEW_LENGTH
    ? `${compact.substring(0, MESSAGE_PREVIEW_LENGTH)}…` : compact;
};

const messageResultsFrom = async (
  query: string, candidates: ConversationSummary[], shouldContinue: () => boolean,
): Promise<SearchResult[]> => {
  const needle: string = query.toLowerCase();
  const results: SearchResult[] = [];
  for (const summary of candidates) {
    if (!shouldContinue()) break;
    try {
      const conversation: Conversation | null = await getChatRepository().getById(summary.id);
      if (conversation === null) continue;
      const messages: UIMessage[] = currentMessages(conversation);
      let hits: number = 0;
      for (let nodeIndex = conversation.messageNodes.length - 1;
        nodeIndex >= 0 && hits < MAX_MESSAGE_HITS_PER_SESSION; nodeIndex--) {
        if (!shouldContinue()) break;
        const message: UIMessage | undefined = messages[nodeIndex];
        if (message === undefined || (message.role !== 'user' && message.role !== 'assistant')) continue;
        const textParts: string[] = [];
        for (const rawPart of message.parts) {
          if (rawPart.type === 'text') textParts.push((rawPart as UIMessagePartText).text);
        }
        const text: string = textParts.join('\n');
        if (!text.toLowerCase().includes(needle)) continue;
        const nodeId: string = conversation.messageNodes[nodeIndex].id;
        results.push({
          id: `message-${conversation.id}-${nodeId}-${message.id}`,
          title: conversation.title.length > 0 ? conversation.title : '未命名对话',
          snippet: messagePreview(text),
          source: message.role === 'user' ? '我的' : '助手',
          url: '',
          domain: '',
          updatedAt: message.createdAt.length > 0 ? message.createdAt : conversation.updateAt,
          kind: 'message',
          conversationId: conversation.id,
          nodeId,
          messageId: message.id,
        });
        hits++;
      }
    } catch (_error) {
      // A malformed or unavailable conversation must not hide other search results.
    }
  }
  return results;
};

@Entry
@Component
struct SearchPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State searchText: string = '';
  @State results: SearchResult[] = [];
  @State loading: boolean = false;
  @State searched: boolean = false;
  @State errorMsg: string = '';
  private pageAlive: boolean = false;
  private searchSeq: number = 0;
  private debounceTimer: number = -1;

  aboutToAppear(): void {
    this.pageAlive = true;
    const params = router.getParams() as Record<string, string>;
    const initialQuery: string = params ? (params['query'] ?? '') : '';
    if (initialQuery.trim().length > 0) this.onSearch(initialQuery);
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.searchSeq++;
    if (this.debounceTimer >= 0) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = -1;
    }
  }

  private formatDate(iso: string): string {
    const date: Date = new Date(iso);
    if (isNaN(date.getTime())) return iso.length > 10 ? iso.substring(0, 10) : iso;
    const monthDay: string = `${date.getMonth() + 1}月${date.getDate()}日`;
    const now: Date = new Date();
    if (date.getFullYear() !== now.getFullYear()) return `${date.getFullYear()}年${monthDay}`;
    return `${monthDay} ${date.getHours()}:${date.getMinutes() < 10 ? '0' : ''}${date.getMinutes()}`;
  }

  private async executeWebSearch(query: string): Promise<SearchExecution> {
    try {
      const settings: SearchSettings = await loadSearchPrefs(getChatKvStore());
      ensureSearchSdk();
      const params: JsonObject = { query: query, max_results: 20 };
      const payload: JsonObject = await searchOrchestratorSearch(settings, params);
      const results: SearchResult[] = webResultsFrom(payload);
      let errorMsg: string = stringValue(payload['error']).trim();
      if (errorMsg.length === 0) errorMsg = stringValue(payload['message']).trim();
      return { results, errorMsg };
    } catch (error) {
      return { results: [], errorMsg: errorMessage(error as Error) };
    }
  }

  private async executeLocalSearch(
    query: string, shouldContinue: () => boolean,
    publishTitleResults: (results: SearchResult[]) => void,
  ): Promise<SearchExecution> {
    let titleMatches: ConversationSummary[] = [];
    try {
      const repository = getChatRepository();
      titleMatches = await repository.search(query);
      const titleResults: SearchResult[] = localResultsFrom(titleMatches);
      publishTitleResults(titleResults);
      const allSessions: ConversationSummary[] = await repository.list();
      const candidateIds: Set<string> = new Set<string>();
      const candidates: ConversationSummary[] = [];
      for (const item of titleMatches) {
        if (!candidateIds.has(item.id)) {
          candidateIds.add(item.id);
          candidates.push(item);
        }
      }
      for (const item of allSessions) {
        if (candidates.length >= MAX_MESSAGE_SCAN_SESSIONS || candidateIds.has(item.id)) continue;
        candidateIds.add(item.id);
        candidates.push(item);
      }
      const messageResults: SearchResult[] = await messageResultsFrom(query, candidates, shouldContinue);
      return { results: [...messageResults, ...titleResults], errorMsg: '' };
    } catch (error) {
      const titleResults: SearchResult[] = localResultsFrom(titleMatches);
      return {
        results: titleResults,
        errorMsg: titleResults.length > 0 ? '' : `本地会话搜索失败：${errorMessage(error as Error)}`,
      };
    }
  }

  private onSearch(text: string): void {
    this.searchText = text;
    const query: string = text.trim();
    const seq: number = ++this.searchSeq;
    this.errorMsg = '';
    if (this.debounceTimer >= 0) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = -1;
    }
    if (query.length === 0) {
      this.results = [];
      this.searched = false;
      this.loading = false;
      return;
    }
    this.loading = true;
    this.searched = true;
    this.results = [];
    this.debounceTimer = setTimeout((): void => {
      this.debounceTimer = -1;
      const isCurrent: () => boolean = (): boolean => this.pageAlive && seq === this.searchSeq;
      const localPromise: Promise<SearchExecution> = this.executeLocalSearch(query, isCurrent,
        (titleResults: SearchResult[]): void => {
          if (isCurrent()) {
            this.results = [...titleResults, ...this.results.filter(
              (item: SearchResult): boolean => item.kind === 'web')];
          }
        }).then(
        (outcome: SearchExecution): SearchExecution => {
          if (isCurrent()) {
            this.results = [...outcome.results, ...this.results.filter(
              (item: SearchResult): boolean => item.kind === 'web')];
            this.errorMsg = outcome.errorMsg;
          }
          return outcome;
        });
      const webPromise: Promise<SearchExecution> = this.executeWebSearch(query).then(
        (outcome: SearchExecution): SearchExecution => {
          if (isCurrent()) {
            this.results = [...this.results.filter(
              (item: SearchResult): boolean => item.kind !== 'web'), ...outcome.results];
            if (outcome.errorMsg.length > 0 && outcome.results.length === 0) this.errorMsg = outcome.errorMsg;
          }
          return outcome;
        });
      Promise.allSettled([localPromise, webPromise]).then((): void => {
        if (isCurrent()) this.loading = false;
      });
    }, SEARCH_DEBOUNCE_MS);
  }

  private openResult(item: SearchResult): void {
    if (item.kind === 'message' || item.kind === 'conversation') {
      const params: Record<string, string> = {};
      params['conversationId'] = item.conversationId;
      if (item.kind === 'message' && item.nodeId.length > 0) {
        params['nodeId'] = item.nodeId;
      }
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

  private resultsOf(kind: SearchResultKind): SearchResult[] {
    return this.results.filter((item: SearchResult): boolean => item.kind === kind);
  }

  @Builder
  private buildResultRow(item: SearchResult): void {
    ListItem() {
      Column() {
        Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (item.snippet.length > 0) {
          Text(item.snippet).fontSize(META).fontColor(INK3).margin({ top: 4 })
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        if (item.kind === 'message') {
          Text(`消息 · ${item.source} · ${this.formatDate(item.updatedAt)}`)
            .fontSize(META).fontColor(ACCENT).margin({ top: 6 })
        } else if (item.kind === 'conversation') {
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
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '搜索' })

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

      if (this.loading && this.results.length === 0) {
        Column() {
          DotLoading({ dotSize: 6, color: INK3 })
          Text('搜索中…').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center).padding({ top: 48 })
      } else if (this.errorMsg.length > 0 && this.results.length === 0) {
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
          if (this.resultsOf('message').length > 0 || this.resultsOf('conversation').length > 0) {
            ListItem() {
              Text('会话消息').fontSize(BODY).fontWeight(FontWeight.Bold).fontColor(INK2)
                .padding({ left: 16, right: 16, top: 14, bottom: 8 })
                .width('100%')
            }
            ForEach(this.resultsOf('message'), (item: SearchResult): void => {
              this.buildResultRow(item);
            }, (item: SearchResult): string => item.id)
            ForEach(this.resultsOf('conversation').slice(0, MAX_TITLE_RESULTS), (item: SearchResult): void => {
              this.buildResultRow(item);
            }, (item: SearchResult): string => item.id)
            if (this.resultsOf('conversation').length > MAX_TITLE_RESULTS) {
              ListItem() {
                Text(`更多 ${this.resultsOf('conversation').length - MAX_TITLE_RESULTS} 条`)
                  .fontSize(META).fontColor(INK3)
                  .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                  .width('100%')
              }
            }
          }
          if (this.resultsOf('web').length > 0) {
            ListItem() {
              Text('网页结果').fontSize(BODY).fontWeight(FontWeight.Bold).fontColor(INK2)
                .padding({ left: 16, right: 16, top: 14, bottom: 8 })
                .width('100%')
            }
            ForEach(this.resultsOf('web'), (item: SearchResult): void => {
              this.buildResultRow(item);
            }, (item: SearchResult): string => item.id)
          }
        }
        .width('100%').layoutWeight(1).divider({ strokeWidth: 1, color: LINE })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
