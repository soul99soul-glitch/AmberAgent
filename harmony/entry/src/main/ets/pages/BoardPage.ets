// BoardPage — DeepRead 热榜/今日页
// 按来源分组(综合热点/知乎热榜/微博热搜/百度热点/掘金热门/GitHub Trending)
// 每项:排名 + 标题 + 热度,点击打开 DeepReadArticlePage
// Android 基准: feature/ui/pages/board/BoardPage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface BoardEntry {
  id: string;
  rank: number;
  title: string;
  heat: string;
}
interface BoardGroup {
  source: string;
  items: BoardEntry[];
}

function makeEntry(id: string, rank: number, title: string, heat: string): BoardEntry {
  return { id: id, rank: rank, title: title, heat: heat };
}
function makeGroup(source: string, items: BoardEntry[]): BoardGroup {
  return { source: source, items: items };
}

function placeholderLoadBoard(): Promise<BoardGroup[]> {
  const groups: BoardGroup[] = [
    makeGroup('综合热点', [
      makeEntry('c-1', 1, '国产大模型集体降价,API 价格战再升级', '2.4万'),
      makeEntry('c-2', 2, '鸿蒙 NEXT 5.0 发布,纯血原生应用破万', '1.9万'),
      makeEntry('c-3', 3, 'AI 编程助手效率提升实测对比', '1.5万'),
      makeEntry('c-4', 4, '端侧小模型部署最佳实践总结', '1.1万'),
      makeEntry('c-5', 5, '开源向量数据库年度横评', '0.9万'),
    ]),
    makeGroup('知乎热榜', [
      makeEntry('zh-1', 1, '如何评价 2026 年大模型推理能力？', '8.2万热度'),
      makeEntry('zh-2', 2, '程序员如何保持长期学习动力？', '5.6万热度'),
      makeEntry('zh-3', 3, '远程办公三年,我的一些体会', '4.1万热度'),
      makeEntry('zh-4', 4, '从零搭建个人知识库的完整方案', '3.3万热度'),
    ]),
    makeGroup('微博热搜', [
      makeEntry('wb-1', 1, '#国产AI芯片突破#', '沸'),
      makeEntry('wb-2', 2, '#鸿蒙生态加速#', '热'),
      makeEntry('wb-3', 3, '#程序员日常#', '热'),
    ]),
    makeGroup('百度热点', [
      makeEntry('bd-1', 1, '最新人工智能政策解读', '342万'),
      makeEntry('bd-2', 2, '智能驾驶落地进展', '287万'),
      makeEntry('bd-3', 3, '量子计算新突破', '156万'),
    ]),
    makeGroup('掘金热门', [
      makeEntry('jj-1', 1, 'ArkTS 状态管理深度剖析', '1.2k'),
      makeEntry('jj-2', 2, 'Compose 与 ArkUI 对比指南', '0.9k'),
      makeEntry('jj-3', 3, '前端性能优化全景图 2026', '0.8k'),
    ]),
    makeGroup('GitHub Trending', [
      makeEntry('gh-1', 1, 'amber-agent/amber-agent', '★ 12.3k'),
      makeEntry('gh-2', 2, 'deepseek-ai/DeepRead', '★ 8.7k'),
      makeEntry('gh-3', 3, 'open-harmony/awesome-arkts', '★ 5.1k'),
    ]),
  ];
  return Promise.resolve(groups);
}

@Entry
@Component
struct BoardPage {
  @State groups: BoardGroup[] = [];
  @State loaded: boolean = false;
  @State refreshing: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    this.refreshing = true;
    placeholderLoadBoard().then((list: BoardGroup[]): void => {
      this.groups = list;
      this.loaded = true;
      this.refreshing = false;
    });
  }

  private openArticle(item: BoardEntry): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  @Builder groupHeader(source: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${source}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK2)
    }
    .width('100%').padding({ left: 16, top: 22, bottom: 8 })
  }

  @Builder entryRow(item: BoardEntry, last: boolean): void {
    Row() {
      Text(item.rank < 10 ? '0' + item.rank.toString() : item.rank.toString())
        .fontSize(META).fontWeight(FontWeight.Bold).fontColor(INK4).width(28)
      Text(item.title)
        .fontSize(BODY).fontColor(INK).layoutWeight(1).maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(item.heat).fontSize(LABEL).fontColor(INK4).margin({ left: 8 })
    }
    .width('100%').padding({ left: 16, right: 16, top: 13, bottom: 13 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: last ? 0 : 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.openArticle(item); })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 刷新
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('热榜').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.refreshing ? '刷新中…' : '↻ 刷新').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.refresh(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          LoadingProgress().width(28).height(28).color(ACCENT)
          Text('加载中…').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            ForEach(this.groups, (g: BoardGroup): void => {
              this.groupHeader(g.source)
              Column() {
                ForEach(g.items, (item: BoardEntry, index: number): void => {
                  this.entryRow(item, index === g.items.length - 1)
                }, (item: BoardEntry): string => item.id)
              }
              .width('100%').backgroundColor(SURFACE).border({ width: 1, color: LINE })
              .margin({ left: 16, right: 16, bottom: 4 })
              .borderRadius(12)
            }, (g: BoardGroup): string => g.source)
            Column() {}.height(40)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
