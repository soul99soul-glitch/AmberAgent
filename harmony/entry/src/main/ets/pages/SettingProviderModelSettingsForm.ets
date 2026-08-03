// SettingProviderModelSettingsForm — 模型设置表单
// 字段:modelName / contextWindow / maxOutputTokens /
//   temperature(滑块) / topP(滑块) / presencePenalty(滑块) / frequencyPenalty(滑块) /
//   stream(开关) / enabled(开关)
// 数字字段用 FlatTextField,布尔用 AmberSwitch,连续值用 Slider
// 占位持久化;接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface ModelSettings {
  name: string;
  contextWindow: number;
  maxOutputTokens: number;
  temperature: number;
  topP: number;
  presencePenalty: number;
  frequencyPenalty: number;
  stream: boolean;
  enabled: boolean;
}

function makeSettings(): ModelSettings {
  return {
    name: '', contextWindow: 8192, maxOutputTokens: 4096,
    temperature: 0.7, topP: 0.9, presencePenalty: 0, frequencyPenalty: 0,
    stream: true, enabled: true,
  };
}

function placeholderLoadSettings(): Promise<ModelSettings> {
  const s: ModelSettings = makeSettings();
  s.name = 'gpt-4o';
  return Promise.resolve(s);
}

@Entry
@Component
struct SettingProviderModelSettingsForm {
  @State s: ModelSettings = makeSettings();
  @State loaded: boolean = false;
  @State savedMsg: string = '';

  aboutToAppear(): void {
    placeholderLoadSettings().then((v: ModelSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setName(v: string): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: v, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setContextWindow(v: string): void {
    const n: number = parseInt(v, 10);
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: isNaN(n) ? 0 : n, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setMaxOutput(v: string): void {
    const n: number = parseInt(v, 10);
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: isNaN(n) ? 0 : n,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setTemperature(v: number): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: v, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setTopP(v: number): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: v,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setPresence(v: number): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: v, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setFrequency(v: number): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: v,
      stream: c.stream, enabled: c.enabled,
    };
  }

  private setStream(v: boolean): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: v, enabled: c.enabled,
    };
  }

  private setEnabled(v: boolean): void {
    const c: ModelSettings = this.s;
    this.s = {
      name: c.name, contextWindow: c.contextWindow, maxOutputTokens: c.maxOutputTokens,
      temperature: c.temperature, topP: c.topP,
      presencePenalty: c.presencePenalty, frequencyPenalty: c.frequencyPenalty,
      stream: c.stream, enabled: v,
    };
  }

  private save(): void {
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder sliderRow(label: string, desc: string, value: number, min: number, max: number, step: number, onChange: (v: number) => void): void {
    Column() {
      Row() {
        Column() {
          Text(label).fontSize(BODY).fontColor(INK)
          if (desc.length > 0) {
            Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(value.toFixed(2)).fontSize(META).fontColor(INK2)
      }
      .width('100%')
      Slider({ value: value, min: min, max: max, step: step })
        .blockColor(ACCENT).trackColor(SURFACE2).selectedColor(ACCENT)
        .width('100%').margin({ top: 6 })
        .onChange((v: number): void => { onChange(v); })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: (v: boolean) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked, switchSize: SwitchSize.Small,
        onCheckedChange: (v: boolean): void => { onToggle(v); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('模型设置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('基本')

            Column() {
              FlatTextField({
                value: this.s.name, label: '模型名称', placeholder: 'gpt-4o',
                onChange: (v: string): void => { this.setName(v); },
              })
              FlatTextField({
                value: `${this.s.contextWindow}`, label: '上下文窗口 (tokens)', placeholder: '8192',
                onChange: (v: string): void => { this.setContextWindow(v); },
              }).margin({ top: 8 })
              FlatTextField({
                value: `${this.s.maxOutputTokens}`, label: '最大输出 tokens', placeholder: '4096',
                onChange: (v: string): void => { this.setMaxOutput(v); },
              }).margin({ top: 8 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('采样参数')

            this.sliderRow('温度', '越高越随机,0 = 贪心', this.s.temperature, 0, 2, 0.1,
              (v: number): void => { this.setTemperature(v); })
            this.sliderRow('Top P', '核采样概率截断', this.s.topP, 0, 1, 0.05,
              (v: number): void => { this.setTopP(v); })
            this.sliderRow('Presence Penalty', '正值抑制已出现 token', this.s.presencePenalty, -2, 2, 0.1,
              (v: number): void => { this.setPresence(v); })
            this.sliderRow('Frequency Penalty', '正值抑制高频 token', this.s.frequencyPenalty, -2, 2, 0.1,
              (v: number): void => { this.setFrequency(v); })

            this.sectionHeader('行为')

            this.toggleRow('流式输出', '逐 token 返回', this.s.stream,
              (v: boolean): void => { this.setStream(v); })
            this.toggleRow('启用', '停用后该模型不出现在选择列表', this.s.enabled,
              (v: boolean): void => { this.setEnabled(v); })

            if (this.savedMsg.length > 0) {
              Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
                .padding({ top: 16, bottom: 24 })
            }
            Column() {}.height(24)
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
