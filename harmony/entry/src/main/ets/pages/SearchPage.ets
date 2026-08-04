// SearchPage — 全局搜索页
// 搜索框(WorkspaceSearchField)+ 结果列表(标题 + 片段 + 来源);加载/空状态
// Android 基准: feature/ui/pages/search/SearchPage.kt
import { router } from '@kit.ArkUI';
import { WorkspaceSearchField } from '../components/ui/WorkspaceSearchField.ets';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface SearchResult {
  id: string;
  title: string;
  snippet: string;
  source: string;
}

function makeResult(id: string, title: string, snippet: string, source: string): SearchResult {
  return { id: id, title: title, snippet: snippet, source: source };
}

function runSearch(query: string): Promise<SearchResult[]> {
  const q: string = query.trim();
  if (q.length === 0) {
    return Promise.resolve([]);
  }
  const seed: SearchResult[] = [
    makeResult('r-1', `${q} 相关讨论`, `关于「${q}」的精选讨论与高赞回答…`, '知乎'),
    makeResult('r-2', `${q} 入门指南`, `从零开始了解「${q}」的完整路径与资源…`, '掘金'),
    makeResult('r-3', `${q} 最新进展`, `「${q}」近期的动态与趋势分析…`, '微博'),
    makeResult('r-4', `${q} 官方文档`, `「${q}」的官方说明与参考手册…`, '百度'),
    makeResult('r-5', `${q} 开源项目`, `与「${q}」相关的热门开源仓库…`, 'GitHub'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SearchPage {
  @State searchText: string = '';
  @State results: SearchResult[] = [];
  @State loading: boolean = false;
  @State searched: boolean = false;

  private onSearch(text: string): void {
    this.searchText = text;
    const q: string = text.trim();
    if (q.length === 0) {
      this.results = [];
      this.searched = false;
      this.loading = false;
      return;
    }
    this.loading = true;
    this.searched = true;
    runSearch(q).then((list: SearchResult[]): void => {
      this.results = list;
      this.loading = false;
    });
  }

  private openResult(item: SearchResult): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('搜索').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      WorkspaceSearchField({
        placeholder: '搜索话题、文章、关键词…',
        onSearch: (v: string): void => { this.onSearch(v); },
      })
        .margin({ left: 16, right: 16, top: 12, bottom: 8 })

      if (this.loading) {
        Column() {
          LoadingProgress().width(28).height(28).color(ACCENT)
          Text('搜索中…').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
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
                Text(item.snippet).fontSize(META).fontColor(INK3).margin({ top: 4 })
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(item.source).fontSize(LABEL).fontColor(ACCENT).margin({ top: 6 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.openResult(item); })
            }
          }, (item: SearchResult): string => item.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
