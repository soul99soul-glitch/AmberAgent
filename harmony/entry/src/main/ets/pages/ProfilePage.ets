// ProfilePage — 个人中心(头像落地页 + 使用统计)
//
// 头部:大头像 + 昵称(可编辑) + 会话摘要
// 统计卡:总会话/总消息/输入Token/输出Token(+缓存Token)— 数据来自 RDB 实时聚合
// 热力图:53 周 × 7 天用户消息活跃度(对齐 Android StatsPage 算法)
// Android 基准: StatsPage.kt + StatsVM.kt
// 数据: DisplaySetting(KV) + ChatRepository(RDB 遍历聚合,不造假)
import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting, ConversationSummary, Conversation, UIMessage } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';
import { UIAvatar } from '../components/UIAvatar.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG,
  BODY, BODY2, META, LABEL, ACCENT, RADIUS,
} from '../design/tokens.ets';

// 昵称编辑弹窗
@CustomDialog
struct NicknameEditDialog {
  controller: CustomDialogController;
  currentName: string = '';
  @State draft: string = '';
  onSave: (name: string) => void = (_n: string): void => {};

  aboutToAppear(): void {
    this.draft = this.currentName;
  }

  build(): void {
    Column() {
      Text('编辑昵称')
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK)
        .margin({ bottom: 16 })

      TextInput({ text: this.draft, placeholder: '输入昵称' })
        .fontSize(BODY)
        .fontColor(INK)
        .placeholderColor(INK3)
        .backgroundColor(SURFACE2)
        .borderRadius(10)
        .padding({ left: 12, right: 12, top: 10, bottom: 10 })
        .onChange((v: string): void => { this.draft = v; })

      Row({ space: 10 }) {
        Button('取消')
          .type(ButtonType.Normal)
          .backgroundColor(SURFACE2)
          .fontColor(INK2)
          .fontSize(BODY)
          .height(44)
          .layoutWeight(1)
          .borderRadius(10)
          .onClick((): void => { this.controller.close(); })
        Button('保存')
          .type(ButtonType.Normal)
          .backgroundColor(ACCENT)
          .fontColor('#FFFFFF')
          .fontSize(BODY)
          .height(44)
          .layoutWeight(1)
          .borderRadius(10)
          .onClick((): void => {
            const trimmed: string = this.draft.trim();
            if (trimmed.length > 0) this.onSave(trimmed);
            this.controller.close();
          })
      }
      .width('100%')
      .margin({ top: 18 })
    }
    .padding({ left: 20, right: 20, top: 20, bottom: 20 })
    .backgroundColor(SURFACE)
    .borderRadius(16)
  }
}

// ── 统计聚合结果 ──
interface StatsResult {
  totalConversations: number;
  totalMessages: number;
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  // 热力图数据: yyyy-MM-dd → 用户消息数
  dayCounts: Map<string, number>;
}

const EMPTY_STATS: StatsResult = {
  totalConversations: 0,
  totalMessages: 0,
  promptTokens: 0,
  completionTokens: 0,
  cachedTokens: 0,
  dayCounts: new Map<string, number>(),
};

// 格式化数字(K/M 后缀,对齐 Android formatCount)
const formatCount = (n: number): string => {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return `${n}`;
};

