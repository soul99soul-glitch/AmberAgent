// SettingSlidesFontPage — 幻灯片字体设置页
// F19:当前版本未支持 —— 生成式幻灯片在鸿蒙端无实现,本页字体族/字号
//   既无持久化(KV)也无任何消费者,原“完成”仅返回、私有常量每次重置属伪保存。
//   处置:入口已从 SkillsPage 移除;本页显式标记未支持并禁用全部编辑,
//   不再接受修改、不再提供“完成”动作(仅可返回),避免制造已保存的错觉。
import {
  INK, INK2, INK3, BG, BODY, META, TEXT_STYLES, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
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

// F19:硬编码未支持;无真实配置源时不得假装可编辑/可保存
const SLIDES_FONT_SUPPORTED: boolean = false;

@Entry
@Component
struct SettingSlidesFontPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State s: SlidesFontSettings = makeFontSettings(0, 18);
  @State loaded: boolean = false;
  private fonts: string[] = ['系统默认', '思源黑体', '思源宋体', '等距更纱黑体', 'JetBrains Mono'];

  aboutToAppear(): void {
    if (!SLIDES_FONT_SUPPORTED) {
      // 未支持:不加载伪配置、不接受编辑
      this.loaded = true;
      return;
    }
    placeholderLoadFontSettings().then((v: SlidesFontSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setFamily(i: number): void {
    if (!SLIDES_FONT_SUPPORTED) return;
    const c: SlidesFontSettings = this.s;
    this.s = makeFontSettings(i, c.fontSize);
  }

  private setSize(v: number): void {
    if (!SLIDES_FONT_SUPPORTED) return;
    const c: SlidesFontSettings = this.s;
    this.s = makeFontSettings(c.fontFamilyIndex, v);
  }

  private currentFontFamily(): string {
    if (this.s.fontFamilyIndex < this.fonts.length) {
      return this.fonts[this.s.fontFamilyIndex];
    }
    return this.fonts[0];
  }

  @Builder selectRow(label: string, desc: string, options: string[], index: number,
    isLast: boolean, onSelect: (i: number) => void): void {
    Column() {
      Row() {
        Column() {
          Text(label)
            .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          Text(desc)
            .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3).margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        PillSelect({
          options: options, selectedIndex: index,
          onSelect: (i: number): void => { onSelect(i); },
        })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  @Builder sliderRow(label: string, desc: string, value: number, min: number, max: number, step: number,
    isLast: boolean, onChange: (v: number) => void): void {
    Column() {
      Column() {
        Text(label)
          .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
        Text(desc)
          .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK3).margin({ top: 4 })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      NotionSlider({
        value: value, min: min, max: max, step: step, labelMode: 'int',
        onChange: (v: number): void => { onChange(v); },
      }).margin({ top: 6 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      PageTitleBar({ title: '幻灯片字体' })

      if (!SLIDES_FONT_SUPPORTED) {
        // F19:显式未支持,禁编辑、无“完成”动作(仅可返回)
        Column() {
          Text('此功能在当前版本未支持')
            .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          Text('生成式幻灯片尚未在鸿蒙端实现,字体资源设置暂无存储与消费者,故不提供修改。')
            .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(FONT_SANS)
            .fontColor(INK3).margin({ top: 8 })
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding({ left: 16, right: 16, top: 40 })
        .layoutWeight(1)
      } else if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '字体族' })

            this.selectRow('字体', '幻灯片正文字体族', this.fonts, this.s.fontFamilyIndex, true,
              (i: number): void => { this.setFamily(i); })

            SectionHeader({ label: '字号' })

            this.sliderRow('字号', '正文字号(sp)', this.s.fontSize, 12, 32, 1, true,
              (v: number): void => { this.setSize(v); })

            SectionHeader({ label: '预览' })

            Column() {
              Text('幻灯片标题示例')
                .fontSize(this.s.fontSize + 6).fontWeight(600)
                .lineHeight(lineHeightForSize(this.s.fontSize + 6)).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
              Text('这是正文内容预览,用于检查字体效果。')
                .fontSize(this.s.fontSize).fontWeight(FontWeight.Normal)
                .lineHeight(lineHeightForSize(this.s.fontSize)).fontFamily(FONT_SANS).fontColor(INK2).margin({ top: 8 })
              Text(`字体: ${this.currentFontFamily()}  ·  字号: ${Math.round(this.s.fontSize)}sp`)
                .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO).fontColor(INK3).margin({ top: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 16, bottom: 16 })
            .margin({ bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
