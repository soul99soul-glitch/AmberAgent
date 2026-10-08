// Index — legacy 首页(已从 main_pages 移除;冷启动页为 ChatListPage)
// 本文件保留供对照 Android 首屏,无路由入口,勿再挂新导航。
//
// 首页只负责新会话入口和最近会话列表。模型选择与图片物化仍由既有页面承接,
// 以保持首页与 ChatPage 的发送/附件行为一致。

import { router, window } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import type { ConversationSummary, ContinueCandidate, AgentCronTask } from '@amber/chat-domain';
import type { DeepReadScheduler } from '@amber/deepread-domain';
import { mergeContinueCandidates, continueStatusText, SOURCE_LABEL } from '@amber/chat-domain';
import { getChatRepository, describeChatModel, getNovelCreation, getAgentCronManager, getRepository, getDeepReadScheduler } from '../di/AppContainer.ets';
import { getMiniAppRepositorySync } from '../platform_impl/MiniAppPlatform.ets';
import { loadDeepReadContinueCandidates } from '../platform_impl/DeepReadContinueSupport.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, LINE, BG, ACCENT, ACCENT_INK, TINY, TEXT_STYLES,
} from '../design/tokens.ets';
import {
  FONT_SANS, FONT_SANS_MEDIUM, FONT_SANS_BOLD, FONT_MONO, FONT_MONO_BOLD,
} from '../design/fonts.ets';
import { SectionHeader } from '../components/PageTemplate.ets';
import { BlinkingCursor } from '../design/components.ets';

// Index-specific values: keep new hierarchy values together until they become shared tokens.
const HEADER_HEIGHT: number = 56;
const TOUCH_SIZE: number = 46;
const COMPOSER_GAP: number = 9;
const COMPOSER_MIN_HEIGHT: number = 46;
const COMPOSER_MAX_HEIGHT: number = 111;
const COMPOSER_VERTICAL_PADDING: number = 18;
const CHIP_LINE_HEIGHT: number = 18;
// header 两侧触控方块(Android TopBar 同款 36dp)
const HEADER_TOUCH_SIZE: number = 36;
// Android TopBar: model-id 行为 lerp(ink3, ink2, 0.5) 的暖中灰
const MODEL_LABEL_LIGHT: string = '#737066';
const MODEL_LABEL_DARK: string = '#8E887E';

const greetingMessage = (): string => {
  const hour: number = new Date().getHours();
  if (hour < 6) return '夜深了，光';
  if (hour < 12) return '早上好，光';
  if (hour < 18) return '下午好，光';
  return '晚上好，光';
};

const SUGGESTIONS: string[] = [
  '陪我把今天捋一捋',
  '解释一个概念',
  '帮我润色这段话',
  '随便聊聊',
];

// ArkUI accepts rgba strings; the accent is a user-selectable #RRGGBB token.
const accentWithAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

@Component
struct IndexSuggestionChip {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop label: string = '';
  @State pressedScale: number = 1;
  onSelect: () => void = (): void => {};

  private setPressed(event: TouchEvent): void {
    const pressed: boolean = event.type === TouchType.Down;
    animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
      this.pressedScale = pressed ? 0.975 : 1;
    });
  }

  build(): void {
    Text(this.label)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(12)
      .fontWeight(FontWeight.Normal)
      .lineHeight(CHIP_LINE_HEIGHT)
      .fontFamily(FONT_SANS)
      .fontColor(this.accentColorStore)
      .maxLines(1)
      .textOverflow({ overflow: TextOverflow.Ellipsis })
      .padding({ left: 10, right: 10, top: 6, bottom: 6 })
      .backgroundColor(SURFACE)
      .borderRadius(50)
      .border({ width: 1, color: accentWithAlpha(this.accentColorStore, 0.14) })
      .scale({ x: this.pressedScale, y: this.pressedScale })
      .onTouch((event: TouchEvent): void => { this.setPressed(event); })
      .onClick((): void => { this.onSelect(); });
  }
}

