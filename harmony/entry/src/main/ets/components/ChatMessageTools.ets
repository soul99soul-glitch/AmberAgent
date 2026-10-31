// ChatMessageTools — 工具调用卡片组件(G4-S2 升级)
//
// Android 基准: ChatMessageTools.kt(985 行)
// 升级内容: AgentToolCallCapsule pill + 状态徽章 + 图标映射 + toolDisplayTitle
//   + 失败检测 + 拒绝原因 + 点击胶囊查看输出详情 + 工具类型分类
// 降级(跳过): shimmer / FaviconRow / ZoomableAsyncImage / TTS replay / workspace-file 卡片

import type { UIMessagePartTool, UIMessagePartText, UIMessagePartImage, ToolApprovalSummary,
  JsonObject, JsonValue } from '@amber/chat-domain';
import {
  toolActivityStatus, toolOutputJson, jevAutoApprovalReason, toolDisplayTitle, toolStaticDisplayTitle,
  buildToolApprovalSummary, toolInputAsJson, approvalTargetProfile,
} from '@amber/chat-domain';
import { MOTION_OVERLAY, INK2, INK3, SURFACE, LINE, ERROR, META, LABEL, PAD_ITEM, RADIUS } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { GeneratedImageCarousel } from './GeneratedImageCarousel.ets';
import { isSSHApprovalTool, sshApprovalTargetLabel, sshApprovalCommand, sshApprovalProtocolLabel } from './SSHApprovalSummary.ets';
import { SSHUIColors } from './SSHUIColors.ets';
import { RecipeApprovalView, recipeApprovalView } from './RecipeApprovalSummary.ets';
import { pluginApprovalView } from './PluginApprovalSummary.ets';
import { JevApprovalFacts } from './JevApprovalFacts.ets';
import { NovelChapterPlanProposalCard } from './NovelChapterPlanProposalCard.ets';
import { parseNovelChapterPlanProposal } from '../platform_impl/NovelPlanProposalProjection.ets';
import { ToolApprovalSummaryCard } from './ToolApprovalSummary.ets';
import type { MessageRowViewState } from './MessageRowViewState.ets';
import {
  AgentToolCallCapsule,
  ToolStatus,
  toolIconName,
} from './AgentToolCapsule.ets';

// photos_pick 缩略图:边长 / 间距(内容左缘与胶囊标题对齐 = CONTENT_INSET)
const PHOTO_THUMB_SIZE: number = 64;
const PHOTO_THUMB_GAP: number = 6;
const CONTENT_INSET: number = 32;

@Component
export struct ChatMessageTools {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  viewState: MessageRowViewState | null = null;
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

  aboutToAppear(): void {
    if (this.viewState !== null && this.part.toolCallId.length > 0) {
      this.outputExpanded = this.viewState.toolOutputExpanded.get(this.part.toolCallId) ?? false;
    }
  }

  aboutToDisappear(): void {
    this.saveOutputState();
  }

  private saveOutputState(): void {
    if (this.viewState !== null && this.part.toolCallId.length > 0) {
      this.viewState.toolOutputExpanded.set(this.part.toolCallId, this.outputExpanded);
    }
  }

