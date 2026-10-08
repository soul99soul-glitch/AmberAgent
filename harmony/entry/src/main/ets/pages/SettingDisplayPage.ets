// SettingDisplayPage — 显示与字体设置页(G3-S2)
// 高频 DisplaySetting 字段的卡片分组编辑。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1984):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow),逻辑零改动。
import { loadDisplaySetting, saveDisplaySetting, makeDisplaySetting } from '@amber/chat-domain';
import type { DisplaySetting } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, Seg, BtnFill,
} from '../components/PageTemplate.ets';
import { INK, INK3, BG, ACCENT, GUTTER, TEXT_STYLES, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { textToSpeech } from '@kit.CoreSpeechKit';
import { promptAction } from '@kit.ArkUI';

const TTS_SPEED_OPTIONS: string[] = ['0.75×', '1.0×', '1.25×', '1.5×'];
const TTS_SPEED_VALUES: number[] = [0.75, 1.0, 1.25, 1.5];
const TTS_SAMPLE_TEXT: string = '你好,我是 Amber。这是一段系统语音引擎试听。';
const TTS_SPEED_KEY: string = 'tts_speed';

const FONT_RATIO_OPTIONS: string[] = ['85%', '100%', '115%', '130%'];
const ratios: number[] = [0.85, 1.0, 1.15, 1.3];
const PASTE_THRESHOLD_OPTIONS: string[] = ['500', '1000', '2000', '5000'];
const PASTE_THRESHOLDS: number[] = [500, 1000, 2000, 5000];
// 聊天字体族(DisplaySetting.chatFontFamily;Android 默认/衬线/等宽三档)
const FONT_FAMILY_OPTIONS: string[] = ['默认', '衬线', '等宽'];
const FONT_FAMILY_VALUES: string[] = ['DEFAULT', 'SERIF', 'MONO'];

@Entry
@Component
struct SettingDisplayPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State setting: DisplaySetting = makeDisplaySetting();
  @State loaded: boolean = false;
  // D1-3:TTS 系统引擎试听(CoreSpeechKit;语速档位持久化)
  @State ttsSpeed: number = 1.0;
  @State ttsSpeaking: boolean = false;
  private ttsEngine: textToSpeech.TextToSpeechEngine | null = null;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      this.setting = s;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
    getChatKvStore().get(TTS_SPEED_KEY).then((raw: string | null): void => {
      if (raw === null) return;
      const v: number = parseFloat(raw);
      if (!Number.isNaN(v) && TTS_SPEED_VALUES.indexOf(v) >= 0) this.ttsSpeed = v;
    }).catch((): void => {});
  }

  aboutToDisappear(): void {
    if (this.ttsEngine !== null) {
      try { this.ttsEngine.stop(); } catch (_e) { /* 已停止 */ }
    }
  }

  // D1-3:试听 — canIUse 包裹;引擎懒建,requestId 每次唯一(同实例不可复用)
  private async speakTtsSample(): Promise<void> {
    if (this.ttsSpeaking) return;
    if (!canIUse('SystemCapability.AI.TextToSpeech')) {
      promptAction.showToast({ message: '当前系统不支持语音合成', duration: 2000 });
      return;
    }
    this.ttsSpeaking = true;
    try {
      if (this.ttsEngine === null) {
        const extraParam: Record<string, Object> = {
          'style': 'interaction-broadcast', 'locate': 'CN', 'name': 'amber_tts',
        };
        const initParams: textToSpeech.CreateEngineParams = {
          language: 'zh-CN', person: 0, online: 1, extraParams: extraParam,
        };
        this.ttsEngine = await textToSpeech.createEngine(initParams);
      }
      const speakExtra: Record<string, Object> = {
        'speed': this.ttsSpeed, 'volume': 2, 'pitch': 1,
        'languageContext': 'zh-CN', 'audioType': 'pcm',
      };
      const params: textToSpeech.SpeakParams = {
        requestId: `tts_${Date.now()}`,
        extraParams: speakExtra,
      };
      this.ttsEngine.speak(TTS_SAMPLE_TEXT, params);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      promptAction.showToast({ message: `试听失败:${msg}`, duration: 2000 });
    } finally {
      this.ttsSpeaking = false;
    }
  }

  private setTtsSpeed(idx: number): void {
    this.ttsSpeed = TTS_SPEED_VALUES[idx];
    getChatKvStore().put(TTS_SPEED_KEY, `${this.ttsSpeed}`).catch((): void => {});
  }

  private update(patch: Partial<DisplaySetting>): void {
    // ArkTS 禁止对象 spread(arkts-no-spread)→ 手动合并
    const next: DisplaySetting = makeDisplaySetting({
      showAssistantBubble: patch.showAssistantBubble ?? this.setting.showAssistantBubble,
      showModelIcon: patch.showModelIcon ?? this.setting.showModelIcon,
      showModelName: patch.showModelName ?? this.setting.showModelName,
      showUserAvatar: patch.showUserAvatar ?? this.setting.showUserAvatar,
      showDateBelowName: patch.showDateBelowName ?? this.setting.showDateBelowName,
      showThinkingContent: patch.showThinkingContent ?? this.setting.showThinkingContent,
      autoCloseThinking: patch.autoCloseThinking ?? this.setting.autoCloseThinking,
      showUpdates: patch.showUpdates ?? this.setting.showUpdates,
      showMessageJumper: patch.showMessageJumper ?? this.setting.showMessageJumper,
      messageJumperOnLeft: patch.messageJumperOnLeft ?? this.setting.messageJumperOnLeft,
      fontSizeRatio: patch.fontSizeRatio ?? this.setting.fontSizeRatio,
      enableMessageGenerationHapticEffect: patch.enableMessageGenerationHapticEffect ?? this.setting.enableMessageGenerationHapticEffect,
      skipCropImage: patch.skipCropImage ?? this.setting.skipCropImage,
      enableNotificationOnMessageGeneration: patch.enableNotificationOnMessageGeneration ?? this.setting.enableNotificationOnMessageGeneration,
      enableLiveUpdateNotification: patch.enableLiveUpdateNotification ?? this.setting.enableLiveUpdateNotification,
      codeBlockAutoWrap: patch.codeBlockAutoWrap ?? this.setting.codeBlockAutoWrap,
      codeBlockAutoCollapse: patch.codeBlockAutoCollapse ?? this.setting.codeBlockAutoCollapse,
      showLineNumbers: patch.showLineNumbers ?? this.setting.showLineNumbers,
      pasteLongTextAsFile: patch.pasteLongTextAsFile ?? this.setting.pasteLongTextAsFile,
      pasteLongTextThreshold: patch.pasteLongTextThreshold ?? this.setting.pasteLongTextThreshold,
      sendOnEnter: patch.sendOnEnter ?? this.setting.sendOnEnter,
      enableAutoScroll: patch.enableAutoScroll ?? this.setting.enableAutoScroll,
      showBottomFollowAnimation: patch.showBottomFollowAnimation ?? this.setting.showBottomFollowAnimation,
      enableLatexRendering: patch.enableLatexRendering ?? this.setting.enableLatexRendering,
      enableGenerativeWidgets: patch.enableGenerativeWidgets ?? this.setting.enableGenerativeWidgets,
      enableBlurEffect: patch.enableBlurEffect ?? this.setting.enableBlurEffect,
      chatFontFamily: patch.chatFontFamily ?? this.setting.chatFontFamily,
      enableVolumeKeyScroll: patch.enableVolumeKeyScroll ?? this.setting.enableVolumeKeyScroll,
      volumeKeyScrollRatio: patch.volumeKeyScrollRatio ?? this.setting.volumeKeyScrollRatio,
      chatThemeChoice: patch.chatThemeChoice ?? this.setting.chatThemeChoice,
      amberBaseFamily: patch.amberBaseFamily ?? this.setting.amberBaseFamily,
      accentColor: patch.accentColor ?? this.setting.accentColor,
      userNickname: patch.userNickname ?? this.setting.userNickname,
      userAvatar: patch.userAvatar ?? this.setting.userAvatar,
    });
    if (patch.pasteLongTextAsFile !== undefined) {
      this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
        this.setting = next;
      });
    } else {
      this.setting = next;
    }
    saveDisplaySetting(getChatKvStore(), this.setting);
  }

  private fontSizeRatioIndex(): number {
    let closest: number = 0;
    let distance: number = Math.abs(this.setting.fontSizeRatio - ratios[0]);
    for (let i = 1; i < ratios.length; i++) {
      const nextDistance: number = Math.abs(this.setting.fontSizeRatio - ratios[i]);
      if (nextDistance < distance) {
        closest = i;
        distance = nextDistance;
      }
    }
    return closest;
  }

  private pasteThresholdIndex(): number {
    const idx: number = PASTE_THRESHOLDS.indexOf(this.setting.pasteLongTextThreshold);
    return idx >= 0 ? idx : 1;
  }

  private fontFamilyIndex(): number {
    const idx: number = FONT_FAMILY_VALUES.indexOf(this.setting.chatFontFamily);
    return idx >= 0 ? idx : 0;
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  // 字体组:字号比例 Seg(原有实现装入卡片容器,iOS 同位为聊天字体大小卡)
  @Builder
  FontCard(): void {
    Column() {
      Row() {
        Text('字号比例')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
        Blank()
        Text(`${Math.round(this.setting.fontSizeRatio * 100)}%`)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      Seg({
        options: FONT_RATIO_OPTIONS,
        selected: this.fontSizeRatioIndex(),
        onSelect: (idx: number): void => { this.update({ fontSizeRatio: ratios[idx] }); },
      })
        .margin({ top: 12 })
      Row() {
        Text('字体族')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .margin({ top: 16 })
      Seg({
        options: FONT_FAMILY_OPTIONS,
        selected: this.fontFamilyIndex(),
        onSelect: (idx: number): void => { this.update({ chatFontFamily: FONT_FAMILY_VALUES[idx] }); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  MessageDisplayCard(): void {
    CardToggleRow({
      label: '助手气泡', desc: '为助手消息显示气泡背景',
      checked: this.setting.showAssistantBubble,
      onToggle: (): void => { this.update({ showAssistantBubble: !this.setting.showAssistantBubble }); },
    })
    CardToggleRow({
      label: '显示模型名', desc: '在消息头部显示模型名称',
      checked: this.setting.showModelName,
      onToggle: (): void => { this.update({ showModelName: !this.setting.showModelName }); },
    })
    CardToggleRow({
      // F16:原「显示用户头像」错绑 showModelIcon;该字段实际控制模型图标
      label: '显示模型图标', desc: '在助手消息头部显示模型首字母标识',
      checked: this.setting.showModelIcon,
      onToggle: (): void => { this.update({ showModelIcon: !this.setting.showModelIcon }); },
    })
    CardToggleRow({
      label: '显示用户头像', desc: '在用户消息气泡旁显示个人头像',
      checked: this.setting.showUserAvatar,
      onToggle: (): void => { this.update({ showUserAvatar: !this.setting.showUserAvatar }); },
    })
    CardToggleRow({
      label: '思考内容', desc: '在消息中显示模型的思考过程',
      checked: this.setting.showThinkingContent,
      onToggle: (): void => { this.update({ showThinkingContent: !this.setting.showThinkingContent }); },
    })
    CardToggleRow({
      label: '生成式组件', desc: '显示正文中的卡片、图表和幻灯片，并收录到产物架',
      checked: this.setting.enableGenerativeWidgets,
      onToggle: (): void => { this.update({ enableGenerativeWidgets: !this.setting.enableGenerativeWidgets }); },
    })
    CardToggleRow({
      label: '自动收起思考', desc: '生成完成后自动折叠思考过程',
      checked: this.setting.autoCloseThinking,
      onToggle: (): void => { this.update({ autoCloseThinking: !this.setting.autoCloseThinking }); },
    })
    CardToggleRow({
      label: '自动滚动', desc: '新消息到达时自动滚动到底部',
      checked: this.setting.enableAutoScroll,
      onToggle: (): void => { this.update({ enableAutoScroll: !this.setting.enableAutoScroll }); },
    })
    CardToggleRow({
      label: '消息跳转按钮', desc: '长会话显示回到底部/顶部悬浮按钮',
      checked: this.setting.showMessageJumper,
      onToggle: (): void => { this.update({ showMessageJumper: !this.setting.showMessageJumper }); },
    })
    CardToggleRow({
      label: '跳转按钮靠左', desc: '跳转按钮显示在屏幕左下角',
      checked: this.setting.messageJumperOnLeft,
      onToggle: (): void => { this.update({ messageJumperOnLeft: !this.setting.messageJumperOnLeft }); },
    })
    CardToggleRow({
      label: '生成完成触觉反馈', desc: '生成结束时震动提醒',
      checked: this.setting.enableMessageGenerationHapticEffect, isLast: true,
      onToggle: (): void => { this.update({ enableMessageGenerationHapticEffect: !this.setting.enableMessageGenerationHapticEffect }); },
    })
  }

  @Builder
  InputCard(): void {
    CardToggleRow({
      label: '回车发送', desc: '软键盘回车键直接发送消息',
      checked: this.setting.sendOnEnter,
      onToggle: (): void => { this.update({ sendOnEnter: !this.setting.sendOnEnter }); },
    })
    CardToggleRow({
      label: '长文本粘贴为文件', desc: '超过阈值的粘贴内容转为文件附件',
      checked: this.setting.pasteLongTextAsFile,
      isLast: !this.setting.pasteLongTextAsFile,
      onToggle: (): void => { this.update({ pasteLongTextAsFile: !this.setting.pasteLongTextAsFile }); },
    })
    if (this.setting.pasteLongTextAsFile) {
      Column() {
        Row() {
          Text('粘贴阈值')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
          Blank()
          Text(`${this.setting.pasteLongTextThreshold} 字符`)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        Seg({
          options: PASTE_THRESHOLD_OPTIONS,
          selected: this.pasteThresholdIndex(),
          onSelect: (idx: number): void => { this.update({ pasteLongTextThreshold: PASTE_THRESHOLDS[idx] }); },
        })
          .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  CodeBlockCard(): void {
    CardToggleRow({
      label: '自动折叠长代码', desc: '超过 10 行的代码块自动折叠',
      checked: this.setting.codeBlockAutoCollapse,
      onToggle: (): void => { this.update({ codeBlockAutoCollapse: !this.setting.codeBlockAutoCollapse }); },
    })
    CardToggleRow({
      label: '显示行号', desc: '在代码块中显示行号',
      checked: this.setting.showLineNumbers, isLast: true,
      onToggle: (): void => { this.update({ showLineNumbers: !this.setting.showLineNumbers }); },
    })
  }

  @Builder
  TtsCard(): void {
    Column() {
      Row() {
        Text('语速')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
        Blank()
        Text(`${this.ttsSpeed}×`)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      Seg({
        options: TTS_SPEED_OPTIONS,
        selected: TTS_SPEED_VALUES.indexOf(this.ttsSpeed),
        onSelect: (idx: number): void => { this.setTtsSpeed(idx); },
      })
        .margin({ top: 12 })
      Row() {
        Text('系统引擎试听,朗读固定示例句')
          .fontSize(TEXT_STYLES.meta.size)
          .fontColor(INK3)
          .layoutWeight(1)
        BtnFill({
          label: this.ttsSpeaking ? '播报中…' : '试听',
          onTap: (): void => { this.speakTtsSample().catch((): void => {}); },
        })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '显示与字体' })
      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // ── 字体(对齐 iOS「字体」组)──
            PlainSection({ label: '字体' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.FontCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 消息显示 ──
            PlainSection({ label: '消息显示' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.MessageDisplayCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 输入 ──
            PlainSection({ label: '输入' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.InputCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 代码块 ──
            PlainSection({ label: '代码块' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.CodeBlockCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            // ── 语音试听(D1-3) ──
            PlainSection({ label: '语音试听' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.TtsCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .enabled(this.themeEpoch >= 0)
    .width('100%').height('100%').backgroundColor(BG)
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
