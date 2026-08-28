// HistoryPage — 历史记录(真实会话归档,按日分组)
//
// 原页误置为 DeepRead 配置(与 DeepReadArticlePage 内联设置重复,且热榜源
//   配置已归位 SettingDeepReadPage),本页归位 Android 历史语义:
//   全量会话按 updateAt 降序 + 日期分组,点击进对话。

import { router } from '@kit.ArkUI';
import type { ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import {
  BG, INK, INK3, INK4,
  META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, SectionHeader, NavRow } from '../components/PageTemplate.ets';

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
          Text('// 暂无历史')
            .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.groups, (g: HistoryGroup): void => {
            ListItem() {
              Column() {
                SectionHeader({ label: g.date })
                ForEach(g.items, (s: ConversationSummary, index: number): void => {
                  NavRow({
                    label: s.title.length > 0 ? s.title : '未命名对话',
                    valueText: this.timeOf(s),
                    isLast: index === g.items.length - 1,
                    onRowClick: (): void => {
                      const params: Record<string, string> = {};
                      params['conversationId'] = s.id;
                      router.pushUrl({ url: 'pages/ChatPage', params: params });
                    },
                  })
                }, (s: ConversationSummary): string => s.id)
              }
              .width('100%')
            }
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
