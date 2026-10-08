// iOS ChatSubAgentResultCard 对应：结果默认折叠，点击在聊天原位展开完整 Markdown。
import type { UIMessagePartTool } from '@amber/chat-domain';
import { subAgentNameFromTools, subAgentFinalTextFromTools } from '@amber/chat-domain';
import { INK2, INK3, LINE, SURFACE, META, MOTION_OVERLAY } from '../design/tokens.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';
import { loadSubAgentHistoricalText } from '../platform_impl/SubAgentRunDisplaySupport.ets';

@Component
export struct SubAgentResultCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop runId: string = '';
  @Prop @Watch('refreshResult') tools: UIMessagePartTool[];
  onToggleResult: () => void = (): void => {};
  @State expanded: boolean = false;
  @State resultText: string = '';
  @State loading: boolean = false;
  private alive: boolean = false;
  private readSequence: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.resultText = subAgentFinalTextFromTools(this.runId, this.tools);
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.readSequence++;
  }

  private refreshResult(): void {
    this.resultText = subAgentFinalTextFromTools(this.runId, this.tools);
    if (!this.alive || !this.expanded) return;
    const sequence: number = ++this.readSequence;
    const runId: string = this.runId;
    this.loading = true;
    loadSubAgentHistoricalText(runId, this.tools).then((text: string): void => {
      if (!this.alive || sequence !== this.readSequence || runId !== this.runId) return;
      this.resultText = text;
      this.loading = false;
    }).catch((): void => {
      if (this.alive && sequence === this.readSequence) this.loading = false;
    });
  }

  private toggle(): void {
    this.onToggleResult();
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.expanded = !this.expanded;
    });
    if (this.expanded) this.refreshResult();
  }

  build(): void {
    Column() {
      Column() {
        Row({ space: 7 }) {
          SubAgentAvatar({
            subagentId: subAgentNameFromTools(this.tools),
            name: subAgentNameFromTools(this.tools),
            avatarSize: 20,
          })
          Text(`@${subAgentNameFromTools(this.tools)}`)
            .fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK2)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ maxWidth: '60%' })
          Text('已返回结果').fontSize(META).fontColor(INK3)
          if (this.expanded) { Blank() }
          Icon({ name: 'chevron_down', iconSize: 12, color: INK3 })
            .rotate({ angle: this.expanded ? 180 : 0 })
        }
        .height(44).padding({ left: 10, right: 10 })
        .width(this.expanded ? '100%' : 'auto')
        .constraintSize({ maxWidth: '100%' })
        .onClick((): void => { this.toggle(); })
        .accessibilityText(`@${subAgentNameFromTools(this.tools)}，已返回结果，${this.expanded ? '收起' : '展开'}`)
        if (this.expanded) {
          Column() {
            if (this.resultText.trim().length > 0) {
              MarkdownText({ content: this.resultText, fontSize: 14, fontColor: INK2 })
            } else {
              Text(this.loading ? '正在读取子代理结果…' : '暂无结果').fontSize(META).fontColor(INK3)
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 10, right: 10, top: 2, bottom: 10 })
        }
      }
      .constraintSize({ maxWidth: '100%', minWidth: this.expanded ? '100%' : 0 })
      .backgroundColor(SURFACE)
      .borderRadius(this.expanded ? 16 : 22)
      .border({ width: 0.7, color: LINE })
      .clip(true)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').alignItems(HorizontalAlign.Start)
  }
}
