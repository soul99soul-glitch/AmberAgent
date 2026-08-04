// SkillsPage — 技能(G7 杂志风重构)
//
// 结构: PageHeader(← + SKILL eyebrow + 技能) + Scroll
//   - Library 卡(🧩 图标 + Skill 库 + 统计药丸 + 说明 + 操作按钮 + 全量规整)
//   - SectionHeader "已安装 · 6"
//   - 技能卡(逐行 SkillRow: 图标 + 名称/徽章/描述 + ⋯ 切换)
// 设计来源: Amber Redesign SkillsScreen / oc-amber.css

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';

// ── 数据模型 ──
interface SkillItem {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
}

function makeSkill(id: string, name: string, description: string, enabled: boolean): SkillItem {
  return { id: id, name: name, description: description, enabled: enabled };
}

// 6 条占位技能(4 启用 / 2 未启用)
function placeholderSkills(): SkillItem[] {
  const list: SkillItem[] = [
    makeSkill('skill-summary', '摘要总结', '为长文本生成结构化摘要与要点提取,支持分段与关键句高亮。', true),
    makeSkill('skill-translate', '多语翻译', '中英日韩互译,保留术语与格式,支持领域词典。', true),
    makeSkill('skill-code-review', '代码审查', '静态分析代码质量、安全与规范,给出可执行修改建议。', false),
    makeSkill('skill-meeting', '会议纪要', '从转录文本提炼议题、决议与待办,自动归档到知识库。', true),
    makeSkill('skill-email', '邮件起草', '按场景生成商务/正式/提醒邮件,支持语气与长度调节。', false),
    makeSkill('skill-mindmap', '思维导图', '将主题拆解为层级化导图结构,可导出为 Markdown 大纲。', true),
  ];
  return list;
}

@Entry
@Component
struct SkillsPage {
  @State skills: SkillItem[] = [];

