// AgentToolCallCapsule — 工具调用胶囊(对齐 Android ChatMessageTools.kt AgentToolCallCapsule)
// wrap-content pill: 图标 + 标题文本 + 状态徽章 / 审批按钮
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, SIGNAL, ERROR, META, STATUS_AMBER } from '../design/tokens.ets';
import { DotLoading } from './ui/DotLoading.ets';

// 工具状态
export enum ToolStatus {
  Running,
  WaitingApproval,
  Succeeded,
  Failed,
  Cancelled,
}

// 工具类型分类
export enum ToolKind {
  File,
  Terminal,
  Mcp,
  Screen,
  Web,
  Memory,
  Generic,
}

// 工具名 → 图标 emoji 映射(简化版,不用 hugeicons 库)
export const toolIconEmoji = (toolName: string): string => {
  if (toolName.startsWith('memory_tool')) return '✍️';
  if (toolName.startsWith('search_web') || toolName.startsWith('scrape_web') || toolName.startsWith('webview_')) return '🔍';
  if (toolName.startsWith('file_')) return '📄';
  if (toolName.startsWith('terminal_')) return '⌨️';
  if (toolName.startsWith('screen_') || toolName.startsWith('vlm_task')) return '🖥️';
  if (toolName.startsWith('mcp__')) return '📦';
  if (toolName === 'get_time_info') return '🕐';
  if (toolName === 'clipboard_tool') return '📋';
  if (toolName === 'text_to_speech') return '🔊';
  if (toolName === 'ask_user') return '❓';
  if (toolName === 'use_skill') return '✨';
  if (toolName.startsWith('wm_') || toolName.startsWith('hn_') || toolName.startsWith('reddit_') ||
      toolName.startsWith('juejin_') || toolName.startsWith('github_') || toolName.startsWith('bilibili_') ||
      toolName.startsWith('zhihu_') || toolName.startsWith('feishu_docs_')) return '🔍';
  return '🔧';
};

// 工具名 → 类型分类
export const getToolKind = (toolName: string): ToolKind => {
  if (toolName.startsWith('file_')) return ToolKind.File;
  if (toolName.startsWith('terminal_')) return ToolKind.Terminal;
  if (toolName.startsWith('mcp__')) return ToolKind.Mcp;
  if (toolName.startsWith('screen_') || toolName.startsWith('vlm_task')) return ToolKind.Screen;
  if (toolName.startsWith('search_web') || toolName.startsWith('scrape_web') || toolName.startsWith('webview_') ||
      toolName.startsWith('wm_') || toolName.startsWith('hn_') || toolName.startsWith('reddit_') ||
      toolName.startsWith('juejin_') || toolName.startsWith('github_') || toolName.startsWith('bilibili_') ||
      toolName.startsWith('zhihu_') || toolName.startsWith('feishu_docs_')) return ToolKind.Web;
  if (toolName.startsWith('memory_tool')) return ToolKind.Memory;
  return ToolKind.Generic;
};

// 工具类型 → 中文标签
export const toolKindLabel = (kind: ToolKind): string => {
  if (kind === ToolKind.File) return '文件';
  if (kind === ToolKind.Terminal) return '终端';
  if (kind === ToolKind.Mcp) return 'MCP';
  if (kind === ToolKind.Screen) return '屏幕';
  if (kind === ToolKind.Web) return '网页';
  if (kind === ToolKind.Memory) return '记忆';
  return '工具';
};

// 工具状态 → 中文标签
export const toolStatusLabel = (status: ToolStatus): string => {
  if (status === ToolStatus.Running) return '执行中';
  if (status === ToolStatus.WaitingApproval) return '待授权';
  if (status === ToolStatus.Succeeded) return '成功';
  if (status === ToolStatus.Failed) return '失败';
  return '已取消';
};

// 工具状态 → 状态色
export const toolStatusColor = (status: ToolStatus): string => {
  if (status === ToolStatus.Running) return ACCENT;
  if (status === ToolStatus.WaitingApproval) return STATUS_AMBER;
  if (status === ToolStatus.Succeeded) return SIGNAL;
  if (status === ToolStatus.Failed) return ERROR;
  return INK4;
};

// 状态徽章图标
export const toolStatusIcon = (status: ToolStatus): string => {
  if (status === ToolStatus.Succeeded) return '✓';
  if (status === ToolStatus.Running) return '';
  if (status === ToolStatus.WaitingApproval) return '⏱';
  if (status === ToolStatus.Failed) return '✕';
  return '✕';
};

@Component
export struct AgentToolCallCapsule {
  @Prop toolName: string = '';
  @Prop title: string = '';
  @Prop status: ToolStatus = ToolStatus.Running;
  @Prop showApproval: boolean = false;
  onApprove: () => void = (): void => {};
  onDeny: () => void = (): void => {};
  onCapsuleClick: () => void = (): void => {};

  build(): void {
    Row() {
      Row() {
        Text(toolIconEmoji(this.toolName))
          .fontSize(14)
          .margin({ right: 6 })

        Text(this.title)
          .fontSize(META)
          .fontColor(INK2)
          .fontWeight(FontWeight.Medium)
          .fontFamily('JetBrainsMono')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)

        if (!this.showApproval) {
          if (this.status === ToolStatus.Running) {
            DotLoading({ dotSize: 6, color: ACCENT })
          } else {
            Text(toolStatusIcon(this.status))
              .fontSize(10)
              .fontColor('#FFFFFF')
              .width(16)
              .height(16)
              .borderRadius(8)
              .backgroundColor(toolStatusColor(this.status))
              .textAlign(TextAlign.Center)
          }
        }
      }
      .padding({ left: 7, right: 7, top: 3, bottom: 3 })
      .constraintSize({ minHeight: 44 })
      .backgroundColor(SURFACE2)
      .borderRadius(50)
      .border({ width: 1, color: LINE })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => { this.onCapsuleClick(); })
      .accessibilityText(`查看工具 ${this.title}`)

      if (this.showApproval) {
        Text('✕')
          .fontSize(12)
          .fontColor(ERROR)
          .width(44)
          .height(44)
          .textAlign(TextAlign.Center)
          .onClick((): void => { this.onDeny(); })
          .accessibilityText('拒绝工具执行')
        Text('✓')
          .fontSize(12)
          .fontColor(SIGNAL)
          .width(44)
          .height(44)
          .textAlign(TextAlign.Center)
          .onClick((): void => { this.onApprove(); })
          .accessibilityText('批准工具执行')
      }
    }
    .alignItems(VerticalAlign.Center)
  }
}
