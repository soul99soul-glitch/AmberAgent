// CouncilRunPage — 模型议会房间(对齐 iOS CouncilChatRuntimeView):
//   进入即房间:底部输入议题 → 发送即开跑;席位输出以聊天消息流呈现(头像 + 名/角色 + 正文),
//   综合裁决为收尾卡片;运行中发送键变停止键;头部胶囊切换 对比/辩论(辩论可选轮数)。
//   带 runId 参数进入 = 查看/继续既有运行(落地页「最近讨论」/首页继续卡)。
import { router, promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ACCENT, ACCENT_INK, BG, INK, INK2, INK3, INK4, LINE, SURFACE, SURFACE2, BODY, META, LABEL } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { CouncilSeatTools } from '../components/CouncilSeatTools.ets';
import { CouncilToolModePicker } from '../components/CouncilToolModePicker.ets';
import {
  CORE_SEATS, LENS_PRESETS, SYNTHESIZER_SEAT_KEY, isRunningStatus, makeRuntimeSetting, makeSeat,
  resolveCouncilDynamicSeats, readCouncilArchive,
} from '@amber/deepread-domain';
import type {
  CouncilApprovalRequest, CouncilDynamicSeatSelection, CouncilTaskInput, CouncilToolMode, CouncilToolSnapshot,
  CouncilToolVerdict, ModelConfig, ModelCouncilManager, ModelCouncilMode, ModelCouncilRolePreset, ModelCouncilRun,
  ModelCouncilRuntimeSetting, ModelCouncilSeat, ModelCouncilTurn,
} from '@amber/deepread-domain';
import {
  getAgentPromptSettingsPort, getCouncilManager, getCouncilRunner, getModelRegistry, prepareCouncilManager, getFileStore,
} from '../di/AppContainer.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Council';
const ROUND_OPTIONS: number[] = [2, 3, 4, 5];
const POLL_MS: number = 400;

interface ActionMenuResult {
  index: number;
}

// 持久化 setting → makeRuntimeSetting 入参(仅覆盖字段,缺省走默认)
const baseToInit = (base: ModelCouncilRuntimeSetting): Record<string, Object | null> => {
  const init: Record<string, Object | null> = {};
  init['enabled'] = base.enabled;
  init['defaultSeats'] = base.defaultSeats;
  init['synthesisModelId'] = base.synthesisModelId;
  init['maxSeats'] = base.maxSeats;
  init['defaultRounds'] = base.defaultRounds;
  init['maxRounds'] = base.maxRounds;
  init['seatTimeoutMs'] = base.seatTimeoutMs;
  init['totalTimeoutMs'] = base.totalTimeoutMs;
  init['outputBudgetChars'] = base.outputBudgetChars;
  init['showSeatOutputs'] = base.showSeatOutputs;
  init['toolMode'] = base.toolMode;
  return init;
};

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

// 动态席位 LLM 返回文档
interface DynamicSeatDoc {
  name?: string;
  role?: string;
  systemPrompt?: string;
}