  private toggleOutput(): void {
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.outputExpanded = !this.outputExpanded;
      this.saveOutputState();
    });
  }

  private refreshSSHColors(): void { this.sshColors = new SSHUIColors(); }

  private packageApprovalView(): RecipeApprovalView | null {
    return recipeApprovalView(this.part) ?? pluginApprovalView(this.part);
  }

  // F15:复用 chat-domain tool_activity 既有结构化判定
  //   (toolActivityStatus → ok===false(cancelled/empty_selection → 已取消)/
  //    indicatesFailure:exit_code≠0 / error / failed / status∈{failed,error,denied};
  //    超长输出嗅探开头 {"ok":false);不再扫描正文是否含 error/exception/failed
  //   字样 —— 搜索/源码/日志正文可合法包含这些词(UI-F15 原始误报)。
  private resolveStatus(): ToolStatus {
    const activity: string = toolActivityStatus(this.part, this.streaming, toolOutputJson(this.part));
    if (activity === 'waiting_for_permission') return ToolStatus.WaitingApproval;
    if (activity === 'cancelled') return ToolStatus.Cancelled;
    if (activity === 'failed') return ToolStatus.Failed;
    if (activity === 'succeeded') return ToolStatus.Succeeded;
    return ToolStatus.Running;
  }

  // 胶囊标题:display_title → 静态中文标签 → 沙盒标题 → recipe/plugin 前缀 → 原名。
  //   状态不再拼进标题(状态图标 + 无障碍文本已表达)。
  private toolTitle(): string {
    return toolDisplayTitle(this.part);
  }

  // 授权界面用的标题:不取模型 display_title,以工具真实身份为准
  private approvalTitle(): string {
    return toolStaticDisplayTitle(this.part);
  }

  private isPending(): boolean {
    return this.part.approvalState.type === 'pending';
  }

  // 通用待授权摘要:recipe/plugin/SSH/本章计划提案已有专用卡片时不重复展示
  private genericApprovalSummary(): ToolApprovalSummary | null {
    if (!this.isPending()) return null;
    if (this.packageApprovalView() !== null || isSSHApprovalTool(this.part.toolName)) return null;
    if (parseNovelChapterPlanProposal(this.part) !== null) return null;
    // 改/删 SSH 配置时补上保存的 profile,卡片才能写清「改哪一条」。
    const input: JsonObject = toolInputAsJson(this.part);
    const profileId: JsonValue | undefined = input['id'];
    return buildToolApprovalSummary(this.part.toolName, input,
      { sshProfile: approvalTargetProfile(typeof profileId === 'string' ? profileId : null) });
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
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(view.stepLabel).fontSize(LABEL).fontColor(this.sshColors.secondary).width('100%')
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (view.kind === 'import') {
        Text(`候选 hash: ${view.hash}\n基线: ${view.baseHash ?? '首次安装'}`)
          .fontSize(LABEL).fontFamily(FONT_MONO).fontColor(this.sshColors.secondary)
          .width('100%').wordBreak(WordBreak.BREAK_ALL)
      } else if (view.pendingStep !== null) {
        if (isSSHApprovalTool(view.pendingStep.toolName)) {
          Text(`${sshApprovalProtocolLabel(view.pendingStep)} · ${sshApprovalTargetLabel(view.pendingStep)}`)
            .fontSize(META).fontColor(this.sshColors.ink).fontFamily(FONT_MONO)
            .width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).wordBreak(WordBreak.BREAK_ALL)
        }
        Text(view.pendingStep.input).fontSize(LABEL).fontColor(this.sshColors.secondary)
          .fontFamily(FONT_MONO).lineHeight(17).width('100%').maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
    }
    .width('100%').padding(PAD_ITEM)
    .backgroundColor(this.sshColors.inset).borderRadius(RADIUS).margin({ bottom: 6 })
    .onClick((): void => { if (this.canOpenPreview) this.onOpenPreview(); })
  }

  build(): void {
    Column() {
      if (this.packageApprovalView() !== null && this.part.approvalState.type === 'pending') {
        this.RecipeSummary(this.packageApprovalView()!)
      } else if (isSSHApprovalTool(this.part.toolName) && this.part.approvalState.type === 'pending') {
        Column({ space: 4 }) {
          Text(`${sshApprovalProtocolLabel(this.part)} · ${sshApprovalTargetLabel(this.part)}`)
            .fontSize(META).fontColor(this.sshColors.ink).fontFamily(FONT_MONO)
            .width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).wordBreak(WordBreak.BREAK_ALL)
          Text(sshApprovalCommand(this.part))
            .fontSize(LABEL).fontColor(this.sshColors.secondary).fontFamily(FONT_MONO).lineHeight(17)
            .width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).wordBreak(WordBreak.BREAK_ALL)
        }
        .width('100%').padding(PAD_ITEM)
        .backgroundColor(this.sshColors.inset).borderRadius(RADIUS).margin({ bottom: 6 })
        .onClick((): void => { if (this.canOpenPreview) this.onOpenPreview(); })
      }
      // E13:本章计划提案专用摘要(批准/拒绝仍走下方原胶囊回调,同一 toolCallId)
      if (this.part.approvalState.type === 'pending') {
        NovelChapterPlanProposalCard({ view: parseNovelChapterPlanProposal(this.part) })
      }
      // 通用待授权摘要(日历/提醒/通知/闹钟/模型槽位/WebMount… + 未知工具标量参数兜底)
      if (this.genericApprovalSummary() !== null) {
        ToolApprovalSummaryCard({
          summary: this.genericApprovalSummary()!,
          heading: this.approvalTitle(),
          iconName: toolIconName(this.part.toolName),
          canOpen: this.canOpenPreview,
          onOpen: (): void => { this.onOpenPreview(); },
        })
      }
      // 工具胶囊(pill)
      AgentToolCallCapsule({
        toolName: this.part.toolName,
        title: this.toolTitle(),
        status: this.resolveStatus(),
        showApproval: this.part.approvalState.type === 'pending' && (this.canApprove || this.canDeny),
        approvalSubject: this.approvalTitle(),
        canOpen: this.canOpenPreview,
        canApprove: this.canApprove && this.part.metadata?.['ssh_trust_settings_required'] !== true,
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
        .width('100%').padding({ left: CONTENT_INSET, top: 4 })
      }

      // denied → 拒绝原因
      if (this.part.approvalState.type === 'denied' && this.deniedLabel.length > 0) {
        Text(this.deniedLabel)
          .fontSize(LABEL)
          .fontColor(ERROR)
          .width('100%')
          .margin({ top: 4, left: CONTENT_INSET })
      }

      // generate_image:图片轮播(「修改」= edit 流程,回传宿主)
      if (this.part.toolName === 'generate_image' && this.hasImageOutput()) {
        GeneratedImageCarousel({
          imageUrls: this.getImageUrls(),
          showActions: true,
          showModify: true,
          onModify: (url: string): void => { this.onModifyImage(url); },
        })
          .margin({ top: 4, left: CONTENT_INSET })
      }

      // photos_pick:所选照片缩略图(点击打开详情)
      if (this.part.toolName === 'photos_pick' && this.hasImageOutput()) {
        Scroll() {
          Row({ space: PHOTO_THUMB_GAP }) {
            ForEach(this.getImageUrls(), (url: string, idx: number): void => {
              Image(url)
                .width(PHOTO_THUMB_SIZE).height(PHOTO_THUMB_SIZE)
                .objectFit(ImageFit.Cover)
                .borderRadius(RADIUS)
                .border({ width: 0.5, color: LINE })
                .clip(true)
                .onClick((): void => { if (this.canOpenPreview) this.onOpenPreview(); })
                .accessibilityText(`所选照片 ${idx + 1}`)
            }, (url: string, idx: number): string => `photo-${idx}-${url.length}`)
          }
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .align(Alignment.Start)
        .width('100%')
        .padding({ left: CONTENT_INSET, top: 4 })
      }

      // 无详情入口的宿主保留结果；Chat 只在点击胶囊后展示。
      if (!this.canOpenPreview && this.part.toolName !== 'generate_image' && this.firstOutputText().length > 0) {
        Column() {
          Column() {
            if (this.outputExpanded) {
              Text(this.firstOutputText())
                .fontSize(LABEL)
                .fontColor(INK2)
                .fontFamily(FONT_MONO)
                .width('100%')
                .lineHeight(16)
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            } else {
              Text(this.firstOutputText())
                .fontSize(LABEL)
                .fontColor(INK2)
                .fontFamily(FONT_MONO)
                .maxLines(3)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .lineHeight(16)
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            }
            // 与上方输出文本同一左缘(卡片 padding 已提供缩进,不再叠加左 padding)
            Text(this.outputExpanded ? '收起' : '展开')
              .fontSize(LABEL)
              .fontColor(INK3)
              .padding({ right: 12, top: 10, bottom: 6 })
              .margin({ top: 2 })
              .onClick((): void => { this.toggleOutput(); })
              .accessibilityText(this.outputExpanded ? '收起工具输出' : '展开工具输出')
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .backgroundColor(SURFACE)
          .borderRadius(8)
          .border({ width: 0.5, color: LINE })
          .clip(true)
        }
        .width('100%')
        .padding({ left: CONTENT_INSET, top: 4 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 4 })
  }
}
