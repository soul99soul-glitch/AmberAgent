// ExtensionSelector — 扩展选择器(ui/ExtensionSelector.kt:197 行)
// Android 基准: 4 tab(QuickMessages/ModeInjections/Lorebooks/Skills) + HorizontalPager
//   每 tab 列出可选扩展(checkbox toggle → assistant.copy)
// ArkUI MVP: 3 tab(ModeInjections/Lorebooks/Skills);QuickMessages tab 待内容 store 移植后补
//   Tabs + TabContent(替代 HorizontalPager)
//
// 后端依赖:assistant.{quickMessageIds, modeInjectionIds, lorebookIds, enabledSkills}
//   + SkillManager.listSkills()(已就绪)
//   QuickMessage/ModeInjection/Lorebook 的内容 store 部分未移植 → tab 内容用 ID 列表 + 名称占位
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';

export interface ExtensionItem {
  id: string;
  name: string;
  description: string;
}

@Component
export struct ExtensionSelector {
  @Prop modeInjections: ExtensionItem[] = [];
  @Prop lorebooks: ExtensionItem[] = [];
  @Prop skills: ExtensionItem[] = [];
  @Prop selectedModeInjectionIds: string[] = [];
  @Prop selectedLorebookIds: string[] = [];
  @Prop selectedSkillIds: string[] = [];
  onToggleModeInjection: (id: string) => void = (_id: string): void => {};
  onToggleLorebook: (id: string) => void = (_id: string): void => {};
  onToggleSkill: (id: string) => void = (_id: string): void => {};
  @State selectedTab: number = 0;

  private isSelected(list: string[], id: string): boolean {
    return list.indexOf(id) >= 0;
  }

  @Builder ExtensionList(items: ExtensionItem[], selectedIds: string[], onToggle: (id: string) => void): void {
    if (items.length === 0) {
      Column() {
        Text('暂无可用扩展').fontSize(BODY).fontColor(INK3)
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.Center)
    } else {
      Scroll() {
        Column() {
          ForEach(items, (item: ExtensionItem): void => {
            Row() {
              Column() {
                Text(item.name).fontSize(BODY).fontColor(INK2)
                if (item.description.length > 0) {
                  Text(item.description)
                    .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              Checkbox()
                .select(this.isSelected(selectedIds, item.id))
                .selectedColor(ACCENT)
                .onChange((_v: boolean): void => { onToggle(item.id); })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
          }, (item: ExtensionItem): string => item.id)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
  }

  build(): void {
    Column() {
      Tabs({ index: this.selectedTab }) {
        TabContent() {
          this.ExtensionList(this.modeInjections, this.selectedModeInjectionIds,
            (id: string): void => { this.onToggleModeInjection(id); })
        }
        .tabBar('模式注入')

        TabContent() {
          this.ExtensionList(this.lorebooks, this.selectedLorebookIds,
            (id: string): void => { this.onToggleLorebook(id); })
        }
        .tabBar('Lorebook')

        TabContent() {
          this.ExtensionList(this.skills, this.selectedSkillIds,
            (id: string): void => { this.onToggleSkill(id); })
        }
        .tabBar('技能')
      }
      .onChange((i: number): void => { this.selectedTab = i; })
      .layoutWeight(1)
      .barMode(BarMode.Fixed)
    }
    .width('100%').height('100%')
  }
}
