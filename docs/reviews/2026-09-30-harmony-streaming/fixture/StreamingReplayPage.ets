// Verification-only page, copied into entry only for the simulator replay build.
import { MarkdownText } from '../components/MarkdownText.ets';
import { InteractiveMessageTimeline } from '../components/InteractiveMessageTimeline.ets';
import { NativeTimelineFollow } from '../components/NativeTimelineFollow.ets';
import { hilog } from '@kit.PerformanceAnalysisKit';

@Entry
@Component
struct StreamingReplayPage {
  @State text: string = '';
  @State streaming: boolean = true;
  @State following: boolean = true;
  private scroller: Scroller = new Scroller();
  private driver: NativeTimelineFollow | null = null;
  private viewport: number = 0;
  private timer: number = -1;
  private source: string = '';
  private started: number = 0;
  private count: number = 0;
  private lastLog: number = 0;
  private alive: boolean = true;

  aboutToAppear(): void {
    this.source = '# 流式回放\n\n';
    for (let i: number = 1; i <= 18; i++) {
      this.source += `## 第 ${i} 段\n\n` + '这是一段持续增长的中文正文。文字保持在同一个原生段落中，新行出现后底部滚动逐帧追随，段落不应反复跳变。'.repeat(3) + '\n\n';
      if (i % 4 === 0) this.source += '- 列表第一项\n- 列表第二项继续追加\n\n```typescript\nconst frame = 48;\nconst target = "原生滚动";\n```\n\n| 阶段 | 验证 |\n| --- | --- |\n| 流式 | 持续增长 |\n| 完成 | 全文保留 |\n\n';
    }
    this.source += '最终标记：全文完成，没有丢字。';
    this.started = Date.now();
    this.timer = setInterval((): void => {
      this.text = this.source.substring(0, Math.min(this.source.length, this.text.length + 8));
      if (this.text.length === this.source.length) {
        this.streaming = false;
        clearInterval(this.timer); this.timer = -1;
        setTimeout((): void => { this.log('terminal'); }, 1000);
      }
    }, 48);
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.timer !== -1) clearInterval(this.timer);
    this.driver?.cancel();
  }

  private log(stage: string): void {
    const status: string = JSON.stringify({ stage: stage, ms: Date.now() - this.started,
      chars: this.text.length, offset: this.scroller.currentOffset().yOffset,
      atEnd: this.scroller.isAtEnd(), following: this.following, frames: this.count });
    hilog.info(1, 'Amber.StreamReplay', '%{public}s', status);
  }

  private grow(): void {
    if (this.driver === null) {
      this.driver = new NativeTimelineFollow(this.getUIContext(), this.scroller,
        (): boolean => this.alive && this.following,
        (): number | null => {
          const offset: number = this.scroller.currentOffset().yOffset;
          if (this.scroller.isAtEnd()) return offset;
          const rect: RectResult = this.scroller.getItemRect(0);
          return offset + Math.max(0, rect.y + rect.height + 28 - this.viewport);
        },
        (): void => {
          this.count++;
          if (Date.now() - this.lastLog > 10) { this.lastLog = Date.now(); this.log('frame'); }
        });
    }
    this.driver.request();
  }

  @Builder
  Rows(): void {
    ListItem() {
      MarkdownText({ content: this.text, streaming: this.streaming, streamKey: 'qa-replay',
        enableLatexRendering: false, onVisibleGrow: (): void => { this.grow(); } })
    }
  }

  @Builder
  Empty(): void {}

  build(): void {
    Column() {
      Row() {
        Text('48ms 原生流式回放').fontSize(18).layoutWeight(1)
        Button(this.following ? '暂停' : '回底').onClick((): void => {
          this.following = !this.following;
          if (this.following) { this.scroller.scrollEdge(Edge.Bottom); this.grow(); }
          else this.driver?.cancel();
          this.log('toggle');
        })
      }.padding(16).margin({ top: 40 }).width('100%')
      Text(`${this.text.length}/${this.source.length} 字符 · ${this.streaming ? '生成中' : '完成'} · ${this.following ? '跟随' : '暂停'}`)
      InteractiveMessageTimeline({ scroller: this.scroller, bottomPadding: 28,
        rows: (): void => { this.Rows(); }, activeTail: (): void => { this.Empty(); }, hostTail: (): void => { this.Empty(); },
        onViewportHeightChange: (height: number): void => { this.viewport = height; },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) { this.following = false; this.driver?.cancel(); this.log('touch'); }
        },
      })
    }.width('100%').height('100%').backgroundColor('#F1EADB')
  }
}
