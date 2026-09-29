// ChatListGrouped — 分组会话列表(G5-S2)
// Android 基准: ConversationList.kt:78-171 + ChatDrawerVM.kt:29-108
//   顺序: PinnedHeader + 置顶项 → DateHeader(今天/昨天/本周/更早) + 普通项
//   插入规则(insertSeparators): 首条或置顶→非置顶边界插 DateHeader;
//     同日不重复插;跨日插
//   选中态: accentSoft(ACCENT_LIGHT) 背景 + accent(ACCENT) 文字
//   运行中: ConversationRunningIndicator(脉动点,见 ChatListIndicators)
//   长按: DropdownMenu(置顶/重新生成标题/删除)
import type { ConversationSummary } from '@amber/chat-domain';
import { BG, SURFACE, INK, INK2, INK3, LINE, ACCENT, ACCENT_LIGHT, BODY, META, LABEL } from '../design/tokens.ets';
import { RunningIndicator, PinnedIndicator, UnreadBadge } from './ChatListIndicators.ets';

// 分组行类型(避免 union;用 kind 区分)
//   kind 0 = DateHeader(label),kind 1 = PinnedHeader,kind 2 = Item(conv)
interface GroupedRow {
  kind: number;          // 0=DateHeader,1=PinnedHeader,2=Item
  key: string;           // ForEach key
  label: string;         // DateHeader / PinnedHeader 文案(Item 空串)
  conv: ConversationSummary | null;  // Item 的会话(DateHeader/Pinned 为 null)
}

// 日期桶标签(ChatDrawerVM.kt:99-107 getDateLabel)
//   today → 今天;yesterday → 昨天;本周 → 本周;更早 → 更早
function dateBucketLabel(iso: string): string {
  const ts: number = Date.parse(iso);
  if (Number.isNaN(ts)) return '更早';
  const d: Date = new Date(ts);
  const now: Date = new Date();
  // 当地日期(截到日)
  const today: Date = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const target: Date = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const dayDiff: number = Math.round((today.getTime() - target.getTime()) / 86400000);
  if (dayDiff <= 0) return '今天';
  if (dayDiff === 1) return '昨天';
  if (dayDiff <= 6) return '本周';
  return '更早';
}

@Component
export struct ChatListGrouped {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onConversationsChange') conversations: ConversationSummary[] = [];
  @Prop selectedId: string = '';
  @Prop runningIds: string[] = [];
  onOpenConversation: (id: string) => void = (_id: string): void => {};
  onDelete: (item: ConversationSummary) => void = (_i: ConversationSummary): void => {};
  onPin: (item: ConversationSummary) => void = (_i: ConversationSummary): void => {};
  onRegenerateTitle: (item: ConversationSummary) => void = (_i: ConversationSummary): void => {};
  @State rows: GroupedRow[] = [];

  // conversations 变化 → 重算分组(ChatDrawerVM insertSeparators 语义)
  // 置顶项在前(各自成 Item),插一个 PinnedHeader;
  // 非置顶项按 dateBucketLabel 分组,每组前插 DateHeader
  private rebuild(): void {
    const result: GroupedRow[] = [];
    const pinned: ConversationSummary[] = [];
    const others: ConversationSummary[] = [];
    for (const c of this.conversations) {
      if (c.isPinned) {
        pinned.push(c);
      } else {
        others.push(c);
      }
    }
    // 置顶区(有置顶项才插 header)
    if (pinned.length > 0) {
      result.push({ kind: 1, key: 'pinned_header', label: '置顶', conv: null });
      for (const c of pinned) {
        result.push({ kind: 2, key: `item_${c.id}`, label: '', conv: c });
      }
    }
    // 普通区:按日期桶顺序(今天/昨天/本周/更早)分组
    // others 假定已按 updateAt 倒序(repo.list 语义);逐条与上一条比较日期标签
    let lastLabel: string = '';
    for (const c of others) {
      const lbl: string = dateBucketLabel(c.updateAt);
      if (lbl !== lastLabel) {
        result.push({ kind: 0, key: `date_${lbl}`, label: lbl, conv: null });
        lastLabel = lbl;
      }
      result.push({ kind: 2, key: `item_${c.id}`, label: '', conv: c });
    }
    this.rows = result;
  }

  aboutToAppear(): void {
    this.rebuild();
  }

  // @Prop conversations 变化 → 触发 @Watch → rebuild 分组行
  private onConversationsChange(): void {
    this.rebuild();
  }

  private isSelected(id: string): boolean {
    return id === this.selectedId;
  }

  private isRunning(id: string): boolean {
    return this.runningIds.indexOf(id) >= 0;
  }

  @Builder
  DateHeaderRow(label: string): void {
    Row() {
      Text(label)
        .fontSize(LABEL)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK3)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 11, bottom: 11 })
  }

  @Builder
  PinnedHeaderRow(): void {
    Row() {
      PinnedIndicator({ tint: INK3 })
      Text('置顶')
        .fontSize(LABEL)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK3)
        .margin({ left: 10 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 11, bottom: 11 })
  }

  @Builder
  ConversationRow(conv: ConversationSummary): void {
    Row() {
      Text(conv.title.length > 0 ? conv.title : '未命名对话')
        .fontSize(BODY)
        .fontColor(this.isSelected(conv.id) ? ACCENT : INK)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
      if (conv.isPinned) {
        PinnedIndicator({ tint: INK3 }).margin({ left: 8 })
      }
      if (this.isRunning(conv.id)) {
        RunningIndicator().margin({ left: 8 })
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(this.isSelected(conv.id) ? ACCENT_LIGHT : SURFACE)
    .borderRadius(8)
    .onClick((): void => { this.onOpenConversation(conv.id); })
    .bindContextMenu(this.ContextMenu(conv), ResponseType.LongPress)
  }

  @Builder
  ContextMenu(conv: ConversationSummary): void {
    Menu() {
      MenuItem({ content: conv.isPinned ? '取消置顶' : '置顶' })
        .onClick((): void => { this.onPin(conv); })
      MenuItem({ content: '重新生成标题' })
        .onClick((): void => { this.onRegenerateTitle(conv); })
      MenuItem({ content: '删除' })
        .onClick((): void => { this.onDelete(conv); })
    }
  }

  build(): void {
    List({ space: 4 }) {
      ForEach(this.rows, (row: GroupedRow): void => {
        ListItem() {
          if (row.kind === 0) {
            this.DateHeaderRow(row.label)
          } else if (row.kind === 1) {
            this.PinnedHeaderRow()
          } else {
            if (row.conv !== null) {
              this.ConversationRow(row.conv)
            }
          }
        }
      }, (row: GroupedRow): string => row.key)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
    .divider({ strokeWidth: 1, color: LINE, startMargin: 16, endMargin: 16 })
  }
}
