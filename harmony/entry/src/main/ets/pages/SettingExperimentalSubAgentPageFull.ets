// SettingExperimentalSubAgentPageFull — SubAgent 实验设置页(Full)
// 子代理名册(名称/描述/模型) + 默认模型 + 并发/超时 + 工具自动批准
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface SubAgentEntry {
  id: string;
  name: string;
  description: string;
  modelIndex: number;
}

interface SubAgentSettings {
  enabled: boolean;
  roster: SubAgentEntry[];
  defaultModelIndex: number;
  maxConcurrent: number;
  timeoutSec: number;
  autoApproveTools: boolean;
}

function makeAgent(id: string, name: string, description: string, modelIndex: number): SubAgentEntry {
  return { id: id, name: name, description: description, modelIndex: modelIndex };
}

function defaultSubAgent(): SubAgentSettings {
  return {
    enabled: false,
    roster: [
      makeAgent('a-1', '搜索员', '联网检索并总结资料', 1),
      makeAgent('a-2', '代码执行员', '在沙箱中运行代码并返回结果', 2),
    ],
    defaultModelIndex: 1,
    maxConcurrent: 2,
    timeoutSec: 180,
    autoApproveTools: false,
  };
}

function placeholderLoadSubAgent(): Promise<SubAgentSettings> {
  return Promise.resolve(defaultSubAgent());
}

@Entry
@Component
struct SettingExperimentalSubAgentPageFull {
  @State subAgentEnabled: boolean = false;
  @State roster: SubAgentEntry[] = [];
  @State defaultModelIndex: number = 1;
  @State maxConcurrent: number = 2;
  @State timeoutSec: number = 180;
  @State autoApproveTools: boolean = false;
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  private agentCounter: number = 100;
  private modelOptions: string[] = ['未指定', 'gpt-4o', 'claude-3.5-sonnet', 'gemini-1.5-pro', 'glm-4'];

  aboutToAppear(): void {
    placeholderLoadSubAgent().then((s: SubAgentSettings): void => {
      this.subAgentEnabled = s.enabled;
      this.roster = s.roster;
      this.defaultModelIndex = s.defaultModelIndex;
      this.maxConcurrent = s.maxConcurrent;
      this.timeoutSec = s.timeoutSec;
      this.autoApproveTools = s.autoApproveTools;
      this.loaded = true;
    });
  }

  private nextAgentId(): string {
    this.agentCounter += 1;
    return `a-${this.agentCounter}`;
  }

  private addAgent(): void {
    const id: string = this.nextAgentId();
    this.roster = [...this.roster, makeAgent(id, '', '', 0)];
  }

  private removeAgent(id: string): void {
    this.roster = this.roster.filter((a: SubAgentEntry): boolean => a.id !== id);
  }

  private setAgentName(id: string, name: string): void {
    this.roster = this.roster.map((a: SubAgentEntry): SubAgentEntry => {
      if (a.id !== id) return a;
      return makeAgent(a.id, name, a.description, a.modelIndex);
    });
  }

  private setAgentDesc(id: string, desc: string): void {
    this.roster = this.roster.map((a: SubAgentEntry): SubAgentEntry => {
      if (a.id !== id) return a;
      return makeAgent(a.id, a.name, desc, a.modelIndex);
    });
  }

  private setAgentModel(id: string, i: number): void {
    this.roster = this.roster.map((a: SubAgentEntry): SubAgentEntry => {
      if (a.id !== id) return a;
      return makeAgent(a.id, a.name, a.description, i);
    });
  }

  private setMaxConcurrent(v: string): void {
    const n: number = parseInt(v, 10);
    this.maxConcurrent = isNaN(n) ? 0 : n;
  }

  private setTimeoutSec(v: string): void {
    const n: number = parseInt(v, 10);
    this.timeoutSec = isNaN(n) ? 0 : n;
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
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder selectRow(label: string, desc: string, options: string[], index: number, onSelect: (i: number) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      PillSelect({
        options: options, selectedIndex: index,
        onSelect: (i: number): void => { onSelect(i); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder agentCard(a: SubAgentEntry): void {
    Column() {
      Row() {
        Text(a.name.length > 0 ? a.name : '未命名子代理')
          .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
        Text('删除').fontSize(META).fontColor('ERROR')
          .padding({ left: 8, right: 4, top: 4, bottom: 4 })
          .onClick((): void => { this.removeAgent(a.id); })
      }
      .width('100%').margin({ bottom: 10 })

      FlatTextField({
        value: a.name, label: '名称', placeholder: '搜索员',
        onChange: (v: string): void => { this.setAgentName(a.id, v); },
      })

      FlatTextField({
        value: a.description, label: '描述', placeholder: '联网检索并总结资料',
        singleLine: false, maxLines: 2,
        onChange: (v: string): void => { this.setAgentDesc(a.id, v); },
      }).margin({ top: 8 })

      Row() {
        Text('模型').fontSize(META).fontColor(INK3).layoutWeight(1)
        PillSelect({
          options: this.modelOptions, selectedIndex: a.modelIndex,
          onSelect: (i: number): void => { this.setAgentModel(a.id, i); },
        })
      }
      .width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 12 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('子代理').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('功能开关')

            this.toggleRow('启用子代理', '允许主 Agent 委派子任务给隔离的子代理', this.subAgentEnabled,
              (): void => { this.subAgentEnabled = !this.subAgentEnabled; })

            this.toggleRow('自动批准工具', '子代理调用工具时无需人工确认', this.autoApproveTools,
              (): void => { this.autoApproveTools = !this.autoApproveTools; })

            this.sectionHeader('子代理名册')

            if (this.roster.length === 0) {
              Text('暂无子代理,点下方添加').fontSize(BODY).fontColor(INK3)
                .padding({ top: 8, bottom: 8 })
            }

            ForEach(this.roster, (a: SubAgentEntry): void => {
              this.agentCard(a)
            }, (a: SubAgentEntry): string => a.id)

            Row() {
              Text('+ 添加子代理').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.addAgent(); })
              Blank()
            }
            .width('100%').margin({ top: 4, bottom: 8 })

            this.sectionHeader('默认模型')

            this.selectRow('默认模型', '新建子代理未指定模型时使用', this.modelOptions, this.defaultModelIndex,
              (i: number): void => { this.defaultModelIndex = i; })

            this.sectionHeader('运行参数')

            Column() {
              FlatTextField({
                value: `${this.maxConcurrent}`, label: '最大并发数', placeholder: '2',
                onChange: (v: string): void => { this.setMaxConcurrent(v); },
              })
              FlatTextField({
                value: `${this.timeoutSec}`, label: '超时 (秒)', placeholder: '180',
                supportingText: '单个子代理执行超时;设为 0 则不限制。',
                onChange: (v: string): void => { this.setTimeoutSec(v); },
              }).margin({ top: 12 })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

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
