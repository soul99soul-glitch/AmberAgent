// CouncilRunPage — 议会运行面板:按席位分页查看逐字流式输出 + 综合裁决 + 结果
import { ACCENT,BG,INK,INK2,INK3,INK4,LINE,SURFACE,SURFACE2 } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import { getCouncilManager } from '../di/AppContainer.ets';
import type { ModelCouncilManager, CouncilToolMode, CouncilToolSnapshot, CouncilApprovalRequest, CouncilToolVerdict } from '@amber/deepread-domain';
import { SYNTHESIZER_SEAT_KEY, isRunningStatus } from '@amber/deepread-domain';
import type { ModelCouncilRun, ModelCouncilTurn } from '@amber/deepread-domain';
import { CouncilSeatTools } from '../components/CouncilSeatTools.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';


const statusVerb = (s: string): string => {
  switch (s) {
    case 'running': return '进行中';
    case 'completed': return '已完成';
    case 'partial_failed': return '部分完成';
    case 'failed': return '失败';
    case 'cancelled': return '已取消';
    case 'timed_out': return '超时';
    case 'interrupted': return '已中断';
    default: return s;
  }
};

@Entry
@Component
struct CouncilRunPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State tabKeys: string[] = [];
  @State tabLabels: string[] = [];
  @State liveTexts: string[] = [];
  @State currentIndex: number = 0;
  @State statusText: string = '进行中';
  @State objective: string = '';
  @State finished: boolean = false;
  @State errorMsg: string = '';
  // F12:真实进度(由 snapshot.turns 推导),非墙钟伪阶段
  @State progressText: string = '';
  @State toolMode: CouncilToolMode = 'off';
  @State toolSnapshots: CouncilToolSnapshot[] = [];
  @State turns: ModelCouncilTurn[] = [];
  @State approvalError: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  private manager: ModelCouncilManager | null = null;
  private runId: string = '';
  private unsubs: Array<() => void> = [];
  private pollTimer: number = 0;

  aboutToAppear(): void {
    this.stopTimers();
    this.toolSnapshots = [];
    this.approvalError = '';
    const params = router.getParams() as Record<string, string>;
    const runId: string = params ? (params['runId'] ?? '') : '';
    const mgr: ModelCouncilManager | null = getCouncilManager();
    if (mgr === null || runId.length === 0) {
      this.errorMsg = '议会运行宿主不可用，运行已中断。本页不会自动恢复工具执行。';
      this.finished = true;
      return;
    }
    this.manager = mgr;
    this.runId = runId;
    const snap: ModelCouncilRun | null = mgr.snapshot(runId);
    if (snap === null) {
      this.errorMsg = '运行不存在';
      return;
    }
    this.objective = snap.task.objective;
    this.toolMode = snap.task.toolMode ?? 'off';
    this.turns = snap.turns;
    this.statusText = statusVerb(snap.status);
    this.progressText = this.deriveProgress(snap);
    this.finished = !isRunningStatus(snap.status);
    const keys: string[] = [SYNTHESIZER_SEAT_KEY];
    const labels: string[] = ['综合裁决'];
    for (let i = 0; i < snap.seats.length; i++) {
      keys.push(snap.seats[i].seatId);
      labels.push(snap.seats[i].name);
    }
    this.tabKeys = keys;
    this.tabLabels = labels;
    const init: string[] = [];
    for (let i = 0; i < keys.length; i++) init.push('');
    this.liveTexts = init;
    // 运行中默认看首个席位;已结束直接看综合裁决
    this.currentIndex = this.finished ? 0 : (keys.length > 1 ? 1 : 0);

    for (let i = 0; i < keys.length; i++) {
      const idx: number = i;
      const key: string = keys[i];
      const unsub: () => void = mgr.subscribeLive(runId, key, (text: string): void => {
        const arr: string[] = this.liveTexts.slice();
        arr[idx] = text;
        this.liveTexts = arr;
      });
      this.unsubs.push(unsub);
    }

    this.unsubs.push(mgr.subscribeTools(runId, (snapshot: CouncilToolSnapshot): void => {
      if (snapshot.key.runId !== this.runId) return;
      const next: CouncilToolSnapshot[] = this.toolSnapshots.filter((current: CouncilToolSnapshot): boolean =>
        current.key.round !== snapshot.key.round || current.key.seatId !== snapshot.key.seatId);
      next.push(snapshot);
      this.toolSnapshots = next;
    }));

    if (!this.finished) this.startPolling();
  }

  aboutToDisappear(): void {
    this.stopTimers();
  }

  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private selectedTools(): CouncilToolSnapshot[] {
    const seatId: string = this.tabKeys[this.currentIndex] ?? '';
    return this.toolSnapshots.filter((snapshot: CouncilToolSnapshot): boolean => snapshot.key.seatId === seatId)
      .sort((a: CouncilToolSnapshot, b: CouncilToolSnapshot): number => a.key.round - b.key.round);
  }

  private turnFor(snapshot: CouncilToolSnapshot): ModelCouncilTurn | null {
    return this.turns.find((turn: ModelCouncilTurn): boolean =>
      turn.round === snapshot.key.round && turn.seatId === snapshot.key.seatId) ?? null;
  }

  private toolsActive(snapshot: CouncilToolSnapshot): boolean {
    const turn: ModelCouncilTurn | null = this.turnFor(snapshot);
    return !this.finished && (turn === null || isRunningStatus(turn.status));
  }

  private toolRoundLabel(snapshot: CouncilToolSnapshot): string {
    const turn: ModelCouncilTurn | null = this.turnFor(snapshot);
    return `第 ${snapshot.key.round} 轮工具 · ${turn === null ? this.statusText : statusVerb(turn.status)}`;
  }

  private submitVerdict(request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void {
    const manager: ModelCouncilManager | null = this.manager;
    if (this.finished || manager === null || !manager.submitToolVerdict(request, verdict)) {
      this.approvalError = '该审批已结束或步骤已变化，请以当前卡片为准。';
      return;
    }
    this.approvalError = '';
  }

  startPolling(): void {
    this.pollTimer = setInterval(() => {
      const mgr: ModelCouncilManager | null = this.manager;
      if (mgr === null) return;
      const snap: ModelCouncilRun | null = mgr.snapshot(this.runId);
      if (snap === null) return;
      this.statusText = statusVerb(snap.status);
      this.progressText = this.deriveProgress(snap);
      this.turns = snap.turns;
      if (!isRunningStatus(snap.status)) {
        this.finished = true;
        this.currentIndex = 0;
        this.stopTimers();
      }
    }, 400);
  }

  // F12:只用真实快照推导进度(已完成席位数 / 是否进入综合裁决),不仿造阶段轮播。
  private deriveProgress(snap: ModelCouncilRun): string {
    const seatCount: number = snap.seats.length;
    if (seatCount === 0) return '';
    const expected: number = snap.mode === 'debate'
      ? seatCount * Math.max(snap.task.rounds, 1) : seatCount;
    let completed: number = 0;
    for (let i = 0; i < snap.turns.length; i++) {
      if (snap.turns[i].status === 'completed') completed++;
    }
    if (isRunningStatus(snap.status)) {
      if (completed >= expected) return '综合裁决中…';
      return `已完成 ${completed}/${expected} 席`;
    }
    return '';
  }

  stopTimers(): void {
    if (this.pollTimer !== 0) { clearInterval(this.pollTimer); this.pollTimer = 0; }
    for (let i = 0; i < this.unsubs.length; i++) this.unsubs[i]();
    this.unsubs = [];
  }

  cancelRun(): void {
    const mgr: ModelCouncilManager | null = this.manager;
    if (mgr !== null) mgr.cancel(this.runId);
  }

  displayText(idx: number): string {
    const live: string = this.liveTexts[idx];
    const mgr: ModelCouncilManager | null = this.manager;
    if (mgr === null) return '';
    const snap: ModelCouncilRun | null = mgr.snapshot(this.runId);
    if (snap === null) return '';
    const key: string = this.tabKeys[idx];
    if (key === SYNTHESIZER_SEAT_KEY) {
      if (snap.result !== null && snap.result.error.length > 0) {
        return '裁决失败：' + snap.result.error;
      }
      if (live.length > 0) return live;
      if (snap.result !== null && snap.result.finalRecommendation.length > 0) return snap.result.finalRecommendation;
      return this.finished ? '（无裁决内容）' : '等待各方输出后综合…';
    }
    if (!this.finished && live.length > 0) return live;
    const turns: ModelCouncilTurn[] = snap.turns.filter(t => t.seatId === key);
    if (turns.length === 0) return this.finished ? '（该席位无输出）' : '等待输出…';
    let s: string = '';
    for (let i = 0; i < turns.length; i++) {
      const t: ModelCouncilTurn = turns[i];
      s += `--- 第 ${t.round} 轮 · ${statusVerb(t.status)} ---\n\n`;
      if (t.content.length > 0) s += `${t.content}\n\n`;
      if (t.error.length > 0) s += `（${t.error}）\n\n`;
    }
    return s.length > 0 ? s : '等待输出…';
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Row() { Icon({ name: 'back', iconSize: 20, color: this.colors.muted }) }
          .width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { router.back(); })
        Text('@Council').fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily(FONT_SERIF).margin({ left: 12 })
        Blank()
        if (!this.finished) {
          LoadingProgress().width(14).height(14).color(ACCENT).margin({ right: 6 })
          Text(this.progressText.length > 0 ? this.statusText + ' · ' + this.progressText : this.statusText)
            .fontSize(12).fontColor(this.colors.muted).layoutWeight(1)
        } else {
          Text(this.statusText).fontSize(12).fontColor(ACCENT)
        }
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .backgroundColor(BG)

      if (this.errorMsg.length > 0) {
        Column() {
          Text(this.errorMsg).fontSize(14).fontColor('#E53935')
          Text('返回').fontSize(14).fontColor(ACCENT).margin({ top: 16 })
            .constraintSize({ minHeight: 44 })
            .onClick(() => { router.back(); })
        }
        .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        // 议题
        Text(this.objective)
          .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).lineHeight(22)
          .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 6 })
        Text(`本轮工具功率：${this.toolMode === 'full' ? 'FULL' : (this.toolMode === 'search' ? '联网搜索' : '关闭')}`)
          .fontSize(12).fontColor(this.colors.muted).width('100%').padding({ left: 16, right: 16, bottom: 10 })

        // 分页 chips
        Scroll() {
          Row() {
            ForEach(this.tabKeys, (key: string, idx: number) => {
              Text(this.tabLabels[idx])
                .fontSize(13)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 }).borderRadius(18)
                .fontColor(idx === this.currentIndex ? '#FFFFFF' : INK2)
                .backgroundColor(idx === this.currentIndex ? ACCENT : SURFACE2)
                .margin({ right: 8 })
                .constraintSize({ minHeight: 44 })
                .onClick(() => { this.currentIndex = idx; })
            }, (key: string) => key)
          }
          .padding({ left: 14, right: 14, top: 2, bottom: 11 })
        }
        .align(Alignment.TopStart)
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%').margin({ bottom: 10 })

        // 内容
        Scroll() {
          Column() {
            if (this.approvalError.length > 0) {
              Text(this.approvalError).fontSize(12).fontColor(this.colors.muted).width('100%').margin({ bottom: 8 })
            }
            ForEach(this.selectedTools(), (snapshot: CouncilToolSnapshot): void => {
              Text(this.toolRoundLabel(snapshot)).fontSize(12).fontColor(this.colors.muted)
                .width('100%').margin({ top: 8, bottom: 8 })
              CouncilSeatTools({ snapshot: snapshot, active: this.toolsActive(snapshot),
                onVerdict: (request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void => {
                  this.submitVerdict(request, verdict);
                } })
            }, (snapshot: CouncilToolSnapshot): string => `${snapshot.key.round}-${snapshot.key.seatId}`)
            Text(this.displayText(this.currentIndex))
              .fontSize(14).fontColor(INK).lineHeight(24)
              .width('100%')
          }
          .width('100%').padding({ left: 16, right: 16, bottom: 24 })
          .alignItems(HorizontalAlign.Start)
        }
        .layoutWeight(1)
        .align(Alignment.Top)

        // 底部:取消按钮(运行中)
        if (!this.finished) {
          Row() {
            Text('取消运行').fontSize(14).fontColor('#E53935')
              .padding({ left: 20, right: 20, top: 10, bottom: 10 })
              .constraintSize({ minHeight: 44 })
              .onClick(() => { this.cancelRun(); })
          }
          .width('100%').justifyContent(FlexAlign.Center)
          .padding({ top: 8, bottom: 16 })
          .border({ width: { top: 0.5 }, color: { top: LINE } })
        }
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
