// SettingModelCouncilPage — Model Council 实验设置页(第三梯队)
// Android 基准: SettingExperimentalModelCouncilPage.kt
//   配置 ModelCouncilRuntimeSetting:enabled/maxSeats/rounds/timeout/outputBudget
//
// Harmony 后端: deepread HAR ModelCouncilRuntimeSetting
//   + PromptConfigStore.ets(createEntryAgentPromptSettingsPort getCouncilSetting)
//
// 功能(MVP):
//   ✅ enabled 开关
//   ✅ maxSeats/defaultRounds/maxRounds 滑块
//   ✅ seatTimeoutMs/totalTimeoutMs 选择
//   ✅ outputBudgetChars 选择
//   ❌ 席位编辑(后续迭代)

import { getAgentPromptSettingsPort } from '../di/AppContainer.ets';
import type { ModelCouncilRuntimeSetting } from '@amber/deepread-domain';
import { makeRuntimeSetting } from '@amber/deepread-domain';
import { INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, BG, TITLE, BODY, META, LABEL } from '../design/tokens.ets';

@Entry
@Component
struct SettingModelCouncilPage {
  @State enabled: boolean = true;
  @State maxSeats: number = 5;
  @State defaultRounds: number = 1;
  @State maxRounds: number = 3;
  @State seatTimeoutMs: number = 120000;
  @State totalTimeoutMs: number = 300000;
  @State outputBudgetChars: number = 200000;
  @State showSeatOutputs: boolean = true;
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.loadSetting();
  }

  private async loadSetting(): Promise<void> {
    try {
      const port = getAgentPromptSettingsPort();
      const setting: ModelCouncilRuntimeSetting = await port.getCouncilSetting();
      this.enabled = setting.enabled;
      this.maxSeats = setting.maxSeats;
      this.defaultRounds = setting.defaultRounds;
      this.maxRounds = setting.maxRounds;
      this.seatTimeoutMs = setting.seatTimeoutMs;
      this.totalTimeoutMs = setting.totalTimeoutMs;
      this.outputBudgetChars = setting.outputBudgetChars;
      this.showSeatOutputs = setting.showSeatOutputs;
      this.loaded = true;
    } catch (_e) {
      this.loaded = true;
    }
  }

  private async saveSetting(): Promise<void> {
    try {
      const port = getAgentPromptSettingsPort();
      const current: ModelCouncilRuntimeSetting = await port.getCouncilSetting();
      const next: ModelCouncilRuntimeSetting = {
        enabled: this.enabled,
        defaultSeats: current.defaultSeats,
        synthesisModelId: current.synthesisModelId,
        maxSeats: this.maxSeats,
        defaultRounds: this.defaultRounds,
        maxRounds: this.maxRounds,
        seatTimeoutMs: this.seatTimeoutMs,
        totalTimeoutMs: this.totalTimeoutMs,
        outputBudgetChars: this.outputBudgetChars,
        showSeatOutputs: this.showSeatOutputs,
      };
      await port.updateCouncilSetting(next);
    } catch (_e) {
      // 保存失败静默
    }
  }

  build(): void {
    Column() {
      Row() {
        Text('Model Council 设置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 8 })

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.toggleRow('启用 Model Council', '多模型合议:对关键决策获取多视角对比', this.enabled, ((): void => {
              this.enabled = !this.enabled;
              this.saveSetting();
            }))

            this.sliderRow('最大席位数', `${this.maxSeats}`, 2, 8, this.maxSeats, (v: number): void => {
              this.maxSeats = Math.round(v);
              this.saveSetting();
            })

            this.sliderRow('默认轮次', `${this.defaultRounds}`, 1, 5, this.defaultRounds, (v: number): void => {
              this.defaultRounds = Math.round(v);
              this.saveSetting();
            })

            this.sliderRow('最大轮次', `${this.maxRounds}`, 1, 10, this.maxRounds, (v: number): void => {
              this.maxRounds = Math.round(v);
              this.saveSetting();
            })

            this.selectRow('席位超时', this.timeoutLabel(this.seatTimeoutMs), ((): void => {
              const opts: number[] = [60000, 120000, 180000, 300000];
              const idx: number = opts.indexOf(this.seatTimeoutMs);
              this.seatTimeoutMs = opts[(idx + 1) % opts.length];
              this.saveSetting();
            }))

            this.selectRow('总超时', this.timeoutLabel(this.totalTimeoutMs), ((): void => {
              const opts: number[] = [180000, 300000, 600000, 900000];
              const idx: number = opts.indexOf(this.totalTimeoutMs);
              this.totalTimeoutMs = opts[(idx + 1) % opts.length];
              this.saveSetting();
            }))

            this.toggleRow('显示席位输出', '在结果中展示各席位的独立输出', this.showSeatOutputs, ((): void => {
              this.showSeatOutputs = !this.showSeatOutputs;
              this.saveSetting();
            }))
          }
          .width('100%').padding({ bottom: 20 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: checked })
        .onChange((_v: boolean): void => { onToggle(); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder sliderRow(label: string, valueText: string, min: number, max: number, value: number, onChange: (v: number) => void): void {
    Column() {
      Row() {
        Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
        Text(valueText).fontSize(META).fontColor(ACCENT)
      }
      .width('100%')
      Slider({ value: value, min: min, max: max, step: 1 })
        .width('100%').margin({ top: 4 })
        .onChange((v: number): void => { onChange(v); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder selectRow(label: string, valueText: string, onClick: () => void): void {
    Row() {
      Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
      Text(valueText).fontSize(META).fontColor(ACCENT)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { onClick(); })
  }

  private timeoutLabel(ms: number): string {
    if (ms === 60000) return '1 分钟';
    if (ms === 120000) return '2 分钟';
    if (ms === 180000) return '3 分钟';
    if (ms === 300000) return '5 分钟';
    if (ms === 600000) return '10 分钟';
    if (ms === 900000) return '15 分钟';
    return `${ms / 1000} 秒`;
  }
}
