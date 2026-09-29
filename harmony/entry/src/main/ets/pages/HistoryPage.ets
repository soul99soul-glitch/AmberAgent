// HistoryPage — 历史记录(真实会话归档,按日分组;多选导出 Markdown/JSON)
//
// 原页误置为 DeepRead 配置(与 DeepReadArticlePage 内联设置重复,且热榜源
//   配置已归位 SettingDeepReadPage),本页归位 Android 历史语义:
//   全量会话按 updateAt 降序 + 日期分组,点击进对话;多选后导出文件。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection(日期组标)/ CardGroup r18+发丝描边 +
//   CardRow(标题 + mono 时间)/ sans 空态;逻辑(列表/多选/导出/跳转)零改动。

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import type { ConversationSummary, Conversation, UIMessage } from '@amber/chat-domain';
import { currentMessages, toText } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import {
  BG, INK3, ACCENT, LINE, SURFACE, BODY2, lineHeightForSize, MOTION_OVERLAY,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, PageSpacer,
} from '../components/PageTemplate.ets';

interface HistoryGroup {
  date: string;
  items: ConversationSummary[];
}

// JSON 导出条目形态(arkts-no-untyped-obj-literals)
interface ExportEntry {
  id: string;
  title: string;
  updatedAt: string;
  messages: UIMessage[];
}

@Entry
@Component
struct HistoryPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State groups: HistoryGroup[] = [];
  @State loaded: boolean = false;
  // 多选导出
  @State selecting: boolean = false;
  @State selectedIds: string[] = [];
  // aboutToAppear 与首次 onPageShow 都会触发 reload,首屏只加载一次
  private firstShowDone: boolean = false;

  aboutToAppear(): void {
    this.reload();
  }

  onPageShow(): void {
    if (this.firstShowDone) {
      this.reload();
    } else {
      this.firstShowDone = true;
    }
  }

  private reload(): void {
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

  private toggleSelect(id: string): void {
    const i: number = this.selectedIds.indexOf(id);
    if (i >= 0) {
      const next: string[] = this.selectedIds.slice();
      next.splice(i, 1);
      this.selectedIds = next;
    } else {
      this.selectedIds = [...this.selectedIds, id];
    }
  }

  private exitSelecting(): void {
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.selecting = false; });
  }

  // F14:顶栏「选择」按钮此前恒调用 exitSelecting,导致选择模式不可达;按当前态切换。
  private toggleSelecting(): void {
    if (this.selecting) {
      this.exitSelecting();
      return;
    }
    this.selectedIds = [];
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.selecting = true; });
  }

  // ===== 导出(DocumentSavePicker;多会话单文件拼接) =====

  private static exportFileName(ext: string): string {
    const d: Date = new Date();
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `amber-export-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}.${ext}`;
  }

  private async buildExportText(asMarkdown: boolean): Promise<string> {
    const parts: string[] = [];
    for (const id of this.selectedIds) {
      const conv: Conversation | null = await getChatRepository().getById(id);
      if (conv === null) continue;
      if (asMarkdown) {
        const title: string = conv.title.length > 0 ? conv.title : '未命名对话';
        parts.push(`# ${title}\n\n_${conv.updateAt}_\n\n`);
        const msgs: UIMessage[] = currentMessages(conv);
        for (const m of msgs) {
          const text: string = toText(m).trim();
          if (text.length === 0) continue;
          parts.push(`**${m.role}**:\n\n${text}\n\n`);
        }
        parts.push('---\n\n');
      } else {
        const msgs: UIMessage[] = currentMessages(conv);
        const entry: ExportEntry = {
          id: conv.id,
          title: conv.title,
          updatedAt: conv.updateAt,
          messages: msgs,
        };
        parts.push(JSON.stringify(entry, null, 2));
      }
    }
    return asMarkdown ? parts.join('') : `[\n${parts.join(',\n')}\n]`;
  }

  private exportSelected(asMarkdown: boolean): void {
    if (this.selectedIds.length === 0) return;
    this.buildExportText(asMarkdown).then((content: string): void => {
      const opts: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
      opts.newFileNames = [HistoryPage.exportFileName(asMarkdown ? 'md' : 'json')];
      const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker();
      docPicker.save(opts).then((uris: string[]): void => {
        if (uris.length === 0) return;
        const file = fileIo.openSync(uris[0], fileIo.OpenMode.WRITE_ONLY);
        fileIo.writeSync(file.fd, content);
        fileIo.closeSync(file);
        promptAction.showToast({ message: `已导出 ${this.selectedIds.length} 个会话`, duration: 1600 });
        this.exitSelecting();
      }).catch((): void => {
        promptAction.showToast({ message: '导出取消/失败', duration: 1400 });
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `导出失败:${e.message}`, duration: 1600 });
    });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  // 单日分组卡:组内会话行(CardRow,多选时标题带 ✓ 前缀)
  @Builder
  groupCard(g: HistoryGroup): void {
    Column() {
      ForEach(g.items, (s: ConversationSummary, index: number): void => {
        CardRow({
          label: `${this.selecting && this.selectedIds.indexOf(s.id) >= 0 ? '✓ ' : ''}${s.title.length > 0 ? s.title : '未命名对话'}`,
          valueText: this.timeOf(s),
          isLast: index === g.items.length - 1,
          onRowClick: (): void => {
            if (this.selecting) {
              this.toggleSelect(s.id);
              return;
            }
            const params: Record<string, string> = {};
            params['conversationId'] = s.id;
            router.pushUrl({ url: 'pages/ChatPage', params: params });
          },
        })
      }, (s: ConversationSummary): string => s.id)
    }
    .width('100%')
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({
        title: '历史记录',
        actionText: this.selecting ? '取消' : '选择',
        onAction: (): void => { this.toggleSelecting(); },
      })

      if (this.loaded && this.groups.length === 0) {
        Column() {
          Text('暂无历史')
            .fontSize(15).fontWeight(600).lineHeight(lineHeightForSize(15))
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK3)
          Text('对话记录会按日期归档在这里')
            .fontSize(13).lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.groups, (g: HistoryGroup): void => {
            ListItem() {
              Column() {
                PlainSection({ label: g.date })
                Column() {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.groupCard(g); } })
                }
                .width('100%').padding({ left: 16, right: 16 })
              }
              .width('100%')
            }
          }, (g: HistoryGroup): string => g.date)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }

      // 多选导出操作条
      if (this.selecting) {
        Row() {
          Text(`已选 ${this.selectedIds.length}`).fontSize(BODY2).fontColor(INK3)
          Blank()
          Text('导出 MD').fontSize(BODY2).fontColor(ACCENT)
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .onClick((): void => { this.exportSelected(true); })
          Text('导出 JSON').fontSize(BODY2).fontColor(ACCENT)
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .onClick((): void => { this.exportSelected(false); })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 8, bottom: 8 })
        .border({ width: { top: 0.5 }, color: { top: LINE } })
        .backgroundColor(SURFACE)
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 12 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      PageSpacer()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