@Entry
@Component
struct CouncilRunPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  // ── 议题与设置(setup 阶段可改) ──
  @State objective: string = '';
  @State mode: ModelCouncilMode = 'compare';
  @State toolMode: CouncilToolMode = 'off';
  @State rounds: number = 2;
  @State selectedLens: string[] = [];
  @State models: ModelConfig[] = [];
  @State modelsLoaded: boolean = false;
  @State persistedSetting: ModelCouncilRuntimeSetting | null = null;
  @State generatedSeats: ModelCouncilSeat[] = [];
  @State generatingSeats: boolean = false;
  @State dynamicSeatError: string = '';
  @State starting: boolean = false;
  // ── 运行态 ──
  @State runId: string = '';
  @State seats: ModelCouncilSeat[] = [];
  @State runObjective: string = '';
  @State statusText: string = '进行中';
  @State progressText: string = '';
  @State turns: ModelCouncilTurn[] = [];
  @State toolSnapshots: CouncilToolSnapshot[] = [];
  @State finished: boolean = false;
  @State errorMsg: string = '';
  @State approvalError: string = '';
  @State liveTick: number = 0;
  @State finalResult: string = '';
  @State resultError: string = '';
  private liveTexts: Map<string, string> = new Map();
  private manager: ModelCouncilManager | null = null;
  private unsubs: Array<() => void> = [];
  private pollTimer: number = 0;
  private pageAlive: boolean = false;
  private loadToken: number = 0;
  private dynamicSeatToken: number = 0;
  private dynamicSeatAbortController: EntryAbortController | null = null;

  aboutToAppear(): void {
    this.pageAlive = true;
    const token: number = ++this.loadToken;
    this.loadModels(token).catch((e: Error): void => {
      if (this.pageAlive && token === this.loadToken) this.errorMsg = String(e);
    });
    getAgentPromptSettingsPort().getCouncilSetting().then((persisted: ModelCouncilRuntimeSetting): void => {
      if (this.pageAlive && token === this.loadToken) {
        this.persistedSetting = persisted;
        this.toolMode = persisted.toolMode;
        this.reconcileDynamicSeats(persisted);
      }
    }).catch((): void => {});

    const params = router.getParams() as Record<string, string>;
    const runId: string = params ? (params['runId'] ?? '') : '';
    if (runId.length > 0) this.attachRun(runId);
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.loadToken++;
    this.dynamicSeatToken++;
    if (this.dynamicSeatAbortController !== null) this.dynamicSeatAbortController.abort();
    this.dynamicSeatAbortController = null;
    this.generatingSeats = false;
    this.starting = false;
    this.detachRun();
  }

  private async loadModels(token: number): Promise<void> {
    const models: ModelConfig[] = await getModelRegistry().list();
    if (this.pageAlive && token === this.loadToken) {
      this.models = models;
      this.modelsLoaded = true;
    }
  }

  // ── 席位选择(setup 阶段) ──

  private toggleLens(id: string): void {
    const next: string[] = [];
    let found: boolean = false;
    for (let i = 0; i < this.selectedLens.length; i++) {
      if (this.selectedLens[i] === id) { found = true; } else { next.push(this.selectedLens[i]); }
    }
    if (!found) next.push(id);
    this.selectedLens = next;
    this.reconcileDynamicSeats(this.persistedSetting ?? makeRuntimeSetting());
  }

  private dynamicSeatCapacity(): number {
    return resolveCouncilDynamicSeats(this.persistedSetting ?? makeRuntimeSetting(),
      this.selectedLens, this.models, this.generatedSeats).remainingCapacity;
  }

  private reconcileDynamicSeats(setting: ModelCouncilRuntimeSetting): boolean {
    const selected: CouncilDynamicSeatSelection = resolveCouncilDynamicSeats(
      setting, this.selectedLens, this.models, this.generatedSeats);
    if (selected.seats.length === this.generatedSeats.length) return false;
    this.generatedSeats = selected.seats;
    this.dynamicSeatError = `席位上限为 ${setting.maxSeats} 个，已移除未纳入的动态席位`;
    return true;
  }

  private async generateDynamicSeats(): Promise<void> {
    const objective: string = this.objective.trim();
    if (objective.length === 0 || this.models.length === 0 || this.generatingSeats || this.starting) return;
    const token: number = ++this.dynamicSeatToken;
    const controller: EntryAbortController = new EntryAbortController();
    this.dynamicSeatAbortController = controller;
    this.generatingSeats = true;
    this.dynamicSeatError = '';
    try {
      const base: ModelCouncilRuntimeSetting = this.persistedSetting !== null
        ? this.persistedSetting : await getAgentPromptSettingsPort().getCouncilSetting();
      if (!this.pageAlive || token !== this.dynamicSeatToken) return;
      this.persistedSetting = base;
      if (!base.enabled) {
        this.dynamicSeatError = '模型议会已在设置中关闭';
        return;
      }
      this.reconcileDynamicSeats(base);
      const remaining: number = this.dynamicSeatCapacity();
      if (remaining === 0) {
        this.dynamicSeatError = `席位已满（${base.maxSeats} 个），请先移除可选视角席或动态席位`;
        return;
      }
      const limit: number = Math.min(3, remaining);
      const model: ModelConfig = this.models[0];
      const result = await getCouncilRunner().generate({
        model: model,
        systemPrompt: `你是多模型议会的组委。根据用户议题生成 ${limit} 个互补的评审视角席位,`
          + '只输出 JSON 数组:[{"name":"不超过6字","role":"不超过12字",'
          + '"systemPrompt":"该视角的评审指令(不超过80字)"}],不要输出任何解释。'
          + '视角之间必须有明显差异(如 成本/风险/用户/技术/伦理/市场)。',
        userPrompt: objective,
        outputBudgetChars: 900,
        reasoningLevel: null,
        temperature: null,
        onChunk: (): void => {},
        signal: controller.signal,
        toolMode: 'off',
      });
      if (!this.pageAlive || token !== this.dynamicSeatToken || controller.signal.aborted) return;
      const text: string = result.text;
      const start: number = text.indexOf('[');
      const end: number = text.lastIndexOf(']');
      if (start < 0 || end <= start) throw new Error('模型未返回席位 JSON');
      const arr = JSON.parse(text.substring(start, end + 1)) as DynamicSeatDoc[];
      const next: ModelCouncilSeat[] = [...this.generatedSeats];
      let added: number = 0;
      for (const d of arr) {
        if (d.name === undefined || d.role === undefined || d.systemPrompt === undefined) continue;
        if (next.findIndex((s: ModelCouncilSeat): boolean => s.role === d.role) >= 0) continue;
        next.push(makeSeat({
          name: (d.name as string).substring(0, 12),
          role: (d.role as string).substring(0, 16),
          modelId: '',
          systemPrompt: (d.systemPrompt as string).substring(0, 200),
        }));
        added++;
        if (added >= limit) break;
      }
      if (added === 0) throw new Error('未解析出可用席位');
      const selected: CouncilDynamicSeatSelection = resolveCouncilDynamicSeats(
        base, this.selectedLens, this.models, next);
      const included: number = selected.seats.length - this.generatedSeats.length;
      if (included <= 0) throw new Error('没有可加入的席位，请检查席位容量和重复角色');
      this.generatedSeats = selected.seats;
      if (included < added) this.dynamicSeatError = `本轮已加入 ${included} 个动态席位，其余席位因容量或重复角色未纳入`;
    } catch (e) {
      if (this.pageAlive && token === this.dynamicSeatToken && !controller.signal.aborted) {
        const msg: string = e instanceof Error ? e.message : String(e);
        this.dynamicSeatError = `生成失败:${msg}`;
      }
    } finally {
      if (this.pageAlive && token === this.dynamicSeatToken) {
        this.generatingSeats = false;
        this.dynamicSeatAbortController = null;
      }
    }
  }

  // ── 运行生命周期 ──

  private async startCouncil(): Promise<void> {
    if (this.starting || this.generatingSeats || this.runId.length > 0) return;
    if (this.objective.trim().length === 0) {
      this.errorMsg = '请输入议题';
      return;
    }
    if (!this.modelsLoaded) {
      this.errorMsg = '模型库仍在加载，请稍候再试';
      return;
    }
    if (this.models.length === 0) {
      this.errorMsg = '请先到 设置 → 模型库 添加至少一个模型';
      return;
    }
    this.errorMsg = '';
    this.starting = true;
    const token: number = this.loadToken;
    try {
      // 读取未完成时先 await(否则可用默认 enabled=true 绕过禁用开关)
      const base: ModelCouncilRuntimeSetting = this.persistedSetting !== null
        ? this.persistedSetting
        : await getAgentPromptSettingsPort().getCouncilSetting();
      if (!this.pageAlive || token !== this.loadToken) return;
      const toolMode: CouncilToolMode = this.persistedSetting === null ? base.toolMode : this.toolMode;
      if (!base.enabled) {
        this.errorMsg = '模型议会已在设置中关闭';
        return;
      }
      const init: Record<string, Object | null> = baseToInit(base);
      init['defaultRounds'] = this.rounds;
      init['maxRounds'] = Math.max(base.maxRounds, this.rounds);
      init['toolMode'] = toolMode;
      const setting: ModelCouncilRuntimeSetting = makeRuntimeSetting(init);
      if (this.reconcileDynamicSeats(setting)) return;
      const mgr = await prepareCouncilManager(setting);
      if (!this.pageAlive || token !== this.loadToken) return;
      const input: CouncilTaskInput = {
        mode: this.mode,
        toolMode: toolMode,
        objective: this.objective.trim(),
        rounds: this.rounds,
        extraLens: this.selectedLens,
        extraSeats: this.generatedSeats,
      };
      const run = mgr.start(input);
      hilog.info(DOMAIN, TAG, 'council started runId=%{public}s', run.runId);
      this.runObjective = input.objective;
      this.attachRun(run.runId);
    } catch (e) {
      if (this.pageAlive && token === this.loadToken) this.errorMsg = String(e);
      hilog.error(DOMAIN, TAG, 'startCouncil failed: %{public}s', String(e));
    } finally {
      if (this.pageAlive && token === this.loadToken) this.starting = false;
    }
  }

  // 订阅既有/新起 run;setup 状态随之锁定
  private attachRun(runId: string): void {
    this.detachRun();
    const mgr: ModelCouncilManager | null = getCouncilManager();
    const snap: ModelCouncilRun | null = mgr?.snapshot(runId) ?? null;
    if (mgr === null || snap === null) {
      this.runId = runId;
      const token: number = this.loadToken;
      readCouncilArchive(getFileStore(), runId).then((stored: ModelCouncilRun | null): void => {
        if (!this.pageAlive || token !== this.loadToken || this.runId !== runId) return;
        this.manager = null;
        if (stored === null) this.errorMsg = '没有可读取的议会记录';
        else this.applyRunSnapshot(stored);
        this.finished = true;
      }).catch((error: Error): void => {
        if (this.pageAlive && token === this.loadToken && this.runId === runId) {
          this.errorMsg = error.message; this.finished = true;
        }
      });
      return;
    }
    this.manager = mgr;
    this.applyRunSnapshot(snap);

    const keys: string[] = [SYNTHESIZER_SEAT_KEY];
    for (let i = 0; i < snap.seats.length; i++) keys.push(snap.seats[i].seatId);
    const subs: Array<() => void> = [];
    const makeLiveCb = (key: string): ((text: string) => void) => {
      return (text: string): void => {
        this.liveTexts.set(key, text);
        this.liveTick++;
      };
    };
    for (let i = 0; i < keys.length; i++) {
      subs.push(mgr.subscribeLive(runId, keys[i], makeLiveCb(keys[i])));
    }
    subs.push(mgr.subscribeTools(runId, (snapshot: CouncilToolSnapshot): void => {
      if (snapshot.key.runId !== this.runId) return;
      const next: CouncilToolSnapshot[] = this.toolSnapshots.filter((current: CouncilToolSnapshot): boolean =>
        current.key.round !== snapshot.key.round || current.key.seatId !== snapshot.key.seatId);
      next.push(snapshot);
      this.toolSnapshots = next;
    }));
    this.unsubs = subs;

    if (!this.finished) this.startPolling();
  }

  private applyRunSnapshot(snap: ModelCouncilRun): void {
    this.runId = snap.runId;
    this.runObjective = snap.task.objective;
    this.mode = snap.mode;
    this.toolMode = snap.task.toolMode ?? 'off';
    this.seats = snap.seats;
    this.turns = snap.turns;
    this.statusText = statusVerb(snap.status);
    this.progressText = this.deriveProgress(snap);
    this.finished = !isRunningStatus(snap.status);
    this.syncResult(snap);
  }

  private detachRun(): void {
    if (this.pollTimer !== 0) { clearInterval(this.pollTimer); this.pollTimer = 0; }
    for (let i = 0; i < this.unsubs.length; i++) this.unsubs[i]();
    this.unsubs = [];
    this.liveTexts.clear();
  }

  private startPolling(): void {
    this.pollTimer = setInterval((): void => {
      const mgr: ModelCouncilManager | null = this.manager;
      if (mgr === null || this.runId.length === 0) return;
      const snap: ModelCouncilRun | null = mgr.snapshot(this.runId);
      if (snap === null) return;
      this.statusText = statusVerb(snap.status);
      this.progressText = this.deriveProgress(snap);
      this.turns = snap.turns;
      this.syncResult(snap);
      if (!isRunningStatus(snap.status)) {
        this.finished = true;
        if (this.pollTimer !== 0) { clearInterval(this.pollTimer); this.pollTimer = 0; }
      }
    }, POLL_MS);
  }

  private syncResult(snap: ModelCouncilRun): void {
    if (snap.result !== null) {
      this.resultError = snap.result.error;
      this.finalResult = snap.result.finalRecommendation;
    }
  }

  // 只用真实快照推导进度(已完成席位数 / 是否进入综合裁决),不仿造阶段轮播
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

  private cancelRun(): void {
    const mgr: ModelCouncilManager | null = this.manager;
    if (mgr !== null && this.runId.length > 0) mgr.cancel(this.runId);
  }

  // 席位流式行文本:turn 落座(同文)后隐藏,避免流式行与正式行重复
  private liveRowText(seatId: string): string {
    const tick: number = this.liveTick;
    const live: string = this.liveTexts.get(seatId) ?? '';
    if (tick < 0 || live.length === 0 || this.finished) return '';
    const materialized: boolean = this.turns.some(
      (turn: ModelCouncilTurn): boolean => turn.seatId === seatId && turn.content === live);
    return materialized ? '' : live;
  }

  private synthesisLiveText(): string {
    const tick: number = this.liveTick;
    const live: string = this.liveTexts.get(SYNTHESIZER_SEAT_KEY) ?? '';
    if (tick < 0 || live.length === 0 || this.finished) return '';
    return live;
  }

  private seatName(seatId: string): string {
    const seat: ModelCouncilSeat | undefined = this.seats.find((s: ModelCouncilSeat): boolean => s.seatId === seatId);
    return seat !== undefined ? seat.name : '席位';
  }

  private seatRole(seatId: string): string {
    const seat: ModelCouncilSeat | undefined = this.seats.find((s: ModelCouncilSeat): boolean => s.seatId === seatId);
    return seat !== undefined ? seat.role : '';
  }

  private submitVerdict(request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void {
    const manager: ModelCouncilManager | null = this.manager;
    if (this.finished || manager === null || !manager.submitToolVerdict(request, verdict)) {
      this.approvalError = '该审批已结束或步骤已变化，请以当前卡片为准。';
      return;
    }
    this.approvalError = '';
  }

  private toolsActive(snapshot: CouncilToolSnapshot): boolean {
    const turn: ModelCouncilTurn | undefined = this.turns.find((t: ModelCouncilTurn): boolean =>
      t.round === snapshot.key.round && t.seatId === snapshot.key.seatId);
    return !this.finished && (turn === undefined || isRunningStatus(turn.status));
  }

  private toolRoundLabel(snapshot: CouncilToolSnapshot): string {
    const turn: ModelCouncilTurn | undefined = this.turns.find((t: ModelCouncilTurn): boolean =>
      t.round === snapshot.key.round && t.seatId === snapshot.key.seatId);
    return `第 ${snapshot.key.round} 轮工具 · ${turn === undefined ? this.statusText : statusVerb(turn.status)}`;
  }

  // 开新议会:清运行态,回到 setup(已完成/已取消后再发起)
  private resetRoom(): void {
    this.detachRun();
    this.runId = '';
    this.seats = [];
    this.runObjective = '';
    this.turns = [];
    this.toolSnapshots = [];
    this.finalResult = '';
    this.resultError = '';
    this.statusText = '进行中';
    this.progressText = '';
    this.finished = false;
    this.approvalError = '';
    this.objective = '';
  }

  private showModeMenu(): void {
    if (this.runId.length > 0) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '对比 · 各席独立作答一轮', color: INK },
        { text: '辩论 · 多轮交锋后裁决', color: INK },
      ],
    }).then((result: ActionMenuResult): void => {
      if (result.index === 0) this.mode = 'compare';
      else if (result.index === 1) this.mode = 'debate';
    });
  }

  // ── UI ──

  @Builder
  Header(): void {
    Stack({ alignContent: Alignment.Center }) {
      // 底层:左右圆钮(胶囊只居中小宽,不遮挡两端)
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 18, color: INK })
        }
        .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .accessibilityText('返回')
        .onClick((): void => { router.back(); })
        Blank()
        Row() {
          Icon({ name: 'gear', iconSize: 18, color: INK })
        }
        .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .justifyContent(FlexAlign.Center)
        .accessibilityText('议会设置')
        .onClick((): void => { router.pushUrl({ url: 'pages/SettingModelCouncilPage' }); })
      }
      .width('100%')

      // 顶层:居中模式胶囊(后声明在上层,点击不被整宽 Row 截走)
      Column() {
        Row() {
          Text(this.mode === 'compare' ? '对比' : '辩论')
            .fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          if (this.runId.length === 0) {
            Icon({ name: 'chevron_down', iconSize: 10, color: INK3 }).margin({ left: 3 })
          }
        }
        .justifyContent(FlexAlign.Center)
        Text(this.runId.length === 0
          ? '模型议会'
          : `${this.seats.length} 位成员 · ${this.statusText}${this.progressText.length > 0 ? ' · ' + this.progressText : ''}`)
          .fontSize(10.5).fontFamily(FONT_SANS).fontColor(INK3).margin({ top: 1 }).maxLines(1)
      }
      .padding({ left: 14, right: 14 })
      .height(40)
      .justifyContent(FlexAlign.Center)
      .borderRadius(20)
      .backgroundColor(SURFACE)
      .border({ width: 0.5, color: LINE })
      .enabled(this.runId.length === 0)
      .onClick((): void => { this.showModeMenu(); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
    .backgroundColor(BG)
  }

  // 用户议题气泡(右对齐)
  @Builder
  UserBubble(text: string): void {
    Row() {
      Blank()
      Column() {
        MarkdownText({ content: text, fontSize: BODY })
      }
      .constraintSize({ maxWidth: '82%' })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(this.accentColorStore)
      .borderRadius(18)
    }
    .width('100%').margin({ bottom: 16 })
  }

  // 席位消息行(头像 + 名/角色 + 正文)
  @Builder
  SeatRow(name: string, role: string, roundLabel: string, content: string, streaming: boolean): void {
    Row({ space: 10 }) {
      Column() {
        Text(name.length > 0 ? name.substring(0, 1) : '席')
          .fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_SEMIBOLD).fontColor(ACCENT_INK)
      }
      .width(30).height(30).borderRadius(15)
      .backgroundColor(this.accentColorStore)
      .justifyContent(FlexAlign.Center)
      .margin({ top: 2 })

      Column() {
        Row({ space: 6 }) {
          Text(name).fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          if (role.length > 0) {
            Text(role).fontSize(11).fontFamily(FONT_SANS).fontColor(INK3)
          }
          if (roundLabel.length > 0) {
            Text(roundLabel).fontSize(11).fontFamily(FONT_SANS).fontColor(INK4)
          }
        }
        .alignItems(VerticalAlign.Center)
        MarkdownText({ content: content, fontSize: BODY, streaming: streaming })
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .width('100%')
    .alignItems(VerticalAlign.Top)
    .margin({ bottom: 16 })
  }

  // 综合裁决收尾卡
  @Builder
  SynthesisCard(): void {
    Column() {
      Row({ space: 6 }) {
        Icon({ name: 'shield', iconSize: 14, color: this.accentColorStore })
        Text('综合裁决').fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(this.accentColorStore)
      }
      .margin({ bottom: 6 })
      if (this.resultError.length > 0) {
        Text(`裁决失败：${this.resultError}`).fontSize(BODY).fontColor('#E53935').fontFamily(FONT_SANS)
      } else {
        MarkdownText({
          content: this.finalResult.length > 0 ? this.finalResult
            : (this.synthesisLiveText().length > 0 ? this.synthesisLiveText() : '等待各方输出后综合…'),
          fontSize: BODY,
          streaming: this.finalResult.length === 0,
        })
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding(14)
    .backgroundColor(SURFACE)
    .borderRadius(14)
    .border({ width: 1, color: this.accentColorStore })
    .margin({ bottom: 16 })
  }

  // setup:席位与工具配置区(发送前可见)
  @Builder
  SetupPanel(): void {
    Column() {
      // 辩论轮数
      if (this.mode === 'debate') {
        Row() {
          Text('轮数').fontSize(13).fontFamily(FONT_SANS).fontColor(INK3).margin({ right: 12 })
          ForEach(ROUND_OPTIONS, (r: number) => {
            Text(r.toString())
              .fontSize(13).width(36).textAlign(TextAlign.Center)
              .padding({ top: 8, bottom: 8 }).borderRadius(8)
              .fontColor(this.rounds === r ? '#FFFFFF' : INK2)
              .backgroundColor(this.rounds === r ? this.accentColorStore : SURFACE2)
              .margin({ right: 8 })
              .onClick((): void => { this.rounds = r; })
          }, (r: number) => r.toString())
        }
        .width('100%').margin({ bottom: 16 })
      }

      // 核心席
      Text('核心席自动加入').fontSize(LABEL).fontFamily(FONT_SANS).fontColor(INK4).width('100%').margin({ bottom: 8 })
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(CORE_SEATS, (p: ModelCouncilRolePreset) => {
          Text(p.name)
            .fontSize(12).padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(14)
            .fontColor(this.accentColorStore).backgroundColor(SURFACE2).margin({ right: 8, bottom: 8 })
        }, (p: ModelCouncilRolePreset) => p.id)
      }
      .width('100%').margin({ bottom: 8 })

      // 可选视角席
      Text('可选视角席').fontSize(LABEL).fontFamily(FONT_SANS).fontColor(INK4).width('100%').margin({ bottom: 8 })
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(LENS_PRESETS, (p: ModelCouncilRolePreset) => {
          Text(p.name)
            .fontSize(13).padding({ left: 14, right: 14, top: 8, bottom: 8 }).borderRadius(18)
            .fontColor(this.selectedLens.indexOf(p.id) >= 0 ? '#FFFFFF' : INK2)
            .backgroundColor(this.selectedLens.indexOf(p.id) >= 0 ? this.accentColorStore : SURFACE2)
            .margin({ right: 8, bottom: 8 })
            .onClick((): void => { this.toggleLens(p.id); })
        }, (p: ModelCouncilRolePreset) => p.id)
      }
      .width('100%').margin({ bottom: 8 })

      // 动态席位
      Row() {
        Text('动态席位').fontSize(META).fontFamily(FONT_SANS).fontColor(INK3).layoutWeight(1)
        if (this.generatingSeats) {
          LoadingProgress().width(14).height(14).color(this.accentColorStore).margin({ right: 6 })
        }
        Text(this.generatingSeats ? '生成中…' : 'AI 生成')
          .fontSize(13).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.objective.trim().length === 0 || this.models.length === 0
            || this.dynamicSeatCapacity() === 0
            || (this.persistedSetting !== null && !this.persistedSetting.enabled)
            ? INK4 : this.accentColorStore)
          .padding({ left: 12, right: 4, top: 6, bottom: 6 })
          .enabled(!this.generatingSeats && !this.starting && this.dynamicSeatCapacity() > 0
            && (this.persistedSetting === null || this.persistedSetting.enabled))
          .onClick((): void => { this.generateDynamicSeats(); })
      }
      .width('100%')
      Text(this.persistedSetting === null ? '正在读取席位设置…'
        : (!this.persistedSetting.enabled ? '模型议会已在设置中关闭'
          : `还可加入 ${this.dynamicSeatCapacity()} 个动态席位`))
        .fontSize(11).fontFamily(FONT_SANS).fontColor(INK4).width('100%').margin({ top: 4, bottom: 8 })
      if (this.generatedSeats.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.generatedSeats, (s: ModelCouncilSeat) => {
            Row() {
              Text(`${s.name} · ${s.role}`).fontSize(12).fontColor(this.accentColorStore)
              Text(' ×').fontSize(12).fontColor(INK3)
            }
            .padding({ left: 12, right: 10, top: 6, bottom: 6 })
            .backgroundColor(SURFACE2).borderRadius(14)
            .margin({ right: 8, bottom: 8 })
            .onClick((): void => {
              this.generatedSeats = this.generatedSeats.filter(
                (x: ModelCouncilSeat): boolean => x.seatId !== s.seatId);
            })
          }, (s: ModelCouncilSeat) => s.seatId)
        }
        .width('100%')
      }
      if (this.dynamicSeatError.length > 0) {
        Text(this.dynamicSeatError).fontSize(11).fontFamily(FONT_SANS).fontColor('#E53935').width('100%')
          .margin({ bottom: 8 })
      }

      // 工具功率
      CouncilToolModePicker({ mode: this.toolMode, disabled: this.starting || this.generatingSeats || this.persistedSetting === null,
        onSelect: (mode: CouncilToolMode): void => { this.toolMode = mode; } })
      Text('工具配置使用默认助手（首个配置）；各席位仍使用模型库中的独立模型。FULL 不会开启自动批准。')
        .fontSize(11).fontFamily(FONT_SANS).fontColor(INK4).width('100%').margin({ top: 6 })

      // 模型库状态
      if (!this.modelsLoaded) {
        Row() {
          LoadingProgress().width(14).height(14).color(this.accentColorStore).margin({ right: 8 })
          Text('正在加载模型库…').fontSize(13).fontFamily(FONT_SANS).fontColor(INK3)
        }
        .width('100%').margin({ top: 14 })
      } else if (this.models.length === 0) {
        Text('模型库为空。请到 设置 → 模型库 添加模型。')
          .fontSize(13).fontFamily(FONT_SANS).fontColor('#E53935').width('100%').margin({ top: 14 })
      }
    }
    .width('100%')
    .padding(16)
    .backgroundColor(SURFACE)
    .borderRadius(16)
    .margin({ top: 4, bottom: 16 })
  }

  // 底部输入区:setup/结束后可发送;运行中变停止键
  @Builder
  Composer(): void {
    Row() {
      Row() {
        TextArea({ text: this.objective,
          placeholder: this.runId.length === 0 ? '输入议题，开始一场议会…' : '再发起一个议题…' })
          .layoutWeight(1)
          .fontSize(BODY).fontColor(INK).fontFamily(FONT_SANS)
          .placeholderColor(INK4)
          .placeholderFont({ size: BODY })
          .backgroundColor(Color.Transparent)
          .padding({ top: 11, bottom: 11 })
          .maxLines(4)
          .constraintSize({ minHeight: 44, maxHeight: 111 })
          .enabled(!this.starting && (this.runId.length === 0 || this.finished))
          .onChange((v: string): void => { this.objective = v; })
      }
      .layoutWeight(1)
      .constraintSize({ minHeight: 44 })
      .borderRadius(22)
      .backgroundColor(SURFACE)
      .border({ width: 1, color: LINE })
      .padding({ left: 16, right: 16 })
      .alignItems(VerticalAlign.Center)
      .margin({ right: 8 })

      if (this.runId.length > 0 && !this.finished) {
        // 停止键
        Row() {
          Column().width(12).height(12).backgroundColor(SURFACE).borderRadius(3)
        }
        .width(44).height(44).borderRadius(999).backgroundColor(INK)
        .justifyContent(FlexAlign.Center)
        .onClick((): void => { this.cancelRun(); })
        .accessibilityText('取消运行')
      } else {
        // 发送键
        Row() {
          if (this.starting) {
            LoadingProgress().width(16).height(16).color(ACCENT_INK)
          } else {
            Icon({ name: 'arrow_up', iconSize: 20, color: this.canSend() ? ACCENT_INK : INK2 })
          }
        }
        .width(44).height(44).borderRadius(999)
        .backgroundColor(this.canSend() ? this.accentColorStore : SURFACE2)
        .border({ width: 1, color: this.canSend() ? this.accentColorStore : LINE })
        .justifyContent(FlexAlign.Center)
        .enabled(this.canSend() && !this.starting)
        .onClick((): void => {
          if (this.runId.length > 0 && this.finished) {
            // 已完成房间内再发送 = 开新议会
            const nextObjective: string = this.objective;
            this.resetRoom();
            this.objective = nextObjective;
          }
          this.startCouncil();
        })
        .accessibilityText('开始议会')
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 12 })
    .backgroundColor(BG)
  }

  private canSend(): boolean {
    return this.objective.trim().length > 0 && !this.generatingSeats;
  }

  @Builder
  Transcript(): void {
    Scroll() {
      Column() {
        if (this.errorMsg.length > 0) {
          Text(this.errorMsg).fontSize(13).fontFamily(FONT_SANS).fontColor('#E53935')
            .width('100%').margin({ bottom: 12 })
        }
        if (this.runId.length === 0) {
          // setup:席位/工具配置
          this.SetupPanel()
        } else {
          this.UserBubble(this.runObjective)
          // 席位发言(按 turn 顺序)
          ForEach(this.turns, (t: ModelCouncilTurn) => {
            this.SeatRow(t.seatName, t.role, this.mode === 'debate' ? `第 ${t.round} 轮` : '',
              t.content.length > 0 ? t.content : (t.error.length > 0 ? `（${t.error}）` : '（无输出）'), false)
          }, (t: ModelCouncilTurn, i: number) => `${t.seatId}-${t.round}-${t.status}-${i}`)
          // 流式中的席位行
          ForEach(this.seats, (s: ModelCouncilSeat) => {
            if (this.liveRowText(s.seatId).length > 0) {
              this.SeatRow(s.name, s.role, '', this.liveRowText(s.seatId), true)
            }
          }, (s: ModelCouncilSeat) => `live-${s.seatId}`)
          // 工具快照(含审批)
          if (this.approvalError.length > 0) {
            Text(this.approvalError).fontSize(12).fontFamily(FONT_SANS).fontColor(INK3)
              .width('100%').margin({ bottom: 8 })
          }
          ForEach(this.toolSnapshots, (snapshot: CouncilToolSnapshot): void => {
            Text(this.toolRoundLabel(snapshot)).fontSize(12).fontFamily(FONT_SANS).fontColor(INK3)
              .width('100%').margin({ top: 4, bottom: 8 })
            CouncilSeatTools({ snapshot: snapshot, active: this.toolsActive(snapshot),
              onVerdict: (request: CouncilApprovalRequest, verdict: CouncilToolVerdict): void => {
                this.submitVerdict(request, verdict);
              } })
          }, (snapshot: CouncilToolSnapshot): string => `${snapshot.key.round}-${snapshot.key.seatId}`)
          // 综合裁决
          if (this.finalResult.length > 0 || this.resultError.length > 0 || this.synthesisLiveText().length > 0
            || (!this.finished && this.progressText === '综合裁决中…')) {
            this.SynthesisCard()
          }
          if (this.finished) {
            Row() {
              Text(`议会${this.statusText}`)
                .fontSize(12).fontFamily(FONT_SANS).fontColor(INK4)
              Text(' · ')
                .fontSize(12).fontColor(INK4)
              Text('开新议会')
                .fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
                .padding({ top: 6, bottom: 6 })
                .onClick((): void => { this.resetRoom(); })
            }
            .width('100%').justifyContent(FlexAlign.Center).margin({ top: 4, bottom: 8 })
          }
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 4, bottom: 12 })
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
    .scrollBar(BarState.Off)
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      this.Header()
      this.Transcript()
      this.Composer()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
