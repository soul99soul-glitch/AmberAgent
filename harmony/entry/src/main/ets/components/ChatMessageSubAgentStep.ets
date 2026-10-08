// 子代理在聊天中只显示紧凑身份和状态；完整任务与输出由运行详情承载。
import type { UIMessagePartTool } from '@amber/chat-domain';
import { subAgentNameFromTools, subAgentWorkSummary, subAgentStatusFromTools, subAgentStatusLabel } from '@amber/chat-domain';
import { INK2, INK3, SURFACE2, LINE, ACCENT, META, SUCCESS_FG, ERROR } from '../design/tokens.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';
import { Icon } from './ui/Icon.ets';
import { SubAgentResultCard } from './SubAgentResultCard.ets';

@Component
export struct ChatMessageSubAgentStep {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  @Prop canOpenRun: boolean = true;
  onOpenRun: (runId: string) => void = (_id: string): void => {};
  onResultToggle: () => void = (): void => {};

  private statusColor(): string {
    const status: string = subAgentStatusFromTools(this.tools);
    if (status === 'completed') return SUCCESS_FG;
    if (status === 'failed' || status === 'timed_out' || status === 'interrupted') return ERROR;
    return status === 'running' ? ACCENT : INK3;
  }

  build(): void {
    Column() {
      Row({ space: 7 }) {
        SubAgentAvatar({
          subagentId: subAgentNameFromTools(this.tools),
          name: subAgentNameFromTools(this.tools),
          avatarSize: 20,
          status: subAgentStatusFromTools(this.tools),
        })
        Text(`@${subAgentNameFromTools(this.tools)}`)
          .fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK2)
          .constraintSize({ maxWidth: '55%' })
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(subAgentWorkSummary(this.tools))
          .fontSize(10).fontWeight(FontWeight.Medium).fontColor(this.statusColor())
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (subAgentStatusFromTools(this.tools) === 'completed') {
          Icon({ name: 'check', iconSize: 12, color: this.statusColor() })
        } else if (subAgentStatusFromTools(this.tools) === 'running') {
          LoadingProgress().width(18).height(18).color(this.statusColor())
        } else {
          Icon({ name: 'info', iconSize: 12, color: this.statusColor() })
        }
        if (this.canOpenRun) {
          Icon({ name: 'chevron_right', iconSize: 10, color: INK3 })
        }
      }
      .height(30)
      .constraintSize({ maxWidth: '100%' })
      .padding({ left: 12, right: 12 })
      .backgroundColor(SURFACE2)
      .borderRadius(15)
      .border({ width: 0.7, color: LINE })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => {
        if (this.canOpenRun) this.onOpenRun(this.runId);
      })
      .accessibilityText(`@${subAgentNameFromTools(this.tools)}，${subAgentStatusLabel(subAgentStatusFromTools(this.tools))}，查看任务和输出`)
      if (subAgentStatusFromTools(this.tools) === 'completed') {
        SubAgentResultCard({
          runId: this.runId, tools: this.tools,
          onToggleResult: (): void => { this.onResultToggle(); },
        }).margin({ top: 8 })
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 10 })
  }
}
