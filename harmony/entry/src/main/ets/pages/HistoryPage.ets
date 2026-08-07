// HistoryPage — 历史记录(真实会话归档,按日分组)
//
// 原页误置为 DeepRead 配置(与 DeepReadArticlePage 内联设置重复,且热榜源
//   配置已归位 SettingDeepReadPage),本页归位 Android 历史语义:
//   全量会话按 updateAt 降序 + 日期分组,点击进对话。

import { router } from '@kit.ArkUI';
import type { ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE,
  BODY, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';

interface HistoryGroup {
  date: string;
  items: ConversationSummary[];
}

@Entry
@Component
struct HistoryPage {
  @State groups: HistoryGroup[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    getChatRepository().list().then((list: ConversationSummary[]): void => {
      const sorted: ConversationSummary[] = list.slice().sort(
        (a: ConversationSummary, b: ConversationSummary): number =>
          b.updateAt.localeCompare(a.updateAt),
      );
      const acc: HistoryGroup[] = [];
      for (const s of sorted) {
        // updateAt 为 UTC ISO → 按本地时区分组(否则 UTC+8 凌晨归错日)
        const d: string = this.localDateOf(s.updateAt);
        let g: HistoryGroup | null = null;
        for (const x of acc) {
          if (x.date === d) g = x;
        }
        if (g === null) {
          const ng: HistoryGroup = { date: d, items: [s] };
          acc.push(ng);
        } else {
          g.items.push(s);
        }
      }
      this.groups = acc;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private localDateOf(iso: string): string {
    const dt: Date = new Date(iso);
    if (isNaN(dt.getTime())) return iso.substring(0, 10);
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
  }

  private timeOf(s: ConversationSummary): string {
    const dt: Date = new Date(s.updateAt);
    if (isNaN(dt.getTime())) return '';
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${p(dt.getHours())}:${p(dt.getMinutes())}`;
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '历史记录' })

      if (this.loaded && this.groups.length === 0) {
        Column() {
          Text('// 暂无历史').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.groups, (g: HistoryGroup): void => {
            ListItem() {
              Text(g.date).fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                .margin({ left: 20, top: 14, bottom: 6 })
            }
            ForEach(g.items, (s: ConversationSummary): void => {
              ListItem() {
                Row() {
                  Text(s.title.length > 0 ? s.title : '未命名对话')
                    .fontSize(BODY).fontColor(INK).layoutWeight(1)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(this.timeOf(s)).fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                }
                .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 12 })
                .backgroundColor(SURFACE)
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick((): void => {
                  const params: Record<string, string> = {};
                  params['conversationId'] = s.id;
                  router.pushUrl({ url: 'pages/ChatPage', params: params });
                })
              }
            }, (s: ConversationSummary): string => s.id)
          }, (g: HistoryGroup): string => g.date)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
