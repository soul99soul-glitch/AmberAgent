// SkillsPage — 技能列表页
// 展示可用技能卡片(名称/描述/启用徽章/最后使用时间),点击进入 SkillDetailPage
// Android 基准: feature/ui/pages/skill/SkillsPage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface SkillItem {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  lastUsed: string;
}

function makeSkill(id: string, name: string, description: string, enabled: boolean, lastUsed: string): SkillItem {
  return { id: id, name: name, description: description, enabled: enabled, lastUsed: lastUsed };
}

function placeholderLoadSkills(): Promise<SkillItem[]> {
  const seed: SkillItem[] = [
    makeSkill('skill-summary', '摘要总结', '为长文本生成结构化摘要与要点提取', true, '2026-08-01'),
    makeSkill('skill-translate', '多语翻译', '中英日韩互译,保留术语与格式', true, '2026-07-30'),
    makeSkill('skill-code-review', '代码审查', '静态分析代码质量、安全与规范', false, '2026-07-15'),
    makeSkill('skill-meeting', '会议纪要', '从转录文本提炼议题、决议与待办', true, '2026-08-02'),
    makeSkill('skill-email', '邮件起草', '按场景生成商务/正式/提醒邮件', false, '—'),
    makeSkill('skill-mindmap', '思维导图', '将主题拆解为层级化导图结构', true, '2026-07-28'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SkillsPage {
  @State items: SkillItem[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadSkills().then((list: SkillItem[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private openDetail(id: string): void {
    const params: Record<string, string> = {};
    params['skillId'] = id;
    router.pushUrl({ url: 'pages/SkillDetailPage', params: params });
  }

  private addSkill(): void {
    router.pushUrl({ url: 'pages/SkillDetailPage' });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 8 })
  }

  @Builder skillCard(item: SkillItem): void {
    Column() {
      Row() {
        Text(item.name).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(item.enabled ? '启用' : '停用')
          .fontSize(LABEL).fontColor(item.enabled ? ACCENT : INK4)
      }
      .width('100%')

      Text(item.description).fontSize(META).fontColor(INK3).margin({ top: 6 })
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })

      Row() {
        Text('最后使用').fontSize(LABEL).fontColor(INK4)
        Text(item.lastUsed).fontSize(LABEL).fontColor(INK3).margin({ left: 6 })
        Blank()
        Text('›').fontSize(BODY).fontColor(INK4)
      }
      .width('100%').margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(12)
    .onClick((): void => { this.openDetail(item.id); })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 添加
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('技能').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('+ 新技能').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.addSkill(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.items.length === 0) {
        Column() {
          Text('还没有技能').fontSize(BODY).fontColor(INK2)
          Text('点右上角添加第一个技能').fontSize(META).fontColor(INK3).margin({ top: 8 })
        }.margin({ top: 64 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader(`共 ${this.items.length.toString()} 项`)

            Column({ space: 12 }) {
              ForEach(this.items, (item: SkillItem): void => {
                this.skillCard(item)
              }, (item: SkillItem): string => item.id)
            }
            .width('100%').padding({ left: 16, right: 16 })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
