// DeepReadTemplateWorkbenchPage — DeepRead 模板工作台
// UI only:占位持久化,无真实模板引擎。
// 对照 Android: DeepReadTemplateWorkbench.kt(503 行,简化 UI 版)
//
// 功能:
//   - 模板选择器:default / magazine / academic / brief
//   - 模板编辑器:名称 + Markdown 内容(TextArea)
//   - 字体族选择:serif / sans / mono
//   - 字号滑块
//   - 预览区(MarkdownText 渲染)
//   - 保存 / 重置 按钮
//
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
  content: string;
  fontFamily: string;
  fontSize: number;
}

function makeTemplate(
  id: string, name: string, content: string, fontFamily: string, fontSize: number,
): DeepReadTemplate {
  return { id: id, name: name, content: content, fontFamily: fontFamily, fontSize: fontSize };
}

function seedTemplates(): DeepReadTemplate[] {
  const tpl: DeepReadTemplate[] = [
    makeTemplate('default', '默认', '# {title}\n\n{summary}\n\n## 正文\n\n{content}', 'serif', 16),
    makeTemplate('magazine', '杂志', '# {title}\n\n> {summary}\n\n{content}', 'serif', 18),
    makeTemplate('academic', '学术', '# {title}\n\n**摘要**: {summary}\n\n## 1. 引言\n\n{content}', 'serif', 15),
    makeTemplate('brief', '简报', '# {title}\n\n{summary}\n\n{content}', 'sans', 14),
  ];
  return tpl;
}

@Entry
@Component
struct DeepReadTemplateWorkbenchPage {
  @State templates: DeepReadTemplate[] = seedTemplates();
  @State selectedId: string = 'default';
  @State editName: string = '';
  @State editContent: string = '';
  @State fontFamilyIndex: number = 0;
  @State fontSize: number = 16;
  @State savedMsg: string = '';
  private fontOptions: string[] = ['serif', 'sans', 'mono'];

  aboutToAppear(): void {
    this.loadSelected();
  }

  private fontIndex(family: string): number {
    const idx: number = this.fontOptions.indexOf(family);
    return idx >= 0 ? idx : 0;
  }

  private currentTemplate(): DeepReadTemplate {
    for (const t of this.templates) {
      if (t.id === this.selectedId) return t;
    }
    return this.templates[0];
  }

  // 从模板填充编辑字段
  private loadSelected(): void {
    const t: DeepReadTemplate = this.currentTemplate();
    this.editName = t.name;
    this.editContent = t.content;
    this.fontFamilyIndex = this.fontIndex(t.fontFamily);
    this.fontSize = t.fontSize;
  }

  private selectTemplate(id: string): void {
    this.selectedId = id;
    this.loadSelected();
  }

  private setName(v: string): void {
    this.editName = v;
  }

  private setContent(v: string): void {
    this.editContent = v;
  }

  private setFamily(i: number): void {
    this.fontFamilyIndex = i;
  }

  private setSize(v: number): void {
    this.fontSize = v;
  }

  // 保存:回写模板数组(占位)
  private save(): void {
    const id: string = this.selectedId;
    const updated: DeepReadTemplate = makeTemplate(
      id, this.editName.trim(), this.editContent,
      this.fontOptions[this.fontFamilyIndex], this.fontSize,
    );
    this.templates = this.templates.map((t: DeepReadTemplate): DeepReadTemplate => {
      return t.id === id ? updated : t;
    });
    this.flashSaved('已保存');
  }

  // 重置:恢复当前模板的原始值
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
    return '# 预览\n\n（模板内容为空）';
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  // —— 模板选择器:横向 pill 行 ——
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
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('DeepRead 模板').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      Scroll() {
        Column() {
          // —— 模板选择 ——
          this.sectionHeader('模板')
          this.templateSelector()

          // —— 编辑器 ——
          this.sectionHeader('编辑')

          // 名称
          Column() {
            FlatTextField({
              value: this.editName, label: '模板名称', placeholder: '默认',
              onChange: (v: string): void => { this.setName(v); },
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })

          // Markdown 内容(TextArea)
          Column() {
            Text('Markdown 内容').fontSize(META).fontColor(INK3)
              .margin({ left: 2, bottom: 8 })
            TextArea({ text: this.editContent, placeholder: '# {title}\n\n{content}' })
              .fontSize(BODY).fontColor(INK)
              .fontFamily('JetBrainsMono')
              .backgroundColor(SURFACE)
              .borderRadius(12)
              .border({ width: 1, color: LINE })
              .padding({ left: 14, right: 14, top: 14, bottom: 14 })
              .width('100%')
              .height(160)
              .onChange((v: string): void => { this.setContent(v); })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8, bottom: 12 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          // —— 字体 ——
          this.sectionHeader('字体')

          Row() {
            Column() {
              Text('字体族').fontSize(BODY).fontColor(INK)
              Text('预览正文字体族').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
            PillSelect({
              options: this.fontOptions, selectedIndex: this.fontFamilyIndex,
              onSelect: (i: number): void => { this.setFamily(i); },
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Column() {
            Row() {
              Text('字号').fontSize(BODY).fontColor(INK).layoutWeight(1)
              Text(`${Math.round(this.fontSize)} sp`).fontSize(BODY).fontColor(INK3)
            }.width('100%')
            NotionSlider({
              value: this.fontSize, min: 12, max: 32, step: 1, labelMode: 'int',
              onChange: (v: number): void => { this.setSize(v); },
            }).margin({ top: 6 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          // —— 预览 ——
          this.sectionHeader('预览')

          Column() {
            Row() {
              Text(`字体: ${this.fontOptions[this.fontFamilyIndex]}`)
                .fontSize(LABEL).fontColor(INK3)
              Text(`  ·  字号: ${Math.round(this.fontSize)}sp`)
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

          // —— 保存 / 重置 ——
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
