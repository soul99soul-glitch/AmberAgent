// SettingAgentExecutionPageFull — Agent 执行设置页(Full)
// 运行参数:最大迭代 / 单轮超时 / 自动压缩上下文 + 阈值 / 停止序列 /
//   失败重试 + 上限 / 并行工具调用 / 流式输出 / 温度覆盖
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';
import { TagsInput, AmberTag } from '../components/ui/TagsInput.ets';

interface AgentExecutionSettings {
  maxIterations: number;
  timeoutPerTurnSec: number;
  autoCompact: boolean;
  compactThreshold: number;     // 0.0 ~ 1.0(占比)
  retryOnFailure: boolean;
  maxRetries: number;
  parallelToolCalls: boolean;
  streaming: boolean;
  temperatureOverride: number;  // 0.0 ~ 2.0
}

function defaultSettings(): AgentExecutionSettings {
  return {
    maxIterations: 10,
    timeoutPerTurnSec: 120,
    autoCompact: false,
    compactThreshold: 0.8,
    retryOnFailure: true,
    maxRetries: 3,
    parallelToolCalls: true,
    streaming: true,
    temperatureOverride: 1.0,
  };
}

function placeholderLoadSettings(): Promise<AgentExecutionSettings> {
  return Promise.resolve(defaultSettings());
}

@Entry
@Component
struct SettingAgentExecutionPageFull {
  @State maxIterations: number = 10;
  @State timeoutPerTurnSec: number = 120;
  @State autoCompact: boolean = false;
  @State compactThreshold: number = 0.8;
  @State retryOnFailure: boolean = true;
  @State maxRetries: number = 3;
  @State parallelToolCalls: boolean = true;
  @State streaming: boolean = true;
  @State temperatureOverride: number = 1.0;
  // 停止序列: AmberTag 列表(selectedIds 恒等于全部 id,即所有 tag 均选中)
  @State stopTags: AmberTag[] = [];
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  private tagCounter: number = 0;

  aboutToAppear(): void {
    placeholderLoadSettings().then((s: AgentExecutionSettings): void => {
      this.maxIterations = s.maxIterations;
      this.timeoutPerTurnSec = s.timeoutPerTurnSec;
      this.autoCompact = s.autoCompact;
      this.compactThreshold = s.compactThreshold;
      this.retryOnFailure = s.retryOnFailure;
      this.maxRetries = s.maxRetries;
      this.parallelToolCalls = s.parallelToolCalls;
      this.streaming = s.streaming;
      this.temperatureOverride = s.temperatureOverride;
      this.loaded = true;
    });
  }

  private setMaxIterations(v: string): void {
    const n: number = parseInt(v, 10);
    this.maxIterations = isNaN(n) ? 0 : n;
  }

  private setTimeoutPerTurn(v: string): void {
    const n: number = parseInt(v, 10);
    this.timeoutPerTurnSec = isNaN(n) ? 0 : n;
  }

  private setMaxRetries(v: string): void {
    const n: number = parseInt(v, 10);
    this.maxRetries = isNaN(n) ? 0 : n;
  }

  // TagsInput onChange: selectedIds 恒等于全部 id(停止序列全集即选中集)
  private onStopSequencesChange(selectedIds: string[], tags: AmberTag[]): void {
    // 过滤出仍被选中的 tag(按 selectedIds 顺序)
    const next: AmberTag[] = [];
    for (let i = 0; i < selectedIds.length; i++) {
      for (let j = 0; j < tags.length; j++) {
        if (tags[j].id === selectedIds[i]) {
          next.push(tags[j]);
          break;
        }
      }
    }
    this.stopTags = next;
  }

  private selectedTagIds(): string[] {
    const ids: string[] = [];
    for (let i = 0; i < this.stopTags.length; i++) {
      ids.push(this.stopTags[i].id);
    }
    return ids;
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

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked,
        switchSize: SwitchSize.Small,
        onCheckedChange: (_c: boolean): void => { onToggle(); },
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
        Text('Agent 执行').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('运行参数')

            Column() {
              FlatTextField({
                value: `${this.maxIterations}`, label: '最大迭代次数', placeholder: '10',
                supportingText: '单次会话最多调用工具的轮数。',
                onChange: (v: string): void => { this.setMaxIterations(v); },
              })
              FlatTextField({
                value: `${this.timeoutPerTurnSec}`, label: '单轮超时 (秒)', placeholder: '120',
                supportingText: '单轮工具调用最长等待时间;0 表示不限制。',
                onChange: (v: string): void => { this.setTimeoutPerTurn(v); },
              }).margin({ top: 12 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('上下文压缩')

            this.toggleRow('自动压缩上下文', '接近上限时自动摘要并裁剪历史消息',
              this.autoCompact,
              (): void => { this.autoCompact = !this.autoCompact; })

            if (this.autoCompact) {
              Column() {
                Row() {
                  Text('压缩阈值').fontSize(BODY).fontColor(INK).layoutWeight(1)
                  Text(`${Math.round(this.compactThreshold * 100)}%`).fontSize(BODY).fontColor(INK3)
                }.width('100%')
                NotionSlider({
                  value: this.compactThreshold, min: 0.5, max: 1.0, step: 0.05,
                  labelMode: 'percent',
                  onChange: (v: number): void => { this.compactThreshold = v; },
                }).margin({ top: 6 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            }

            this.sectionHeader('停止序列')

            Column() {
              Text('当模型输出包含以下任一序列时立即停止生成。')
                .fontSize(LABEL).fontColor(INK3).margin({ bottom: 8 })
              TagsInput({
                selectedIds: this.selectedTagIds(),
                tags: this.stopTags,
                suggestions: ['\n\n\n', '###', 'END'],
                onChange: (ids: string[], tags: AmberTag[]): void => {
                  this.onStopSequencesChange(ids, tags);
                },
              })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('失败重试')

            this.toggleRow('失败自动重试', '网络或服务临时中断时自动重试',
              this.retryOnFailure,
              (): void => { this.retryOnFailure = !this.retryOnFailure; })

            if (this.retryOnFailure) {
              Column() {
                FlatTextField({
                  value: `${this.maxRetries}`, label: '最大重试次数', placeholder: '3',
                  onChange: (v: string): void => { this.setMaxRetries(v); },
                })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 4, bottom: 12 })
              .backgroundColor(SURFACE)
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            }

            this.sectionHeader('工具与输出')

            this.toggleRow('并行工具调用', '允许同一轮内并行执行多个无依赖工具',
              this.parallelToolCalls,
              (): void => { this.parallelToolCalls = !this.parallelToolCalls; })

            this.toggleRow('流式输出', '逐 token 流式返回模型输出',
              this.streaming,
              (): void => { this.streaming = !this.streaming; })

            this.sectionHeader('采样温度覆盖')

            Column() {
              Row() {
                Text('温度').fontSize(BODY).fontColor(INK).layoutWeight(1)
                Text(this.temperatureOverride.toFixed(2)).fontSize(BODY).fontColor(INK3)
              }.width('100%')
              NotionSlider({
                value: this.temperatureOverride, min: 0, max: 2, step: 0.05,
                labelMode: '',
                onChange: (v: number): void => { this.temperatureOverride = v; },
              }).margin({ top: 6 })
              Text('0 = 确定性,2 = 高随机性;覆盖所选模型默认温度。')
                .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            Row() {
              Blank()
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              }
            }
            .width('100%').margin({ top: 12, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