@Component
struct IndexRecentRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop timeText: string = '';
  onSelect: () => void = (): void => {};
  build(): void {
    Row() {
      Text(this.title.length > 0 ? this.title : '未命名对话')
        .fontSize(14)
        .fontWeight(FontWeight.Normal)
        .lineHeight(20)
        .fontFamily(FONT_SANS)
        .fontColor(INK)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
      Text(this.timeText)
        .fontSize(11)
        .fontWeight(FontWeight.Normal)
        .lineHeight(14)
        .fontColor(INK3)
        .fontFamily(FONT_MONO)
        .textAlign(TextAlign.End)
        .maxLines(1)
        .margin({ left: 12 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .constraintSize({ minHeight: 40 })
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(Color.Transparent)
    .onClick((): void => { this.onSelect(); });
  }
}

@Entry
@Component
struct Index {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State inputText: string = '';
  @State recentItems: ConversationSummary[] = [];
  @State continueItems: ContinueCandidate[] = [];
  @State loaded: boolean = false;
  @State modelLabel: string = '';
  @State inputFocused: boolean = false;
  @State inputHeight: number = COMPOSER_MIN_HEIGHT;
  @State inputFlashAlpha: number = 0;
  @State attachScale: number = 1;
  @State sendScale: number = 1;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private keyboardHeightVp: number = 0;
  private imeListenerAttached: boolean = false;
  private imeWindow: window.Window | null = null;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.pageVisible = true;
    this.loadRecent();
    this.loadContinue();
    this.loadModelLabel();
    this.attachKeyboardListener();
  }

  onPageShow(): void {
    this.pageVisible = true;
    this.loadModelLabel();
    this.loadContinue();
    this.attachKeyboardListener();
  }

  onPageHide(): void {
    this.pageVisible = false;
    this.detachKeyboardListener();
    this.keyboardHeightVp = 0;
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.pageVisible = false;
    this.detachKeyboardListener();
  }


  private loadModelLabel(): void {
    describeChatModel().then((label: string): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.modelLabel = label;
    }).catch((): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.modelLabel = '';
    });
  }

  private loadRecent(): void {
    getChatRepository().list().then((list: ConversationSummary[]): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.recentItems = list.slice(0, 9);
      this.loaded = true;
    }).catch((): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.recentItems = [];
      this.loaded = true;
    });
  }

  // 首页「继续」：小说项目 + 到期/运行中 cron（Android ContinueAggregator 子集）
  private loadContinue(): void {
    const novelList: Promise<ContinueCandidate[]> = getNovelCreation().listProjects()
      .then((projects): ContinueCandidate[] => projects.slice(0, 3).map((p): ContinueCandidate => ({
        sourceKind: 'novel',
        sourceId: p.id,
        route: { kind: 'novel', projectId: p.id },
        title: p.name.length > 0 ? p.name : '未命名小说',
        summary: `${p.chapters.length} 章 · ${p.messages.length} 条讨论`,
        lastUpdatedAt: p.updatedAt,
        status: p.chapters.length > 0 ? 'draft' : 'waiting_user',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    const cronList: Promise<ContinueCandidate[]> = getAgentCronManager()
      .then((m): Promise<AgentCronTask[]> => m.listTasksSnapshot())
      .then((tasks: AgentCronTask[]): ContinueCandidate[] => {
        const out: ContinueCandidate[] = [];
        for (const t of tasks) {
          if (!t.enabled) continue;
          if (t.lastStatus !== 'Failed' && t.lastStatus !== 'Running') continue;
          if (out.length >= 3) break;
          out.push({
            sourceKind: 'cron',
            sourceId: t.id,
            route: { kind: 'cron', taskId: t.id },
            title: t.title,
            summary: t.lastStatus === 'Failed' ? '上次失败，可重试' : '运行中',
            lastUpdatedAt: t.updatedAtMs,
            status: t.lastStatus === 'Failed' ? 'failed_resumable' : 'running',
            priority: 0,
            isRunning: t.lastStatus === 'Running',
          });
        }
        return out;
      })
      .catch((_err: Error): ContinueCandidate[] => []);
    const deepList: Promise<ContinueCandidate[]> = getDeepReadScheduler()
      .then((scheduler: DeepReadScheduler): Promise<ContinueCandidate[]> =>
        loadDeepReadContinueCandidates(getRepository(), scheduler))
      .catch((_err: Error): ContinueCandidate[] => []);
    const miniList: Promise<ContinueCandidate[]> = getMiniAppRepositorySync().listAll()
      .then((apps): ContinueCandidate[] => apps.slice(0, 2).map((app): ContinueCandidate => ({
        sourceKind: 'miniapp',
        sourceId: app.id,
        route: { kind: 'miniapp', appId: app.id },
        title: app.title,
        summary: '可打开运行',
        lastUpdatedAt: app.updatedAt,
        status: 'draft',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    Promise.all([novelList, cronList, deepList, miniList]).then((lists): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.continueItems = mergeContinueCandidates(lists, 6);
    }).catch((): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.continueItems = [];
    });
  }

  private openContinue(item: ContinueCandidate): void {
    if (item.route.kind === 'novel') {
      router.pushUrl({
        url: 'pages/NovelWorkspacePage',
        params: { projectId: item.route.projectId },
      });
    } else if (item.route.kind === 'cron') {
      router.pushUrl({ url: 'pages/SettingCronTasksPage' });
    } else if (item.route.kind === 'miniapp') {
      router.pushUrl({
        url: 'pages/MiniAppRunnerPage',
        params: { appId: item.route.appId },
      });
    } else if (item.route.kind === 'deepread') {
      router.pushUrl({
        url: 'pages/DeepReadArticlePage',
        params: { topicId: item.route.topicId, title: item.route.title },
      });
    }
  }

  private updateInputHeight(value: string): void {
    const parts: string[] = value.split('\n');
    let visualLines: number = 0;
    for (const part of parts) {
      visualLines += Math.max(1, Math.ceil(part.length / 24));
    }
    const lines: number = Math.min(4, Math.max(1, visualLines));
    const target: number = Math.max(COMPOSER_MIN_HEIGHT,
      Math.min(COMPOSER_MAX_HEIGHT, COMPOSER_MIN_HEIGHT + (lines - 1) * 21));
    if (target === this.inputHeight) return;
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.inputHeight = target;
    });
  }

  private chooseSuggestion(value: string): void {
    this.inputText = value;
    this.updateInputHeight(value);
    this.flashInputBorder();
  }

  private flashInputBorder(): void {
    this.inputFlashAlpha = 1;
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.inputFlashAlpha = 0;
    });
  }

  private inputBorderColor(): string {
    if (this.inputFlashAlpha > 0) return accentWithAlpha(this.accentColorStore, this.inputFlashAlpha);
    if (this.inputFocused) return accentWithAlpha(this.accentColorStore, 0.42);
    return LINE;
  }

  private pressButton(which: boolean, event: TouchEvent): void {
    const pressed: boolean = event.type === TouchType.Down;
    animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
      if (which) this.attachScale = pressed ? 0.975 : 1;
      else this.sendScale = pressed ? 0.975 : 1;
    });
  }

  private send(sendMode: string = 'send'): void {
    const text: string = this.inputText.trim();
    if (text.length === 0) return;
    const params: Record<string, string> = {};
    params['initialText'] = text;
    params['sendMode'] = sendMode;
    router.replaceUrl({ url: 'pages/ChatPage', params: params });
  }

  private newConversation(): void {
    router.replaceUrl({ url: 'pages/ChatPage' });
  }

  private openImagePickerConversation(): void {
    router.replaceUrl({ url: 'pages/ChatPage', params: { openImagePicker: 'true' } });
  }

  private openConversation(id: string): void {
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    router.replaceUrl({ url: 'pages/ChatPage', params: params });
  }

  private formatTime(updateAt: string): string {
    if (updateAt.length >= 16) return updateAt.substring(11, 16);
    if (updateAt.length >= 10) return updateAt.substring(5, 10);
    return updateAt;
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // Header content is 56vp; the system status inset is kept outside that height.
      Row() {
        Row() {
          Icon({ name: 'menu', iconSize: 20, color: INK2 })
        }
        .width(HEADER_TOUCH_SIZE).height(HEADER_TOUCH_SIZE)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { router.pushUrl({ url: 'pages/ChatListPage' }); })
        .accessibilityText('打开会话列表')

        Column() {
          Text('新会话')
            .fontSize(TEXT_STYLES.sessionTitle.size)
            .fontWeight(TEXT_STYLES.sessionTitle.weight)
            .lineHeight(TEXT_STYLES.sessionTitle.lineHeight)
            .fontFamily(TEXT_STYLES.sessionTitle.family)
            .fontColor(INK)
            .maxLines(1)
          Row() {
            Text(this.modelLabel.length > 0 ? this.modelLabel : '未配置模型')
              .fontSize(TINY)
              .fontWeight(FontWeight.Medium)
              .lineHeight(14)
              .fontColor(this.modelLabel.length > 0
                ? (this.darkMode ? MODEL_LABEL_DARK : MODEL_LABEL_LIGHT) : INK4)
              .fontFamily(FONT_MONO)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Icon({ name: 'chevron_right', iconSize: 13, color: INK3 }).margin({ left: 4 })
          }
          .margin({ top: 1 })
          // Simplified decision: reuse the existing full model picker route; TopModelMenu needs ChatPage session state.
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatModelPickerPage' }); })
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)

        Row() {
          Icon({ name: 'edit', iconSize: 18, color: INK2 })
        }
        .width(HEADER_TOUCH_SIZE).height(HEADER_TOUCH_SIZE)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { this.newConversation(); })
        .accessibilityText('新建会话')
      }
      .width('100%')
      .height(HEADER_HEIGHT)
      .padding({ left: 20, right: 20 })
      .alignItems(VerticalAlign.Center)
      .backgroundColor(BG)
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })

      if (!this.loaded) {
        Column() {
          LoadingProgress()
            .width(28).height(28)
            .color(this.accentColorStore)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else if (this.recentItems.length === 0) {
        // Empty hero contains only the wordmark and greeting.
        Column() {
          Row() {
            Text('amber')
              .fontSize(24)
              .fontWeight(FontWeight.Bold)
              .lineHeight(18)
              .letterSpacing(-0.5)
              .fontColor(INK)
              .fontFamily(FONT_MONO_BOLD)
            BlinkingCursor({ color: this.accentColorStore, cursorWidth: 9, cursorHeight: 20 })
              .margin({ left: 2 })
          }
          .alignItems(VerticalAlign.Bottom)
          Text(greetingMessage())
            .fontSize(26)
            .fontWeight(FontWeight.Medium)
            .lineHeight(36)
            .letterSpacing(0.2)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .textAlign(TextAlign.Center)
            .margin({ top: 14 })
        }
        .width('100%')
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            if (this.continueItems.length > 0) {
              SectionHeader({ label: '继续' })
              ForEach(this.continueItems, (item: ContinueCandidate): void => {
                IndexRecentRow({
                  title: `${SOURCE_LABEL[item.sourceKind]} · ${item.title}`,
                  timeText: continueStatusText(item.status),
                  onSelect: (): void => { this.openContinue(item); },
                })
              }, (item: ContinueCandidate): string => `cont-${item.sourceKind}-${item.sourceId}`)
            }
            SectionHeader({ label: '最近' })
            ForEach(this.recentItems, (item: ConversationSummary): void => {
              IndexRecentRow({
                title: item.title,
                timeText: this.formatTime(item.updateAt),
                onSelect: (): void => { this.openConversation(item.id); },
              })
            }, (item: ConversationSummary): string => `home-${item.id}`)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .align(Alignment.TopStart)
        .backgroundColor(BG)
      }

      // Suggestions stay fixed above the dock instead of occupying the hero.
      if (this.loaded && this.recentItems.length === 0) {
        Scroll() {
          Row() {
            ForEach(SUGGESTIONS, (suggestion: string, index: number): void => {
              IndexSuggestionChip({
                label: suggestion,
                onSelect: (): void => { this.chooseSuggestion(suggestion); },
              })
                .margin({ right: index < SUGGESTIONS.length - 1 ? 8 : 0 })
            }, (suggestion: string, index: number): string => `home-chip-${index}-${suggestion}`)
          }
          .padding({ left: 16, right: 16 })
        }
        .width('100%')
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .padding({ top: 8, bottom: 8 })
        .margin({ bottom: 4 })
      }

      // Three-part input dock: attach, expanding text pill, send.
      Row() {
        Row() {
          Icon({ name: 'plus', iconSize: 24, color: INK2 })
        }
        .width(TOUCH_SIZE).height(TOUCH_SIZE)
        .borderRadius(TOUCH_SIZE / 2)
        .backgroundColor(SURFACE)
        .border({ width: 1, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .scale({ x: this.attachScale, y: this.attachScale })
        .onTouch((event: TouchEvent): void => { this.pressButton(true, event); })
        // Simplified decision: ChatPage owns PhotoViewPicker and image materialization.
        .onClick((): void => { this.openImagePickerConversation(); })
        .accessibilityText('添加图片')

        Row() {
          TextArea({ text: this.inputText, placeholder: '输入消息' })
            .height(Math.max(28, this.inputHeight - COMPOSER_VERTICAL_PADDING))
            .layoutWeight(1)
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK)
            .placeholderColor(INK4)
            .backgroundColor(Color.Transparent)
            .maxLines(4)
            .onFocus((): void => { this.inputFocused = true; })
            .onBlur((): void => { this.inputFocused = false; })
            .onChange((value: string): void => {
              this.inputText = value;
              this.updateInputHeight(value);
            })
            .enterKeyType(EnterKeyType.Send)
            .onSubmit((): void => { this.send(); })
        }
        .layoutWeight(1)
        .height(this.inputHeight)
        .constraintSize({ minHeight: COMPOSER_MIN_HEIGHT, maxHeight: COMPOSER_MAX_HEIGHT })
        .padding({ left: 18, right: 16, top: 9, bottom: 9 })
        .borderRadius(26)
        .backgroundColor(SURFACE)
        .border({ width: 1, color: this.inputBorderColor() })
        .animation({ duration: 220, curve: Curve.EaseOut })
        .margin({ left: COMPOSER_GAP, right: COMPOSER_GAP })

        Row() {
          Icon({
            name: 'arrow_up',
            iconSize: 22,
            color: this.inputText.trim().length > 0 ? ACCENT_INK : INK4,
          })
        }
        .width(TOUCH_SIZE).height(TOUCH_SIZE)
        .borderRadius(TOUCH_SIZE / 2)
        .backgroundColor(this.inputText.trim().length > 0 ? this.accentColorStore : SURFACE)
        .border({
          width: 1,
          color: this.inputText.trim().length > 0 ? this.accentColorStore : LINE,
        })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .scale({ x: this.sendScale, y: this.sendScale })
        .onTouch((event: TouchEvent): void => { this.pressButton(false, event); })
        .gesture(
          GestureGroup(
            GestureMode.Exclusive,
            LongPressGesture().onAction((): void => { this.send('append'); }),
            TapGesture().onAction((): void => { this.send('send'); }),
          ),
        )
        .accessibilityText('发送消息')
      }
      .width('100%')
      .alignItems(VerticalAlign.Bottom)
      .padding({ left: 12, right: 12, top: 10, bottom: this.keyboardHeightVp + this.navBarHeight + 6 })
      .backgroundColor(SURFACE)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .padding({ top: this.statusBarHeight })
    .backgroundColor(BG)
  }

  private attachKeyboardListener(): void {
    if (this.imeListenerAttached) return;
    const ctx: Context | undefined = this.getUIContext().getHostContext();
    if (ctx === undefined) return;
    window.getLastWindow(ctx).then((win: window.Window): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.imeWindow = win;
      win.on('keyboardHeightChange', (heightPx: number): void => {
        if (!this.pageAlive || !this.pageVisible) return;
        this.keyboardHeightVp = heightPx > 0 ? this.getUIContext().px2vp(heightPx) : 0;
      });
      this.imeListenerAttached = true;
    }).catch((): void => {});
  }

  private detachKeyboardListener(): void {
    if (!this.imeListenerAttached || this.imeWindow === null) return;
    try {
      this.imeWindow.off('keyboardHeightChange');
    } catch (_e) {
    }
    this.imeWindow = null;
    this.imeListenerAttached = false;
  }
}
