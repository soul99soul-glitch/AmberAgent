// SettingSearchListCards — 搜索源列表页
// 以卡片形式展示可用搜索源(Exa / Tavily / Zhipu / Bing / DuckDuckGo / Brave)
// 每张卡片:名称 + 描述 + 启用状态徽章,点击进入编辑页
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface SearchSource {
  id: string;
  name: string;
  desc: string;
  enabled: boolean;
}

function makeSource(id: string, name: string, desc: string, enabled: boolean): SearchSource {
  return { id: id, name: name, desc: desc, enabled: enabled };
}

function placeholderLoadSources(): Promise<SearchSource[]> {
  const seed: SearchSource[] = [
    makeSource('exa', 'Exa', 'AI 驱动的语义搜索,适合查找相似内容', true),
    makeSource('tavily', 'Tavily', '面向 AI Agent 的实时搜索 API', true),
    makeSource('zhipu', 'Zhipu Search', '智谱 AI 搜索,支持中文语义检索', false),
    makeSource('bing', 'Bing', '微软 Bing 搜索,覆盖广泛网页结果', false),
    makeSource('duckduckgo', 'DuckDuckGo', '隐私优先的搜索引擎,无追踪', false),
    makeSource('brave', 'Brave Search', '独立索引,注重隐私的搜索服务', false),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingSearchListCards {
  @State sources: SearchSource[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadSources().then((list: SearchSource[]): void => {
      this.sources = list;
      this.loaded = true;
    });
  }

  private goEdit(_id: string): void {
    router.pushUrl({ url: 'pages/SettingSearchServiceEditorSheet' });
  }

  private toggleSource(id: string): void {
    this.sources = this.sources.map((s: SearchSource): SearchSource => {
      if (s.id !== id) return s;
      return makeSource(s.id, s.name, s.desc, !s.enabled);
    });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder sourceCard(s: SearchSource): void {
    Column() {
      Row() {
        Text(s.name).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .layoutWeight(1)
        Text(s.enabled ? '已启用' : '未启用')
          .fontSize(LABEL).fontColor(s.enabled ? ACCENT : INK3)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .backgroundColor(s.enabled ? SURFACE2 : SURFACE2)
          .borderRadius(50)
      }
      .width('100%')

      Text(s.desc).fontSize(META).fontColor(INK3).margin({ top: 4 })

      Row() {
        Text(s.enabled ? '关闭' : '启用').fontSize(META).fontColor(ACCENT)
          .padding({ left: 10, right: 10, top: 5, bottom: 5 })
          .backgroundColor(SURFACE2).borderRadius(50)
          .onClick((): void => { this.toggleSource(s.id); })

        Blank()

        Text('编辑 ›').fontSize(META).fontColor(INK2)
          .onClick((): void => { this.goEdit(s.id); })
      }
      .width('100%').margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(14).backgroundColor(SURFACE)
    .border({ width: 1, color: LINE }).borderRadius(12)
    .margin({ bottom: 10 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 新建
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('搜索源').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('+').fontSize(TITLE).fontColor(ACCENT)
          .onClick((): void => { router.pushUrl({ url: 'pages/SettingSearchServiceEditorSheet' }); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('可用搜索源')

            Row() {
              Text('点击卡片编辑配置,或点右上角添加新源。')
                .fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, right: 16, bottom: 10 })

            ForEach(this.sources, (s: SearchSource): void => {
              this.sourceCard(s)
            }, (s: SearchSource): string => s.id)

            this.sectionHeader('已启用')

            Row() {
              Text(this.sources.filter((s: SearchSource): boolean => s.enabled).length + ' 个搜索源已启用')
                .fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, right: 16, bottom: 10 })

            Column() {}.height(32)
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
