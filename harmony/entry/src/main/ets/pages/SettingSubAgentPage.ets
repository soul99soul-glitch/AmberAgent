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
import type { SubAgentRuntimeSetting, SubAgentMode, SubAgentOverride, SubAgentDefinition } from '@amber/chat-domain';
import {
  DEFAULT_SUB_AGENT_TIMEOUT_MS, DEFAULT_SUB_AGENT_MAX_TURNS,
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  SUB_AGENT_BUILT_INS,
} from '@amber/chat-domain';
import { INK, INK3, ACCENT, SURFACE, SURFACE2, LINE, BG, TITLE, BODY, META, LABEL } from '../design/tokens.ets';

@Entry
@Component
struct SettingSubAgentPage {
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

  aboutToAppear(): void {
    this.loadSetting();
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

  private async saveSetting(): Promise<void> {
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
    } catch (_e) {
      // 保存失败静默(不伪成功,设置页无 toast)
    }
  }

  build(): void {
    Column() {
      // 标题
      Row() {
        Text('子代理设置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 8 })

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // enabled 开关
            this.settingRow('启用子代理', '允许主 Agent 委派子任务给隔离的子代理', (): void => {
              this.subAgentEnabled = !this.subAgentEnabled;
              this.saveSetting();
            }, this.subAgentEnabled)

            // mode 选择
            this.modeRow()

            // allowDynamic(smart_dynamic 模式下恒 true 且不可改)
            if (this.mode !== 'smart_dynamic') {
              this.settingRow('允许动态子代理', '允许创建临时自定义子代理角色', (): void => {
                this.allowDynamic = !this.allowDynamic;
                this.saveSetting();
              }, this.allowDynamic)
            }

            // maxConcurrentRuns 滑块
            this.sliderRow('最大并发数', `${this.maxConcurrentRuns}`, 1, 5, this.maxConcurrentRuns, (v: number): void => {
              this.maxConcurrentRuns = Math.round(v);
              this.saveSetting();
            })

            // maxTurns 滑块
            this.sliderRow('最大轮次', `${this.maxTurns}`, 2, 8, this.maxTurns, (v: number): void => {
              this.maxTurns = Math.round(v);
              this.saveSetting();
            })

            // timeout 选择
            this.selectRow('超时时间', this.timeoutLabel(), ((): void => {
              const options: number[] = [60000, 180000, 300000, 600000];
              const idx: number = options.indexOf(this.timeoutMs);
              this.timeoutMs = options[(idx + 1) % options.length];
              this.saveSetting();
            }))

            // outputBudget 选择
            this.selectRow('输出预算', this.outputBudgetLabel(), ((): void => {
              const options: number[] = [8000, 12000, 24000, 48000];
              const idx: number = options.indexOf(this.outputBudgetChars);
              this.outputBudgetChars = options[(idx + 1) % options.length];
              this.saveSetting();
            }))

            // M3.2:per-role override 编辑(6 内置角色)
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 角色提示词覆盖').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 20, bottom: 8 })

            ForEach(SUB_AGENT_BUILT_INS, (def: SubAgentDefinition): void => {
              this.roleOverrideRow(def)
            }, (def: SubAgentDefinition): string => def.id)
          }
          .width('100%')
          .padding({ bottom: 20 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }

  @Builder settingRow(label: string, desc: string, onToggle: () => void, checked: boolean): void {
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

  @Builder modeRow(): void {
    Row() {
      Column() {
        Text('模式').fontSize(BODY).fontColor(INK)
        Text(this.mode === 'smart_dynamic' ? '智能动态:隐藏内置角色,按需创建临时子代理' : '名册:使用预设的内置子代理角色')
          .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text(this.mode === 'smart_dynamic' ? '智能动态' : '名册').fontSize(META).fontColor(ACCENT)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => {
      this.mode = this.mode === 'roster' ? 'smart_dynamic' : 'roster';
      this.saveSetting();
    })
  }

  @Builder sliderRow(label: string, valueText: string, min: number, max: number, value: number, onChange: (v: number) => void): void {
    Column() {
      Row() {
        Text(label).fontSize(BODY).fontColor(INK).layoutWeight(1)
        Text(valueText).fontSize(META).fontColor(ACCENT)
      }
      .width('100%')
      Slider({ value: value, min: min, max: max, step: 1 })
        .width('100%')
        .margin({ top: 4 })
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

  // M3.2:单个内置角色的 override 编辑行(展开 → systemPrompt textarea + 重置)
  @Builder roleOverrideRow(def: SubAgentDefinition): void {
    Column() {
      Row() {
        Column() {
          Text(def.name).fontSize(BODY).fontColor(INK)
          Text(def.description).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.expandedRoleId === def.id ? '▾' : '▸').fontSize(LABEL).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 14, bottom: 14 })
      .backgroundColor(SURFACE)
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
          Text('自定义提示词(留空 = 继承默认)').fontSize(LABEL).fontColor(INK3)
            .width('100%').margin({ bottom: 4 })
          TextArea({
            text: this.getOverride(def.id).systemPrompt ?? '',
            placeholder: def.systemPrompt,
          })
            .width('100%').height(120).fontSize(BODY).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateRolePrompt(def.id, v); })
          Row() {
            Text('恢复默认').fontSize(META).fontColor(ACCENT)
              .onClick((): void => { this.resetRole(def.id); })
          }
          .width('100%').margin({ top: 8 })
        }
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 12 })
        .backgroundColor(SURFACE)
      }
    }
    .width('100%')
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
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
