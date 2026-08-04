// CouncilRunPage — 议会运行面板:按席位分页查看逐字流式输出 + 综合裁决 + 结果
import { ACCENT,BG,INK,INK2,INK3,INK4,LINE,SURFACE,SURFACE2 } from '../design/tokens.ets';
import { router } from '@kit.ArkUI';
import { getCouncilManager } from '../di/AppContainer.ets';
import type { ModelCouncilManager } from '@amber/deepread-domain';
import { SYNTHESIZER_SEAT_KEY, isRunningStatus } from '@amber/deepread-domain';
import type { ModelCouncilRun, ModelCouncilTurn } from '@amber/deepread-domain';


const PHASES: string[] = ['听证', '辩论', '权衡', '裁决'];

const statusVerb = (s: string): string => {
  switch (s) {
    case 'running': return '进行中';
    case 'completed': return '已完成';
    case 'partial_failed': return '部分完成';
    case 'failed': return '失败';
    case 'cancelled': return '已取消';
    case 'timed_out': return '超时';
    default: return s;
  }
};

@Entry
@Component
struct CouncilRunPage {
  @State tabKeys: string[] = [];
  @State tabLabels: string[] = [];
  @State liveTexts: string[] = [];
  @State currentIndex: number = 0;
  @State statusText: string = '进行中';
  @State phase: string = '听证';
  @State objective: string = '';
  @State finished: boolean = false;
  @State errorMsg: string = '';
  private manager: ModelCouncilManager | null = null;
  private runId: string = '';
  private unsubs: Array<() => void> = [];
  private pollTimer: number = 0;
  private phaseTimer: number = 0;
  private phaseIdx: number = 0;

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    const runId: string = params ? (params['runId'] ?? '') : '';
    const mgr: ModelCouncilManager | null = getCouncilManager();
    if (mgr === null || runId.length === 0) {
      this.errorMsg = '没有进行中的议会运行';
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
    this.currentIndex = keys.length > 1 ? 1 : 0;

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

    this.startPolling();
    this.startPhaseRotation();
  }

  aboutToDisappear(): void {
    this.stopTimers();
  }

  startPolling(): void {
    this.pollTimer = setInterval(() => {
      const mgr: ModelCouncilManager | null = this.manager;
      if (mgr === null) return;
      const snap: ModelCouncilRun | null = mgr.snapshot(this.runId);
      if (snap === null) return;
      this.statusText = statusVerb(snap.status);
      if (!isRunningStatus(snap.status)) {
        this.finished = true;
        this.currentIndex = 0;
        this.stopTimers();
      }
    }, 400);
  }

  startPhaseRotation(): void {
    this.phaseTimer = setInterval(() => {
      if (this.finished) return;
      this.phaseIdx = (this.phaseIdx + 1) % PHASES.length;
      this.phase = PHASES[this.phaseIdx];
    }, 1200);
  }

  stopTimers(): void {
    if (this.pollTimer !== 0) { clearInterval(this.pollTimer); this.pollTimer = 0; }
    if (this.phaseTimer !== 0) { clearInterval(this.phaseTimer); this.phaseTimer = 0; }
    for (let i = 0; i < this.unsubs.length; i++) this.unsubs[i]();
    this.unsubs = [];
  }

  cancelRun(): void {
    const mgr: ModelCouncilManager | null = this.manager;
    if (mgr !== null) mgr.cancel(this.runId);
  }

  displayText(idx: number): string {
    const live: string = this.liveTexts[idx];
    if (live.length > 0) return live;
    const mgr: ModelCouncilManager | null = this.manager;
    if (mgr === null) return '';
    const snap: ModelCouncilRun | null = mgr.snapshot(this.runId);
    if (snap === null) return '';
    const key: string = this.tabKeys[idx];
    if (key === SYNTHESIZER_SEAT_KEY) {
      if (snap.result !== null && snap.result.error.length > 0) {
        return '裁决失败：' + snap.result.error;
      }
      return this.finished ? '（无裁决内容）' : '等待各方输出后综合…';
    }
    const turns: ModelCouncilTurn[] = snap.turns.filter(t => t.seatId === key);
    if (turns.length === 0) return this.finished ? '（该席位无输出）' : '等待输出…';
    let s: string = '';
    for (let i = 0; i < turns.length; i++) {
      const t: ModelCouncilTurn = turns[i];
      if (t.content.length > 0) s += `--- 第 ${t.round} 轮 ---\n\n${t.content}\n\n`;
      else if (t.error.length > 0) s += `（${statusVerb(t.status)}：${t.error}）\n\n`;
    }
    return s.length > 0 ? s : '等待输出…';
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick(() => { router.back(); })
        Text('@Council').fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif').margin({ left: 12 })
        Blank()
        if (!this.finished) {
          LoadingProgress().width(14).height(14).color(ACCENT).margin({ right: 6 })
          Text(this.statusText + ' · ' + this.phase).fontSize(12).fontColor(INK3)
        } else {
          Text(this.statusText).fontSize(12).fontColor(ACCENT)
        }
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      if (this.errorMsg.length > 0) {
        Column() {
          Text(this.errorMsg).fontSize(14).fontColor('#E53935')
          Text('返回').fontSize(14).fontColor(ACCENT).margin({ top: 16 })
            .onClick(() => { router.back(); })
        }
        .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        // 议题
        Text(this.objective)
          .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).lineHeight(22)
          .width('100%').padding({ left: 24, right: 24, top: 8, bottom: 12 })

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
                .onClick(() => { this.currentIndex = idx; })
            }, (key: string) => key)
          }
          .padding({ left: 24, right: 24 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%').margin({ bottom: 10 })

        // 内容
        Scroll() {
          Column() {
            Text(this.displayText(this.currentIndex))
              .fontSize(14).fontColor(INK).lineHeight(24)
              .width('100%')
          }
          .width('100%').padding({ left: 24, right: 24, bottom: 24 })
          .alignItems(HorizontalAlign.Start)
        }
        .layoutWeight(1)
        .align(Alignment.Top)

        // 底部:取消按钮(运行中)
        if (!this.finished) {
          Row() {
            Text('取消运行').fontSize(14).fontColor('#E53935')
              .padding({ left: 20, right: 20, top: 10, bottom: 10 })
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
