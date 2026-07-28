// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ RDB 仓库(D-021)
// D-024:assistant 分支 — ◀ n/m ▶ 切换(selectMessageBranch) + 末条「重新生成」
//   (runRegenerateAt);regenerate 流式预览裁 MVP(spinner 代替,已登记)
import { router } from '@kit.ArkUI';
import type { Conversation, UIMessage, UIMessagePart } from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, runChatTurn,
  createTemplateTransformer, thinkTagTransformer, regexOutputTransformer,
  asChatStreamProvider, seedConversationWithPresets,
  nodeCurrentMessage, nodeRole, selectMessageBranch, runRegenerateAt,
  runAutoTitle, makeUserMessage, toText, makeTextGenerationParams,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, describeChatModel, ChatRuntime,
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

// 节点视图模型:分支切换/重新生成需要节点级信息
interface MsgRow {
  nodeId: string;
  message: UIMessage;
  branchCount: number;
  selectIndex: number;
  isAssistant: boolean;
  isLastAssistant: boolean;
}

const buildRows = (conv: Conversation): MsgRow[] => {
  let lastAssistantIdx: number = -1;
  for (let i = conv.messageNodes.length - 1; i >= 0; i--) {
    if (nodeRole(conv.messageNodes[i]) === 'assistant') {
      lastAssistantIdx = i;
      break;
    }
  }
  return conv.messageNodes.map((n, i: number): MsgRow => ({
    nodeId: n.id,
    message: nodeCurrentMessage(n),
    branchCount: n.messages.length,
    selectIndex: n.selectIndex,
    isAssistant: nodeRole(n) === 'assistant',
    isLastAssistant: i === lastAssistantIdx,
  }));
};

@Component
struct MessageRow {
  @Prop message: UIMessage;
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  @Prop isAssistant: boolean = false;
  @Prop isLastAssistant: boolean = false;
  onSwitch: (idx: number) => void = (_idx: number): void => {};
  onRegenerate: () => void = (): void => {};

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
      if (this.isAssistant && (this.branchCount > 1 || this.isLastAssistant)) {
        Row() {
          if (this.branchCount > 1) {
            Text('◀').fontSize(11).fontColor(this.selectIndex > 0 ? ACCENT : INK3)
              .onClick((): void => {
                if (this.selectIndex > 0) this.onSwitch(this.selectIndex - 1);
              })
            Text(`${this.selectIndex + 1}/${this.branchCount}`)
              .fontSize(10).fontColor(INK3).margin({ left: 6, right: 6 })
            Text('▶').fontSize(11)
              .fontColor(this.selectIndex < this.branchCount - 1 ? ACCENT : INK3)
              .margin({ right: 12 })
              .onClick((): void => {
                if (this.selectIndex < this.branchCount - 1) this.onSwitch(this.selectIndex + 1);
              })
          }
          if (this.isLastAssistant) {
            Text('重新生成').fontSize(11).fontColor(ACCENT)
              .onClick((): void => this.onRegenerate())
          }
        }
        .margin({ top: 2 })
      }
    }
    .width('100%')
    .alignItems(this.message.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
  }
}

// holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制 + arkts-no-obj-literals-as-types)
interface RuntimeHolder {
  rt: ChatRuntime | null;
}

