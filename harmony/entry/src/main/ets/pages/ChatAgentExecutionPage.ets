// ChatAgentExecutionPage — 运行与显示设置面(D-115)
// Android 锚点:
//   SettingAgentExecutionPage.kt(全文 395 行)— 本切片移植两卡三行:
//     '运行' CardGroup(:77-153):工具循环上限 Select
//       (options 64/128/256/384/512,:46;selectedOption coerceIn(16,512),:132-135;
//       optionToString '%1$d 步';写入 :136-143)
//     '生成稳定性' CardGroup(:320-392 前两行):自动重试生成 Switch(:325-345)
//       + 重试上限 Select(options 1/2/3/5,:47;coerceIn(1,5),:353;
//       optionToString '%1$d 次';写入 :354-364)
//   PreferencesStore.kt:163,181(maxToolLoopSteps 默认 256;
//     generationRetry 默认 GenerationRetrySetting(){enabled=true,maxRetries=5})
//   PreferencesStore.kt:268-270(界 MIN 16 / DEFAULT 256 / MAX 512)
//   values-zh/strings.xml:643-644,646-651,993-995(标题/组名/文案逐字)
// 未移植行(P1 登记,对应未落地子系统,不做静默无操作开关):
//   显示卡 generativeUi 行(:80-124 部分;operationPreviewMode 已落地 D-122);
//   实时状态卡 enableLiveStatusNotification/hideSensitiveLiveStatus(:155-199);
//   Live 伴随卡 liveMode 五行(:201-318);
//   后台保活 keepGenerationAliveInBackground(:372-390)
import { ACCENT,BG,INK,INK3,LINE,SURFACE } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimeExecution,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type {
  AgentOperationPreviewMode, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';


// :46/:47 选项逐字
const TOOL_LOOP_STEP_OPTIONS: number[] = [64, 128, 256, 384, 512];
const RETRY_COUNT_OPTIONS: number[] = [1, 2, 3, 5];
// D-122::48 AgentOperationPreviewMode.entries 序 + values-zh:986-990 文案逐字
const PREVIEW_MODE_OPTIONS: AgentOperationPreviewMode[] = ['always', 'auto', 'hidden'];
const PREVIEW_MODE_LABELS: string[] = ['常驻', '自动', '隐藏'];

// :132-135 / :353 — 显示选中值 coerceIn(与 Android 同:持久化原样持有,显示钳制)
const coerceIn = (v: number, lo: number, hi: number): number =>
  Math.min(Math.max(v, lo), hi);

@Entry
@Component
struct ChatAgentExecutionPage {
  @State loaded: boolean = false;
  @State maxToolLoopSteps: number = 256;
  @State retryEnabled: boolean = true;
  @State retryMaxRetries: number = 5;
  @State operationPreviewMode: AgentOperationPreviewMode = 'always';

  aboutToAppear(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const snap: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(getChatKvStore());
    this.maxToolLoopSteps = snap.maxToolLoopSteps;
    this.retryEnabled = snap.generationRetry.enabled;
    this.retryMaxRetries = snap.generationRetry.maxRetries;
    this.operationPreviewMode = snap.operationPreviewMode;
    this.loaded = true;
  }

  // :136-143 — vm.updateSettings { copy(agentRuntime = copy(maxToolLoopSteps = steps)) }
  private setToolLoopSteps(steps: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { maxToolLoopSteps: steps })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.maxToolLoopSteps = snap.maxToolLoopSteps;
      });
  }

  // D-122::81-90 — agentRuntime.copy(operationPreviewMode = mode)
  private setPreviewMode(mode: AgentOperationPreviewMode): void {
    updateAgentRuntimeExecution(getChatKvStore(), { operationPreviewMode: mode })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.operationPreviewMode = snap.operationPreviewMode;
      });
  }

  // :331-344 — generationRetry.copy(enabled = checked)
  private setRetryEnabled(checked: boolean): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryEnabled: checked })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.retryEnabled = snap.generationRetry.enabled;
      });
  }

  // :354-364 — generationRetry.copy(maxRetries = maxRetries)
  private setRetryMaxRetries(maxRetries: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryMaxRetries: maxRetries })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.retryMaxRetries = snap.generationRetry.maxRetries;
      });
  }

  @Builder
  groupLabel(text: string) {
    Row() {
      Text(text)
        .fontSize(11).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 8, right: 8, top: 16, bottom: 6 })
  }

  @Builder
  switchCard(title: string, desc: string, value: boolean, onChange: (v: boolean) => void) {
    Row() {
      Column() {
        Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(desc).fontSize(11).fontColor(INK3).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
      Toggle({ type: ToggleType.Switch, isOn: value })
        .selectedColor(ACCENT)
        .onChange(onChange)
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
  }

  // Select + ValueChip(:129-151) → 芯片行(选中态 ACCENT 填充,同 D-107 chip 先例)
  @Builder
  selectCard(title: string, desc: string, options: number[], selected: number,
    valueSuffix: string, onSelect: (v: number) => void) {
    Row() {
      Column() {
        Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(desc).fontSize(11).fontColor(INK3).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
      Row() {
        ForEach(options, (opt: number) => {
          Text(`${opt} ${valueSuffix}`)
            .fontSize(12)
            .fontColor(opt === selected ? '#FFFFFF' : INK)
            .padding({ left: 10, right: 10, top: 5, bottom: 5 })
            .backgroundColor(opt === selected ? ACCENT : SURFACE)
            .border({ width: 1, color: opt === selected ? ACCENT : LINE })
            .borderRadius(10)
            .margin({ left: 6 })
            .onClick((): void => { onSelect(opt); })
        }, (opt: number): string => `${opt}`)
      }
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
  }

  // D-122:字符串选项变体(:79-100 Select + optionToString)
  @Builder
  selectTextCard(title: string, desc: string, options: string[], labels: string[],
    selected: string, onSelect: (v: string) => void) {
    Row() {
      Column() {
        Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(desc).fontSize(11).fontColor(INK3).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
      Row() {
        ForEach(options, (opt: string, index: number) => {
          Text(labels[index])
            .fontSize(12)
            .fontColor(opt === selected ? '#FFFFFF' : INK)
            .padding({ left: 10, right: 10, top: 5, bottom: 5 })
            .backgroundColor(opt === selected ? ACCENT : SURFACE)
            .border({ width: 1, color: opt === selected ? ACCENT : LINE })
            .borderRadius(10)
            .margin({ left: 6 })
            .onClick((): void => { onSelect(opt); })
        }, (opt: string): string => opt)
      }
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
  }

  build() {
    Column() {
      PageTitleBar({ title: '执行参数' })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            // :644 组名逐字
            this.groupLabel('运行')
            // D-122::75-100 操作预览行(组内首项;values-zh:986-990 逐字)
            this.selectTextCard('操作预览', '控制输入框上方的 Agent 实时操作预览显示方式。',
              PREVIEW_MODE_OPTIONS, PREVIEW_MODE_LABELS, this.operationPreviewMode,
              (v: string): void => { this.setPreviewMode(v as AgentOperationPreviewMode); })
            // :993-995 文案逐字;:132-135 selectedOption coerceIn(16,512)
            this.selectCard('工具循环上限', '单次回复最多调用多少轮工具。',
              TOOL_LOOP_STEP_OPTIONS, coerceIn(this.maxToolLoopSteps, 16, 512), '步',
              (v: number): void => { this.setToolLoopSteps(v); })

            // :646 组名逐字
            this.groupLabel('生成稳定性')
            // :647-648 文案逐字
            this.switchCard('自动重试生成', '网络或服务临时中断时自动重试。',
              this.retryEnabled,
              (v: boolean): void => { this.setRetryEnabled(v); })
            // :649-651 文案逐字;:353 selectedOption coerceIn(1,5)
            this.selectCard('重试上限', '单次模型调用最多重试次数。',
              RETRY_COUNT_OPTIONS, coerceIn(this.retryMaxRetries, 1, 5), '次',
              (v: number): void => { this.setRetryMaxRetries(v); })
          }
          .width('100%').padding({ left: 16, right: 16, bottom: 24 })
        }
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
