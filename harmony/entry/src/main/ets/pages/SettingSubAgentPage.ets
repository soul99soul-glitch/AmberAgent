// SettingSubAgentPage — SubAgent 实验设置页(第三梯队)
// Android 基准: SettingExperimentalSubAgentPage.kt(588 行)
//   配置 SubAgentRuntimeSetting:enabled/mode/allowDynamic/maxConcurrentRuns/
//   maxTurns/timeout/outputBudget + 每个 built-in role 的 override
//
// Harmony 后端: agent_prompt_config.ts(SubAgentRuntimeSetting)
//   + PromptConfigStore.ets(createEntryAgentPromptSettingsPort RMW)
//   + AppContainer getAgentPromptSettingsPort/refreshSubAgentSetting
//
// 功能(MVP):
//   ✅ enabled 开关
//   ✅ mode 选择(roster/smart_dynamic)
//   ✅ maxConcurrentRuns/maxTurns 滑块
//   ✅ timeout/outputBudget 选择
//   ❌ per-role override 编辑(后续迭代)
//   ❌ custom definition 编辑(后续迭代)

import { getAgentPromptSettingsPort, refreshSubAgentSetting } from '../di/AppContainer.ets';
import { router } from '@kit.ArkUI';
import type { SubAgentRuntimeSetting, SubAgentMode, SubAgentOverride, SubAgentDefinition } from '@amber/chat-domain';
import {
  DEFAULT_SUB_AGENT_TIMEOUT_MS, DEFAULT_SUB_AGENT_MAX_TURNS,
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  SUB_AGENT_BUILT_INS,
} from '@amber/chat-domain';
import { INK, INK3, SURFACE2, BG, ERROR, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS_SEMIBOLD, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, ToggleRow, SelectRow, Seg, PaperSeparator, PageSpacer, BtnGhost } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingSubAgentPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State subAgentEnabled: boolean = false;
  @State mode: string = 'roster';
  @State allowDynamic: boolean = true;
  @State maxConcurrentRuns: number = DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS;
  @State maxTurns: number = DEFAULT_SUB_AGENT_MAX_TURNS;
  @State timeoutMs: number = DEFAULT_SUB_AGENT_TIMEOUT_MS;
  @State outputBudgetChars: number = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS;
  // M3.2:per-role overrides(built-in role id → override)
  @State overrides: Record<string, SubAgentOverride> = {};
  @State expandedRoleId: string = '';
  @State loaded: boolean = false;
  // F21:文本编辑未落盘标记 + 显式保存状态(唯一机制:显式保存 + 脏返回确认;
  //   不加 aboutToDisappear flush,也不每键自动保存)
  @State dirty: boolean = false;
  @State saving: boolean = false;
  @State saveError: string = '';

  aboutToAppear(): void {
    this.loadSetting();
  }

  // F21:系统返回手势/物理返回;脏则弹确认(返回 true 消费本次返回)
  onBackPress(): boolean {
    if (this.dirty || this.saving) {
      this.requestBack();
      return true;
    }
    return false;
  }

  private async loadSetting(): Promise<void> {
    try {
      const port = getAgentPromptSettingsPort();
      const setting: SubAgentRuntimeSetting = await port.getSubAgentSetting();
      this.subAgentEnabled = setting.enabled;
      this.mode = setting.mode;
      this.allowDynamic = setting.allowDynamicSubAgents;
      this.maxConcurrentRuns = setting.maxConcurrentRuns;
      this.maxTurns = setting.maxTurns;
      this.timeoutMs = setting.timeoutMs;
      this.outputBudgetChars = setting.outputBudgetChars;
      // M3.2:加载 overrides(Map → Record 便于 @State 响应式)
      const ov: Record<string, SubAgentOverride> = {};
      setting.overrides.forEach((v: SubAgentOverride, k: string): void => { ov[k] = v; });
      this.overrides = ov;
      this.loaded = true;
    } catch (_e) {
      this.loaded = true;
    }
  }

  // F21:返回是否成功,供「保存并返回」在落盘成功后才 back,失败留草稿在页。
  //   注意:saveSetting 写整份 @State,故任何一次成功保存都会把文本编辑一并落盘。
  private async saveSetting(): Promise<boolean> {
    try {
      const port = getAgentPromptSettingsPort();
      const current: SubAgentRuntimeSetting = await port.getSubAgentSetting();
      const next: SubAgentRuntimeSetting = {
        enabled: this.subAgentEnabled,
        mode: this.mode as SubAgentMode,
        allowDynamicSubAgents: this.allowDynamic,
        maxConcurrentRuns: this.maxConcurrentRuns,
        timeoutMs: this.timeoutMs,
        maxTurns: this.maxTurns,
        outputBudgetChars: this.outputBudgetChars,
        // M3.2:写回 overrides(Record → Map,过滤空 override)
        overrides: this.buildOverridesMap(),
        customDefinitions: current.customDefinitions,
      };
      await port.updateSubAgentSetting(next);
      await refreshSubAgentSetting();
      this.dirty = false;
      this.saveError = '';
      return true;
    } catch (_e) {
      // F21:不再静默吞错;失败保留草稿并给出可见反馈
      this.saveError = '保存失败,修改尚未写入,请重试';
      return false;
    }
  }

  // F21:显式「保存」— await 成功才 back;失败留在本页且草稿不丢
  private async saveAndBack(): Promise<void> {
    if (this.saving) return;
    this.saving = true;
    this.saveError = '';
    const ok: boolean = await this.saveSetting();
    this.saving = false;
    if (ok) {
      router.back();
    } else {
      AlertDialog.show({
        title: '保存失败',
        message: '设置未写入存储,草稿已保留,可重试或放弃修改。',
        primaryButton: { value: '知道了', action: (): void => {} },
      });
    }
  }

  // F21:脏返回确认(放弃 / 保存并返回;点外部取消)
  private requestBack(): void {
    if (this.saving) return;
    if (!this.dirty) {
      router.back();
      return;
    }
    AlertDialog.show({
      title: '有未保存的修改',
      message: '角色提示词修改尚未保存,是否保存后返回?',
      primaryButton: {
        value: '放弃修改',
        action: (): void => {
          this.dirty = false;
          router.back();
        },
      },
      secondaryButton: {
        value: '保存并返回',
        action: (): void => { this.saveAndBack(); },
      },
    });
  }

  build(): void {
    Column() {
      PageTitleBar({
        title: '子代理设置',
        actionText: this.saving ? '保存中…' : '保存',
        onAction: (): void => { this.saveAndBack(); },
        onBack: (): void => { this.requestBack(); },
      })

      if (this.saveError.length > 0) {
        Text(this.saveError)
          .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(ERROR)
          .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .backgroundColor(SURFACE2)
      }

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '运行参数' })
            this.settingRow('启用子代理', '允许主 Agent 委派子任务给隔离的子代理', (): void => {
              this.subAgentEnabled = !this.subAgentEnabled;
              this.saveSetting();
            }, this.subAgentEnabled, false)

            this.modeRow(false)

            if (this.mode !== 'smart_dynamic') {
              this.settingRow('允许动态子代理', '允许创建临时自定义子代理角色', (): void => {
                this.allowDynamic = !this.allowDynamic;
                this.saveSetting();
              }, this.allowDynamic, false)
            }

            this.sliderRow('最大并发数', `${this.maxConcurrentRuns}`, 1, 5, this.maxConcurrentRuns, (v: number): void => {
              this.maxConcurrentRuns = Math.round(v);
              this.saveSetting();
            }, false)

            this.sliderRow('最大轮次', `${this.maxTurns}`, 2, 8, this.maxTurns, (v: number): void => {
              this.maxTurns = Math.round(v);
              this.saveSetting();
            }, false)

            this.selectRow('超时时间', this.timeoutLabel(), ((): void => {
              const options: number[] = [60000, 180000, 300000, 600000];
              const idx: number = options.indexOf(this.timeoutMs);
              this.timeoutMs = options[(idx + 1) % options.length];
              this.saveSetting();
            }), false)

            this.selectRow('输出预算', this.outputBudgetLabel(), ((): void => {
              const options: number[] = [8000, 12000, 24000, 48000];
              const idx: number = options.indexOf(this.outputBudgetChars);
              this.outputBudgetChars = options[(idx + 1) % options.length];
              this.saveSetting();
            }), true)

            SectionHeader({ label: '角色提示词覆盖' })

            ForEach(SUB_AGENT_BUILT_INS, (def: SubAgentDefinition, idx: number): void => {
              this.roleOverrideRow(def, idx === SUB_AGENT_BUILT_INS.length - 1)
            }, (def: SubAgentDefinition): string => def.id)
            PageSpacer()
          }
          .width('100%')
          .padding({ bottom: 20 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }

  @Builder settingRow(label: string, desc: string, onToggle: () => void, checked: boolean, isLast: boolean): void {
    ToggleRow({ label: label, desc: desc, checked: checked, isLast: isLast, onToggle: onToggle })
  }

  @Builder modeRow(isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text('模式').fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          Text(this.mode === 'smart_dynamic' ? '智能动态:隐藏内置角色,按需创建临时子代理' : '名册:使用预设的内置子代理角色')
            .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3).margin({ top: 4 }).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Seg({ options: ['名册', '智能动态'], selected: this.mode === 'smart_dynamic' ? 1 : 0,
          onSelect: (idx: number): void => {
            this.mode = idx === 1 ? 'smart_dynamic' : 'roster';
            this.saveSetting();
          } })
      }
      .width('100%').constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  @Builder sliderRow(label: string, valueText: string, min: number, max: number, value: number,
    onChange: (v: number) => void, isLast: boolean): void {
    Column() {
      Row() {
        Text(label).fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK).layoutWeight(1)
        Text(valueText).fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
      }
      .width('100%')
      Slider({ value: value, min: min, max: max, step: 1 }).width('100%').margin({ top: 4 })
        .onChange((v: number): void => { onChange(v); })
    }
    .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
    if (!isLast) {
      PaperSeparator()
    }
  }

  @Builder selectRow(label: string, valueText: string, onClick: () => void, isLast: boolean): void {
    SelectRow({ label: label, valueText: valueText, isLast: isLast, onRowClick: onClick })
  }

  // M3.2:单个内置角色的 override 编辑行(展开 → systemPrompt textarea + 重置)
  @Builder roleOverrideRow(def: SubAgentDefinition, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(def.name)
            .fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          Text(def.description)
            .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3).margin({ top: 4 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.expandedRoleId === def.id ? '▾' : '▸')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .onClick((): void => {
        // M3.2:折叠时保存(展开 → 折叠 = 提交编辑)
        if (this.expandedRoleId === def.id) {
          this.saveSetting();
          this.expandedRoleId = '';
        } else {
          this.expandedRoleId = def.id;
        }
      })

      if (this.expandedRoleId === def.id) {
        Column() {
          Text('自定义提示词(留空 = 继承默认)')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ bottom: 4 })
          TextArea({
            text: this.getOverride(def.id).systemPrompt ?? '',
            placeholder: def.systemPrompt,
          })
            .width('100%').height(120).fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateRolePrompt(def.id, v); })
          Row() {
            BtnGhost({ label: '恢复默认', onTap: (): void => { this.resetRole(def.id); } })
          }
          .width('100%').margin({ top: 8 })
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

  // M3.2:构建 overrides Map(Record → Map;空 override 不写)
  private buildOverridesMap(): Map<string, SubAgentOverride> {
    const m: Map<string, SubAgentOverride> = new Map();
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) {
      const o: SubAgentOverride = this.overrides[k];
      if (o.systemPrompt !== null || o.modelId !== null || o.temperature !== null
        || o.reasoningLevel !== null || o.maxTurnsOverride !== null
        || o.timeoutMsOverride !== null || o.outputBudgetOverride !== null) {
        m.set(k, o);
      }
    }
    return m;
  }

  // M3.2:获取某 role 的 override(无则空 override)
  private getOverride(roleId: string): SubAgentOverride {
    const o: SubAgentOverride | undefined = this.overrides[roleId];
    if (o !== undefined) return o;
    return {
      systemPrompt: null, modelId: null, temperature: null,
      reasoningLevel: null, maxTurnsOverride: null,
      timeoutMsOverride: null, outputBudgetOverride: null,
    };
  }

  // M3.2:更新某 role 的 systemPrompt override
  private updateRolePrompt(roleId: string, prompt: string): void {
    const o: SubAgentOverride = this.getOverride(roleId);
    const next: SubAgentOverride = {
      systemPrompt: prompt, modelId: o.modelId, temperature: o.temperature,
      reasoningLevel: o.reasoningLevel, maxTurnsOverride: o.maxTurnsOverride,
      timeoutMsOverride: o.timeoutMsOverride, outputBudgetOverride: o.outputBudgetOverride,
    };
    const ov: Record<string, SubAgentOverride> = {};
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) ov[k] = this.overrides[k];
    ov[roleId] = next;
    this.overrides = ov;
    this.dirty = true;
  }

  // M3.2:清除某 role 的 override(恢复默认)
  private resetRole(roleId: string): void {
    const ov: Record<string, SubAgentOverride> = {};
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) {
      if (k !== roleId) ov[k] = this.overrides[k];
    }
    this.overrides = ov;
    this.saveSetting();
  }

  private timeoutLabel(): string {
    if (this.timeoutMs === 60000) return '1 分钟';
    if (this.timeoutMs === 180000) return '3 分钟';
    if (this.timeoutMs === 300000) return '5 分钟';
    if (this.timeoutMs === 600000) return '10 分钟';
    return `${this.timeoutMs / 1000} 秒`;
  }

  private outputBudgetLabel(): string {
    if (this.outputBudgetChars === 8000) return '8K 字符';
    if (this.outputBudgetChars === 12000) return '12K 字符';
    if (this.outputBudgetChars === 24000) return '24K 字符';
    if (this.outputBudgetChars === 48000) return '48K 字符';
    return `${this.outputBudgetChars} 字符`;
  }
}
