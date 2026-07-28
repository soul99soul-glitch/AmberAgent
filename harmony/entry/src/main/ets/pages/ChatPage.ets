// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ memory 仓库(D-019 登记)
import { router } from '@kit.ArkUI';
import type { Conversation, UIMessage, UIMessagePart } from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, runChatTurn,
  createTemplateTransformer, thinkTagTransformer, regexOutputTransformer,
  asChatStreamProvider, seedConversationWithPresets,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, ChatRuntime,
} from '../di/AppContainer.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

const partKey = (p: UIMessagePart, i: number): string => `${p.type}-${i}`;

@Component
struct MessageRow {
  @Prop message: UIMessage;

  build() {
    Column() {
      Text(this.message.role === 'user' ? '我' : '助手')
        .fontSize(11).fontColor(INK3).margin({ bottom: 4 })
      ForEach(this.message.parts, (p: UIMessagePart, i: number): void => {
        if (p.type === 'text') {
          Text(p.text)
            .fontSize(15).fontColor(INK)
            .padding(10)
            .backgroundColor(this.message.role === 'user' ? SURFACE2 : SURFACE)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (p.type === 'reasoning') {
          Text(p.reasoning)
            .fontSize(12).fontColor(INK2)
            .padding(8)
            .backgroundColor(SURFACE2)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (p.type === 'tool') {
          Text(`工具:${p.toolName}`)
            .fontSize(11).fontColor(ACCENT)
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor(SURFACE2)
            .borderRadius(6)
            .margin({ bottom: 4 })
        }
      }, partKey)
    }
    .width('100%')
    .alignItems(this.message.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
  }
}

@Entry
@Component
struct ChatPage {
  @State messages: UIMessage[] = [];
  @State inputText: string = '';
  @State sending: boolean = false;
  @State errorMsg: string = '';
  @State title: string = '新对话';
  private conversation: Conversation | null = null;
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    if (convId.length > 0) {
      getChatRepository().getById(convId).then((conv: Conversation | null): void => {
        if (conv !== null) {
          this.conversation = conv;
          this.title = conv.title.length > 0 ? conv.title : '对话';
          this.messages = currentMessages(conv);
        } else {
          this.errorMsg = '会话不存在';
        }
      });
    } else {
      getChatAssistants().then((assistants): void => {
        const fresh = makeConversation(newId(), []);
        this.conversation = seedConversationWithPresets(fresh, assistants[0]);
        this.messages = currentMessages(this.conversation);
      });
    }
  }

  private scrollToBottom(): void {
    this.scroller.scrollEdge(Edge.Bottom);
  }

  private send(): void {
    const text: string = this.inputText.trim();
    if (text.length === 0 || this.sending || this.conversation === null) return;
    this.sending = true;
    this.errorMsg = '';
    const conv: Conversation = this.conversation;
    resolveChatRuntime()
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型(providers 未持久化或无可用 auth)'));
        }
        return runChatTurn(conv, text, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          onUpdate: (msgs: UIMessage[]): void => {
            this.messages = msgs;
          },
        });
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.messages = currentMessages(out);
        this.inputText = '';
        this.sending = false;
        this.scrollToBottom();
      })
      .catch((e: Error): void => {
        this.errorMsg = e.message;
        this.sending = false;
        // user 消息快照已持久化(chat_turn 语义),刷新显示
        getChatRepository().getById(conv.id).then((saved: Conversation | null): void => {
          if (saved !== null) {
            this.conversation = saved;
            this.messages = currentMessages(saved);
          }
        });
      });
  }

  build() {
    Column() {
      Text(this.title)
        .fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK)
        .padding({ top: 16, bottom: 8 })

      List({ scroller: this.scroller }) {
        ForEach(this.messages, (m: UIMessage): void => {
          ListItem() {
            MessageRow({ message: m })
          }
        }, (m: UIMessage): string => m.id)
        if (this.sending) {
          ListItem() {
            Text('生成中…').fontSize(12).fontColor(INK3)
              .padding({ left: 16, top: 4, bottom: 4 })
          }
        }
      }
      .width('100%').layoutWeight(1)
      .divider({ strokeWidth: 1, color: LINE })

      if (this.errorMsg.length > 0) {
        Text(this.errorMsg)
          .fontSize(12).fontColor('#B33A3A')
          .padding({ left: 16, right: 16, top: 4, bottom: 4 })
      }

      Row() {
        TextInput({ text: this.inputText, placeholder: '输入消息…' })
          .layoutWeight(1)
          .fontSize(15)
          .padding(10)
          .backgroundColor(SURFACE)
          .borderRadius(8)
          .onChange((v: string): void => { this.inputText = v; })
        Text(this.sending ? '…' : '发送')
          .fontSize(15)
          .fontColor(this.sending ? INK3 : ACCENT)
          .padding({ left: 14, right: 6 })
          .onClick((): void => this.send())
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 12 })
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
