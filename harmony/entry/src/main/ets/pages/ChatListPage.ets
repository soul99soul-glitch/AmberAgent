// ChatListPage — 冷启动根页。首页主体在 components/HomeListPane(iOS ConversationsView 复刻)。
//
// 单栏:首页整页。两栏(平板 / 折叠屏展开横屏,design/adaptive.ets):首页成为左侧窄栏,
//   右侧未打开会话时放词标占位;点会话 push ChatPage,ChatPage 同样左栏 + 右聊天,
//   两栏下关掉页面转场,看上去只换了右栏。深链消费留在根页(R23)。

import { markWantRoutingReady, routePendingWant } from '../entryability/EntryAbility.ets';
import { HomeListPane } from '../components/HomeListPane.ets';
import { AmberWordmark } from '../components/ui/AmberWordmark.ets';
import { BG, INK3, LINE2 } from '../design/tokens.ets';
import {
  isTwoPaneWindow, twoPaneListWidth, WINDOW_HEIGHT_VP_KEY, WINDOW_WIDTH_VP_KEY,
} from '../design/adaptive.ets';

@Entry
@Component
struct ChatListPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp(WINDOW_WIDTH_VP_KEY) windowWidthVp: number = 0;
  @StorageProp(WINDOW_HEIGHT_VP_KEY) windowHeightVp: number = 0;
  @State paneVisible: boolean = false;

  onPageShow(): void {
    this.paneVisible = true;
    // onPageShow 在首次渲染后触发；冷 Want 从这里使用本页绑定的 Router 派发。
    markWantRoutingReady(this.getUIContext().getRouter());
  }

  onPageHide(): void {
    this.paneVisible = false;
  }

  aboutToAppear(): void {
    // EntryAbility 写入的通知/分享深链在根页消费，避免改变返回栈语义。
    routePendingWant();
  }

  private twoPane(): boolean {
    return isTwoPaneWindow(this.windowWidthVp, this.windowHeightVp);
  }

  pageTransition(): void {
    // 两栏:左栏在前后两页里是同一份列表,不滑动整页
    if (this.twoPane()) {
      PageTransitionEnter({ type: RouteType.None, duration: 0 })
      PageTransitionExit({ type: RouteType.None, duration: 0 })
    }
  }

  build(): void {
    Row() {
      HomeListPane({
        hostVisible: this.paneVisible,
        twoPane: this.twoPane(),
        inChat: false,
        activeId: '',
      })
        .width(this.twoPane() ? twoPaneListWidth(this.windowWidthVp) : '100%')
        .height('100%')
      if (this.twoPane()) {
        // 主题纪元重挂载(勿拆):epoch 变化 → 重读可变令牌
        ForEach([this.themeEpoch], (_e: number): void => {
          // 整 1 物理像素:1vp 落在小数像素上,边缘发虚、显粗
          Column().width(this.getUIContext().px2vp(1)).height('100%').backgroundColor(LINE2)
          Column() {
            AmberWordmark({ color: INK3 }).opacity(0.5)
          }
          .layoutWeight(1)
          .height('100%')
          .justifyContent(FlexAlign.Center)
          .backgroundColor(BG)
        }, (e: number): string => `theme-${e}`)
      }
    }
    .width('100%')
    .height('100%')
  }
}
