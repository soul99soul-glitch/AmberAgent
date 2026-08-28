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
import { INK, INK3, ACCENT, SURFACE2, BG, ERROR, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS_SEMIBOLD, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, ToggleRow, SelectRow, PaperSeparator, PageSpacer, BtnGhost } from '../components/PageTemplate.ets';

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
      PageTitleBar({ title: 'Model Council 设置' })

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '运行参数' })
            this.toggleRow('启用 Model Council', '多模型合议:对关键决策获取多视角对比', this.councilEnabled, ((): void => {
              this.councilEnabled = !this.councilEnabled;
              this.saveSetting();
            }), false)

            this.sliderRow('最大席位数', `${this.maxSeats}`, 2, 8, this.maxSeats, (v: number): void => {
              this.maxSeats = Math.round(v);
              this.saveSetting();
            }, false)

            this.sliderRow('默认轮次', `${this.defaultRounds}`, 1, 5, this.defaultRounds, (v: number): void => {
              this.defaultRounds = Math.round(v);
              this.saveSetting();
            }, false)

            this.sliderRow('最大轮次', `${this.maxRounds}`, 1, 10, this.maxRounds, (v: number): void => {
              this.maxRounds = Math.round(v);
              this.saveSetting();
            }, false)

            this.selectRow('席位超时', this.timeoutLabel(this.seatTimeoutMs), ((): void => {
              const opts: number[] = [60000, 120000, 180000, 300000];
              const idx: number = opts.indexOf(this.seatTimeoutMs);
              this.seatTimeoutMs = opts[(idx + 1) % opts.length];
              this.saveSetting();
            }), false)

            this.selectRow('总超时', this.timeoutLabel(this.totalTimeoutMs), ((): void => {
              const opts: number[] = [180000, 300000, 600000, 900000];
              const idx: number = opts.indexOf(this.totalTimeoutMs);
              this.totalTimeoutMs = opts[(idx + 1) % opts.length];
              this.saveSetting();
            }), false)

            this.toggleRow('显示席位输出', '在结果中展示各席位的独立输出', this.showSeatOutputs, ((): void => {
              this.showSeatOutputs = !this.showSeatOutputs;
              this.saveSetting();
            }), true)

            // M3.3:席位编辑
            SectionHeader({ label: '席位编辑' })

            ForEach(this.seats, (seat: ModelCouncilSeat, idx: number): void => {
              this.seatEditorRow(seat, idx === this.seats.length - 1)
            }, (seat: ModelCouncilSeat): string => seat.seatId)

            // 添加席位
            if (this.seats.length < this.maxSeats) {
              Row() {
                Text('+ 添加视角席')
                  .fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
                  .fontFamily(FONT_SANS_SEMIBOLD).fontColor(ACCENT).layoutWeight(1)
                Text(this.showAddSeatMenu ? '▾' : '▸')
                  .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                  .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
              }
              .width('100%').constraintSize({ minHeight: 64 })
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .onClick((): void => { this.showAddSeatMenu = !this.showAddSeatMenu; })

              if (this.showAddSeatMenu) {
                PaperSeparator()
                Column() {
                  ForEach(LENS_PRESETS, (preset: ModelCouncilRolePreset, idx: number): void => {
                    Row() {
                      Text(preset.name)
                        .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
                        .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
                        .fontColor(INK).layoutWeight(1)
                      Text('添加')
                        .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                        .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
                    }
                    .width('100%').constraintSize({ minHeight: 64 })
                    .padding({ left: 32, right: 16, top: 10, bottom: 10 })
                    .onClick((): void => { this.addLensSeat(preset.id); })
                    if (idx < LENS_PRESETS.length - 1) {
                      PaperSeparator()
                    }
                  }, (preset: ModelCouncilRolePreset): string => preset.id)
                }
                .width('100%').backgroundColor(SURFACE2)
              }
            } else {
              Text(`已达最大席位数 ${this.maxSeats}`)
                .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
                .padding({ left: 16, top: 8, bottom: 8 })
            }
            PageSpacer()
          }
          .width('100%').padding({ bottom: 20 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void, isLast: boolean): void {
    ToggleRow({ label: label, desc: desc, checked: checked, isLast: isLast, onToggle: onToggle })
  }

  @Builder sliderRow(label: string, valueText: string, min: number, max: number, value: number,
    onChange: (v: number) => void, isLast: boolean): void {
    Column() {
      Row() {
        Text(label)
          .fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK).layoutWeight(1)
        Text(valueText)
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
      }
      .width('100%')
      Slider({ value: value, min: min, max: max, step: 1 })
        .width('100%').margin({ top: 4 })
        .onChange((v: number): void => { onChange(v); })
    }
    .width('100%').constraintSize({ minHeight: 64 })
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    if (!isLast) {
      PaperSeparator()
    }
  }

  @Builder selectRow(label: string, valueText: string, onClick: () => void, isLast: boolean): void {
    SelectRow({ label: label, valueText: valueText, isLast: isLast, onRowClick: onClick })
  }

  // M3.3:单个席位编辑行(展开 → name + systemPrompt + 删除)
  @Builder seatEditorRow(seat: ModelCouncilSeat, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(seat.name.length > 0 ? seat.name : '(未命名席位)')
            .fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(isCoreRole(seat.role) ? '核心席' : `视角席 · ${seat.role}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.expandedSeatId === seat.seatId ? '▾' : '▸')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
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
          Text('席位名称')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ bottom: 4 })
          TextInput({ text: seat.name, placeholder: '席位名称' })
            .width('100%').fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatName(seat.seatId, v); })

          Text('系统提示词')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ top: 10, bottom: 4 })
          TextArea({ text: seat.systemPrompt, placeholder: '系统提示词' })
            .width('100%').height(100).fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatPrompt(seat.seatId, v); })

          if (!isCoreRole(seat.role)) {
            Text('删除席位')
              .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
              .fontColor(ERROR).margin({ top: 10 })
              .onClick((): void => { this.deleteSeat(seat.seatId); })
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 12 })
      }
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
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
    const seat: ModelCouncilSeat | undefined = this.seats.find(
      (s: ModelCouncilSeat): boolean => s.seatId === seatId);
    if (seat === undefined) return;
    if (isCoreRole(seat.role)) return;
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
