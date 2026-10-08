import type { UIMessagePartTool } from '@amber/chat-domain';
import { councilRunFromTools } from '@amber/chat-domain';
import type { ModelCouncilManager, ModelCouncilRun, ModelCouncilTurn, ModelCouncilSeat, CouncilToolSnapshot,
  CouncilApprovalRequest, CouncilToolVerdict } from '@amber/deepread-domain';
import { SYNTHESIZER_SEAT_KEY } from '@amber/deepread-domain';
import { getCouncilManager } from '../di/AppContainer.ets';
import { readChatCouncilRun } from '../platform_impl/CouncilChatSupport.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';
import { CouncilSeatTools } from './CouncilSeatTools.ets';
import { ACCENT, INK, INK2, INK3, LINE, SURFACE, SURFACE2, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ChatCouncilRunSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('reload') runId: string = '';
  @Prop tools: UIMessagePartTool[] = [];
  onDismiss: () => void = (): void => {};
  @State private run: ModelCouncilRun | null = null;
  @State private selectedSeat: string = SYNTHESIZER_SEAT_KEY;
  @State private liveTexts: Record<string, string> = {};
  @State private toolSnapshots: CouncilToolSnapshot[] = [];
  @State private error: string = '';
  private manager: ModelCouncilManager | null = null;
  private unsubs: Array<() => void> = [];
  private pollTimer: number = -1;
  private alive: boolean = false;
  private loadVersion: number = 0;

  aboutToAppear(): void { this.alive = true; this.reload(); }
  aboutToDisappear(): void { this.alive = false; this.loadVersion++; this.detach(); }

  private detach(): void {
    for (const unsub of this.unsubs) unsub();
    this.unsubs = [];
    if (this.pollTimer !== -1) clearInterval(this.pollTimer);
    this.pollTimer = -1;
  }
  private reload(): void {
    this.detach();
    this.liveTexts = {}; this.toolSnapshots = []; this.error = ''; this.run = null;
    const version: number = ++this.loadVersion;
    this.manager = getCouncilManager();
    const current: ModelCouncilRun | null = this.manager?.snapshot(this.runId) ?? null;
    if (current !== null) this.attach(current);
    else readChatCouncilRun(this.runId).then((run: ModelCouncilRun | null): void => {
      if (!this.alive || version !== this.loadVersion) return;
      const readable: ModelCouncilRun | null = run ?? councilRunFromTools(this.runId, this.tools);
      if (readable === null) this.error = '没有可读取的议会记录'; else this.attach(readable);
    }).catch((error: Error): void => {
      if (this.alive && version === this.loadVersion) this.error = error.message;
    });
  }
  private attach(run: ModelCouncilRun): void {
    const manager: ModelCouncilManager | null = this.manager;
    const live: boolean = manager !== null && manager.snapshot(run.runId) !== null;
    if (!live && run.status === 'running') run.status = 'interrupted';
    this.run = run;
    this.selectedSeat = run.status === 'running' && run.seats.length > 0 ? run.seats[0].seatId : SYNTHESIZER_SEAT_KEY;
    if (manager === null || manager.snapshot(run.runId) === null) return;
    for (const seatKey of [SYNTHESIZER_SEAT_KEY, ...run.seats.map((seat: ModelCouncilSeat): string => seat.seatId)]) {
      this.unsubs.push(manager.subscribeLive(run.runId, seatKey, (text: string): void => {
        if (!this.alive) return;
        const next: Record<string, string> = {};
        for (const key of Object.keys(this.liveTexts)) next[key] = this.liveTexts[key];
        next[seatKey] = text; this.liveTexts = next;
      }));
    }
    this.unsubs.push(manager.subscribeTools(run.runId, (snapshot: CouncilToolSnapshot): void => {
      if (!this.alive) return;
      this.toolSnapshots = this.toolSnapshots.filter((value: CouncilToolSnapshot): boolean =>
        value.key.seatId !== snapshot.key.seatId || value.key.round !== snapshot.key.round).concat([snapshot]);
    }));
    if (run.status === 'running') this.pollTimer = setInterval((): void => {
      const latest: ModelCouncilRun | null = manager.snapshot(run.runId);
      if (!this.alive || latest === null) return;
      const wasRunning: boolean = this.run?.status === 'running';
      this.run = latest;
      if (wasRunning && latest.status !== 'running') {
        this.selectedSeat = SYNTHESIZER_SEAT_KEY;
        if (this.pollTimer !== -1) clearInterval(this.pollTimer);
        this.pollTimer = -1;
      }
    }, 400);
  }
  private statusLabel(): string {
    const status: string = this.run?.status ?? '';
    if (status === 'running') return '正在审议';
    if (status === 'completed') return '已完成';
    if (status === 'partial_failed') return '部分失败';
    if (status === 'cancelled') return '已取消';
    if (status === 'timed_out') return '超时';
    if (status === 'interrupted') return '已中断';
    return status === 'failed' ? '失败' : '';
  }
  private seatTurns(): ModelCouncilTurn[] {
    return this.run === null ? [] : this.run.turns.filter((turn: ModelCouncilTurn): boolean => turn.seatId === this.selectedSeat);
  }
  private liveText(): string {
    const live: string = this.liveTexts[this.selectedSeat] ?? '';
    if (this.run?.status !== 'running') return '';
    return live;
  }
  private activeTools(): CouncilToolSnapshot[] {
    if (this.run?.status !== 'running') return [];
    return this.toolSnapshots.filter((snapshot: CouncilToolSnapshot): boolean => snapshot.key.seatId === this.selectedSeat &&
      !this.run!.turns.some((turn: ModelCouncilTurn): boolean => turn.seatId === snapshot.key.seatId && turn.round === snapshot.key.round));
  }
  private turnTools(turn: ModelCouncilTurn): CouncilToolSnapshot {
    return { key: { runId: this.runId, round: turn.round, seatId: turn.seatId }, conversationId: '',
      messages: turn.toolMessages ?? [], sources: turn.sources ?? [], pending: [] };
  }
  private verdict(request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void {
    if (this.manager === null || !this.manager.submitToolVerdict(request, verdict)) this.error = '该审批已结束或步骤已变化';
    else this.error = '';
  }

  @Builder
  private Tab(key: string, title: string): void {
    Text(title).fontSize(META).fontColor(this.selectedSeat === key ? ACCENT : INK2)
      .padding({ left: 12, right: 12, top: 10, bottom: 10 })
      .backgroundColor(this.selectedSeat === key ? SURFACE2 : Color.Transparent).borderRadius(16)
      .onClick((): void => { this.selectedSeat = key; })
  }
  @Builder
  private ResultBody(): void {
    if ((this.run?.result?.finalRecommendation.length ?? 0) > 0) {
      MarkdownText({ content: this.run!.result!.finalRecommendation, fontSize: BODY, fontColor: INK })
    } else if (this.liveText().length > 0) {
      MarkdownText({ content: this.liveText(), fontSize: BODY, streaming: true,
        streamKey: `council-${this.runId}-${this.selectedSeat}` })
    } else {
      Text(this.run?.result?.error || (this.run?.status === 'running' ? '等待综合裁决…' : '没有综合裁决内容'))
        .fontSize(META).fontColor(INK3)
    }
    if ((this.run?.result?.warnings.length ?? 0) > 0) {
      Text(this.run!.result!.warnings.join('\n')).fontSize(LABEL).fontColor(INK3).margin({ top: 10 })
    }
  }
  build(): void {
    Column() {
      Row() {
        Text('@Council').fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.Start)
        Text(this.statusLabel()).fontSize(LABEL).fontColor(INK3).margin({ left: 10 }).maxLines(1)
        Row() {
          Icon({ name: 'close', iconSize: 18, color: INK2 })
        }.width(44).height(44).justifyContent(FlexAlign.Center)
          .onClick((): void => { this.onDismiss(); }).accessibilityText('关闭模型议会运行')
      }.width('100%').height(56).padding({ top: 12 })
      if (this.run !== null) {
        Text(this.run!.task.objective).fontSize(META).fontColor(INK2).maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').margin({ bottom: 10 })
        Scroll() {
          Row({ space: 4 }) {
            this.Tab(SYNTHESIZER_SEAT_KEY, '综合裁决')
            ForEach(this.run!.seats, (seat: ModelCouncilSeat): void => { this.Tab(seat.seatId, seat.name || seat.role); },
              (seat: ModelCouncilSeat): string => seat.seatId)
          }
        }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%')
        Divider().color(LINE).margin({ top: 8, bottom: 12 })
      }
      if (this.error.length > 0) Text(this.error).fontSize(META).fontColor(INK3).margin({ bottom: 8 })
      Scroll() {
        Column({ space: 12 }) {
          if (this.selectedSeat === SYNTHESIZER_SEAT_KEY) this.ResultBody()
          else {
            if (this.liveText().length > 0) {
              MarkdownText({ content: this.liveText(), fontSize: BODY, streaming: true,
                streamKey: `council-${this.runId}-${this.selectedSeat}` })
            } else {
              ForEach(this.seatTurns(), (turn: ModelCouncilTurn): void => {
                Column({ space: 6 }) {
                  Text(`第 ${turn.round} 轮 · ${turn.modelLabel}`).fontSize(LABEL).fontColor(INK3)
                  if (turn.content.length > 0) MarkdownText({ content: turn.content, fontSize: BODY })
                  if (turn.error.length > 0) Text(turn.error).fontSize(META).fontColor(INK3)
                  if ((turn.toolMessages?.length ?? 0) > 0 || (turn.sources?.length ?? 0) > 0) {
                    CouncilSeatTools({ snapshot: this.turnTools(turn), active: false })
                  }
                }.width('100%').alignItems(HorizontalAlign.Start)
              }, (turn: ModelCouncilTurn): string => `${turn.seatId}-${turn.round}-${turn.status}`)
              if (this.seatTurns().length === 0) Text('等待席位发言…').fontSize(META).fontColor(INK3)
            }
            ForEach(this.activeTools(), (snapshot: CouncilToolSnapshot): void => {
              Text(`第 ${snapshot.key.round} 轮工具`).fontSize(LABEL).fontColor(INK3)
              CouncilSeatTools({ snapshot: snapshot, active: this.run?.status === 'running',
                onVerdict: (request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void => { this.verdict(request, verdict); } })
            }, (snapshot: CouncilToolSnapshot): string => `${snapshot.key.seatId}-${snapshot.key.round}-${snapshot.pending.map((request): string => request.subjectHash).join('-')}`)
          }
        }.width('100%').alignItems(HorizontalAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
    }.width('100%').height('100%').padding({ left: 16, right: 16, bottom: 16 })
      .backgroundColor(SURFACE).enabled(this.themeEpoch >= 0)
  }
}
