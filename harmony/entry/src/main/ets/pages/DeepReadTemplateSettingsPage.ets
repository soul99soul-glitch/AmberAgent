// DeepReadTemplateSettingsPage — DeepRead 模板设置页
// 模板列表(default/magazine/academic/brief) + 选中模板编辑器
//   (名称 / 描述 / Markdown 内容) + 字体族 / 字号 / 行高 / 配色方案 +
//   预览(MarkdownText 渲染) + 保存 / 重置
// 占位持久化(本地草稿),无真实模板引擎。
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避保留字;ForEach/@Builder 内不声明 const/let。
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';
import { MarkdownText } from '../components/MarkdownText.ets';

interface DeepReadTemplate {
  id: string;
  name: string;
  description: string;
  content: string;
  fontFamily: string;
  fontSize: number;
  lineHeight: number;
  colorScheme: string;
}

function makeTemplate(
  id: string, name: string, description: string, content: string,
  fontFamily: string, fontSize: number, lineHeight: number, colorScheme: string,
): DeepReadTemplate {
  return {
    id: id, name: name, description: description, content: content,
    fontFamily: fontFamily, fontSize: fontSize, lineHeight: lineHeight,
    colorScheme: colorScheme,
  };
}

function seedTemplates(): DeepReadTemplate[] {
  return [
    makeTemplate('default', '默认', '平衡的通用阅读模板',
      '# {title}\n\n{summary}\n\n## 正文\n\n{content}',
      'serif', 16, 1.6, 'auto'),
    makeTemplate('magazine', '杂志', '杂志风格,引语前置',
      '# {title}\n\n> {summary}\n\n{content}',
      'serif', 18, 1.7, 'light'),
    makeTemplate('academic', '学术', '学术排版,摘要标注',
      '# {title}\n\n**摘要**: {summary}\n\n## 1. 引言\n\n{content}',
      'serif', 15, 1.5, 'light'),
    makeTemplate('brief', '简报', '紧凑简报,无衬线',
      '# {title}\n\n{summary}\n\n{content}',
      'sans', 14, 1.5, 'auto'),
  ];
}

@Entry
@Component
struct DeepReadTemplateSettingsPage {
  @State templates: DeepReadTemplate[] = seedTemplates();
  @State selectedId: string = 'default';
  @State editName: string = '';
  @State editDescription: string = '';
  @State editContent: string = '';
  @State fontFamilyIndex: number = 0;
  @State fontSize: number = 16;
  @State lineHeight: number = 1.6;
  @State colorSchemeIndex: number = 0;
  @State savedMsg: string = '';
  private fontOptions: string[] = ['serif', 'sans', 'mono'];
  private colorOptions: string[] = ['light', 'dark', 'auto'];

  aboutToAppear(): void {
    this.loadSelected();
  }

  private fontIndex(family: string): number {
    const idx: number = this.fontOptions.indexOf(family);
    return idx >= 0 ? idx : 0;
  }

  private colorIndex(scheme: string): number {
    const idx: number = this.colorOptions.indexOf(scheme);
    return idx >= 0 ? idx : 0;
  }

  private currentTemplate(): DeepReadTemplate {
    for (const t of this.templates) {
      if (t.id === this.selectedId) return t;
    }
    return this.templates[0];
  }

  private loadSelected(): void {
    const t: DeepReadTemplate = this.currentTemplate();
    this.editName = t.name;
    this.editDescription = t.description;
    this.editContent = t.content;
    this.fontFamilyIndex = this.fontIndex(t.fontFamily);
    this.fontSize = t.fontSize;
    this.lineHeight = t.lineHeight;
    this.colorSchemeIndex = this.colorIndex(t.colorScheme);
  }

  private selectTemplate(id: string): void {
    this.selectedId = id;
    this.loadSelected();
  }

  private save(): void {
    const id: string = this.selectedId;
    const updated: DeepReadTemplate = makeTemplate(
      id, this.editName.trim(), this.editDescription.trim(), this.editContent,
      this.fontOptions[this.fontFamilyIndex], this.fontSize, this.lineHeight,
      this.colorOptions[this.colorSchemeIndex],
    );
    this.templates = this.templates.map((t: DeepReadTemplate): DeepReadTemplate => {
      return t.id === id ? updated : t;
    });
    this.flashSaved('已保存');
  }

  private reset(): void {
    this.loadSelected();
    this.flashSaved('已重置');
  }

  private done(): void {
    this.save();
    router.back();
  }

