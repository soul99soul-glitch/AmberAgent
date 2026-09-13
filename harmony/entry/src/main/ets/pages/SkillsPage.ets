// SkillsPage — 技能管理(接 SkillManager 真实后端)
//
// Android 基准: pages/extensions/SkillsPage.kt — 列表/启停/删除。
//   鸿蒙接 chat-domain SkillManager(AppContainer.getSkillManager,
//   filesDir/skills 根 + 内置 rawfile 资产);启停 = 当前 assistant.enabledSkills
//   经 patchAssistant/saveAssistants 持久化(对齐 SkillManager.kt setSkillEnabled)。

import { router } from '@kit.ArkUI';
import type { Assistant, SkillMetadata } from '@amber/chat-domain';
import { saveAssistants, patchAssistant } from '@amber/chat-domain';
import { getSkillManager, getChatAssistants, getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, SURFACE2, INK, INK3, INK4, LINE, LINE2, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, NavRow, IconSeat, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SkillsPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State skills: SkillMetadata[] = [];
  @State enabledNames: string[] = [];
  @State loaded: boolean = false;
  @State pressedSkill: string = '';
  private assistant: Assistant | null = null;

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    getChatAssistants().then((list: Assistant[]): void => {
      this.assistant = list.length > 0 ? list[0] : null;
      this.enabledNames = this.assistant !== null ? this.assistant.enabledSkills : [];
      this.skills = getSkillManager().listSkills();
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private isEnabled(name: string): boolean {
    return this.enabledNames.indexOf(name) >= 0;
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  private toggle(name: string, on: boolean): void {
    const cur: Assistant | null = this.assistant;
    if (cur === null) return;
    const ids: string[] = on
      ? (this.enabledNames.indexOf(name) >= 0 ? this.enabledNames.slice() : [...this.enabledNames, name])
      : this.enabledNames.filter((x: string): boolean => x !== name);
    // saveAssistants 全量写:patch 进完整列表,不能只存单个
    getChatAssistants().then((all: Assistant[]): void => {
      const next: Assistant[] = [];
      for (const a of all) {
        next.push(a.id === cur.id ? patchAssistant(a, { enabledSkills: ids }) : a);
      }
      saveAssistants(getChatKvStore(), next).then((): void => {
        this.enabledNames = ids;
        this.assistant = patchAssistant(cur, { enabledSkills: ids });
      });
    });
  }

  @Builder skillRow(s: SkillMetadata, isLast: boolean): void {
    Column() {
      Row() {
        IconSeat({ iconName: 'puzzle' }).margin({ right: 12 })
        Column() {
          Row() {
            Text(s.name)
              .fontSize(BODY)
              .fontWeight(600)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(INK)
              .layoutWeight(1)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Stack({ alignContent: Alignment.Start }) {
              Column()
                .width(42).height(24).borderRadius(99)
                .backgroundColor(this.isEnabled(s.name) ? this.accentColorStore : LINE2)
              Column()
                .width(20).height(20).borderRadius(10)
                .backgroundColor('#FFFFFF')
                .margin({ left: this.isEnabled(s.name) ? 22 : 2 })
                .animation({ duration: 150, curve: Curve.EaseOut })
            }
            .clip(true)
            .margin({ left: 12 })
            .onClick((): void => { this.toggle(s.name, !this.isEnabled(s.name)); })
            .accessibilityText(this.isEnabled(s.name) ? '已开启' : '已关闭')
          }
          .width('100%')

          Text(s.description.length > 0 ? s.description : '（无描述）')
            .fontSize(BODY2)
            .fontWeight(FontWeight.Normal)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(FONT_SANS)
            .fontColor(INK3)
            .margin({ top: 4 })
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })

          Row() {
            Text(this.isEnabled(s.name) ? '已启用' : '已禁用')
              .fontSize(MONO_SMALL)
              .fontWeight(FontWeight.Normal)
              .lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO)
              .fontColor(INK4)
            Blank()
            Row() {
              Icon({ name: 'trash', iconSize: 15, color: ERROR })
            }
            .width(44)
            .height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .accessibilityText('删除技能')
            .onClick((): void => {
              AlertDialog.show({
                title: '删除技能', message: `删除技能「${s.name}」？`,
                primaryButton: { value: '取消', action: (): void => {} },
                secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                  getSkillManager().deleteSkill(s.name);
                  this.reload();
                } },
              });
            })
          }
          .width('100%')
          .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressedSkill === s.name ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressedSkill = s.name;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressedSkill = '';
      })
      if (!isLast) {
        Row().height(1).width('100%').margin({ left: 16, right: 16 }).backgroundColor(LINE)
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '技能' })

      Scroll() {
        Column() {
          SectionHeader({ label: '服务与任务' })
          NavRow({ label: 'MCP 服务器', desc: '管理外部 MCP 工具', iconName: 'plug',
            onRowClick: (): void => { this.go('pages/ChatMcpSettingsPage'); } })
          // F19:移除「Slides 字体资源」入口 —— 该页为私有常量占位,
          //   无存储/无消费者(生成式幻灯片在鸿蒙端未实现),保留入口属误导。
          NavRow({ label: '定时任务', valueText: 'Beta', desc: '查看 Agent 的定时任务和计划运行状态', iconName: 'clock', isLast: true,
            onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })

          SectionHeader({ label: '技能' })
          if (this.loaded && this.skills.length === 0) {
            Column() {
              Text('// 暂无技能')
                .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontColor(INK3).fontFamily(FONT_MONO_MEDIUM)
              Text('内置技能会随首次启动安装；也可在对话中让 Agent 创建')
                .fontSize(BODY2).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(FONT_SANS).fontColor(INK4).margin({ top: 8 })
            }
            .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
          } else {
            ForEach(this.skills, (s: SkillMetadata, idx: number): void => {
              this.skillRow(s, idx === this.skills.length - 1);
            }, (s: SkillMetadata): string => s.name)
          }
          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1)
      .align(Alignment.TopStart)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