  aboutToAppear(): void {
    this.skills = placeholderSkills();
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2000 });
  }

  private enabledCount(): number {
    return this.skills.filter((s: SkillItem): boolean => s.enabled).length;
  }
  private disabledCount(): number {
    return this.skills.filter((s: SkillItem): boolean => !s.enabled).length;
  }

  private toggleSkill(item: SkillItem): void {
    const next: SkillItem[] = this.skills.map((s: SkillItem): SkillItem => {
      if (s.id === item.id) {
        return { id: s.id, name: s.name, description: s.description, enabled: !s.enabled };
      }
      return s;
    });
    this.skills = next;
  }

  private openDetail(item: SkillItem): void {
    const params: Record<string, string> = {};
    params['skillId'] = item.id;
    router.pushUrl({ url: 'pages/SkillDetailPage', params: params });
  }

  private addSkill(): void {
    router.pushUrl({ url: 'pages/SkillDetailPage' });
  }

  private tidyAll(): void {
    this.toast('全量规整:已扫描 ' + this.skills.length.toString() + ' 个 Skill');
  }

  @Builder
  pageHeader() {
    Row() {
      Text('←')
        .fontSize(24)
        .fontColor(INK)
        .onClick((): void => { router.back(); })
      Column() {
        Text('SKILL')
          .fontSize(10.5)
          .fontWeight(600)
          .fontColor(INK3)
          .fontFamily('monospace')
        Text('技能')
          .fontSize(TITLE)
          .fontWeight(700)
          .fontColor(INK)
          .margin({ top: 1 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 8 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }

  // 统计药丸: label + 数值, 边框/底色随状态
  @Builder
  statPill(label: string, kind: string) {
    Text(label)
      .fontSize(11.5)
      .fontWeight(600)
      .fontColor(kind === 'accent' ? ACCENT : INK3)
      .fontFamily('monospace')
      .padding({ left: 10, right: 10, top: 5, bottom: 5 })
      .borderRadius(999)
      .border({ width: 1, color: kind === 'accent' ? ACCENT : LINE2 })
      .backgroundColor(kind === 'accent' ? ACCENT_LIGHT : SURFACE)
  }

  // 40x40 方形操作按钮(边框 LINE2 / 底 SURFACE2)
  @Builder
  squareButton(glyph: string) {
    Column() {
      Text(glyph)
        .fontSize(18)
        .fontColor(INK2)
    }
    .width(40)
    .height(40)
    .borderRadius(11)
    .border({ width: 1, color: LINE2 })
    .backgroundColor(SURFACE2)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  // Library 卡
  @Builder
  libraryCard() {
    Column() {
      // 图标 + 标题
      Row() {
        Column() {
          Icon({ name: 'puzzle', iconSize: 22, color: BG })
        }
        .width(42)
        .height(42)
        .borderRadius(12)
        .backgroundColor(INK)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        Text('Skill 库')
          .fontSize(16.5)
          .fontWeight(700)
          .fontColor(INK)
          .margin({ left: 13 })
      }
      .width('100%')

      // 统计药丸行
      Row() {
        this.statPill('已安装 ' + this.skills.length.toString(), 'plain')
        this.statPill('已启用 ' + this.enabledCount().toString(), 'accent')
        this.statPill('未启用 ' + this.disabledCount().toString(), 'plain')
      }
      .width('100%')
      .margin({ top: 14 })

      // 说明
      Text('Agent 会在每次运行前重新扫描已安装 Skill。')
        .fontSize(12.5)
        .fontColor(INK3)
        .margin({ top: 13 })

      // 操作按钮行
      Row() {
        Column() {
          Text('+')
            .fontSize(18)
            .fontColor(INK2)
        }
        .width(40)
        .height(40)
        .borderRadius(11)
        .border({ width: 1, color: LINE2 })
        .backgroundColor(SURFACE2)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .onClick((): void => { this.addSkill(); })

        Column() {
          Text('↑')
            .fontSize(18)
            .fontColor(INK2)
        }
        .width(40)
        .height(40)
        .borderRadius(11)
        .border({ width: 1, color: LINE2 })
        .backgroundColor(SURFACE2)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .margin({ left: 9 })
        .onClick((): void => { this.toast('导入 Skill 包(占位)'); })

        Column() {
          Text('↻')
            .fontSize(18)
            .fontColor(INK2)
        }
        .width(40)
        .height(40)
        .borderRadius(11)
        .border({ width: 1, color: LINE2 })
        .backgroundColor(SURFACE2)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .margin({ left: 9 })
        .onClick((): void => { this.toast('重新扫描 Skill(占位)'); })

        Blank()

        Text('全量规整')
          .fontSize(14)
          .fontWeight(600)
          .fontColor(ACCENT)
          .height(40)
          .padding({ left: 16, right: 16 })
          .borderRadius(11)
          .border({ width: 1, color: ACCENT })
          .backgroundColor(ACCENT_LIGHT)
          .textAlign(TextAlign.Center)
          .onClick((): void => { this.tidyAll(); })
      }
      .width('100%')
      .margin({ top: 15 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .padding(16)
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(14)
    .margin({ top: 8 })
  }

  @Builder
  sectionHeader() {
    Row() {
      Text('//')
        .fontSize(11)
        .fontWeight(600)
        .fontColor(ACCENT)
        .fontFamily('monospace')
      Text(' 已安装 · ' + this.skills.length.toString())
        .fontSize(11)
        .fontWeight(600)
        .fontColor(INK3)
        .fontFamily('monospace')
    }
    .width('100%')
    .padding({ left: 6, top: 24, bottom: 10 })
  }

  // 单个 SkillRow
  @Builder
  skillRow(item: SkillItem, last: boolean) {
    Row() {
      // 图标盒(on: ACCENT_LIGHT/ACCENT, off: SURFACE2/INK4)
      Column() {
        Icon({ name: 'puzzle', iconSize: 20, color: item.enabled ? ACCENT : INK4 })
      }
      .width(38)
      .height(38)
      .borderRadius(10)
      .backgroundColor(item.enabled ? ACCENT_LIGHT : SURFACE2)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)

      // 名称 + 徽章 + 描述
      Column() {
        Row() {
          Text(item.name)
            .fontSize(15.5)
            .fontWeight(600)
            .fontColor(INK)
          if (!item.enabled) {
            Text('未启用')
              .fontSize(10.5)
              .fontWeight(600)
              .fontColor(INK3)
              .fontFamily('monospace')
              .backgroundColor(SURFACE2)
              .padding({ left: 7, right: 7, top: 2, bottom: 2 })
              .borderRadius(5)
              .margin({ left: 8 })
          }
        }
        .width('100%')

        Text(item.description)
          .fontSize(13)
          .fontColor(INK3)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 5 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 13 })

      // ⋯ 切换启用
      Text('⋯')
        .fontSize(20)
        .fontColor(INK4)
        .onClick((): void => { this.toggleSkill(item); })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .border({ width: { bottom: last ? 0 : 1 }, color: { bottom: LINE } })
    .alignItems(VerticalAlign.Center)
    .onClick((): void => { this.openDetail(item); })
  }

  build(): void {
    Column() {
      this.pageHeader()
      Scroll() {
        Column() {
          this.libraryCard()
          this.sectionHeader()
          Column() {
            ForEach(this.skills, (item: SkillItem, index: number): void => {
              this.skillRow(item, index === this.skills.length - 1)
            }, (item: SkillItem): string => item.id)
          }
          .width('100%')
          .backgroundColor(SURFACE)
          .border({ width: 1, color: LINE })
          .borderRadius(14)
          .clip(true)
        }
        .width('100%')
      }
      .layoutWeight(1)
      .padding({ left: 18, right: 18, top: 0, bottom: 28 })
      .backgroundColor(BG)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}