  private flashSaved(msg: string): void {
    this.savedMsg = msg;
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private previewContent(): string {
    if (this.editContent.length > 0) return this.editContent;
    return '# 预览\n\n(模板内容为空)';
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder templateSelector(): void {
    Row() {
      ForEach(this.templates, (t: DeepReadTemplate): void => {
        Text(t.name)
          .fontSize(BODY)
          .fontColor(this.selectedId === t.id ? ACCENT : INK3)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(this.selectedId === t.id ? ACCENT_LIGHT : SURFACE2)
          .borderRadius(50)
          .border({ width: 1, color: this.selectedId === t.id ? ACCENT : LINE })
          .margin({ right: 8 })
          .onClick((): void => { this.selectTemplate(t.id); })
      }, (t: DeepReadTemplate): string => t.id)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('DeepRead 模板').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      Scroll() {
        Column() {
          this.sectionHeader('模板')
          this.templateSelector()

          this.sectionHeader('编辑')

          Column() {
            FlatTextField({
              value: this.editName, label: '模板名称', placeholder: '默认',
              onChange: (v: string): void => { this.editName = v; },
            })
            FlatTextField({
              value: this.editDescription, label: '描述', placeholder: '平衡的通用阅读模板',
              singleLine: false, maxLines: 2,
              onChange: (v: string): void => { this.editDescription = v; },
            }).margin({ top: 8 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 8 })

          Column() {
            Text('Markdown 内容').fontSize(META).fontColor(INK3)
              .margin({ left: 2, bottom: 8 })
            TextArea({ text: this.editContent, placeholder: '# {title}\n\n{content}' })
              .fontSize(BODY).fontColor(INK)
              .fontFamily('monospace')
              .backgroundColor(SURFACE)
              .borderRadius(12)
              .border({ width: 1, color: LINE })
              .padding({ left: 14, right: 14, top: 12, bottom: 12 })
              .width('100%')
              .height(140)
              .onChange((v: string): void => { this.editContent = v; })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          this.sectionHeader('排版')

          Row() {
            Column() {
              Text('字体族').fontSize(BODY).fontColor(INK)
              Text('预览正文字体族').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
            PillSelect({
              options: this.fontOptions, selectedIndex: this.fontFamilyIndex,
              onSelect: (i: number): void => { this.fontFamilyIndex = i; },
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Column() {
            Row() {
              Text('字号').fontSize(BODY).fontColor(INK).layoutWeight(1)
              Text(`${Math.round(this.fontSize)} sp`).fontSize(BODY).fontColor(INK3)
            }.width('100%')
            NotionSlider({
              value: this.fontSize, min: 12, max: 32, step: 1, labelMode: 'int',
              onChange: (v: number): void => { this.fontSize = v; },
            }).margin({ top: 6 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Column() {
            Row() {
              Text('行高').fontSize(BODY).fontColor(INK).layoutWeight(1)
              Text(this.lineHeight.toFixed(2)).fontSize(BODY).fontColor(INK3)
            }.width('100%')
            NotionSlider({
              value: this.lineHeight, min: 1.2, max: 2.0, step: 0.05, labelMode: '',
              onChange: (v: number): void => { this.lineHeight = v; },
            }).margin({ top: 6 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Row() {
            Column() {
              Text('配色方案').fontSize(BODY).fontColor(INK)
              Text('light / dark / auto(跟随系统)').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
            PillSelect({
              options: this.colorOptions, selectedIndex: this.colorSchemeIndex,
              onSelect: (i: number): void => { this.colorSchemeIndex = i; },
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          this.sectionHeader('预览')

          Column() {
            Row() {
              Text(`字体: ${this.fontOptions[this.fontFamilyIndex]}`)
                .fontSize(LABEL).fontColor(INK3)
              Text(`  ·  字号: ${Math.round(this.fontSize)}sp`)
                .fontSize(LABEL).fontColor(INK3)
              Text(`  ·  行高: ${this.lineHeight.toFixed(2)}`)
                .fontSize(LABEL).fontColor(INK3)
            }.width('100%').margin({ bottom: 8 })

            MarkdownText({
              content: this.previewContent(),
              fontSize: this.fontSize,
              fontColor: INK,
            })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding(16).backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(12)
          .margin({ bottom: 16 })

          Row() {
            Text('重置').fontSize(BODY).fontColor(INK3)
              .padding({ left: 16, right: 16, top: 10, bottom: 10 })
              .backgroundColor(SURFACE2).borderRadius(50)
              .onClick((): void => { this.reset(); })
            Blank()
            Text('保存').fontSize(BODY).fontColor('#FFFFFF').fontWeight(FontWeight.Medium)
              .padding({ left: 24, right: 24, top: 10, bottom: 10 })
              .backgroundColor(ACCENT).borderRadius(50)
              .onClick((): void => { this.save(); })
          }
          .width('100%')
          .margin({ bottom: 16 })

          if (this.savedMsg.length > 0) {
            Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              .textAlign(TextAlign.Center).width('100%').margin({ bottom: 16 })
          }
        }
        .width('100%').padding({ left: 16, right: 16, bottom: 32 })
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
