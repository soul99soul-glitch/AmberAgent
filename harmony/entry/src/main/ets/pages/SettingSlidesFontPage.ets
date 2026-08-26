// SettingSlidesFontPage — 幻灯片字体设置页
// 字体族选择(PillSelect) + 字号滑块(NotionSlider) + 预览文本
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';

interface SlidesFontSettings {
  fontFamilyIndex: number;
  fontSize: number;
}

function makeFontSettings(familyIndex: number, size: number): SlidesFontSettings {
  return { fontFamilyIndex: familyIndex, fontSize: size };
}

function placeholderLoadFontSettings(): Promise<SlidesFontSettings> {
  return Promise.resolve(makeFontSettings(0, 18));
}

@Entry
@Component
struct SettingSlidesFontPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State s: SlidesFontSettings = makeFontSettings(0, 18);
  @State loaded: boolean = false;
  private fonts: string[] = ['系统默认', '思源黑体', '思源宋体', '等距更纱黑体', 'JetBrains Mono'];

  aboutToAppear(): void {
    placeholderLoadFontSettings().then((v: SlidesFontSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setFamily(i: number): void {
    const c: SlidesFontSettings = this.s;
    this.s = makeFontSettings(i, c.fontSize);
  }

  private setSize(v: number): void {
    const c: SlidesFontSettings = this.s;
    this.s = makeFontSettings(c.fontFamilyIndex, v);
  }

  private done(): void {
    router.back();
  }

  private currentFontFamily(): string {
    if (this.s.fontFamilyIndex < this.fonts.length) {
      return this.fonts[this.s.fontFamilyIndex];
    }
    return this.fonts[0];
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
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
        value: value, min: min, max: max, step: step, labelMode: 'int',
        onChange: (v: number): void => { onChange(v); },
      }).margin({ top: 6 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('幻灯片字体').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('字体族')

            this.selectRow('字体', '幻灯片正文字体族', this.fonts, this.s.fontFamilyIndex,
              (i: number): void => { this.setFamily(i); })

            this.sectionHeader('字号')

            this.sliderRow('字号', '正文字号(sp)', this.s.fontSize, 12, 32, 1,
              (v: number): void => { this.setSize(v); })

            this.sectionHeader('预览')

            Column() {
              Text('幻灯片标题示例')
                .fontSize(this.s.fontSize + 6)
                .fontColor(INK)
                .fontWeight(FontWeight.Bold)
              Text('这是正文内容预览,用于检查字体效果。')
                .fontSize(this.s.fontSize)
                .fontColor(INK2)
                .margin({ top: 8 })
              Text(`字体: ${this.currentFontFamily()}  ·  字号: ${Math.round(this.s.fontSize)}sp`)
                .fontSize(META)
                .fontColor(INK3)
                .margin({ top: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)
            .margin({ bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .align(Alignment.Start)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
