// ChatDrawer — 侧边抽屉(G4-S4)
// Android 基准: ChatDrawer.kt(634 行) + ConversationList.kt(410 行)
// 必要: 导航入口(新聊天/看板/小应用) + 快捷按钮(文件/统计/搜索)
//   + 用户头像+昵称+设置 + 搜索框 + 会话列表(日期分组/置顶/选中态)
// 降级(跳过): ModalNavigationDrawer(用 Stack overlay) / FavoritesLiveSheet / 滚动位置持久化
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, BG, LINE, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { WorkspaceSearchField } from './ui/WorkspaceSearchField.ets';
import { UIAvatar } from './UIAvatar.ets';
import { Icon } from './ui/Icon.ets';

// 抽屉导航项
interface DrawerNavItem {
  icon: string;
  label: string;
  accent: boolean;
  route: string;
}

@Component
export struct ChatDrawer {
  @Prop show: boolean = false;
  @Prop userNickname: string = '用户';
  @Prop userAvatar: string = '';
  onNewChat: () => void = (): void => {};
  onOpenBoard: () => void = (): void => {};
  onOpenSettings: () => void = (): void => {};
  onOpenSearch: () => void = (): void => {};
  onDismiss: () => void = (): void => {};
  onEditNickname: (name: string) => void = (_n: string): void => {};
  @BuilderParam conversationList: () => void;

  build(): void {
    if (this.show) {
      // 遮罩 + 抽屉(右侧滑入)
      Row() {
        // 左侧遮罩(点击关闭)
        Column()
          .width('30%')
          .height('100%')
          .backgroundColor('rgba(0,0,0,0.4)')
          .onClick((): void => { this.onDismiss(); })

        // 抽屉内容
        Column() {
          // 搜索栏(固定)
          WorkspaceSearchField({
            placeholder: '搜索聊天',
            onSearch: (_text: string): void => { /* TODO: 搜索过滤 */ },
          })
            .margin({ top: 14, left: 16, right: 16 })

          // 导航入口
          Column() {
            this.NavRow('✨', '新对话', true, this.onNewChat)
            this.NavRow('📰', '今日看板', false, this.onOpenBoard)
            this.NavRow('🔲', '小应用', false, (): void => {})
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 8 })

          Divider().color(LINE).strokeWidth(0.5).margin({ left: 16, right: 16, top: 12, bottom: 8 })

          // "最近" 标签
          Row() {
            Icon({ name: 'history', iconSize: 14, color: ACCENT }).margin({ right: 6 })
            Text('最近').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(ACCENT)
          }
          .width('100%')
          .padding({ left: 16, bottom: 4 })

          // 会话列表(由父组件注入)
          Scroll() {
            Column() {
              this.conversationList()
            }
            .width('100%')
          }
          .layoutWeight(1)

          // 底部: 头像 + 昵称 + 设置
          Divider().color(LINE).strokeWidth(0.5)
          Row() {
            UIAvatar({
              name: this.userNickname,
              value: this.userAvatar,
              avatarSize: 32,
            })
            Text(this.userNickname)
              .fontSize(BODY)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
              .margin({ left: 12 })
              .onClick((): void => { /* TODO: 昵称编辑弹窗 */ })
            Icon({ name: 'gear', iconSize: 18, color: INK3 })
              .onClick((): void => { this.onOpenSettings(); })
          }
          .width('100%')
          .padding({ left: 22, right: 16, top: 14, bottom: 10 })
          .alignItems(VerticalAlign.Center)
        }
        .width('70%')
        .height('100%')
        .backgroundColor(SURFACE)
        .border({ width: { left: 1 }, color: LINE })
      }
      .width('100%')
      .height('100%')
    }
  }

  @Builder
  NavRow(icon: string, label: string, accent: boolean, onClick: () => void): void {
    Row() {
      Text(icon).fontSize(16).margin({ right: 14 })
      Text(label)
        .fontSize(BODY)
        .fontWeight(accent ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(accent ? ACCENT : INK)
    }
    .width('100%')
    .padding({ left: 11, right: 11, top: 6, bottom: 6 })
    .borderRadius(10)
    .onClick((): void => { onClick(); this.onDismiss(); })
  }
}
