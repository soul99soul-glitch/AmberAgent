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
  BG, SURFACE, INK, INK2, INK3, INK4, LINE, ACCENT,
  BODY, BODY2, META, MONO_SMALL, LABEL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct BoardPage {
  @State sections: SourceSection[] = [];
  @State loading: boolean = false;
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
              if (n.messages.length > 0) msgs++;
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
      .then((all: SourceSection[]): void => {
        // 消费深度阅读设置的热榜开关(source_<id>),关掉的源不上看板
        const storage = getAppContainer().storage;
        const acc: SourceSection[] = [];
        const step = (i: number): void => {
          if (i >= all.length) {
            this.sections = acc;
            this.loading = false;
            return;
          }
          const sec: SourceSection = all[i];
          storage.get<string>(`source_${sec.sourceId}`, 'true').then((v: string): void => {
            if (v === 'true') acc.push(sec);
            step(i + 1);
          });
        };
        step(0);
      });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '今日看板' })

      // 本地真实统计
      Row() {
        Column() {
          Text(`${this.todayConvs}`).fontSize(22).fontWeight(FontWeight.Bold)
            .fontColor(INK).fontFamily('JetBrainsMono')
          Text('今日会话').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }.layoutWeight(1)
        Column() {
          Text(`${this.totalMsgs}`).fontSize(22).fontWeight(FontWeight.Bold)
            .fontColor(INK).fontFamily('JetBrainsMono')
          Text('消息条数').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }.layoutWeight(1)
        Column() {
          Text(`${this.pinnedConvs}`).fontSize(22).fontWeight(FontWeight.Bold)
            .fontColor(INK).fontFamily('JetBrainsMono')
          Text('已置顶').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }.layoutWeight(1)
      }
      .padding({ top: 14, bottom: 14 })
      .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
      .margin({ left: 18, right: 18, top: 10 })

      Row() {
        Text('// HOTLIST').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          .layoutWeight(1).margin({ left: 18 })
        Icon({ name: 'retry', iconSize: 15, color: INK3 }).margin({ right: 18 })
          .onClick((): void => { this.loadHotlist(); })
      }
      .width('100%').margin({ top: 14 })

      if (this.loading) {
        Text('抓取热榜中…').fontSize(BODY2).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).margin({ top: 40 })
      } else {
        List() {
          ForEach(this.sections, (sec: SourceSection): void => {
            ListItem() {
              Column() {
                Text(sec.sourceName).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(ACCENT)
                if (sec.error !== null || sec.items.length === 0) {
                  Text(sec.error !== null ? `抓取失败:${sec.error}` : '无条目')
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                    .margin({ top: 6 })
                } else {
                  ForEach(sec.items.slice(0, 5), (item: HotItem): void => {
                    Row() {
                      Text(`${item.rank}`).fontSize(MONO_SMALL).fontColor(INK4)
                        .fontFamily('JetBrainsMono').width(20)
                      Text(item.title).fontSize(BODY2).fontColor(INK2)
                        .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (item.heat.length > 0) {
                        Text(item.heat).fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                      }
                    }
                    .width('100%').margin({ top: 6 })
                  }, (item: HotItem): string => `${sec.sourceId}-${item.rank}`)
                }
              }
              .padding(14)
              .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
              .margin({ left: 18, right: 18, bottom: 10 })
              .alignItems(HorizontalAlign.Start)
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
