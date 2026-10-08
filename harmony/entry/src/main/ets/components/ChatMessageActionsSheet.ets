// ChatMessageActionsSheet — 消息操作 action sheet(Phase 6)
//
// Android 基准: ChatMessageActionsSheet.kt(:225-372)— ModalBottomSheet
//   单卡细线边框 + 行间细分割线 + 44dp 最小触达 + 底部时间/模型元数据。
// 鸿蒙适配:
//   1) bindSheet 内容:本组件不持有 sheet 状态/不调用 bindSheet,由宿主
//      (ChatPage)用 copySheet 同款套法接入,onDismiss 驱动关闭。
//   2) 按角色/状态过滤合法操作(Android parity):
//      copy=始终(顶部第一项) / regenerate=user 或最后 assistant /
//      edit=仅 user / speak=仅 assistant / share=始终 / fork=始终 /
//      delete=始终(danger,末行)。
//   3) 无嵌套卡片:单卡 + 细分割线(对齐 Android;替换旧"堆叠大卡片"风格)。
//   4) 图标:复用 Icon(既有 name 映射);fork 无现成映射 → 行内 SymbolGlyph
//      sys.symbol.arrow_triangle_merge(SDK id_defined 已确认存在;
//      Icon.ets 正被并行演进,不动共享文件)。API12-safe(SymbolGlyph ≥ 12)。
//   5) share 仅暴露回调,组件内不做外部调用。

import type { UIMessage } from '@amber/chat-domain';
import { INK, INK2, INK3, SURFACE, LINE, ERROR, BODY, LABEL } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatMessageActionsSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop message: UIMessage;
  @Prop isUser: boolean = false;
  @Prop isLastAssistant: boolean = false;
  @Prop canFavorite: boolean = false;
  @Prop isFavorite: boolean = false;
  @Prop canArchive: boolean = false;
  onCopy: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onFork: () => void = (): void => {};
  onShare: () => void = (): void => {};
  onFavorite: () => void = (): void => {};
  onExport: () => void = (): void => {};
  onArchive: () => void = (): void => {};
  onOpenWorkspace: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    Column() {
      // 单卡(细线边框 + 圆角,行间细分隔线;clip 遮圆角处分割线)
      Column() {
        // copy:恒为第一项(Android 旧 actions row 顺序)
        this.actionRow('copy', '复制', false, (): void => {
          this.onDismiss();
          this.onCopy();
        })
        this.rowDivider()
        if (this.canRegenerate()) {
          this.actionRow('retry', '重新生成', false, (): void => {
            this.onDismiss();
            this.onRegenerate();
          })
          this.rowDivider()
        }
        if (this.isUser) {
          this.actionRow('edit', '编辑消息', false, (): void => {
            this.onDismiss();
            this.onEdit();
          })
          this.rowDivider()
        }
        if (this.canFavorite) {
          this.actionRow('star', this.isFavorite ? '取消收藏' : '收藏消息', false, (): void => {
            this.onDismiss();
            this.onFavorite();
          })
          this.rowDivider()
        }
        // share:恒显示,仅暴露回调(组件内不做外部调用)
        this.actionRow('share', '分享', false, (): void => {
          this.onDismiss();
          this.onShare();
        })
        this.rowDivider()
        this.actionRow('doc', '导出对话', false, (): void => {
          this.onDismiss();
          this.onExport();
        })
        this.rowDivider()
        if (this.canArchive) {
          this.actionRow('doc', '保存到 Workspace', false, (): void => {
            this.onDismiss();
            this.onArchive();
          })
          this.rowDivider()
        }
        this.actionRow('folder', '查看 Workspace 文件', false, (): void => {
          this.onDismiss();
          this.onOpenWorkspace();
        })
        this.rowDivider()
        // fork:恒显示
        this.actionRow('fork', '创建分支', false, (): void => {
          this.onDismiss();
          this.onFork();
        })
        this.rowDivider()
        // delete:恒为末行(danger 红)
        this.actionRow('trash', '删除', true, (): void => {
          this.onDismiss();
          this.onDelete();
        })
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius(12)
      .border({ width: 1, color: LINE })
      .clip(true)

      // 元数据脚注(Android parity:时间 + 模型名)
      Column() {
        Text(this.timeText())
          .fontSize(LABEL).fontColor(INK3)
        if (this.message.modelId !== null && this.message.modelId.trim().length > 0) {
          Text(this.message.modelId)
            .fontSize(LABEL).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .margin({ top: 12 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 16, right: 16, top: 10, bottom: 24 })
  }

  // 行触发逻辑:先关 sheet 再回调(Android onDismissRequest() 在前)
  private canRegenerate(): boolean {
    return this.isLastAssistant || this.isUser;
  }

  private timeText(): string {
    try {
      return new Date(this.message.createdAt).toLocaleString();
    } catch (_e) {
      return this.message.createdAt;
    }
  }

  @Builder
  actionRow(iconName: string, label: string, danger: boolean, action: () => void) {
    Row() {
      if (iconName === 'fork') {
        // fork 无 Icon 映射:行内 SymbolGlyph(arrow_triangle_merge 已确认存在)
        SymbolGlyph($r('sys.symbol.arrow_triangle_merge'))
          .fontSize(18)
          .fontColor([danger ? ERROR : INK2])
          .margin({ right: 14 })
      } else {
        Icon({ name: iconName, iconSize: 18, color: danger ? ERROR : INK2 }).margin({ right: 14 })
      }
      Text(label)
        .fontSize(BODY)
        .fontColor(danger ? ERROR : INK)
        .layoutWeight(1)
    }
    .width('100%')
    .height(48) // ≥ 44vp 触达目标
    .padding({ left: 16, right: 16 })
    .alignItems(VerticalAlign.Center)
    .accessibilityText(label)
    .onClick((): void => { action(); })
  }

  @Builder
  rowDivider() {
    // 对齐 Android MessageActionDivider:start 44dp(文字下方,不在图标下方)
    Divider().color(LINE).strokeWidth(0.5).margin({ left: 48 })
  }
}
