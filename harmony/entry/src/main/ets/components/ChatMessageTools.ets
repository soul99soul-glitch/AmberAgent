// ChatMessageTools — 工具调用卡片组件(G4-S2 升级)
//
// Android 基准: ChatMessageTools.kt(985 行)
// 升级内容: AgentToolCallCapsule pill + 状态徽章 + 图标映射 + toolDisplayTitle
//   + 失败检测 + 拒绝原因弹窗 + output 预览折叠 + 工具类型分类
// 降级(跳过): shimmer / FaviconRow / ZoomableAsyncImage / TTS replay / workspace-file 卡片

import type { UIMessagePartTool, UIMessagePartText, UIMessagePartImage } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ERROR, META, LABEL } from '../design/tokens.ets';
import { GeneratedImageCarousel } from './GeneratedImageCarousel.ets';
import {
  AgentToolCallCapsule,
  ToolStatus,
  ToolKind,
  toolIconEmoji,
  getToolKind,
  toolKindLabel,
} from './AgentToolCapsule.ets';

@Component
export struct ChatMessageTools {
  @Prop part: UIMessagePartTool;
  onApprove: (toolCallId: string) => void = (): void => {};
  onDeny: (toolCallId: string) => void = (): void => {};
  onOpenPreview: () => void = (): void => {};
  deniedLabel: string = '';
  @State outputExpanded: boolean = false;

  // 状态判定(对齐 Android toolHasFailure)
  private resolveStatus(): ToolStatus {
    const approvalType: string = this.part.approvalState.type;
    if (approvalType === 'pending') return ToolStatus.WaitingApproval;
    if (approvalType === 'denied') return ToolStatus.Cancelled;

    // 检查 output 中是否有失败标志
    if (this.part.output.length > 0) {
      for (const p of this.part.output) {
        if (p.type === 'text') {
          const text: string = (p as UIMessagePartText).text.toLowerCase();
          if (text.includes('error') || text.includes('exception') || text.includes('failed')) {
            return ToolStatus.Failed;
          }
        }
      }
      return ToolStatus.Succeeded;
    }

    // 无 output:approved 但无结果 → running
    if (approvalType === 'approved') return ToolStatus.Running;
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

  build(): void {
    Column() {
      // 工具胶囊(pill)
      AgentToolCallCapsule({
        toolName: this.part.toolName,
        title: this.toolTitle(),
        status: this.resolveStatus(),
        showApproval: this.part.approvalState.type === 'pending',
        onApprove: (): void => { this.onApprove(this.part.toolCallId); },
        onDeny: (): void => { this.onDeny(this.part.toolCallId); },
        onCapsuleClick: (): void => { this.onOpenPreview(); },
      })

      // denied → 拒绝原因
      if (this.part.approvalState.type === 'denied' && this.deniedLabel.length > 0) {
        Text(this.deniedLabel)
          .fontSize(LABEL)
          .fontColor(ERROR)
          .width('100%')
          .margin({ top: 4, left: 32 })
      }

      // generate_image:图片轮播
      if (this.part.toolName === 'generate_image' && this.hasImageOutput()) {
        GeneratedImageCarousel({ imageUrls: this.getImageUrls(), showActions: true })
          .margin({ top: 4, left: 32 })
      }

      // output 预览(折叠/展开)
      if (this.part.toolName !== 'generate_image' && this.firstOutputText().length > 0) {
        Column() {
          if (this.outputExpanded) {
            Text(this.firstOutputText())
              .fontSize(LABEL)
              .fontColor(INK2)
              .fontFamily('monospace')
              .width('100%')
              .lineHeight(16)
          } else {
            Text(this.firstOutputText())
              .fontSize(LABEL)
              .fontColor(INK2)
              .fontFamily('monospace')
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
              .lineHeight(16)
          }
          Text(this.outputExpanded ? '收起' : '展开')
            .fontSize(LABEL)
            .fontColor(INK3)
            .margin({ top: 2 })
            .onClick((): void => { this.outputExpanded = !this.outputExpanded; })
        }
        .width('100%')
        .margin({ top: 4, left: 32 })
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 0.5, color: LINE })
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 4 })
  }
}
