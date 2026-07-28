// CouncilPage — 模型议会配置页:议题 / 模式 / 轮数 / 视角席 → 发起运行
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getModelRegistry, prepareCouncilManager } from '../di/AppContainer.ets';
import { LENS_PRESETS, CORE_SEATS, makeRuntimeSetting } from '@amber/deepread-domain';
import type { ModelConfig, ModelCouncilRolePreset, CouncilTaskInput, ModelCouncilMode } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Council';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const INK4: string = '#B6B1A4';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

const ROUND_OPTIONS: number[] = [2, 3, 4, 5];

@Entry
@Component
struct CouncilPage {
  @State objective: string = '';
  @State mode: ModelCouncilMode = 'compare';
  @State rounds: number = 2;
  @State selectedLens: string[] = [];
  @State models: ModelConfig[] = [];
  @State errorMsg: string = '';
  @State starting: boolean = false;

  async aboutToAppear(): Promise<void> {
    this.models = await getModelRegistry().list();
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

  async startCouncil(): Promise<void> {
    if (this.starting) return;
    if (this.objective.trim().length === 0) {
      this.errorMsg = '请输入议题';
      return;
    }
    if (this.models.length === 0) {
      this.errorMsg = '请先到 设置 → 模型库 添加至少一个模型';
      return;
    }
    this.errorMsg = '';
    this.starting = true;
    try {
      const setting = makeRuntimeSetting({ defaultRounds: this.rounds, maxRounds: 5 });
      const mgr = await prepareCouncilManager(setting);
      const input: CouncilTaskInput = {
        mode: this.mode,
        objective: this.objective,
        rounds: this.rounds,
        extraLens: this.selectedLens,
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
        Text('←').fontSize(18).fontColor(INK3).onClick(() => { router.back(); })
        Text('模型议会').fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif').margin({ left: 12 })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
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
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE }).margin({ bottom: 20 })

          // 模型库状态
          Column() {
            this.sectionTitle('模型', '席位轮流使用')
            if (this.models.length === 0) {
              Text('模型库为空。请到 设置 → 模型库 添加模型。')
                .fontSize(13).fontColor('#E53935')
            } else {
              ForEach(this.models, (m: ModelConfig) => {
                Row() {
                  Text(m.label.length > 0 ? m.label : m.model).fontSize(14).fontColor(INK).layoutWeight(1)
                  Text(m.model).fontSize(11).fontColor(INK3)
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
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
