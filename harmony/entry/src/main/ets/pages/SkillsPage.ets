// SkillsPage — 技能管理(接 SkillManager 真实后端)
//
// Android 基准: pages/extensions/SkillsPage.kt — 列表/启停/删除。
//   鸿蒙接 chat-domain SkillManager(AppContainer.getSkillManager,
//   filesDir/skills 根 + 内置 rawfile 资产);启停 = 当前 assistant.enabledSkills
//   经 patchAssistant/saveAssistants 持久化(对齐 SkillManager.kt setSkillEnabled)。

import type { Assistant, SkillMetadata } from '@amber/chat-domain';
import { saveAssistants, patchAssistant } from '@amber/chat-domain';
import { getSkillManager, getChatAssistants, getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SkillsPage {
  @State skills: SkillMetadata[] = [];
  @State enabledNames: string[] = [];
  @State loaded: boolean = false;
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

  build(): void {
    Column() {
      PageTitleBar({ title: '技能' })

      if (this.loaded && this.skills.length === 0) {
        Column() {
          Text('// 暂无技能').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          Text('内置技能会随首次启动安装；也可在对话中让 Agent 创建')
            .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.skills, (s: SkillMetadata): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(s.name).fontSize(BODY).fontWeight(FontWeight.Medium)
                    .fontColor(INK).fontFamily('JetBrainsMono')
                    .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Toggle({ type: ToggleType.Switch, isOn: this.isEnabled(s.name) })
                    .selectedColor(ACCENT)
                    .onChange((on: boolean): void => { this.toggle(s.name, on); })
                }
                .width('100%')

                Text(s.description.length > 0 ? s.description : '（无描述）')
                  .fontSize(BODY2).fontColor(INK3).margin({ top: 4 })
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })

                Row() {
                  Text(`${this.isEnabled(s.name) ? '已启用' : '已禁用'}`)
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                  Blank()
                  Icon({ name: 'trash', iconSize: 15, color: ERROR })
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
                .width('100%').margin({ top: 6 })
              }
              .padding(14)
              .backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(14)
              .margin({ left: 18, right: 18, bottom: 10 })
            }
          }, (s: SkillMetadata): string => s.name)
        }
        .width('100%').layoutWeight(1).padding({ top: 10 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
