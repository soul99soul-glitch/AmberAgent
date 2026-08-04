// SettingTTSProviderPage — TTS 提供商配置页
// UI only:无真实 TTS 引擎,占位持久化。
// 对照 Android: TTSProviderConfigure.kt(971 行,简化 UI 版)
//
// 功能:
//   - Provider 列表:name + 类型徽章(system/azure/openai) + enabled 开关
//   - 添加 Provider 表单:name / type / apiKey / endpoint / voice
//   - 编辑 Provider(点卡 → 内联表单展开)
//   - 试听按钮(占位,无真实 TTS)
//   - 语速 / 音调 / 音量 滑块(每 Provider)
//   - 删除(确认弹窗)
//
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避保留字;ForEach/@Builder 内不声明 const/let。

import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, SIGNAL,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';
import { ConfirmDialog } from '../components/ui/ConfirmDialog.ets';

interface TtsProviderRow {
  id: string;
  name: string;
  type: string;
  apiKey: string;
  endpoint: string;
  voice: string;
  speed: number;
  pitch: number;
  volume: number;
  enabled: boolean;
}

function makeRow(
  id: string, name: string, type: string, apiKey: string, endpoint: string,
  voice: string, speed: number, pitch: number, volume: number, enabled: boolean,
): TtsProviderRow {
  return {
    id: id, name: name, type: type, apiKey: apiKey, endpoint: endpoint,
    voice: voice, speed: speed, pitch: pitch, volume: volume, enabled: enabled,
  };
}

