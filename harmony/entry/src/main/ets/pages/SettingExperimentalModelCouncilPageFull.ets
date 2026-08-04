// SettingExperimentalModelCouncilPageFull — Model Council 实验设置页(Full)
// 多模型合议:席位管理(名称/模型/权重) + 阶段标签编辑 + 综合模型 + 运行参数
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface CouncilSeat {
  id: string;
  name: string;
  modelIndex: number;
  weight: number;
}

interface CouncilPhase {
  id: string;
  label: string;
  durationSec: number;
}

interface CouncilSettings {
  enabled: boolean;
  seats: CouncilSeat[];
  phases: CouncilPhase[];
  synthesisModelIndex: number;
  maxRounds: number;
  timeoutSec: number;
}

function makeSeat(id: string, name: string, modelIndex: number, weight: number): CouncilSeat {
  return { id: id, name: name, modelIndex: modelIndex, weight: weight };
}

function makePhase(id: string, label: string, durationSec: number): CouncilPhase {
  return { id: id, label: label, durationSec: durationSec };
}

function defaultCouncil(): CouncilSettings {
  return {
    enabled: false,
    seats: [
      makeSeat('s-1', '席位 A', 1, 50),
      makeSeat('s-2', '席位 B', 2, 50),
    ],
    phases: [
      makePhase('p-1', '听证', 60),
      makePhase('p-2', '辩论', 120),
      makePhase('p-3', '权衡', 90),
      makePhase('p-4', '裁决', 60),
    ],
    synthesisModelIndex: 1,
    maxRounds: 3,
    timeoutSec: 300,
  };
}

function placeholderLoadCouncil(): Promise<CouncilSettings> {
  return Promise.resolve(defaultCouncil());
}

@Entry
@Component
struct SettingExperimentalModelCouncilPageFull {
  @State councilEnabled: boolean = false;
  @State seats: CouncilSeat[] = [];
  @State phases: CouncilPhase[] = [];
  @State synthesisModelIndex: number = 1;
  @State maxRounds: number = 3;
  @State timeoutSec: number = 300;
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  private seatCounter: number = 100;
  private modelOptions: string[] = ['未指定', 'gpt-4o', 'claude-3.5-sonnet', 'gemini-1.5-pro', 'glm-4'];

  aboutToAppear(): void {
    placeholderLoadCouncil().then((c: CouncilSettings): void => {
      this.councilEnabled = c.enabled;
      this.seats = c.seats;
      this.phases = c.phases;
      this.synthesisModelIndex = c.synthesisModelIndex;
      this.maxRounds = c.maxRounds;
      this.timeoutSec = c.timeoutSec;
      this.loaded = true;
    });
  }

  private nextSeatId(): string {
    this.seatCounter += 1;
    return `s-${this.seatCounter}`;
  }

  private addSeat(): void {
    const id: string = this.nextSeatId();
    this.seats = [...this.seats, makeSeat(id, '', 0, 50)];
  }

  private removeSeat(id: string): void {
    this.seats = this.seats.filter((s: CouncilSeat): boolean => s.id !== id);
  }

  private setSeatName(id: string, name: string): void {
    this.seats = this.seats.map((s: CouncilSeat): CouncilSeat => {
      if (s.id !== id) return s;
      return makeSeat(s.id, name, s.modelIndex, s.weight);
    });
  }

  private setSeatModel(id: string, i: number): void {
    this.seats = this.seats.map((s: CouncilSeat): CouncilSeat => {
      if (s.id !== id) return s;
      return makeSeat(s.id, s.name, i, s.weight);
    });
  }

  private setSeatWeight(id: string, w: number): void {
    this.seats = this.seats.map((s: CouncilSeat): CouncilSeat => {
      if (s.id !== id) return s;
      return makeSeat(s.id, s.name, s.modelIndex, Math.round(w));
    });
  }

  private setPhaseLabel(id: string, label: string): void {
    this.phases = this.phases.map((p: CouncilPhase): CouncilPhase => {
      if (p.id !== id) return p;
      return makePhase(p.id, label, p.durationSec);
    });
  }

