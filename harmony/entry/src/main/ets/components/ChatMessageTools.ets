// ChatMessageTools — 工具调用卡片组件(G4-S2 升级)
//
// Android 基准: ChatMessageTools.kt(985 行)
// 升级内容: AgentToolCallCapsule pill + 状态徽章 + 图标映射 + toolDisplayTitle
//   + 失败检测 + 拒绝原因 + 点击胶囊查看输出详情 + 工具类型分类
// 降级(跳过): shimmer / FaviconRow / ZoomableAsyncImage / TTS replay / workspace-file 卡片

import type { UIMessagePartTool, UIMessagePartText, UIMessagePartImage } from '@amber/chat-domain';
import { toolActivityStatus, toolOutputJson, jevAutoApprovalReason } from '@amber/chat-domain';
import { MOTION_OVERLAY, INK2, INK3, SURFACE, LINE, ERROR, META, LABEL } from '../design/tokens.ets';
import { GeneratedImageCarousel } from './GeneratedImageCarousel.ets';
import { isSSHApprovalTool, sshApprovalTargetLabel, sshApprovalCommand, sshApprovalProtocolLabel } from './SSHApprovalSummary.ets';
import { SSHUIColors } from './SSHUIColors.ets';
import { RecipeApprovalView, recipeApprovalView } from './RecipeApprovalSummary.ets';
import { pluginApprovalView } from './PluginApprovalSummary.ets';
import { JevApprovalFacts } from './JevApprovalFacts.ets';
import { NovelChapterPlanProposalCard } from './NovelChapterPlanProposalCard.ets';
import { parseNovelChapterPlanProposal } from '../platform_impl/NovelPlanProposalProjection.ets';
import {
  AgentToolCallCapsule,
  ToolStatus,
  ToolKind,
  getToolKind,
  toolKindLabel,
} from './AgentToolCapsule.ets';

@Component
export struct ChatMessageTools {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  @Prop jevSubjectHash: string = '';
  onApprove: (toolCallId: string) => void = (): void => {};
  onDeny: (toolCallId: string) => void = (): void => {};
  onOpenPreview: () => void = (): void => {};
  deniedLabel: string = '';
  @Prop canApprove: boolean = true;
  @Prop canDeny: boolean = true;
  @Prop canOpenPreview: boolean = true;
  // F15:结构化终态需要 loading(工具执行中 vs 已完成)
  @Prop streaming: boolean = false;
  // generate_image 修改回调(edit 流程,回传宿主 ChatPage)
  onModifyImage: (url: string) => void = (_url: string): void => {};
  @State outputExpanded: boolean = false;
  @State sshColors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshSSHColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshSSHColors') themePackId: string = '';

  private refreshSSHColors(): void { this.sshColors = new SSHUIColors(); }

  private packageApprovalView(): RecipeApprovalView | null {
    return recipeApprovalView(this.part) ?? pluginApprovalView(this.part);
  }

  // F15:复用 chat-domain tool_activity 既有结构化判定
  //   (toolActivityStatus → indicatesFailure:exit_code≠0 / error / failed /
  //    status∈{failed,error,denied});不再扫描正文是否含 error/exception/failed
  //   字样 —— 搜索/源码/日志正文可合法包含这些词(UI-F15 原始误报)。
  private resolveStatus(): ToolStatus {
    const activity: string = toolActivityStatus(this.part, this.streaming, toolOutputJson(this.part));
    if (activity === 'waiting_for_permission') return ToolStatus.WaitingApproval;
    if (activity === 'cancelled') return ToolStatus.Cancelled;
    if (activity === 'failed') return ToolStatus.Failed;
    if (activity === 'succeeded') return ToolStatus.Succeeded;
    return ToolStatus.Running;
  }

  // 工具显示标题(简化版 toolDisplayTitle)
  private toolTitle(): string {
    const name: string = this.part.toolName;
    const kind: ToolKind = getToolKind(name);
    const kindLabel: string = toolKindLabel(kind);
    const status: ToolStatus = this.resolveStatus();

    // 状态动词
    let statusVerb: string = '';
    if (status === ToolStatus.Running) statusVerb = '执行中';
    else if (status === ToolStatus.WaitingApproval) statusVerb = '待授权';
    else if (status === ToolStatus.Succeeded) statusVerb = '成功';
    else if (status === ToolStatus.Failed) statusVerb = '失败';
    else statusVerb = '已取消';

    return `${kindLabel} ${name} · ${statusVerb}`;
  }

  // output 首条文本(预览)
  private firstOutputText(): string {
    for (const p of this.part.output) {
      if (p.type === 'text') return (p as UIMessagePartText).text;
    }
    return '';
  }

  private hasImageOutput(): boolean {
    for (const p of this.part.output) {
      if (p.type === 'image') return true;
    }
    return false;
  }

  private getImageUrls(): string[] {
    const urls: string[] = [];
    for (const p of this.part.output) {
      if (p.type === 'image') {
        urls.push((p as UIMessagePartImage).url);
      }
    }
    return urls;
  }