// 格式化 Token(K/M/B 后缀)
const formatTokens = (n: number): string => {
  if (n >= 1000000000) return `${(n / 1000000000).toFixed(1)}B`;
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K`;
  return `${n}`;
};

// 从 ISO 时间提取 yyyy-MM-dd
const dayKey = (iso: string): string => {
  try {
    const d: Date = new Date(iso);
    if (isNaN(d.getTime())) return '';
    const y: number = d.getFullYear();
    const m: string = `${d.getMonth() + 1}`.padStart(2, '0');
    const day: string = `${d.getDate()}`.padStart(2, '0');
    return `${y}-${m}-${day}`;
  } catch (_e) {
    return '';
  }
};

// 热力图:计算起始日(52 周前的周日)
const heatmapStartDate = (): Date => {
  const today: Date = new Date();
  const dow: number = today.getDay(); // 0=Sun
  today.setDate(today.getDate() - dow); // 回到本周日
  today.setDate(today.getDate() - 52 * 7); // 再减 52 周
  return today;
};

// 热力图颜色(alpha 分档)
const heatmapAlpha = (count: number, q1: number, q2: number, q3: number): number => {
  if (count === 0) return 0;
  if (count <= q1) return 0.25;
  if (count <= q2) return 0.5;
  if (count <= q3) return 0.75;
  return 1.0;
};

@Entry
@Component
struct ProfilePage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State setting: DisplaySetting = makeDisplaySetting();
  @State stats: StatsResult = EMPTY_STATS;
  @State statsLoaded: boolean = false;
  @State versionLabel: string = '';
  private dialogController: CustomDialogController | null = null;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      this.setting = s;
    }).catch((): void => {});
    this.loadStats();
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionLabel = `${info.versionName} (${info.versionCode})`;
    } catch (_e) {
      this.versionLabel = '';
    }
  }

  // 异步遍历 RDB 聚合统计(不造假,真实数据)
  private loadStats(): void {
    const repo = getChatRepository();
    repo.list().then(async (summaries: ConversationSummary[]): Promise<void> => {
      const dayCounts: Map<string, number> = new Map<string, number>();
      let totalMessages: number = 0;
      let promptTokens: number = 0;
      let completionTokens: number = 0;
      let cachedTokens: number = 0;

      for (const summary of summaries) {
        const conv: Conversation | null = await repo.getById(summary.id);
        if (conv === null) continue;
        for (const node of conv.messageNodes) {
          for (const msg of node.messages) {
            totalMessages++;
            if (msg.usage !== null) {
              promptTokens += msg.usage.promptTokens;
              completionTokens += msg.usage.completionTokens;
              cachedTokens += msg.usage.cachedTokens;
            }
            // 热力图只统计用户消息
            if (msg.role === 'user') {
              const key: string = dayKey(msg.createdAt);
              if (key.length > 0) {
                dayCounts.set(key, (dayCounts.get(key) ?? 0) + 1);
              }
            }
          }
        }
      }

      this.stats = {
        totalConversations: summaries.length,
        totalMessages,
        promptTokens,
        completionTokens,
        cachedTokens,
        dayCounts,
      };
      this.statsLoaded = true;
    }).catch((): void => {
      this.statsLoaded = true;
    });
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  private openNicknameEditor(): void {
    this.dialogController = new CustomDialogController({
      builder: NicknameEditDialog({
        currentName: this.setting.userNickname,
        onSave: (name: string): void => { this.saveNickname(name); },
      }),
      alignment: DialogAlignment.Center,
      customStyle: true,
    });
    this.dialogController.open();
  }

  private saveNickname(name: string): void {
    this.setting = makeDisplaySetting({
      showAssistantBubble: this.setting.showAssistantBubble,
      showModelIcon: this.setting.showModelIcon,
      showModelName: this.setting.showModelName,
      showDateBelowName: this.setting.showDateBelowName,
      showThinkingContent: this.setting.showThinkingContent,
      autoCloseThinking: this.setting.autoCloseThinking,
      showUpdates: this.setting.showUpdates,
      showMessageJumper: this.setting.showMessageJumper,
      messageJumperOnLeft: this.setting.messageJumperOnLeft,
      fontSizeRatio: this.setting.fontSizeRatio,
      enableMessageGenerationHapticEffect: this.setting.enableMessageGenerationHapticEffect,
      skipCropImage: this.setting.skipCropImage,
      enableNotificationOnMessageGeneration: this.setting.enableNotificationOnMessageGeneration,
      enableLiveUpdateNotification: this.setting.enableLiveUpdateNotification,
      codeBlockAutoWrap: this.setting.codeBlockAutoWrap,
      codeBlockAutoCollapse: this.setting.codeBlockAutoCollapse,
      showLineNumbers: this.setting.showLineNumbers,
      ttsOnlyReadQuoted: this.setting.ttsOnlyReadQuoted,
      autoPlayTTSAfterGeneration: this.setting.autoPlayTTSAfterGeneration,
      pasteLongTextAsFile: this.setting.pasteLongTextAsFile,
      pasteLongTextThreshold: this.setting.pasteLongTextThreshold,
      sendOnEnter: this.setting.sendOnEnter,
      enableAutoScroll: this.setting.enableAutoScroll,
      showBottomFollowAnimation: this.setting.showBottomFollowAnimation,
      enableLatexRendering: this.setting.enableLatexRendering,
      enableBlurEffect: this.setting.enableBlurEffect,
      chatFontFamily: this.setting.chatFontFamily,
      enableVolumeKeyScroll: this.setting.enableVolumeKeyScroll,
      volumeKeyScrollRatio: this.setting.volumeKeyScrollRatio,
      chatThemeChoice: this.setting.chatThemeChoice,
      amberBaseFamily: this.setting.amberBaseFamily,
      accentColor: this.setting.accentColor,
      userNickname: name,
      userAvatar: this.setting.userAvatar,
    });
    saveDisplaySetting(getChatKvStore(), this.setting);
  }

  @Builder
  statCard(label: string, value: string) {
    Column() {
      Text(value)
        .fontSize(22)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK)
        .fontFamily('JetBrainsMono')
      Text(label)
        .fontSize(LABEL)
        .fontColor(INK3)
        .margin({ top: 4 })
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)
  }

  build() {
    Column() {
      PageTitleBar({ title: '个人中心' })

      Scroll() {
        Column() {
          // ── 头部:大头像 + 昵称 + 摘要 ──
          Column() {
            UIAvatar({
              name: this.setting.userNickname,
              emoji: '',
              imageUrl: this.setting.userAvatar,
              avatarSize: 72,
              circular: true,
              accentColor: this.accentColorStore,
            })
            Row() {
              Text(this.setting.userNickname.length > 0 ? this.setting.userNickname : '未设置昵称')
                .fontSize(20)
                .fontWeight(FontWeight.Bold)
                .fontColor(INK)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .constraintSize({ maxWidth: '70%' })
              Text(' ›')
                .fontSize(20)
                .fontColor(INK4)
                .margin({ left: 4 })
            }
            .margin({ top: 14 })
            .onClick((): void => { this.openNicknameEditor(); })

            Text(`${formatCount(this.stats.totalConversations)} 个会话 · ${formatCount(this.stats.totalMessages)} 条消息`)
              .fontSize(LABEL)
              .fontColor(INK3)
              .fontFamily('JetBrainsMono')
              .margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 32, bottom: 24 })
          .alignItems(HorizontalAlign.Center)

          // ── 统计卡片 ──
          if (this.statsLoaded) {
            SectionHeader({ label: 'STATISTICS' })
            Row() {
              Column() {
                this.statCard('总会话', formatCount(this.stats.totalConversations))
              }
              .layoutWeight(1)
              Column() {
                this.statCard('总消息', formatCount(this.stats.totalMessages))
              }
              .layoutWeight(1)
              .margin({ left: 8 })
            }
            .width('100%')

            Row() {
              Column() {
                this.statCard('输入 Token', formatTokens(this.stats.promptTokens))
              }
              .layoutWeight(1)
              Column() {
                this.statCard('输出 Token', formatTokens(this.stats.completionTokens))
              }
              .layoutWeight(1)
              .margin({ left: 8 })
            }
            .width('100%')
            .margin({ top: 8 })

            if (this.stats.cachedTokens > 0) {
              Row() {
                Column() {
                  this.statCard('缓存 Token', formatTokens(this.stats.cachedTokens))
                }
                .layoutWeight(1)
              }
              .width('100%')
              .margin({ top: 8 })
            }

            // ── 聊天热力图 ──
            SectionHeader({ label: 'ACTIVITY' })
            this.HeatmapCard()
          } else {
            Column() {
              Text('统计加载中…')
                .fontSize(BODY2)
                .fontColor(INK3)
            }
            .width('100%')
            .padding({ top: 40, bottom: 40 })
            .alignItems(HorizontalAlign.Center)
          }

          // ── 快捷入口 ──
          SectionHeader({ label: 'PROFILE' })
          Column() {
            NavRow({
              label: '昵称',
              valueText: this.setting.userNickname.length > 0 ? this.setting.userNickname : '未设置',
              isLast: true,
              onRowClick: (): void => { this.openNicknameEditor(); },
            })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)

          SectionHeader({ label: 'SHORTCUTS' })
          Column() {
            NavRow({ label: '显示设置', onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
            NavRow({ label: '设置', onRowClick: (): void => { this.go('pages/SettingPage'); } })
            NavRow({ label: '关于', isLast: true, onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(RADIUS).clip(true)

          // 版本信息
          Row() {
            Text('$').fontColor(this.accentColorStore).fontSize(LABEL).fontFamily('JetBrainsMono')
            Text(` amber v${this.versionLabel}`).fontColor(INK3).fontSize(LABEL).fontFamily('JetBrainsMono')
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 22 })

          PageSpacer()
        }
        .width('100%')
        .padding({ left: 18, right: 18 })
      }
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  HeatmapCard() {
    Column() {
      Text('聊天热力图')
        .fontSize(BODY2)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK)
        .margin({ bottom: 12 })

      if (this.stats.dayCounts.size > 0) {
        this.HeatmapGrid()
      } else {
        Text('暂无活动数据')
          .fontSize(LABEL)
          .fontColor(INK4)
          .padding({ top: 20, bottom: 20 })
      }

      // 图例
      Row() {
        Text('少').fontSize(LABEL).fontColor(INK4)
        ForEach([0.25, 0.5, 0.75, 1.0], (alpha: number): void => {
          Column()
            .width(11).height(11).borderRadius(2)
            .backgroundColor(this.heatColor(alpha))
            .margin({ left: 3 })
        }, (alpha: number): string => `legend-${alpha}`)
        Text('多').fontSize(LABEL).fontColor(INK4).margin({ left: 3 })
      }
      .margin({ top: 10 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(RADIUS)

    // 周标签(简化:只标 Mon/Wed/Fri)
  }

  @Builder
  HeatmapGrid() {
    // 计算 53 周 × 7 天,每格 11px + 2px 间距
    // 横向可滚动(53 周 × 13px ≈ 689px,超屏宽)
    Scroll() {
      Grid() {
        ForEach(this.heatmapDates(), (weekArr: string[]): void => {
          GridItem() {
            Column({ space: 2 }) {
              ForEach(weekArr, (dayKeyStr: string): void => {
                Column()
                  .width(11).height(11).borderRadius(2)
                  .backgroundColor(this.heatColor(this.heatAlphaForDay(dayKeyStr)))
              }, (dayKeyStr: string, idx: number): string => `${dayKeyStr}-${idx}`)
            }
          }
        }, (weekArr: string[], wIdx: number): string => `week-${wIdx}`)
      }
      .columnsTemplate(`${'1fr '.repeat(53)}`)
      .rowsTemplate('1fr')
      .columnsGap(2)
      .rowsGap(0)
      .height(89) // 7×11 + 6×2
    }
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
  }

  // 生成 53 周 × 7 天的 dayKey 矩阵
  private heatmapDates(): string[][] {
    const start: Date = heatmapStartDate();
    const weeks: string[][] = [];
    for (let w = 0; w < 53; w++) {
      const week: string[] = [];
      for (let d = 0; d < 7; d++) {
        const date: Date = new Date(start);
        date.setDate(start.getDate() + w * 7 + d);
        week.push(dayKey(date.toISOString()));
      }
      weeks.push(week);
    }
    return weeks;
  }

  // 获取某天的消息数 → alpha
  private heatAlphaForDay(key: string): number {
    const count: number = this.stats.dayCounts.get(key) ?? 0;
    if (count === 0) return 0;
    // 动态四分位(对齐 Android)
    const counts: number[] = Array.from(this.stats.dayCounts.values()).filter((v: number): boolean => v > 0).sort((a: number, b: number): number => a - b);
    if (counts.length === 0) return 0.5;
    const q1: number = counts[Math.floor(counts.length * 0.25)] ?? 1;
    const q2: number = counts[Math.floor(counts.length * 0.50)] ?? 2;
    const q3: number = counts[Math.floor(counts.length * 0.75)] ?? 3;
    return heatmapAlpha(count, q1, q2, q3);
  }

  // alpha → 实际颜色(0 = 空格底色)
  private heatColor(alpha: number): string {
    if (alpha === 0) return SURFACE2;
    // accentColorStore 是 #RRGGBB,转 rgba 叠加 alpha
    const hex: string = this.accentColorStore.replace('#', '');
    if (hex.length !== 6) return SURFACE2;
    const r: number = parseInt(hex.substring(0, 2), 16);
    const g: number = parseInt(hex.substring(2, 4), 16);
    const b: number = parseInt(hex.substring(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }
}
