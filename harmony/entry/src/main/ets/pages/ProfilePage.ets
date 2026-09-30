// ProfilePage — 个人中心(头像落地页 + 使用统计)
//
// 头部:大头像 + 昵称(可编辑) + 会话摘要
// 统计卡:总会话/总消息/输入Token/输出Token(+缓存Token)— 数据来自 RDB 实时聚合
// 热力图:53 周 × 7 天用户消息活跃度(对齐 Android StatsPage 算法)
// Android 基准: StatsPage.kt + StatsVM.kt
// 数据: DisplaySetting(KV) + ChatRepository(RDB 遍历聚合,不造假)
import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { common } from '@kit.AbilityKit';
import type { DisplaySetting, ConversationSummary, Conversation, UIMessage } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { compressImageAsDataUrl } from '../platform_impl/ImageCompress.ets';
import { CenteredTitleBar, PlainSection, CardGroup, PageSpacer } from '../components/PageTemplate.ets';
import { UIAvatar } from '../components/UIAvatar.ets';
import { Icon } from '../components/ui/Icon.ets';
import { FONT_MONO } from '../design/fonts.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG,
  BODY, BODY2, META, LABEL, ACCENT, RADIUS,
} from '../design/tokens.ets';
import { canvasGradientColors, currentCanvasGradientAngle } from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';

// 昵称编辑弹窗
@CustomDialog
struct NicknameEditDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
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

// F07:热力图渲染模型 — 日期矩阵与四分位阈值在 loadStats 后一次算好,build 只读取,
//   避免每次 build 重建 53×7 Date 并对活跃日重复 sort。
interface HeatmapModel {
  dates: string[][];
  q1: number;
  q2: number;
  q3: number;
}

