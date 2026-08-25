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
import type { ModelCouncilRuntimeSetting, ModelCouncilSeat, ModelCouncilRolePreset } from '@amber/deepread-domain';
import {
  LENS_PRESETS, findRolePreset, isCoreRole, makeSeat,
} from '@amber/deepread-domain';
import { INK, INK3, ACCENT, SURFACE, SURFACE2, LINE, BG, ERROR, TITLE, BODY, META, LABEL } from '../design/tokens.ets';

@Entry
@Component
struct SettingModelCouncilPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State councilEnabled: boolean = true;
  @State maxSeats: number = 5;
  @State defaultRounds: number = 1;
  @State maxRounds: number = 3;
  @State seatTimeoutMs: number = 120000;
  @State totalTimeoutMs: number = 300000;
  @State outputBudgetChars: number = 200000;
  @State showSeatOutputs: boolean = true;
  // M3.3:席位编辑
  @State seats: ModelCouncilSeat[] = [];
  @State expandedSeatId: string = '';
  @State showAddSeatMenu: boolean = false;
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.loadSetting();
  }

  private async loadSetting(): Promise<void> {
    try {
      const port = getAgentPromptSettingsPort();
      const setting: ModelCouncilRuntimeSetting = await port.getCouncilSetting();
      this.councilEnabled = setting.enabled;
      this.maxSeats = setting.maxSeats;
      this.defaultRounds = setting.defaultRounds;
      this.maxRounds = setting.maxRounds;
      this.seatTimeoutMs = setting.seatTimeoutMs;
      this.totalTimeoutMs = setting.totalTimeoutMs;
      this.outputBudgetChars = setting.outputBudgetChars;
      this.showSeatOutputs = setting.showSeatOutputs;
      // M3.3:加载席位
      this.seats = setting.defaultSeats.map((s: ModelCouncilSeat): ModelCouncilSeat => ({
        seatId: s.seatId, name: s.name, role: s.role, modelId: s.modelId,
        systemPrompt: s.systemPrompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature,
      }));
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
        enabled: this.councilEnabled,
        defaultSeats: this.seats,
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
      .width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 16, bottom: 8 })

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.toggleRow('启用 Model Council', '多模型合议:对关键决策获取多视角对比', this.councilEnabled, ((): void => {
              this.councilEnabled = !this.councilEnabled;
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

            // M3.3:席位编辑
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 席位编辑').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 20, bottom: 8 })

            ForEach(this.seats, (seat: ModelCouncilSeat): void => {
              this.seatEditorRow(seat)
            }, (seat: ModelCouncilSeat): string => seat.seatId)

            // 添加席位
            if (this.seats.length < this.maxSeats) {
              Row() {
                Text('+ 添加视角席').fontSize(BODY).fontColor(ACCENT).layoutWeight(1)
                Text(this.showAddSeatMenu ? '▾' : '▸').fontSize(LABEL).fontColor(INK3)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.showAddSeatMenu = !this.showAddSeatMenu; })

              if (this.showAddSeatMenu) {
                Column() {
                  ForEach(LENS_PRESETS, (preset: ModelCouncilRolePreset): void => {
                    Row() {
                      Text(preset.name).fontSize(BODY).fontColor(INK).layoutWeight(1)
                      Text('添加').fontSize(META).fontColor(ACCENT)
                    }
                    .width('100%')
                    .padding({ left: 24, right: 16, top: 10, bottom: 10 })
                    .backgroundColor(SURFACE2)
                    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                    .onClick((): void => { this.addLensSeat(preset.id); })
                  }, (preset: ModelCouncilRolePreset): string => preset.id)
                }
                .width('100%').backgroundColor(SURFACE2)
              }
            } else {
              Text(`已达最大席位数 ${this.maxSeats}`).fontSize(LABEL).fontColor(INK3)
                .padding({ left: 16, top: 8, bottom: 8 })
            }
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
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
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
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder selectRow(label: string, valueText: string, onClick: () => void): void {
    Row() {
      Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
      Text(valueText).fontSize(META).fontColor(ACCENT)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { onClick(); })
  }

  // M3.3:单个席位编辑行(展开 → name + systemPrompt + 删除)
  @Builder seatEditorRow(seat: ModelCouncilSeat): void {
    Column() {
      Row() {
        Column() {
          Text(seat.name.length > 0 ? seat.name : '(未命名席位)')
            .fontSize(BODY).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(isCoreRole(seat.role) ? '核心席' : `视角席 · ${seat.role}`)
            .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.expandedSeatId === seat.seatId ? '▾' : '▸').fontSize(LABEL).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 14, bottom: 14 })
      .backgroundColor(SURFACE)
      .onClick((): void => {
        if (this.expandedSeatId === seat.seatId) {
          this.saveSetting();
          this.expandedSeatId = '';
        } else {
          this.expandedSeatId = seat.seatId;
        }
      })

      if (this.expandedSeatId === seat.seatId) {
        Column() {
          Text('席位名称').fontSize(LABEL).fontColor(INK3).width('100%').margin({ bottom: 4 })
          TextInput({ text: seat.name, placeholder: '席位名称' })
            .width('100%').fontSize(BODY).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatName(seat.seatId, v); })

          Text('系统提示词').fontSize(LABEL).fontColor(INK3)
            .width('100%').margin({ top: 10, bottom: 4 })
          TextArea({ text: seat.systemPrompt, placeholder: '系统提示词' })
            .width('100%').height(100).fontSize(BODY).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatPrompt(seat.seatId, v); })

          if (!isCoreRole(seat.role)) {
            Text('删除席位').fontSize(META).fontColor(ERROR).margin({ top: 10 })
              .onClick((): void => { this.deleteSeat(seat.seatId); })
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 12 })
        .backgroundColor(SURFACE)
      }
    }
    .width('100%')
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  // M3.3:更新席位 name
  private updateSeatName(seatId: string, name: string): void {
    this.seats = this.seats.map((s: ModelCouncilSeat): ModelCouncilSeat =>
      s.seatId === seatId ? { seatId: s.seatId, name, role: s.role, modelId: s.modelId,
        systemPrompt: s.systemPrompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature } : s,
    );
  }

  // M3.3:更新席位 systemPrompt
  private updateSeatPrompt(seatId: string, prompt: string): void {
    this.seats = this.seats.map((s: ModelCouncilSeat): ModelCouncilSeat =>
      s.seatId === seatId ? { seatId: s.seatId, name: s.name, role: s.role, modelId: s.modelId,
        systemPrompt: prompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature } : s,
    );
  }

  // M3.3:删除席位(核心席不可删)
  private deleteSeat(seatId: string): void {
    if (isCoreRole(seatId)) return;
    this.seats = this.seats.filter((s: ModelCouncilSeat): boolean => s.seatId !== seatId);
    this.saveSetting();
  }

  // M3.3:添加视角席(从 LENS_PRESETS 选)
  private addLensSeat(presetId: string): void {
    const preset = findRolePreset(presetId);
    if (preset === null) return;
    if (this.seats.length >= this.maxSeats) return;
    const seat: ModelCouncilSeat = makeSeat({
      name: preset.name, role: preset.id, systemPrompt: preset.prompt,
    });
    this.seats = [...this.seats, seat];
    this.showAddSeatMenu = false;
    this.saveSetting();
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