  @Builder
  private RecipeSummary(view: RecipeApprovalView): void {
    Column({ space: 4 }) {
      Text(view.title).fontSize(META).fontColor(this.sshColors.ink).width('100%')
      Text(view.stepLabel).fontSize(LABEL).fontColor(this.sshColors.secondary).width('100%')
      if (view.kind === 'import') {
        Text(`候选 hash: ${view.hash}\n基线: ${view.baseHash ?? '首次安装'}`)
          .fontSize(LABEL).fontFamily('JetBrainsMono').fontColor(this.sshColors.secondary)
          .width('100%').wordBreak(WordBreak.BREAK_ALL)
      } else if (view.pendingStep !== null) {
        if (isSSHApprovalTool(view.pendingStep.toolName)) {
          Text(`${sshApprovalProtocolLabel(view.pendingStep)} · ${sshApprovalTargetLabel(view.pendingStep)}`)
            .fontSize(META).fontColor(this.sshColors.ink).fontFamily('JetBrainsMono')
            .width('100%').maxLines(3).wordBreak(WordBreak.BREAK_ALL)
        }
        Text(view.pendingStep.input).fontSize(LABEL).fontColor(this.sshColors.secondary)
          .fontFamily('JetBrainsMono').lineHeight(17).width('100%').maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
    }
    .width('100%').padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(this.sshColors.inset).borderRadius(8).margin({ bottom: 6 })
    .onClick((): void => { if (this.canOpenPreview) this.onOpenPreview(); })
  }

  build(): void {
    Column() {
      if (this.packageApprovalView() !== null && this.part.approvalState.type === 'pending') {
        this.RecipeSummary(this.packageApprovalView()!)
      } else if (isSSHApprovalTool(this.part.toolName) && this.part.approvalState.type === 'pending') {
        Column({ space: 4 }) {
          Text(`${sshApprovalProtocolLabel(this.part)} · ${sshApprovalTargetLabel(this.part)}`)
            .fontSize(META).fontColor(this.sshColors.ink).fontFamily('JetBrainsMono')
            .width('100%').maxLines(3).wordBreak(WordBreak.BREAK_ALL)
          Text(sshApprovalCommand(this.part))
            .fontSize(LABEL).fontColor(this.sshColors.secondary).fontFamily('JetBrainsMono').lineHeight(17)
            .width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('100%').padding({ left: 12, right: 12, top: 10, bottom: 10 })
        .backgroundColor(this.sshColors.inset).borderRadius(8).margin({ bottom: 6 })
        .onClick((): void => { if (this.canOpenPreview) this.onOpenPreview(); })
      }
      // E13:本章计划提案专用摘要(批准/拒绝仍走下方原胶囊回调,同一 toolCallId)
      if (this.part.approvalState.type === 'pending') {
        NovelChapterPlanProposalCard({ view: parseNovelChapterPlanProposal(this.part) })
      }
      // 工具胶囊(pill)
      AgentToolCallCapsule({
        toolName: this.part.toolName,
        title: this.toolTitle(),
        status: this.resolveStatus(),
        showApproval: this.part.approvalState.type === 'pending' && (this.canApprove || this.canDeny),
        canOpen: this.canOpenPreview,
        canApprove: this.canApprove,
        canDeny: this.canDeny,
        onApprove: (): void => { this.onApprove(this.part.toolCallId); },
        onDeny: (): void => { this.onDeny(this.part.toolCallId); },
        onCapsuleClick: (): void => { this.onOpenPreview(); },
      })

      JevApprovalFacts({ part: this.part, subjectHash: this.jevSubjectHash })

      if (jevAutoApprovalReason(this.part).length > 0) {
        Column() {
          Text(jevAutoApprovalReason(this.part)).fontSize(LABEL).fontColor(INK2)
            .lineHeight(18).width('100%')
        }
        .width('100%').padding({ left: 32, top: 4 })
      }

      // denied → 拒绝原因
      if (this.part.approvalState.type === 'denied' && this.deniedLabel.length > 0) {
        Text(this.deniedLabel)
          .fontSize(LABEL)
          .fontColor(ERROR)
          .width('100%')
          .margin({ top: 4, left: 32 })
      }

      // generate_image:图片轮播(「修改」= edit 流程,回传宿主)
      if (this.part.toolName === 'generate_image' && this.hasImageOutput()) {
        GeneratedImageCarousel({
          imageUrls: this.getImageUrls(),
          showActions: true,
          showModify: true,
          onModify: (url: string): void => { this.onModifyImage(url); },
        })
          .margin({ top: 4, left: 32 })
      }

      // 无详情入口的宿主保留结果；Chat 只在点击胶囊后展示。
      if (!this.canOpenPreview && this.part.toolName !== 'generate_image' && this.firstOutputText().length > 0) {
        Column() {
          Column() {
            if (this.outputExpanded) {
              Text(this.firstOutputText())
                .fontSize(LABEL)
                .fontColor(INK2)
                .fontFamily('JetBrainsMono')
                .width('100%')
                .lineHeight(16)
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            } else {
              Text(this.firstOutputText())
                .fontSize(LABEL)
                .fontColor(INK2)
                .fontFamily('JetBrainsMono')
                .maxLines(3)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .lineHeight(16)
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            }
            Text(this.outputExpanded ? '收起' : '展开')
              .fontSize(LABEL)
              .fontColor(INK3)
              .padding({ left: 12, right: 12, top: 10, bottom: 6 })
              .margin({ top: 2 })
              .onClick((): void => {
                this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.outputExpanded = !this.outputExpanded;
                });
              })
              .accessibilityText(this.outputExpanded ? '收起工具输出' : '展开工具输出')
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .backgroundColor(SURFACE)
          .borderRadius(8)
          .border({ width: 0.5, color: LINE })
          .clip(true)
        }
        .width('100%')
        .padding({ left: 32, top: 4 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 4 })
  }
}