@Entry
@Component
struct ChatPage {
  @State rows: MsgRow[] = [];
  @State streamingTail: UIMessage[] = [];
  @State inputText: string = '';
  @State sending: boolean = false;
  @State errorMsg: string = '';
  @State title: string = '新对话';
  @State modelLabel: string = '';
  private conversation: Conversation | null = null;
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    // 每次进入(含从选择器返回)刷新模型标签
    describeChatModel().then((label: string): void => { this.modelLabel = label; });
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    if (convId.length > 0) {
      getChatRepository().getById(convId).then((conv: Conversation | null): void => {
        if (conv !== null) {
          this.conversation = conv;
          this.title = conv.title.length > 0 ? conv.title : '对话';
          this.rows = buildRows(conv);
        } else {
          this.errorMsg = '会话不存在';
        }
      });
    } else {
      getChatAssistants().then((assistants): void => {
        const fresh = makeConversation(newId(), []);
        this.conversation = seedConversationWithPresets(fresh, assistants[0]);
        this.rows = buildRows(this.conversation);
      });
    }
  }

  private scrollToBottom(): void {
    this.scroller.scrollEdge(Edge.Bottom);
  }

  private switchBranch(nodeId: string, idx: number): void {
    if (this.conversation === null || this.sending) return;
    const updated: Conversation = selectMessageBranch(this.conversation, nodeId, idx);
    this.conversation = updated;
    this.rows = buildRows(updated);
    getChatRepository().save(updated);
  }

  private regenerate(nodeId: string): void {
    if (this.conversation === null || this.sending) return;
    this.sending = true;
    this.errorMsg = '';
    const conv: Conversation = this.conversation;
    resolveChatRuntime()
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型'));
        }
        return runRegenerateAt(conv, nodeId, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
        });
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.sending = false;
        this.scrollToBottom();
      })
      .catch((e: Error): void => {
        this.errorMsg = e.message;
        this.sending = false;
      });
  }

  // 自动命名(D-025):title 空白时非阻塞调用(对齐 Android 独立 launch);
  // 失败上浮到 errorMsg(非静默,Android addError 同语义)
  private maybeAutoTitle(conv: Conversation, rt: ChatRuntime): void {
    if (conv.title.trim().length > 0) return;
    runAutoTitle(conv, {
      generateText: (prompt: string): Promise<string> =>
        rt.api.generateText(
          [makeUserMessage(prompt)],
          // reasoning OFF(对齐 Android AiAuxiliaryGenerator);arkts-no-spread 禁用接口展开
          makeTextGenerationParams({
            model: rt.params.model,
            temperature: rt.params.temperature,
            topP: rt.params.topP,
            maxTokens: rt.params.maxTokens,
            tools: rt.params.tools,
            reasoningLevel: 'off',
            customBody: rt.params.customBody,
          }),
        ).then((chunk): string => {
          const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
          return msg !== null ? toText(msg) : '';
        }),
      store: getChatRepository(),
      locale: '简体中文',
    })
      .then((titled: Conversation): void => {
        if (this.conversation !== null && this.conversation.id === titled.id) {
          this.conversation = titled;
          this.title = titled.title.length > 0 ? titled.title : this.title;
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = `自动命名失败:${e.message}`;
      });
  }

  private send(): void {
    const text: string = this.inputText.trim();
    if (text.length === 0 || this.sending || this.conversation === null) return;
    this.sending = true;
    this.errorMsg = '';
    const conv: Conversation = this.conversation;
    // 流式尾部起点 = 现有消息 + 即将入列的 user 消息
    const tailStart: number = currentMessages(conv).length + 1;
    // holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制)
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime()
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
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
            this.streamingTail = msgs.slice(tailStart);
          },
        });
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.streamingTail = [];
        this.inputText = '';
        this.sending = false;
        this.scrollToBottom();
        // 自动命名(非阻塞,对齐 Android 独立 launch)
        if (holder.rt !== null) {
          this.maybeAutoTitle(out, holder.rt);
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = e.message;
        this.sending = false;
        this.streamingTail = [];
        // user 消息快照已持久化(chat_turn 语义),刷新显示
        getChatRepository().getById(conv.id).then((saved: Conversation | null): void => {
          if (saved !== null) {
            this.conversation = saved;
            this.rows = buildRows(saved);
          }
        });
      });
  }

  build() {
    Column() {
      Column() {
        Text(this.title)
          .fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(this.modelLabel.length > 0 ? this.modelLabel : '未配置模型 — 点我选择')
          .fontSize(11)
          .fontColor(this.modelLabel.length > 0 ? INK3 : ACCENT)
          .margin({ top: 2 })
      }
      .padding({ top: 16, bottom: 8 })
      .onClick((): void => { router.pushUrl({ url: 'pages/ChatModelPickerPage' }); })

      List({ scroller: this.scroller }) {
        ForEach(this.rows, (row: MsgRow): void => {
          ListItem() {
            MessageRow({
              message: row.message,
              branchCount: row.branchCount,
              selectIndex: row.selectIndex,
              isAssistant: row.isAssistant,
              isLastAssistant: row.isLastAssistant,
              onSwitch: (idx: number): void => this.switchBranch(row.nodeId, idx),
              onRegenerate: (): void => this.regenerate(row.nodeId),
            })
          }
        }, (row: MsgRow): string => row.nodeId)
        ForEach(this.streamingTail, (m: UIMessage): void => {
          ListItem() {
            MessageRow({ message: m })
          }
        }, (m: UIMessage): string => `tail-${m.id}`)
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
