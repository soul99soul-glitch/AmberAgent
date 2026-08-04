// Index — 首页(对齐 Amber Redesign HomeScreen)
//
// 设计来源: aa-chat.jsx HomeScreen
// 结构: ChatHeader(无 meter) + 居中 hero(amber wordmark + 问候 + 副标题 + 建议芯片) + InputBar
// 当有历史会话时: ChatHeader + 会话列表(SessionRow) + InputBar
//
// 导航:
//   左上 history icon → ChatListPage(完整会话列表)
//   右上 edit icon → 新对话
//   芯片点击 → 填入输入框
//   输入框回车 → ChatPage

import { router } from '@kit.ArkUI';
import type { ConversationSummary } from '@amber/chat-domain';
import { getChatRepository } from '../di/AppContainer.ets';
import { SearchField } from '../components/ui/SearchField.ets';
import { SectionHeader, PageSpacer, SessionRowItem } from '../components/PageTemplate.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';

// 问候语(根据时间)
const greetingMessage = (): string => {
  const hour: number = new Date().getHours();
  if (hour < 6) return '夜深了，光';
  if (hour < 12) return '早上好，光';
  if (hour < 18) return '下午好，光';
  return '晚上好，光';
};

// 建议芯片
const SUGGESTIONS: string[] = [
  '陪我把今天捋一捋',
  '解释一个概念',
  '帮我润色这段话',
  '随便聊聊',
];

@Entry
@Component
struct Index {
  @State inputText: string = '';
  @State recentItems: ConversationSummary[] = [];
  @State loaded: boolean = false;
  @State cursorVisible: boolean = true;
  private cursorTimer: number = -1;

  aboutToAppear(): void {
    this.loadRecent();
    this.startCursorBlink();
  }

  aboutToDisappear(): void {
    if (this.cursorTimer >= 0) {
      clearInterval(this.cursorTimer);
      this.cursorTimer = -1;
    }
  }

  private startCursorBlink(): void {
    this.cursorTimer = setInterval((): void => {
      this.cursorVisible = !this.cursorVisible;
    }, 525);
  }

  private loadRecent(): void {
    const repo = getChatRepository();
    repo.list().then((list: ConversationSummary[]): void => {
      // 取最近 5 条非置顶
      this.recentItems = list.slice(0, 5);
      this.loaded = true;
    });
  }

  private send(): void {
    if (this.inputText.trim().length === 0) return;
    const params: Record<string, string> = {};
    params['initialText'] = this.inputText.trim();
    router.replaceUrl({ url: 'pages/ChatPage', params: params });
  }

  private newConversation(): void {
    router.replaceUrl({ url: 'pages/ChatPage' });
  }

  private openConversation(id: string): void {
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    router.replaceUrl({ url: 'pages/ChatPage', params: params });
  }

  private formatTime(updateAt: string): string {
    if (updateAt.length >= 16) return updateAt.substring(11, 16);
    if (updateAt.length >= 10) return updateAt.substring(5, 10);
    return updateAt;
  }

  build(): void {
    Column() {
      // ── ChatHeader(两行: 标题 + 模型行) ──
      Row() {
        // 左: history icon
        Text('☰')
          .fontSize(22)
          .fontColor(INK2)
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatListPage' }); })

        // 中: 标题 + 模型(可点击 → 模型选择)
        Column() {
          Text('新会话')
            .fontSize(HEADER)
            .fontWeight(FontWeight.Bold)
            .fontColor(INK)
          Row() {
            Text('deepseek-v4-flash')
              .fontSize(META)
              .fontColor(INK3)
              .fontFamily('monospace')
            Text('▾')
              .fontSize(11)
              .fontColor(INK4)
              .margin({ left: 4 })
          }
          .margin({ top: 1 })
        }
        .alignItems(HorizontalAlign.Center)
        .layoutWeight(1)
        .margin({ left: 10 })

        // 右: edit icon
        Text('✎')
          .fontSize(21)
          .fontColor(INK2)
          .onClick((): void => { this.newConversation(); })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      if (this.recentItems.length === 0 && this.loaded) {
        // ── 空状态: 居中 hero ──
        Column() {
          // amber wordmark(等宽 + 闪烁光标)
          Row() {
            Text('amber')
              .fontSize(30)
              .fontWeight(FontWeight.Bold)
              .fontColor(INK)
              .fontFamily('monospace')
            Text('')
              .width(8)
              .height(24)
              .backgroundColor(ACCENT)
              .borderRadius(1)
              .margin({ left: 2 })
              .opacity(this.cursorVisible ? 1 : 0)
          }

          // 问候语
          Text(greetingMessage())
            .fontSize(21)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .margin({ top: 18 })

          // 副标题
          Text('今天想聊点什么？或者从下面挑一个开始。')
            .fontSize(BODY2)
            .fontColor(INK3)
            .textAlign(TextAlign.Center)
            .margin({ top: 6 })
            .maxLines(2)

          // 建议芯片
          Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Center }) {
            ForEach(SUGGESTIONS, (s: string, idx: number): void => {
              Text(s)
                .fontSize(BODY2)
                .fontColor(INK2)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .borderRadius(999)
                .border({ width: 1, color: LINE2 })
                .backgroundColor(SURFACE)
                .margin({ right: 9, top: 9 })
                .onClick((): void => {
                  this.inputText = s;
                })
            }, (s: string, idx: number): string => `chip-${idx}`)
          }
          .width('100%')
          .margin({ top: 6 })
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .padding({ left: 30, right: 30 })
      } else {
        // ── 有会话: 最近列表 ──
        Scroll() {
          Column() {
            SectionHeader({ label: '最近' })
            ForEach(this.recentItems, (item: ConversationSummary): void => {
              SessionRowItem({
                title: item.title.length > 0 ? item.title : '未命名对话',
                snippet: '',
                modelId: '',
                timeText: this.formatTime(item.updateAt),
                pinned: item.isPinned,
                onRowClick: (): void => { this.openConversation(item.id); },
              })
            }, (item: ConversationSummary): string => `home-${item.id}`)

            // 查看全部
            Row() {
              Text('查看全部对话')
                .fontSize(BODY2)
                .fontColor(ACCENT)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .onClick((): void => { router.pushUrl({ url: 'pages/ChatListPage' }); })

            PageSpacer()
          }
          .width('100%')
          .padding({ left: 12, right: 12 })
        }
        .layoutWeight(1)
        .backgroundColor(BG)
      }

      // ── InputBar(沉浸式底部) ──
      Row() {
        // 输入药丸
        TextInput({ text: this.inputText, placeholder: '输入消息…' })
          .fontSize(15.5)
          .fontColor(INK)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2)
          .borderRadius(26)
          .border({ width: 1, color: LINE })
          .padding({ left: 18, right: 18, top: 14, bottom: 14 })
          .layoutWeight(1)
          .onChange((v: string): void => { this.inputText = v; })
          .onSubmit((): void => { this.send(); })

        // 发送按钮(有文本时 accent, 无文本时灰)
        Text('↑')
          .fontSize(21)
          .fontColor(this.inputText.trim().length > 0 ? ACCENT_INK : INK4)
          .width(48)
          .height(48)
          .borderRadius(999)
          .backgroundColor(this.inputText.trim().length > 0 ? ACCENT : SURFACE2)
          .border({ width: 1, color: this.inputText.trim().length > 0 ? ACCENT : LINE })
          .textAlign(TextAlign.Center)
          .margin({ left: 9 })
          .onClick((): void => { this.send(); })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 14 })
      .backgroundColor(SURFACE)
      .border({ width: { top: 1 }, color: { top: LINE } })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}
