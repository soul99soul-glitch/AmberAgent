// SlashCommandPanel — 斜杠命令面板(第三梯队)
// Android 基准: ai/ChatInputComposers.kt:524-620 SlashCommandPanel
//   当用户输入 / 时弹出命令列表,点击执行对应 action
//
// 命令清单(MVP):
//   /clear — 清空输入框
//   /compact — 压缩上下文
//   /deepread — 插入 /deepread 前缀
//   /subagent — 插入 subagent 引导文本(subagent enabled 时)
//
// 后续迭代: quickMessages / skills / 过滤 / 样式增强

import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';

export interface SlashCommand {
  id: string;
  title: string;
  description: string;
  accent: boolean;
}

@Component
export struct SlashCommandPanel {
  @Prop commands: SlashCommand[];
  @Prop visible: boolean = false;
  onSelect: (cmd: SlashCommand) => void = (_cmd: SlashCommand): void => {};

  build(): void {
    if (this.visible) {
      Column() {
        if (this.commands.length > 0) {
          ForEach(this.commands, (cmd: SlashCommand, i: number) => {
            Row() {
              Text(`/${cmd.title}`)
                .fontSize(BODY).fontColor(cmd.accent ? ACCENT : INK)
                .fontWeight(FontWeight.Medium)
              Text(cmd.description)
                .fontSize(LABEL).fontColor(INK3)
                .layoutWeight(1).margin({ left: 8 })
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.onSelect(cmd); })
            if (i < this.commands.length - 1) {
              Divider().color(LINE).strokeWidth(0.5)
            }
          }, (cmd: SlashCommand, i: number): string => `slash-${cmd.id}`)
        } else {
          // G2-S4:空匹配行(对齐 Android SlashCommandEmptyRow)
          Row() {
            Text('无匹配命令')
              .fontSize(LABEL).fontColor(INK3)
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
        }
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius(12)
      .border({ width: 1, color: LINE })
      // The parent Stack is the composer; position above its actual height.
      .position({ left: 0, bottom: '100%' })
      .zIndex(20)
      .shadow({ radius: 10, color: '#22000000', offsetY: 3 })
    }
  }
}

// 构建斜杠命令列表(core + skills)
export const buildSlashCommands = (
  subAgentEnabled: boolean,
  subAgentMode: string,
  enabledSkills: SlashCommand[] = [],
): SlashCommand[] => {
  const commands: SlashCommand[] = [
    { id: 'core.clear', title: 'clear', description: '清空当前输入框', accent: false },
    { id: 'core.compact', title: 'compact', description: '立即压缩当前对话上下文', accent: false },
    { id: 'core.deepread', title: 'deepread', description: '全屏打开深度阅读面板', accent: true },
  ];
  if (subAgentEnabled) {
    commands.push({
      id: 'core.subagent',
      title: 'subagent',
      description: '引导 Agent 灵活使用 SubAgent',
      accent: false,
    });
  }
  // G2-S4:动态 skill 命令(由调用方从 SkillManager.listSkills() 装配传入)
  for (const skill of enabledSkills) {
    commands.push(skill);
  }
  return commands;
};
