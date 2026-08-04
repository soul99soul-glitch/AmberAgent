// SettingTTSPage — 语音合成 (TTS) 设置页
// UI only:无真实 TTS 引擎;'TTS 引擎' 固定显示 '系统默认'
// 引擎/音色下拉 + 语速/音调滑块 + 试听按钮(占位)
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';

interface TtsSettings {
  engineIndex: number;
  voiceIndex: number;
  speed: number;
  pitch: number;
}

function makeTts(): TtsSettings {
  return { engineIndex: 0, voiceIndex: 0, speed: 1.0, pitch: 1.0 };
}

function placeholderLoadTts(): Promise<TtsSettings> {
  return Promise.resolve(makeTts());
}

@Entry
@Component
struct SettingTTSPage {
  @State s: TtsSettings = makeTts();
  @State testMsg: string = '';
  @State loaded: boolean = false;
  private engines: string[] = ['系统默认', 'Edge TTS (占位)', '离线引擎 (占位)'];
  private voices: string[] = ['默认女声', '默认男声', '柔和女声'];

  aboutToAppear(): void {
    placeholderLoadTts().then((v: TtsSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setEngine(i: number): void {
    const c: TtsSettings = this.s;
    this.s = { engineIndex: i, voiceIndex: c.voiceIndex, speed: c.speed, pitch: c.pitch };
  }

  private setVoice(i: number): void {
    const c: TtsSettings = this.s;
    this.s = { engineIndex: c.engineIndex, voiceIndex: i, speed: c.speed, pitch: c.pitch };
  }

  private setSpeed(v: number): void {
    const c: TtsSettings = this.s;
    this.s = { engineIndex: c.engineIndex, voiceIndex: c.voiceIndex, speed: v, pitch: c.pitch };
  }

  private setPitch(v: number): void {
    const c: TtsSettings = this.s;
    this.s = { engineIndex: c.engineIndex, voiceIndex: c.voiceIndex, speed: c.speed, pitch: v };
  }

  private test(): void {
    this.testMsg = '试听中…';
    setTimeout((): void => { this.testMsg = '已播放(系统默认引擎)'; }, 500);
  }

  private done(): void {
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder selectRow(label: string, valueLabel: string, options: string[], index: number, onSelect: (i: number) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(valueLabel).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      PillSelect({
        options: options, selectedIndex: index,
        onSelect: (i: number): void => { onSelect(i); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder sliderRow(label: string, desc: string, value: number, min: number, max: number, step: number, onChange: (v: number) => void): void {
    Column() {
      Row() {
        Column() {
          Text(label).fontSize(BODY).fontColor(INK)
          Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      }
      .width('100%')
      NotionSlider({
        value: value, min: min, max: max, step: step, labelMode: 'percent',
        onChange: (v: number): void => { onChange(v); },
      }).margin({ top: 6 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('语音合成').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('引擎')

            // TTS 引擎:固定显示 '系统默认'
            this.selectRow('TTS 引擎', '系统默认(无第三方引擎可用)', this.engines, this.s.engineIndex,
              (i: number): void => { this.setEngine(i); })

            this.selectRow('音色', '朗读消息时使用的声音', this.voices, this.s.voiceIndex,
              (i: number): void => { this.setVoice(i); })

            this.sectionHeader('语速 / 音调')

            this.sliderRow('语速', '1.0 = 正常语速', this.s.speed, 0.5, 2.0, 0.1,
              (v: number): void => { this.setSpeed(v); })

            this.sliderRow('音调', '1.0 = 正常音调', this.s.pitch, 0.5, 2.0, 0.1,
              (v: number): void => { this.setPitch(v); })

            Row() {
              Text('试听').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.test(); })
              Blank()
              if (this.testMsg.length > 0) {
                Text(this.testMsg).fontSize(META).fontColor(INK3)
              }
            }
            .width('100%').margin({ top: 16, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
