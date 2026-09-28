// CouncilPage — 模型议会配置页:议题 / 模式 / 轮数 / 视角席 → 发起运行
import { ACCENT,BG,INK,INK2,INK3,INK4,LINE,SURFACE,SURFACE2,TITLE } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getModelRegistry, prepareCouncilManager, getCouncilRunner } from '../di/AppContainer.ets';
import { LENS_PRESETS, CORE_SEATS, makeRuntimeSetting, makeSeat } from '@amber/deepread-domain';
import type { ModelCouncilRuntimeSetting } from '@amber/deepread-domain';
import { getAgentPromptSettingsPort } from '../di/AppContainer.ets';
import type { ModelConfig, ModelCouncilRolePreset, CouncilTaskInput, ModelCouncilMode, ModelCouncilSeat } from '@amber/deepread-domain';

// D5-3:动态席位 LLM 返回文档
interface DynamicSeatDoc {
  name?: string;
  role?: string;
  systemPrompt?: string;
}

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Council';


const ROUND_OPTIONS: number[] = [2, 3, 4, 5];

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
  return init;
};

@Entry
@Component
struct CouncilPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State objective: string = '';
  @State mode: ModelCouncilMode = 'compare';
  @State rounds: number = 2;
  @State selectedLens: string[] = [];
  @State models: ModelConfig[] = [];
  @State errorMsg: string = '';
  @State starting: boolean = false;
  // F42:区分「尚未加载」与「已加载且为空」,加载前不再误报模型库为空
  @State modelsLoaded: boolean = false;
  // D5-3:动态席位(AI 生成,仅本轮;modelId 留空由 assignModels 分发)
  @State generatedSeats: ModelCouncilSeat[] = [];
  @State generatingSeats: boolean = false;
  @State dynamicSeatError: string = '';
  private pageAlive: boolean = false;
  private loadToken: number = 0;

  private persistedSetting: ModelCouncilRuntimeSetting | null = null;

  aboutToAppear(): void {
    this.pageAlive = true;
    const token: number = ++this.loadToken;
    this.loadModels(token).catch((e: Error): void => {
      if (this.pageAlive && token === this.loadToken) this.errorMsg = String(e);
    });
    // 读取持久化 council 设置(enabled 门/席位/预算/超时/裁判模型)
    getAgentPromptSettingsPort().getCouncilSetting().then((persisted: ModelCouncilRuntimeSetting): void => {
      if (this.pageAlive && token === this.loadToken) this.persistedSetting = persisted;
    }).catch((): void => {});
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.loadToken++;
  }

  private async loadModels(token: number): Promise<void> {
    const models: ModelConfig[] = await getModelRegistry().list();
    if (this.pageAlive && token === this.loadToken) {
      this.models = models;
      this.modelsLoaded = true;
    }
  }

  toggleLens(id: string): void {
    const next: string[] = [];
    let found: boolean = false;
    for (let i = 0; i < this.selectedLens.length; i++) {
      if (this.selectedLens[i] === id) { found = true; } else { next.push(this.selectedLens[i]); }
    }
    if (!found) next.push(id);
    this.selectedLens = next;
  }

  // D5-3:生成动态席位(单 LLM 调用,JSON 数组解析;role 去重追加)
  private async generateDynamicSeats(): Promise<void> {
    const objective: string = this.objective.trim();
    if (objective.length === 0 || this.models.length === 0 || this.generatingSeats) return;
    this.generatingSeats = true;
    this.dynamicSeatError = '';
    try {
      const model: ModelConfig = this.models[0];
      const result = await getCouncilRunner().generate({
        model: model,
        systemPrompt: '你是多模型议会的组委。根据用户议题生成 2-3 个互补的评审视角席位,'
          + '只输出 JSON 数组:[{"name":"不超过6字","role":"不超过12字",'
          + '"systemPrompt":"该视角的评审指令(不超过80字)"}],不要输出任何解释。'
          + '视角之间必须有明显差异(如 成本/风险/用户/技术/伦理/市场)。',
        userPrompt: objective,
        outputBudgetChars: 900,
        reasoningLevel: null,
        temperature: null,
        onChunk: (): void => {},
        signal: null,
      });
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
        if (added >= 3) break;
      }
      if (added === 0) throw new Error('未解析出可用席位');
      this.generatedSeats = next;
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.dynamicSeatError = `生成失败:${msg}`;
    } finally {
      this.generatingSeats = false;
    }
  }

  async startCouncil(): Promise<void> {
    if (this.starting) return;
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
    try {
      // 持久化设置为基础,本轮 UI 选择的轮数覆盖;禁用态直接拦截。
      // 读取未完成时先 await(否则可用默认 enabled=true 绕过禁用开关)
      const base: ModelCouncilRuntimeSetting = this.persistedSetting !== null
        ? this.persistedSetting
        : await getAgentPromptSettingsPort().getCouncilSetting();
      if (!base.enabled) {
        this.errorMsg = '模型议会已在设置中关闭';
        return;
      }
      const init: Record<string, Object | null> = baseToInit(base);
      init['defaultRounds'] = this.rounds;
      init['maxRounds'] = Math.max(base.maxRounds, this.rounds);
      const setting: ModelCouncilRuntimeSetting = makeRuntimeSetting(init);
      const mgr = await prepareCouncilManager(setting);
      const input: CouncilTaskInput = {
        mode: this.mode,
        objective: this.objective,
        rounds: this.rounds,
        extraLens: this.selectedLens,
        extraSeats: this.generatedSeats,
      };
      const run = mgr.start(input);
      hilog.info(DOMAIN, TAG, 'council started runId=%{public}s', run.runId);
      const params: Record<string, string> = { 'runId': run.runId };
      router.pushUrl({ url: 'pages/CouncilRunPage', params: params });
    } catch (e) {
      this.errorMsg = String(e);
      hilog.error(DOMAIN, TAG, 'startCouncil failed: %{public}s', String(e));
    } finally {
      this.starting = false;
    }
  }

  @Builder
  sectionTitle(text: string, hint: string): void {
    Row() {
      Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + text).fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
      Blank()
      if (hint.length > 0) Text(hint).fontSize(11).fontColor(INK4)
    }
    .width('100%').margin({ bottom: 14 })
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Icon({ name: 'back', iconSize: 20, color: INK3 }).onClick(() => { router.back(); })
        Text('模型议会').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily(FONT_SERIF).margin({ left: 12 })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .backgroundColor(BG)

      Scroll() {
        Column() {
          // 议题
          Column() {
            this.sectionTitle('议题', '')
            TextArea({ text: this.objective, placeholder: '例如：是否应该把搜索功能改为订阅制？' })
              .height(110).fontSize(15).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding(14)
              .onChange((v: string) => { this.objective = v; })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE }).margin({ bottom: 20 })

          // 模式
          Column() {
            this.sectionTitle('模式', '')
            Row() {
              Text('对比')
                .fontSize(14).padding({ left: 18, right: 18, top: 10, bottom: 10 }).borderRadius(20)
                .fontColor(this.mode === 'compare' ? '#FFFFFF' : INK2)
                .backgroundColor(this.mode === 'compare' ? ACCENT : SURFACE2)
                .onClick(() => { this.mode = 'compare'; })
              Text('辩论')
                .fontSize(14).padding({ left: 18, right: 18, top: 10, bottom: 10 }).borderRadius(20)
                .fontColor(this.mode === 'debate' ? '#FFFFFF' : INK2)
                .backgroundColor(this.mode === 'debate' ? ACCENT : SURFACE2)
                .margin({ left: 12 })
                .onClick(() => { this.mode = 'debate'; })
              Blank()
                Text(this.mode === 'compare' ? '各席独立作答一轮' : '多轮交锋后裁决')
                .fontSize(11).fontColor(INK3)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .width('100%')

            if (this.mode === 'debate') {
              Row() {
                Text('轮数').fontSize(13).fontColor(INK3).margin({ right: 12 })
                ForEach(ROUND_OPTIONS, (r: number) => {
                  Text(r.toString())
                    .fontSize(13).width(36).textAlign(TextAlign.Center)
                    .padding({ top: 8, bottom: 8 }).borderRadius(8)
                    .fontColor(this.rounds === r ? '#FFFFFF' : INK2)
                    .backgroundColor(this.rounds === r ? ACCENT : SURFACE2)
                    .margin({ right: 8 })
                    .onClick(() => { this.rounds = r; })
                }, (r: number) => r.toString())
              }
              .width('100%').margin({ top: 16 })
            }
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE }).margin({ bottom: 20 })

          // 席位
          Column() {
            this.sectionTitle('席位', '核心席自动加入')
            Row() {
              ForEach(CORE_SEATS, (p: ModelCouncilRolePreset) => {
                Text(p.name)
                  .fontSize(12).padding({ left: 12, right: 12, top: 6, bottom: 6 }).borderRadius(14)
                  .fontColor(ACCENT).backgroundColor('#FFF5F0').margin({ right: 8 })
              }, (p: ModelCouncilRolePreset) => p.id)
            }
            .width('100%').margin({ bottom: 12 })

            Text('可选视角席').fontSize(12).fontColor(INK3).margin({ bottom: 8 })
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(LENS_PRESETS, (p: ModelCouncilRolePreset) => {
                Text(p.name)
                  .fontSize(13).padding({ left: 14, right: 14, top: 8, bottom: 8 }).borderRadius(18)
                  .fontColor(this.selectedLens.indexOf(p.id) >= 0 ? '#FFFFFF' : INK2)
                  .backgroundColor(this.selectedLens.indexOf(p.id) >= 0 ? ACCENT : SURFACE2)
                  .margin({ right: 8, bottom: 8 })
                  .onClick(() => { this.toggleLens(p.id); })
              }, (p: ModelCouncilRolePreset) => p.id)
            }
            .width('100%')

            // D5-3:动态席位(AI 按议题生成自定义视角席,仅本轮)
            Row() {
              Text('动态席位').fontSize(12).fontColor(INK3).layoutWeight(1)
              if (this.generatingSeats) {
                LoadingProgress().width(14).height(14).color(ACCENT).margin({ right: 6 })
              }
              Text(this.generatingSeats ? '生成中…' : 'AI 生成')
                .fontSize(13).fontColor(this.objective.trim().length === 0 || this.models.length === 0
                  ? INK4 : ACCENT)
                .padding({ left: 12, right: 4, top: 6, bottom: 6 })
                .onClick((): void => { this.generateDynamicSeats(); })
            }
            .width('100%').margin({ top: 14 })
            if (this.generatedSeats.length > 0) {
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(this.generatedSeats, (s: ModelCouncilSeat) => {
                  Row() {
                    Text(`${s.name} · ${s.role}`)
                      .fontSize(12).fontColor(ACCENT)
                    Text(' ×').fontSize(12).fontColor(INK3)
                  }
                  .padding({ left: 12, right: 10, top: 6, bottom: 6 })
                  .backgroundColor('#FFF5F0').borderRadius(14)
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
              Text(this.dynamicSeatError).fontSize(11).fontColor('#E53935').width('100%')
            }
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE }).margin({ bottom: 20 })

          // 模型库状态
          Column() {
            this.sectionTitle('模型', '席位轮流使用')
            if (!this.modelsLoaded) {
              Row() {
                LoadingProgress().width(16).height(16).color(ACCENT).margin({ right: 8 })
                Text('正在加载模型库…').fontSize(13).fontColor(INK3)
              }
            } else if (this.models.length === 0) {
              Text('模型库为空。请到 设置 → 模型库 添加模型。')
                .fontSize(13).fontColor('#E53935')
            } else {
              ForEach(this.models, (m: ModelConfig) => {
                Row() {
                  Text(m.label.length > 0 ? m.label : m.model).fontSize(14).fontColor(INK)
                    .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(m.model).fontSize(11).fontColor(INK3)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .width('100%').padding({ top: 8, bottom: 8 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              }, (m: ModelConfig) => m.id)
            }
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE }).margin({ bottom: 20 })

          if (this.errorMsg.length > 0) {
            Text(this.errorMsg).fontSize(13).fontColor('#E53935').margin({ bottom: 12 })
          }

          // 开始按钮
          Column() {
            Row() {
              if (this.starting) LoadingProgress().width(16).height(16).color('#FFFFFF').margin({ right: 8 })
              Text(this.starting ? '发起中…' : '开始议会').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF')
            }
            .justifyContent(FlexAlign.Center)
          }
          .width('70%').height(48).borderRadius(12).backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .opacity(this.starting ? 0.6 : 1)
          .onClick(() => { this.startCouncil(); })
          .margin({ bottom: 32 })
        }
        .width('100%').padding({ left: 20, right: 20, top: 8 })
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
