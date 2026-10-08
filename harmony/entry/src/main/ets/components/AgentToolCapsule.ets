// AgentToolCallCapsule — 工具调用胶囊(对齐 Android ChatMessageTools.kt AgentToolCallCapsule)
// wrap-content pill: 图标 + 标题文本 + 状态徽章 / 审批按钮
import { INK2, INK4, SURFACE2, LINE, ACCENT, SIGNAL, ERROR, META, STATUS_AMBER } from '../design/tokens.ets';
import { DotRingLoading } from './ui/DotRingLoading.ets';
import { Icon } from './ui/Icon.ets';
const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

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

// Use existing flat monochrome symbols, rather than platform emoji artwork.
export const toolIconName = (toolName: string): string => {
  if (toolName.startsWith('memory_tool')) return 'edit';
  if (toolName.startsWith('search_web') || toolName.startsWith('scrape_web') || toolName.startsWith('webview_')) return 'search';
  if (toolName.startsWith('file_')) return 'doc';
  if (toolName.startsWith('terminal_') || toolName === 'python_execute') return 'cpu';
  if (toolName.startsWith('screen_') || toolName.startsWith('vlm_task')) return 'cpu';
  if (toolName.startsWith('mcp__')) return 'plug';
  if (toolName === 'get_time_info') return 'clock';
  if (toolName === 'clipboard_tool') return 'clipboard';
  if (toolName === 'text_to_speech') return 'speaker';
  if (toolName === 'ask_user') return 'info';
  if (toolName === 'use_skill') return 'spark';
  if (toolName.startsWith('wm_') || toolName.startsWith('hn_') || toolName.startsWith('reddit_') ||
      toolName.startsWith('juejin_') || toolName.startsWith('github_') || toolName.startsWith('bilibili_') ||
      toolName.startsWith('zhihu_') || toolName.startsWith('feishu_docs_')) return 'search';
  return 'gear';
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
  if (status === ToolStatus.Succeeded) return 'check';
  if (status === ToolStatus.Running) return '';
  if (status === ToolStatus.WaitingApproval) return 'clock';
  return 'close';
};

@Component
export struct AgentToolCallCapsule {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop toolName: string = '';
  @Prop title: string = '';
  @Prop status: ToolStatus = ToolStatus.Running;
  @Prop showApproval: boolean = false;
  @Prop canOpen: boolean = true;
  @Prop canApprove: boolean = true;
  @Prop canDeny: boolean = true;
  onApprove: () => void = (): void => {};
  onDeny: () => void = (): void => {};
  onCapsuleClick: () => void = (): void => {};

  build(): void {
    Row() {
      Stack() {
        this.CapsuleBody()
      }
      .width('auto').constraintSize({ minHeight: this.canOpen ? 44 : 30, maxWidth: '100%' })
      .flexShrink(1)
      .enabled(this.canOpen)
      .onClick((): void => { if (this.canOpen) this.onCapsuleClick(); })
      .accessibilityText(`${this.title}，${toolStatusLabel(this.status)}`)

      if (this.showApproval) {
        if (this.canDeny) {
          Row() { Icon({ name: 'close', iconSize: 14, color: ERROR }); }
            .width(44).height(44).flexShrink(0)
            .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .onClick((): void => { this.onDeny(); })
            .accessibilityText('拒绝工具执行')
        }
        if (this.canApprove) {
          Row() { Icon({ name: 'check', iconSize: 14, color: SIGNAL }); }
            .width(44).height(44).flexShrink(0)
            .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .onClick((): void => { this.onApprove(); })
            .accessibilityText('批准工具执行')
        }
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').alignItems(VerticalAlign.Center)
  }

  @Builder
  private CapsuleBody(): void {
    Row({ space: 7 }) {
      Icon({ name: toolIconName(this.toolName), iconSize: 14, color: INK2 }).flexShrink(0)
      Text(this.title)
        .fontSize(META).fontColor(INK2).fontWeight(FontWeight.Medium)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).flexShrink(1)
      if (!this.showApproval) {
        if (this.status === ToolStatus.Running) {
          DotRingLoading({ ringSize: 14, color: this.accentColorStore }).flexShrink(0)
        } else {
          Icon({ name: toolStatusIcon(this.status), iconSize: 12, color: toolStatusColor(this.status) })
            .flexShrink(0)
        }
      }
    }
    .width('auto').constraintSize({ maxWidth: '100%' }).height(30)
    .padding({ left: 12, right: 12 })
    .backgroundColor(this.showApproval ? SURFACE2 : accentAlpha(this.accentColorStore, 0.10))
    .borderRadius(15).border({ width: 0.7, color: LINE })
    .alignItems(VerticalAlign.Center)
  }
}
