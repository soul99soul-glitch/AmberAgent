// AgentToolCallCapsule — 工具调用胶囊(对齐 Android ChatMessageTools.kt AgentToolCallCapsule)
// wrap-content pill: 图标 + 标题文本 + 状态徽章 / 审批按钮
import { INK2, INK4, SURFACE2, LINE, ACCENT, SIGNAL, ERROR, META, LABEL, STATUS_AMBER, RADIUS_PILL } from '../design/tokens.ets';
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
//   Icon 名只用 ui/Icon.ets 已登记的(clock/check_circle/bell/person/image/globe/brain/plug/eye/trash…)
export const toolIconName = (toolName: string): string => {
  if (toolName.startsWith('memory_tool')) return 'edit';
  // 设备日程 / 个人上下文 / 设置 / WebMount 对齐工具族(须先于下方 wm_ 通配)
  if (toolName.startsWith('calendar_') || toolName.startsWith('alarm_') || toolName === 'alarms_list') return 'clock';
  if (toolName.startsWith('reminder_') || toolName === 'reminders_list') return 'check_circle';
  if (toolName.startsWith('notification_')) return 'bell';
  if (toolName === 'contacts_pick') return 'person';
  if (toolName === 'photos_pick') return 'image';
  if (toolName === 'weather_read') return 'globe';
  if (toolName === 'settings_set_model_slot') return 'brain';
  if (toolName === 'mcp_describe_tool') return 'plug';
  if (toolName === 'wm_get' || toolName.startsWith('wm_visual_')) return 'eye';
  if (toolName === 'wm_clear_session') return 'trash';
  if (toolName === 'wm_act') return 'globe';
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
  // 审批按钮的无障碍名里点名工具(静态标签,不取模型 display_title);空 → 用 title
  @Prop approvalSubject: string = '';
  onApprove: () => void = (): void => {};
  onDeny: () => void = (): void => {};
  onCapsuleClick: () => void = (): void => {};

  private subject(): string {
    return this.approvalSubject.length > 0 ? this.approvalSubject : this.title;
  }

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
          this.ApprovalButton('close', '拒绝', ERROR, `拒绝执行：${this.subject()}`, (): void => { this.onDeny(); })
        }
        if (this.canApprove) {
          this.ApprovalButton('check', '允许', SIGNAL, `允许执行：${this.subject()}`, (): void => { this.onApprove(); })
        }
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').alignItems(VerticalAlign.Center)
  }

  // 图标 + 文字药丸;外层 44 高保证触控面积,可见药丸 30 高与胶囊对齐
  @Builder
  private ApprovalButton(icon: string, label: string, color: string, a11y: string, action: () => void): void {
    Row() {
      Row({ space: 4 }) {
        Icon({ name: icon, iconSize: 12, color: color })
        Text(label).fontSize(LABEL).fontColor(color).fontWeight(FontWeight.Medium).maxLines(1)
      }
      .constraintSize({ minHeight: 30 }).padding({ left: 10, right: 10, top: 5, bottom: 5 })
      .borderRadius(RADIUS_PILL).border({ width: 0.7, color: LINE }).backgroundColor(SURFACE2)
      .alignItems(VerticalAlign.Center)
    }
    .constraintSize({ minHeight: 44 }).padding({ left: 4 }).flexShrink(0)
    .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
    .onClick(action)
    .accessibilityGroup(true)
    .accessibilityText(a11y)
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
    .width('auto').constraintSize({ minHeight: 30, maxWidth: '100%' })
    .padding({ left: 12, right: 12, top: 5, bottom: 5 })
    .backgroundColor(this.showApproval ? SURFACE2 : accentAlpha(this.accentColorStore, 0.10))
    .borderRadius(15).border({ width: 0.7, color: LINE })
    .alignItems(VerticalAlign.Center)
  }
}
