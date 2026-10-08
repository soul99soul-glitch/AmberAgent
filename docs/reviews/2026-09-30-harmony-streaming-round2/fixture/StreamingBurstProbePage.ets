// Verification-only: exercises the production message/Widget/Markdown chain.
import type { UIMessage } from '@amber/chat-domain';
import { AgentMessageRow } from '../components/AgentMessageRow.ets';
import { makeAssistantMessage, makeAssistant, makeConversation, currentMessages, toText, runChatTurn, createMemoryConversationStore, createOpenAIChatApi, asChatStreamProvider, makeProviderSettingOpenAI, makeTextGenerationParams } from '@amber/chat-domain';
import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
import type { AbortControllerLike } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { FrameCallback } from '@kit.ArkUI';
import { NativeTimelineFollow } from '../components/NativeTimelineFollow.ets';

class ProbeFrame extends FrameCallback {
  private callback: (t: number) => void;
  constructor(callback: (t: number) => void) { super(); this.callback = callback; }
  onFrame(t: number): void { this.callback(t / 1000000); }
}

@Entry
@Component
struct StreamingBurstProbePage {
  @State messages: UIMessage[] = [];
  @State committed: UIMessage[] = [];
  @State source: string = '';
  @State status: string = 'ready';
  private timer: number = -1;
  private tick: number = 0;
  private abort: AbortControllerLike | null = null;
  private started: number = 0;
  private frameEpoch: number = 0;
  private frameTimes: number[] = [];
  private lastFrame: number = 0;
  private scroller: Scroller = new Scroller();
  private viewport: number = 0;
  private follow: NativeTimelineFollow | null = null;
  @State following: boolean = true;
  private sampleFrame(epoch: number, t: number): void {
    if (epoch !== this.frameEpoch) return;
    if (this.lastFrame > 0) this.frameTimes.push(t - this.lastFrame);
    this.lastFrame = t;
    this.getUIContext().postFrameCallback(new ProbeFrame((time: number): void => { this.sampleFrame(epoch, time); }));
  }
  private grow(): void {
    if (this.follow === null) this.follow = new NativeTimelineFollow(this.getUIContext(), this.scroller,
      (): boolean => this.following,
      (): number | null => {
        const offset: number = this.scroller.currentOffset().yOffset;
        if (this.scroller.isAtEnd()) return offset;
        const rect: RectResult = this.scroller.getItemRect(0);
        return offset + Math.max(0, rect.y + rect.height + 16 - this.viewport);
      }, (): void => {});
    this.follow.request();
  }
  private async network(path: string): Promise<void> {
    this.abort?.abort();
    this.abort = createEntryAbortController();
    this.messages = []; this.committed = []; this.tick = 0; this.source = '';
    this.status = '网络请求中'; this.started = Date.now(); this.following = true;
    const epoch: number = ++this.frameEpoch; this.frameTimes = []; this.lastFrame = 0;
    this.getUIContext().postFrameCallback(new ProbeFrame((time: number): void => { this.sampleFrame(epoch, time); }));
    const api = createOpenAIChatApi({ http: createRcpHttpClient(), setting: makeProviderSettingOpenAI({ baseUrl: 'http://127.0.0.1:18765', chatCompletionsPath: path }) });
    try {
      const result = await runChatTurn(makeConversation('probe'), '流式测试', {
        assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [], store: createMemoryConversationStore(),
        provider: asChatStreamProvider(api, () => makeTextGenerationParams({})), abortSignal: this.abort.signal,
        onUpdate: (messages: UIMessage[]): void => {
          this.messages = messages.filter((m: UIMessage): boolean => m.role === 'assistant');
          this.source = this.messages.map((m: UIMessage): string => toText(m)).join(''); this.tick++;
          this.status = `网络流式 ${this.tick} / ${this.source.length}`;
          hilog.info(1, 'Amber.StreamProbe', '%{public}s', JSON.stringify({ phase: 'live', ms: Date.now() - this.started, publications: this.tick, chars: this.source.length }));
        },
      });
      this.committed = currentMessages(result).filter((m: UIMessage): boolean => m.role === 'assistant'); this.messages = [];
      this.status = `完成 ${this.tick} / ${this.source.length}`;
      this.frameEpoch++;
      const sorted: number[] = this.frameTimes.slice().sort((a: number, b: number): number => a - b);
      this.status += ` · p95=${Math.round(sorted[Math.floor(sorted.length * 0.95)] * 10) / 10}ms max=${Math.round(sorted[sorted.length - 1])}ms >32=${sorted.filter((dt: number): boolean => dt > 32).length}/${sorted.length}`;
      hilog.info(1, 'Amber.StreamProbe', '%{public}s', JSON.stringify({ phase: 'frames', count: sorted.length,
        p95: sorted[Math.floor(sorted.length * 0.95)], max: sorted[sorted.length - 1],
        over32: sorted.filter((dt: number): boolean => dt > 32).length }));
      hilog.info(1, 'Amber.StreamProbe', '%{public}s', JSON.stringify({ phase: 'done', ms: Date.now() - this.started, publications: this.tick, chars: this.source.length }));
    } catch (e) { this.frameEpoch++; this.status = String(e); }
  }
  private next(): void {
    this.tick++;
    this.source += `增量${this.tick}号，文字应该正在持续显示。`;
    const message: UIMessage = makeAssistantMessage(this.source);
    message.id = 'burst-probe';
    this.messages = [message];
    this.status = `live ${this.tick} / ${this.source.length}`;
  }
  private start(): void {
    this.source = ''; this.committed = []; this.tick = 0;
    if (this.timer !== -1) clearInterval(this.timer);
    this.next();
    this.timer = setInterval((): void => {
      this.next();
      if (this.tick >= 20) { clearInterval(this.timer); this.timer = -1; }
    }, 250);
  }
  private burst(): void {
    if (this.timer !== -1) { clearInterval(this.timer); this.timer = -1; }
    this.source += '\n\n' + '突然到达的数据包含完整的大段文字。'.repeat(40) + '突发尾标记。';
    const message: UIMessage = makeAssistantMessage(this.source); message.id = 'burst-probe';
    this.messages = [message]; this.status = `burst / ${this.source.length}`;
  }
  private finish(): void {
    if (this.timer !== -1) { clearInterval(this.timer); this.timer = -1; }
    const message: UIMessage = makeAssistantMessage(this.source + '\n\n最终完整标记。');
    message.id = 'burst-probe';
    this.committed = [message]; this.messages = []; this.status = 'committed';
  }
  aboutToDisappear(): void { this.frameEpoch++; this.follow?.cancel(); this.abort?.abort(); if (this.timer !== -1) clearInterval(this.timer); }
  build(): void {
    Column() {
      Text('实际消息组件突发回放').fontSize(18).margin({ top: 45 })
      Row({ space: 8 }) {
        Button('连续').onClick((): void => { this.start(); })
        Button('大包').onClick((): void => { this.burst(); })
        Button('完成').onClick((): void => { this.finish(); })
      }.padding(10)
      Row({ space: 8 }) {
        Button('SSE网络').onClick((): void => { this.network('/sse'); })
        Button(this.following ? '暂停' : '回底').onClick((): void => { this.following = !this.following; if (this.following) { this.scroller.scrollEdge(Edge.Bottom); this.grow(); } else this.follow?.cancel(); })
        Button('错误类型网络').onClick((): void => { this.network('/plain'); })
      }.padding(8)
      Text(this.status).fontSize(16)
      List({ scroller: this.scroller }) {
        ForEach(this.committed, (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({ message: this.committed[index], streaming: false,
              animateEntrance: false, enableLatexRendering: false, onTextGrow: (): void => { this.grow(); },
              hostHeader: (): void => {}, hostFooter: (): void => {} })
          }
        }, (message: UIMessage): string => `committed-${message.id}`)
        ForEach(this.messages, (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({ message: this.messages[index], streaming: true,
              animateEntrance: false, enableLatexRendering: false, onTextGrow: (): void => { this.grow(); },
              hostHeader: (): void => {}, hostFooter: (): void => {} })
          }
        }, (message: UIMessage): string => `tail-${message.id}`)
      }.layoutWeight(1).width('100%').padding(16)
      .onAreaChange((_a: Area, b: Area): void => { this.viewport = Number(b.height); })
      .onTouch((event: TouchEvent): void => { if (event.type === TouchType.Down) { this.following = false; this.follow?.cancel(); } })
    }.width('100%').height('100%').backgroundColor('#F1EADB')
  }
}