function placeholderLoad(): Promise<TtsProviderRow[]> {
  const seed: TtsProviderRow[] = [
    makeRow('t-1', '系统默认', 'system', '', '', '默认女声', 1.0, 1.0, 1.0, true),
    makeRow('t-2', 'Azure 语音', 'azure', 'az-****', 'https://tts.azure.com', 'zh-CN-Xiaoxiao', 1.0, 1.0, 0.9, false),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingTTSProviderPage {
  @State rows: TtsProviderRow[] = [];
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  // 添加表单
  @State addOpen: boolean = false;
  @State addName: string = '';
  @State addTypeIndex: number = 0;
  @State addApiKey: string = '';
  @State addEndpoint: string = '';
  @State addVoice: string = '';
  @State addSpeed: number = 1.0;
  @State addPitch: number = 1.0;
  @State addVolume: number = 1.0;
  @State addEnabled: boolean = true;
  // 内联编辑
  @State editingId: string = '';
  @State editName: string = '';
  @State editTypeIndex: number = 0;
  @State editApiKey: string = '';
  @State editEndpoint: string = '';
  @State editVoice: string = '';
  @State editSpeed: number = 1.0;
  @State editPitch: number = 1.0;
  @State editVolume: number = 1.0;
  @State editEnabled: boolean = true;
  @State testMsg: string = '';
  // 删除确认
  @State pendingDeleteId: string = '';
  private typeOptions: string[] = ['system', 'azure', 'openai'];
  private counter: number = 100;

  aboutToAppear(): void {
    placeholderLoad().then((list: TtsProviderRow[]): void => {
      this.rows = list;
      this.loaded = true;
    });
  }

  private nextId(): string {
    this.counter += 1;
    return `t-${this.counter}`;
  }

  private typeIndex(type: string): number {
    const idx: number = this.typeOptions.indexOf(type);
    return idx >= 0 ? idx : 0;
  }

  private typeBadgeColor(type: string): string {
    if (type === 'azure') return ACCENT;
    if (type === 'openai') return SIGNAL;
    return INK3;
  }

  // —— 添加 ——
  private openAdd(): void {
    this.addOpen = true;
    this.addName = '';
    this.addTypeIndex = 0;
    this.addApiKey = '';
    this.addEndpoint = '';
    this.addVoice = '';
    this.addSpeed = 1.0;
    this.addPitch = 1.0;
    this.addVolume = 1.0;
    this.addEnabled = true;
  }

  private confirmAdd(): void {
    const name: string = this.addName.trim();
    if (name.length === 0) return;
    const id: string = this.nextId();
    const row: TtsProviderRow = makeRow(
      id, name, this.typeOptions[this.addTypeIndex], this.addApiKey.trim(),
      this.addEndpoint.trim(), this.addVoice.trim(),
      this.addSpeed, this.addPitch, this.addVolume, this.addEnabled,
    );
    this.rows = [row, ...this.rows];
    this.addOpen = false;
    this.flashSaved('已添加');
  }

  // —— 编辑(内联展开) ——
  private startEdit(row: TtsProviderRow): void {
    if (this.editingId === row.id) {
      this.editingId = '';
      return;
    }
    this.editingId = row.id;
    this.editName = row.name;
    this.editTypeIndex = this.typeIndex(row.type);
    this.editApiKey = row.apiKey;
    this.editEndpoint = row.endpoint;
    this.editVoice = row.voice;
    this.editSpeed = row.speed;
    this.editPitch = row.pitch;
    this.editVolume = row.volume;
    this.editEnabled = row.enabled;
  }

  private saveEdit(): void {
    const id: string = this.editingId;
    if (id.length === 0) return;
    const updated: TtsProviderRow = makeRow(
      id, this.editName.trim(), this.typeOptions[this.editTypeIndex],
      this.editApiKey.trim(), this.editEndpoint.trim(), this.editVoice.trim(),
      this.editSpeed, this.editPitch, this.editVolume, this.editEnabled,
    );
    this.rows = this.rows.map((r: TtsProviderRow): TtsProviderRow => {
      return r.id === id ? updated : r;
    });
    this.editingId = '';
    this.flashSaved('已保存');
  }

  // —— 删除 ——
  private askDelete(id: string): void {
    this.pendingDeleteId = id;
  }

  private confirmDelete(): void {
    const id: string = this.pendingDeleteId;
    this.pendingDeleteId = '';
    if (this.editingId === id) this.editingId = '';
    this.rows = this.rows.filter((r: TtsProviderRow): boolean => r.id !== id);
    this.flashSaved('已删除');
  }

  // —— 启用/停用 ——
  private toggle(id: string, next: boolean): void {
    this.rows = this.rows.map((r: TtsProviderRow): TtsProviderRow => {
      if (r.id !== id) return r;
      return makeRow(r.id, r.name, r.type, r.apiKey, r.endpoint, r.voice,
        r.speed, r.pitch, r.volume, next);
    });
  }

  // —— 试听(占位) ——
  private testVoice(row: TtsProviderRow): void {
    this.testMsg = `试听中… (${row.voice})`;
    setTimeout((): void => {
      this.testMsg = `已播放: ${row.voice}（占位,无真实 TTS）`;
    }, 500);
  }

  private flashSaved(msg: string): void {
    this.savedMsg = msg;
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.flashSaved('已保存');
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  // —— 类型徽章 ——
  @Builder typeBadge(type: string): void {
    Text(type)
      .fontSize(LABEL).fontColor(this.typeBadgeColor(type))
      .padding({ left: 8, right: 8, top: 2, bottom: 2 })
      .backgroundColor(SURFACE2).borderRadius(50)
  }

  // —— 滑块组(编辑/添加共用样式) ——
  @Builder sliderGroup(
    speed: number, pitch: number, volume: number,
    onSpeed: (v: number) => void, onPitch: (v: number) => void, onVolume: (v: number) => void,
  ): void {
    Column() {
      Text('语速').fontSize(LABEL).fontColor(INK3).width('100%')
      NotionSlider({ value: speed, min: 0.5, max: 2.0, step: 0.1, labelMode: 'percent',
        onChange: (v: number): void => { onSpeed(v); } }).margin({ top: 4 })
      Text('音调').fontSize(LABEL).fontColor(INK3).width('100%').margin({ top: 10 })
      NotionSlider({ value: pitch, min: 0.5, max: 2.0, step: 0.1, labelMode: 'percent',
        onChange: (v: number): void => { onPitch(v); } }).margin({ top: 4 })
      Text('音量').fontSize(LABEL).fontColor(INK3).width('100%').margin({ top: 10 })
      NotionSlider({ value: volume, min: 0, max: 1.0, step: 0.05, labelMode: 'percent',
        onChange: (v: number): void => { onVolume(v); } }).margin({ top: 4 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ top: 8 })
  }

  // —— 编辑表单(内联) ——
  @Builder editForm(row: TtsProviderRow): void {
    Column() {
      FlatTextField({ value: this.editName, label: '名称', placeholder: 'Azure 语音',
        onChange: (v: string): void => { this.editName = v; } })
      Row() {
        Text('类型').fontSize(BODY).fontColor(INK).layoutWeight(1)
        PillSelect({ options: this.typeOptions, selectedIndex: this.editTypeIndex,
          onSelect: (i: number): void => { this.editTypeIndex = i; } })
      }.width('100%').margin({ top: 10 })
      FlatTextField({ value: this.editApiKey, label: 'API Key', placeholder: 'sk-…', mono: true, obscure: true,
        onChange: (v: string): void => { this.editApiKey = v; } }).margin({ top: 8 })
      FlatTextField({ value: this.editEndpoint, label: 'Endpoint', placeholder: 'https://…', mono: true,
        onChange: (v: string): void => { this.editEndpoint = v; } }).margin({ top: 8 })
      FlatTextField({ value: this.editVoice, label: '音色', placeholder: 'zh-CN-Xiaoxiao',
        onChange: (v: string): void => { this.editVoice = v; } }).margin({ top: 8 })
      this.sliderGroup(this.editSpeed, this.editPitch, this.editVolume,
        (v: number): void => { this.editSpeed = v; },
        (v: number): void => { this.editPitch = v; },
        (v: number): void => { this.editVolume = v; })
      Row() {
        Text('启用').fontSize(BODY).fontColor(INK).layoutWeight(1)
        AmberSwitch({ checked: this.editEnabled, switchSize: SwitchSize.Small,
          onCheckedChange: (v: boolean): void => { this.editEnabled = v; } })
      }.width('100%').margin({ top: 10 })
      Row() {
        Text('试听').fontSize(BODY).fontColor(ACCENT)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(SURFACE2).borderRadius(50)
          .onClick((): void => { this.testVoice(row); })
        Blank()
        Text('取消').fontSize(BODY).fontColor(INK3)
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.editingId = ''; })
        Text('保存').fontSize(BODY).fontColor(ACCENT).fontWeight(FontWeight.Medium)
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.saveEdit(); })
      }.width('100%').margin({ top: 10 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: ACCENT }).margin({ top: 8, bottom: 8 })
  }

  // —— 添加表单 ——
  @Builder addForm(): void {
    if (this.addOpen) {
      Column() {
        Text('新增 TTS 提供商').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 8 })
        FlatTextField({ value: this.addName, label: '名称', placeholder: 'Azure 语音',
          onChange: (v: string): void => { this.addName = v; } })
        Row() {
          Text('类型').fontSize(BODY).fontColor(INK).layoutWeight(1)
          PillSelect({ options: this.typeOptions, selectedIndex: this.addTypeIndex,
            onSelect: (i: number): void => { this.addTypeIndex = i; } })
        }.width('100%').margin({ top: 10 })
        FlatTextField({ value: this.addApiKey, label: 'API Key', placeholder: 'sk-…', mono: true, obscure: true,
          onChange: (v: string): void => { this.addApiKey = v; } }).margin({ top: 8 })
        FlatTextField({ value: this.addEndpoint, label: 'Endpoint', placeholder: 'https://…', mono: true,
          onChange: (v: string): void => { this.addEndpoint = v; } }).margin({ top: 8 })
        FlatTextField({ value: this.addVoice, label: '音色', placeholder: 'zh-CN-Xiaoxiao',
          onChange: (v: string): void => { this.addVoice = v; } }).margin({ top: 8 })
        this.sliderGroup(this.addSpeed, this.addPitch, this.addVolume,
          (v: number): void => { this.addSpeed = v; },
          (v: number): void => { this.addPitch = v; },
          (v: number): void => { this.addVolume = v; })
        Row() {
          Text('启用').fontSize(BODY).fontColor(INK).layoutWeight(1)
          AmberSwitch({ checked: this.addEnabled, switchSize: SwitchSize.Small,
            onCheckedChange: (v: boolean): void => { this.addEnabled = v; } })
        }.width('100%').margin({ top: 10 })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.addOpen = false; })
          Blank()
          Text('添加').fontSize(BODY).fontColor(ACCENT).fontWeight(FontWeight.Medium)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.confirmAdd(); })
        }.width('100%').margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      .padding(12).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: ACCENT }).margin({ top: 8, bottom: 8 })
    }
  }

  build(): void {
    Stack() {
      Column() {
        // 标题栏:返回 + 标题 + 完成
        Row() {
          Text('←').fontSize(TITLE).fontColor(INK3)
            .onClick((): void => { router.back(); })
          Text('TTS 提供商').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
            .margin({ left: 12 }).layoutWeight(1)
          Text('完成').fontSize(BODY).fontColor(ACCENT)
            .onClick((): void => { this.done(); })
        }
        .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

        if (!this.loaded) {
          Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
        } else {
          Scroll() {
            Column() {
              // 概览行 + 试听反馈
              Row() {
                Text(`共 ${this.rows.length} 个`).fontSize(META).fontColor(INK3)
                Blank()
                if (this.testMsg.length > 0) {
                  Text(this.testMsg).fontSize(META).fontColor(INK3)
                }
              }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 8 })

              if (this.rows.length === 0) {
                Column() {
                  Text('🔊').fontSize(28).fontColor(INK4)
                  Text('暂无 TTS 提供商,点下方添加').fontSize(BODY).fontColor(INK3)
                    .margin({ top: 8 })
                }
                .width('100%').alignItems(HorizontalAlign.Center)
                .padding({ top: 40, bottom: 40 })
              }

              ForEach(this.rows, (row: TtsProviderRow): void => {
                Column() {
                  // Provider 卡:name + 类型徽章 + 音色 + enabled + chevron
                  Row() {
                    Column() {
                      Text(row.name).fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
                      Row() {
                        this.typeBadge(row.type)
                        if (row.voice.length > 0) {
                          Text(row.voice).fontSize(LABEL).fontColor(INK3).margin({ left: 6 })
                        }
                      }.margin({ top: 4 })
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    AmberSwitch({ checked: row.enabled, switchSize: SwitchSize.Small,
                      onCheckedChange: (v: boolean): void => { this.toggle(row.id, v); } })
                    Text(this.editingId === row.id ? '▲' : '›')
                      .fontSize(BODY).fontColor(INK3).margin({ left: 10 })
                      .onClick((): void => { this.startEdit(row); })
                  }
                  .width('100%')
                  .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                  .backgroundColor(SURFACE)
                  .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

                  // 内联编辑表单
                  if (this.editingId === row.id) {
                    this.editForm(row)
                  }

                  // 删除按钮
                  Row() {
                    Text('删除').fontSize(LABEL).fontColor(INK3)
                      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      .onClick((): void => { this.askDelete(row.id); })
                  }.width('100%').padding({ left: 16, right: 16 }).margin({ bottom: 12 })
                }
                .width('100%')
              }, (row: TtsProviderRow): string => row.id)

              this.addForm()

              // 添加按钮
              Row() {
                Text('+ 添加 TTS 提供商').fontSize(BODY).fontColor(ACCENT)
              }
              .width('100%').justifyContent(FlexAlign.Center)
              .padding({ top: 14, bottom: 14 })
              .margin({ bottom: 32 })
              .onClick((): void => { this.openAdd(); })

              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
                  .textAlign(TextAlign.Center).width('100%').margin({ bottom: 16 })
              }
            }
            .width('100%').padding({ left: 16, right: 16 })
          }
          .layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      // 删除确认浮层
      ConfirmDialog({
        show: this.pendingDeleteId.length > 0,
        title: '删除提供商?',
        message: '此操作不可撤销',
        confirmText: '删除',
        dismissText: '取消',
        onConfirm: (): void => { this.confirmDelete(); },
        onDismiss: (): void => { this.pendingDeleteId = ''; },
      })
    }
    .width('100%').height('100%')
  }
}
