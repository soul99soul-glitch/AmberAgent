// BoardPage — 今日看板(真热榜 + 真本地统计)
//
// Android 基准: board/BoardPage.kt(BoardRepository + hotlist collector)。
//   鸿蒙热榜接 NewsNowProvider.fetchAllSources(真实网络抓取,逐源诚实报错);
//   本地统计接 ChatRepository(今日会话/pinned/消息条数),无假数据。

import type { Conversation, ConversationSummary } from '@amber/chat-domain';
import type { SourceSection, HotItem } from '../platform_impl/NewsNowProvider.ets';
import { createNewsNowProvider } from '../platform_impl/NewsNowProvider.ets';
import { getAppContainer, getChatRepository } from '../di/AppContainer.ets';
import {
  BG, SURFACE2, INK, INK2, INK3, INK4, ACCENT,
  BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, PaperSeparator, SectionHeader } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Component
struct BoardMetricRow {
  @Prop label: string = '';
  @Prop valueText: string = '';
  @Prop isLast: boolean = false;
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        Text(this.label)
          .fontSize(BODY2)
          .fontWeight(600)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(INK)
          .layoutWeight(1)
        Text(this.valueText)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(500)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressed = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
      })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

@Component
struct BoardHotItemRow {
  @Prop rankText: string = '';
  @Prop title: string = '';
  @Prop heat: string = '';
  @Prop isLast: boolean = false;
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        Text(this.rankText)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(500)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK4)
          .width(28)
        Text(this.title)
          .fontSize(BODY2)
          .fontWeight(400)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK2)
          .layoutWeight(1)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.heat.length > 0) {
          Text(this.heat)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(500)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK4)
            .margin({ left: 8 })
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressed = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
      })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

@Entry
@Component
struct BoardPage {
  @State sections: SourceSection[] = [];
  @State loading: boolean = false;
  @State hotlistError: string = '';
  @State todayConvs: number = 0;
  @State pinnedConvs: number = 0;
  @State totalMsgs: number = 0;

  aboutToAppear(): void {
    this.loadStats();
    this.loadHotlist();
  }

  private loadStats(): void {
    getChatRepository().list().then((list: ConversationSummary[]): void => {
      // 本地时区「今天」(updateAt 为 UTC ISO)
      const now: Date = new Date();
      const p0 = (n: number): string => n < 10 ? `0${n}` : `${n}`;
      const today: string = `${now.getFullYear()}-${p0(now.getMonth() + 1)}-${p0(now.getDate())}`;
      let todayCount: number = 0;
      let pinned: number = 0;
      for (const s of list) {
        const dt: Date = new Date(s.updateAt);
        const key: string = isNaN(dt.getTime()) ? '' :
          `${dt.getFullYear()}-${p0(dt.getMonth() + 1)}-${p0(dt.getDate())}`;
        if (key === today) todayCount++;
        if (s.isPinned) pinned++;
      }
      this.todayConvs = todayCount;
      this.pinnedConvs = pinned;
      // 消息条数:逐会话选中分支计数;单条失败跳过不断链
      let msgs: number = 0;
      let done: number = 0;
      if (list.length === 0) {
        this.totalMsgs = 0;
        return;
      }
      for (const s of list) {
        getChatRepository().getById(s.id).then((c: Conversation | null): void => {
          if (c !== null) {
            for (const n of c.messageNodes) {
              msgs += n.messages.length;
            }
          }
          done++;
          if (done >= list.length) this.totalMsgs = msgs;
        }).catch((): void => {
          done++;
          if (done >= list.length) this.totalMsgs = msgs;
        });
      }
    }).catch((): void => { /* 统计失败保持 0,不阻塞热榜 */ });
  }

  private loadHotlist(): void {
    if (this.loading) return; // 防连点并行抓取
    this.loading = true;
    createNewsNowProvider(getAppContainer().httpClient)
      .fetchAllSources()
      .then((all: SourceSection[]): Promise<void> => {
        // 消费深度阅读设置的热榜开关(source_<id>),关掉的源不上看板
        const storage = getAppContainer().storage;
        const acc: SourceSection[] = [];
        let storageFailed: boolean = false;
        const step = (i: number): Promise<void> => {
          if (i >= all.length) {
            if (!storageFailed) this.sections = acc;
            return Promise.resolve();
          }
          const sec: SourceSection = all[i];
          return storage.get<string>(`source_${sec.sourceId}`, 'true')
            .then((v: string): Promise<void> => {
              if (v === 'true') acc.push(sec);
              return step(i + 1);
            })
            .catch((): Promise<void> => {
              storageFailed = true;
              this.hotlistError = '热榜设置读取失败，已保留上次结果';
              return step(i + 1);
            });
        };
        this.hotlistError = '';
        return step(0);
      })
      .catch((e: Error): void => {
        this.hotlistError = `热榜加载失败：${e.message}`;
      })
      .finally((): void => {
        this.loading = false;
      });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '今日看板' })

      SectionHeader({ label: '今日概览' })
      Column() {
        BoardMetricRow({ label: '今日会话', valueText: `${this.todayConvs}` })
        BoardMetricRow({ label: '消息条数', valueText: `${this.totalMsgs}` })
        BoardMetricRow({ label: '已置顶', valueText: `${this.pinnedConvs}`, isLast: true })
      }
      .width('100%')

      Row() {
        Text('//')
          .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
        Text(' HOTLIST')
          .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          .layoutWeight(1)
        Row() {
          Icon({ name: 'retry', iconSize: 15, color: INK3 })
        }
        .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .onClick((): void => { this.loadHotlist(); })
      }
      .width('100%').padding({ left: 16, right: 16, top: 20, bottom: 8 })

      if (this.hotlistError.length > 0 && !this.loading) {
        Text(this.hotlistError)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 4 })
      }

      if (this.loading) {
        Text('抓取热榜中…')
          .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).margin({ top: 40 })
      } else {
        List() {
          ForEach(this.sections, (sec: SourceSection): void => {
            ListItem() {
              Column() {
                SectionHeader({ label: sec.sourceName })
                if (sec.error !== null || sec.items.length === 0) {
                  BoardHotItemRow({
                    rankText: '',
                    title: sec.error !== null ? `抓取失败:${sec.error}` : '无条目',
                    heat: '',
                    isLast: true,
                  })
                } else {
                  ForEach(sec.items.slice(0, 5), (item: HotItem, index: number): void => {
                    BoardHotItemRow({
                      rankText: `${item.rank}`,
                      title: item.title,
                      heat: item.heat,
                      isLast: index === Math.min(sec.items.length, 5) - 1,
                    })
                  }, (item: HotItem): string => `${sec.sourceId}-${item.rank}`)
                }
              }
              .width('100%')
            }
          }, (sec: SourceSection): string => sec.sourceId)
        }
        .width('100%').layoutWeight(1).padding({ top: 8 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