  private setPhaseDuration(id: string, v: string): void {
    const n: number = parseInt(v, 10);
    const value: number = isNaN(n) ? 0 : n;
    this.phases = this.phases.map((p: CouncilPhase): CouncilPhase => {
      if (p.id !== id) return p;
      return makePhase(p.id, p.label, value);
    });
  }

  private setMaxRounds(v: string): void {
    const n: number = parseInt(v, 10);
    this.maxRounds = isNaN(n) ? 0 : n;
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

  @Builder seatCard(seat: CouncilSeat): void {
    Column() {
      Row() {
        Text(seat.name.length > 0 ? seat.name : '未命名席位')
          .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
        Text('删除').fontSize(META).fontColor('ERROR')
          .padding({ left: 8, right: 4, top: 4, bottom: 4 })
          .onClick((): void => { this.removeSeat(seat.id); })
      }
      .width('100%').margin({ bottom: 10 })

      FlatTextField({
        value: seat.name, label: '席位名称', placeholder: '席位 A',
        onChange: (v: string): void => { this.setSeatName(seat.id, v); },
      })

      Row() {
        Text('模型').fontSize(META).fontColor(INK3).layoutWeight(1)
        PillSelect({
          options: this.modelOptions, selectedIndex: seat.modelIndex,
          onSelect: (i: number): void => { this.setSeatModel(seat.id, i); },
        })
      }
      .width('100%').margin({ top: 12 })

      Column() {
        Row() {
          Text('权重').fontSize(META).fontColor(INK3).layoutWeight(1)
          Text(`${seat.weight}`).fontSize(META).fontColor(ACCENT)
        }
        .width('100%')
        Slider({ value: seat.weight, min: 0, max: 100, step: 5 })
          .width('100%').margin({ top: 4 })
          .onChange((v: number): void => { this.setSeatWeight(seat.id, v); })
      }
      .width('100%').margin({ top: 12 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 12 })
  }

  @Builder phaseRow(p: CouncilPhase): void {
    Row() {
      TextInput({ text: p.label, placeholder: '阶段名称' })
        .fontSize(BODY).fontColor(INK).layoutWeight(1)
        .backgroundColor(SURFACE2).borderRadius(8)
        .onChange((v: string): void => { this.setPhaseLabel(p.id, v); })
      TextInput({ text: `${p.durationSec}`, placeholder: '60' })
        .fontSize(BODY).fontColor(INK)
        .backgroundColor(SURFACE2).borderRadius(8)
        .width(72).margin({ left: 8 })
        .onChange((v: string): void => { this.setPhaseDuration(p.id, v); })
      Text('秒').fontSize(META).fontColor(INK3).margin({ left: 4 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('Model Council').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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

            this.toggleRow('启用 Model Council', '多模型合议:多席位对关键决策投票', this.councilEnabled,
              (): void => { this.councilEnabled = !this.councilEnabled; })

            this.sectionHeader('席位管理')

            if (this.seats.length === 0) {
              Text('暂无席位,点下方添加').fontSize(BODY).fontColor(INK3)
                .padding({ top: 8, bottom: 8 })
            }

            ForEach(this.seats, (s: CouncilSeat): void => {
              this.seatCard(s)
            }, (s: CouncilSeat): string => s.id)

            Row() {
              Text('+ 添加席位').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.addSeat(); })
              Blank()
            }
            .width('100%').margin({ top: 4, bottom: 8 })

            this.sectionHeader('阶段设置')

            ForEach(this.phases, (p: CouncilPhase): void => {
              this.phaseRow(p)
            }, (p: CouncilPhase): string => p.id)

            this.sectionHeader('综合模型')

            this.selectRow('综合模型', '最终综合各席位输出的模型', this.modelOptions, this.synthesisModelIndex,
              (i: number): void => { this.synthesisModelIndex = i; })

            this.sectionHeader('运行参数')

            Column() {
              FlatTextField({
                value: `${this.maxRounds}`, label: '最大轮次', placeholder: '3',
                onChange: (v: string): void => { this.setMaxRounds(v); },
              })
              FlatTextField({
                value: `${this.timeoutSec}`, label: '超时 (秒)', placeholder: '300',
                supportingText: '单轮超时;设为 0 则不限制。',
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