const EMPTY_HEATMAP: HeatmapModel = {
  dates: [],
  q1: 1,
  q2: 2,
  q3: 3,
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State setting: DisplaySetting = makeDisplaySetting();
  @State stats: StatsResult = EMPTY_STATS;
  @State heatmap: HeatmapModel = EMPTY_HEATMAP;
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
      // F07:日期矩阵与阈值一次性算好(build 不再重建/排序)
      this.heatmap = this.buildHeatmap(dayCounts);
      this.statsLoaded = true;
    }).catch((): void => {
      this.statsLoaded = true;
    });
  }

  // F07:在 loadStats 完成后一次计算 53×7 日期矩阵与活跃日四分位阈值
  private buildHeatmap(dayCounts: Map<string, number>): HeatmapModel {
    const dates: string[][] = this.computeHeatmapDates();
    const counts: number[] = Array.from(dayCounts.values())
      .filter((v: number): boolean => v > 0)
      .sort((a: number, b: number): number => a - b);
    if (counts.length === 0) return { dates, q1: 1, q2: 2, q3: 3 };
    return {
      dates,
      q1: counts[Math.floor(counts.length * 0.25)] ?? 1,
      q2: counts[Math.floor(counts.length * 0.50)] ?? 2,
      q3: counts[Math.floor(counts.length * 0.75)] ?? 3,
    };
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

  // 头像选择(PhotoViewPicker 单选)→ 物化到 filesDir(KV 只存路径)
  private picking: boolean = false;

  private pickAvatar(): void {
    if (this.picking) return;
    this.picking = true;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 1;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then((result: photoAccessHelper.PhotoSelectResult): Promise<void> => {
      return this.materializeAvatar(result.photoUris);
    }).catch((_e: Error): void => {
      // 用户取消选择等系统侧取消不视为错误
      this.picking = false;
    });
  }

  private async materializeAvatar(uris: string[]): Promise<void> {
    try {
      if (uris.length === 0) return;
      let value: string = '';
      try {
        // 首选发图管线(D-079b):JPEG q85 ≤2048 EXIF 归一 → data: URL
        value = await compressImageAsDataUrl(uris[0]);
      } catch (de) {
        // 部分图源(HEIC/权限窗外的媒体项)PixelMap 解码失败 → 降级为字节级拷贝进沙箱
        const derr: Error = de as Error;
        hilog.warn(0x0001, 'ProfilePage', `头像解码降级拷贝: ${derr.message}`);
        value = this.copyAvatarToSandbox(uris[0]);
      }
      if (value.length === 0) return;
      this.saveAvatar(value);
    } catch (e) {
      const err: Error = e as Error;
      hilog.warn(0x0001, 'ProfilePage', `头像设置失败: ${err.message}`);
    } finally {
      this.picking = false;
    }
  }

  private copyAvatarToSandbox(uri: string): string {
    const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const destPath: string = `${ctx.filesDir}/avatar_${Date.now()}.jpg`;
    const src: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const st: fileIo.Stat = fileIo.statSync(src.fd);
      const buf: ArrayBuffer = new ArrayBuffer(st.size);
      fileIo.readSync(src.fd, buf, { offset: 0, length: st.size });
      const dst: fileIo.File = fileIo.openSync(destPath,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      try {
        fileIo.writeSync(dst.fd, buf);
      } finally {
        fileIo.closeSync(dst);
      }
      return destPath;
    } finally {
      fileIo.closeSync(src);
    }
  }

  private saveAvatar(path: string): void {
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
      userNickname: this.setting.userNickname,
      userAvatar: path,
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
        .fontFamily(FONT_MONO)
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
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // 画布纹理层(共享组件;shell 面:对位 iOS AccountView)
      DesignCanvasLayer({ surface: 'shell' })
      CenteredTitleBar({ title: '个人中心' })

      Scroll() {
        Column() {
          // ── 头部:大头像 + 昵称 + 摘要 ──
          Column() {
            Stack({ alignContent: Alignment.BottomEnd }) {
              UIAvatar({
                name: this.setting.userNickname,
                emoji: '',
                imageUrl: this.setting.userAvatar,
                avatarSize: 72,
                circular: true,
                accentColor: this.accentColorStore,
              })
              Row() {
                Icon({ name: 'image', iconSize: 13, color: INK2 })
              }
              .width(26)
              .height(26)
              .borderRadius(13)
              .backgroundColor('#FFFFFF')
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)
              .border({ width: 1, color: LINE })
            }
            .onClick((): void => { this.pickAvatar(); })
            .accessibilityText('更换头像')
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
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })

              .fontColor(INK3)
              .fontFamily(FONT_MONO)
              .margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 32, bottom: 24 })
          .alignItems(HorizontalAlign.Center)

          // ── 统计卡片 ──
          if (this.statsLoaded) {
            PlainSection({ label: '统计' })
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
            PlainSection({ label: '活跃' })
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

          // 版本信息
          Row() {
            Text('$').fontColor(this.accentColorStore).fontSize(LABEL).fontFamily(FONT_MONO)
            Text(` amber v${this.versionLabel}`).fontColor(INK3).fontSize(LABEL).fontFamily(FONT_MONO)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 22 })

          PageSpacer()
        }
        .width('100%')
        .padding({ left: 18, right: 18 })
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .linearGradient({
      angle: currentCanvasGradientAngle(),
      colors: canvasGradientColors(),
    })
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
        ForEach(this.heatmap.dates, (weekArr: string[]): void => {
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
        .align(Alignment.TopStart)
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
  }

  // 生成 53 周 × 7 天的 dayKey 矩阵(仅 loadStats 后调用一次)
  private computeHeatmapDates(): string[][] {
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

  // 获取某天的消息数 → alpha(F07:阈值来自缓存,不再按活跃日重复排序)
  private heatAlphaForDay(key: string): number {
    const count: number = this.stats.dayCounts.get(key) ?? 0;
    if (count === 0) return 0;
    return heatmapAlpha(count, this.heatmap.q1, this.heatmap.q2, this.heatmap.q3);
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
