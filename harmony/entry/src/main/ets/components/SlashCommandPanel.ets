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

import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META, LABEL, MOTION_OVERLAY } from '../design/tokens.ets';

export interface SlashCommand {
  id: string;
  title: string;
  description: string;
  accent: boolean;
  marker?: string;
  group?: string;
  insertText?: string;
}

export interface QuickMessage {
  id: string;
  title: string;
  content: string;
}

@Component
export struct SlashCommandPanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop commands: SlashCommand[];
  @Prop visible: boolean = false;
  onSelect: (cmd: SlashCommand) => void = (_cmd: SlashCommand): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.visible) {
      Scroll() {
        Column() {
          if (this.commands.length > 0) {
            ForEach(this.commands, (cmd: SlashCommand, i: number) => {
              if (cmd.group !== undefined && (i === 0 || this.commands[i - 1].group !== cmd.group)) {
                Text(cmd.group)
                  .fontSize(META).fontColor(INK3).fontWeight(FontWeight.Medium)
                  .width('100%').padding({ left: 12, right: 12, top: i === 0 ? 8 : 10, bottom: 4 })
              }
              Row() {
                Text(cmd.marker === undefined ? `/${cmd.title}` : cmd.marker)
                  .fontSize(BODY).fontColor(cmd.accent ? ACCENT : INK)
                  .fontWeight(FontWeight.Medium)
                  .constraintSize({ minWidth: 28, maxWidth: 96 })
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Column() {
                  Text(cmd.title)
                    .fontSize(BODY).fontColor(INK)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(cmd.description)
                    .fontSize(LABEL).fontColor(INK3)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .layoutWeight(1).margin({ left: 8 })
              }
              .width('100%')
              .constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .onClick((): void => { this.onSelect(cmd); })
              if (i < this.commands.length - 1 && this.commands[i + 1].group === cmd.group) {
                Divider().color(LINE).strokeWidth(0.5).margin({ left: 12 })
              }
            }, (cmd: SlashCommand, i: number): string => `slash-${cmd.id}-${i}`)
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
      }
        .align(Alignment.TopStart)
      .constraintSize({ maxHeight: 320 })
      .scrollable(ScrollDirection.Vertical)
      .scrollBar(BarState.Off)
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius(12)
      .border({ width: 1, color: LINE })
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      .shadow({ radius: 10, color: '#22000000', offsetY: 3 })
    }
  }
}

// 构建斜杠命令列表(core + skills)
export const buildSlashCommands = (
  subAgentEnabled: boolean,
  subAgentMode: string,
  enabledSkills: SlashCommand[] = [],
  quickMessages: QuickMessage[] = [],
): SlashCommand[] => {
  const commands: SlashCommand[] = [
    { id: 'core.clear', title: 'clear', description: '清空当前输入框', accent: false, group: '命令' },
    { id: 'core.compact', title: 'compact', description: '立即压缩当前对话上下文', accent: false, group: '命令' },
    { id: 'core.deepread', title: 'deepread', description: '全屏打开深度阅读面板', accent: true, group: '命令' },
  ];
  if (subAgentEnabled) {
    commands.push({
      id: 'core.subagent',
      title: 'subagent',
      description: '引导 Agent 灵活使用 SubAgent',
      accent: false,
      group: '命令',
    });
  }
  if (quickMessages.length > 0) {
    for (const quick of quickMessages) {
      commands.push({
        id: `quick.${quick.id}`,
        title: quick.title.trim().length > 0 ? quick.title : '快捷消息',
        description: quick.content,
        accent: false,
        marker: 'Q',
        group: '快捷消息',
        insertText: quick.content,
      });
    }
  }
  // G2-S4:动态 skill 命令(由调用方从 SkillManager.listSkills() 装配传入)
  for (const skill of enabledSkills) {
    commands.push({
      id: skill.id,
      title: skill.title,
      description: skill.description,
      accent: skill.accent,
      marker: skill.marker === undefined ? 'S' : skill.marker,
      group: '技能',
      insertText: skill.insertText,
    });
  }
  return commands;
};
