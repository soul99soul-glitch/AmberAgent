// SettingModelCouncilPage — Model Council 实验设置页(第三梯队)
// Android 基准: SettingExperimentalModelCouncilPage.kt
//   配置 ModelCouncilRuntimeSetting:enabled/maxSeats/rounds/timeout/outputBudget
//
// Harmony 后端: deepread HAR ModelCouncilRuntimeSetting
//   + PromptConfigStore.ets(createEntryAgentPromptSettingsPort getCouncilSetting)
//
// 2026-09-27 二级页 iOS 对齐(无 iOS 截图,沿用规范):仅换视觉骨架
//   (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow/
//    FooterNote;滑杆保留装卡片容器),逻辑零改动。
//
// 功能(MVP):
//   ✅ enabled 开关
//   ✅ maxSeats/defaultRounds/maxRounds 滑块
//   ✅ seatTimeoutMs/totalTimeoutMs 选择
//   ✅ outputBudgetChars 选择
//   ❌ 席位编辑(后续迭代)

import { getAgentPromptSettingsPort, getCouncilRunner, getModelRegistry } from '../di/AppContainer.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import type { ModelCouncilRuntimeSetting, ModelCouncilSeat, ModelCouncilRolePreset, ModelConfig, CouncilToolMode } from '@amber/deepread-domain';
import {
  LENS_PRESETS, findRolePreset, isCoreRole, makeSeat,
} from '@amber/deepread-domain';
import { INK, INK3, ACCENT, SURFACE2, BG, ERROR, LINE, LINE2, GUTTER, MOTION_OVERLAY, TEXT_STYLES, lineHeightForSize, SUCCESS_FG } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';
import { CouncilToolModePicker } from '../components/CouncilToolModePicker.ets';

@Entry
@Component
struct SettingModelCouncilPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State councilEnabled: boolean = true;
  @State toolMode: CouncilToolMode = 'off';
  @State maxSeats: number = 5;
  @State defaultRounds: number = 1;
  @State maxRounds: number = 3;
  @State seatTimeoutMs: number = 120000;
  @State totalTimeoutMs: number = 300000;
  @State outputBudgetChars: number = 200000;
  @State showSeatOutputs: boolean = true;
  // M3.3:席位编辑
  @State seats: ModelCouncilSeat[] = [];
  @State expandedSeatId: string = '';
  @State showAddSeatMenu: boolean = false;
  @State loaded: boolean = false;
  // F21:席位文本编辑未落盘标记 + 显式保存状态(唯一机制:显式保存 + 脏返回确认)
  @State dirty: boolean = false;
  @State saving: boolean = false;
  @State saveError: string = '';
  // D0-3:连通性测试(逐席位最小请求;lines 元素 = 'seatName|ok|detail')
  @State testing: boolean = false;
  @State testLines: string[] = [];

  private editorScroller: Scroller = new Scroller();
  private focusedPromptId: string = '';

  private revealPrompt(id: string): void {
    const context = this.getUIContext();
    const components = context.getComponentUtils();
    const prompt = components.getRectangleById(id);
    const viewport = components.getRectangleById('council-scroll');
    // 把完整输入框移至滚动视口顶部,不只让系统保证首行光标可见。
    const delta: number = context.px2vp(prompt.windowOffset.y - viewport.windowOffset.y) - 16;
    this.editorScroller.scrollTo({
      xOffset: 0, yOffset: Math.max(0, this.editorScroller.currentOffset().yOffset + delta),
      animation: { duration: MOTION_OVERLAY, curve: Curve.EaseOut },
    });
  }

  private previousKeyboardMode: KeyboardAvoidMode | null = null;

  onPageShow(): void {
    if (this.previousKeyboardMode === null) {
      this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    }
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
  }

  onPageHide(): void {
    if (this.previousKeyboardMode !== null) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.previousKeyboardMode = null;
    }
  }

  aboutToDisappear(): void { this.onPageHide(); }

  aboutToAppear(): void {
    this.loadSetting();
  }

  // F21:系统返回手势/物理返回;脏则弹确认(返回 true 消费本次返回)
  onBackPress(): boolean {
    if (this.dirty || this.saving) {
      this.requestBack();
      return true;
    }
    return false;
  }

  private async loadSetting(): Promise<void> {
    try {
      const port = getAgentPromptSettingsPort();
      const setting: ModelCouncilRuntimeSetting = await port.getCouncilSetting();
      this.councilEnabled = setting.enabled;
      this.toolMode = setting.toolMode;
      this.maxSeats = setting.maxSeats;
      this.defaultRounds = setting.defaultRounds;
      this.maxRounds = setting.maxRounds;
      this.seatTimeoutMs = setting.seatTimeoutMs;
      this.totalTimeoutMs = setting.totalTimeoutMs;
      this.outputBudgetChars = setting.outputBudgetChars;
      this.showSeatOutputs = setting.showSeatOutputs;
      // M3.3:加载席位
      this.seats = setting.defaultSeats.map((s: ModelCouncilSeat): ModelCouncilSeat => ({
        seatId: s.seatId, name: s.name, role: s.role, modelId: s.modelId,
        systemPrompt: s.systemPrompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature,
      }));
      this.loaded = true;
    } catch (_e) {
      this.loaded = true;
    }
  }

  // D0-3:连通性测试 — 逐席位解析模型库配置,发最小生成请求,报成功延迟/失败原因
  private async runConnectivityTest(): Promise<void> {
    if (this.testing || this.seats.length === 0) return;
    this.testing = true;
    this.testLines = [];
    const pool: ModelConfig[] = await getModelRegistry().list().catch((): ModelConfig[] => []);
    const lines: string[] = [];
    for (const seat of this.seats) {
      const name: string = seat.name.length > 0 ? seat.name : '(未命名席位)';
      const model: ModelConfig | undefined = pool.find((m: ModelConfig): boolean => m.id === seat.modelId);
      if (model === undefined) {
        lines.push(`${name}|fail|模型未在模型库配置`);
        continue;
      }
      const started: number = Date.now();
      try {
        await getCouncilRunner().generate({
          model: model,
          systemPrompt: 'Reply with the single word: pong.',
          userPrompt: 'ping',
          outputBudgetChars: 32,
          reasoningLevel: null,
          temperature: null,
          onChunk: (): void => {},
          signal: null,
          toolMode: 'off',
        });
        const secs: string = ((Date.now() - started) / 1000).toFixed(1);
        lines.push(`${name}|ok|${secs}s`);
      } catch (e) {
        const msg: string = e instanceof Error ? e.message : String(e);
        lines.push(`${name}|fail|${msg.length > 40 ? msg.substring(0, 40) + '…' : msg}`);
      }
      this.testLines = [...lines];
    }
    this.testing = false;
  }

  // F21:返回是否成功,供「保存并返回」在落盘成功后才 back,失败留草稿在页
  private async saveSetting(): Promise<boolean> {
    try {
      const port = getAgentPromptSettingsPort();
      const current: ModelCouncilRuntimeSetting = await port.getCouncilSetting();
      const next: ModelCouncilRuntimeSetting = {
        enabled: this.councilEnabled,
        toolMode: this.toolMode,
        defaultSeats: this.seats,
        synthesisModelId: current.synthesisModelId,
        maxSeats: this.maxSeats,
        defaultRounds: this.defaultRounds,
        maxRounds: this.maxRounds,
        seatTimeoutMs: this.seatTimeoutMs,
        totalTimeoutMs: this.totalTimeoutMs,
        outputBudgetChars: this.outputBudgetChars,
        showSeatOutputs: this.showSeatOutputs,
      };
      await port.updateCouncilSetting(next);
      this.dirty = false;
      this.saveError = '';
      return true;
    } catch (_e) {
      this.saveError = '保存失败,修改尚未写入,请重试';
      return false;
    }
  }

  // F21:显式「保存」— await 成功才 back;失败留在本页且草稿不丢
  private async saveAndBack(): Promise<void> {
    if (this.saving) return;
    this.saving = true;
    this.saveError = '';
    const ok: boolean = await this.saveSetting();
    this.saving = false;
    if (ok) {
      router.back();
    } else {
      AlertDialog.show({
        title: '保存失败',
        message: '设置未写入存储,草稿已保留,可重试或放弃修改。',
        primaryButton: { value: '知道了', action: (): void => {} },
      });
    }
  }

  // F21:脏返回确认(放弃 / 保存并返回;点外部取消)
  private requestBack(): void {
    if (this.saving) return;
    if (!this.dirty) {
      router.back();
      return;
    }
    AlertDialog.show({
      title: '有未保存的修改',
      message: '席位修改尚未保存,是否保存后返回?',
      primaryButton: {
        value: '放弃修改',
        action: (): void => {
          this.dirty = false;
          router.back();
        },
      },
      secondaryButton: {
        value: '保存并返回',
        action: (): void => { this.saveAndBack(); },
      },
    });
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({
        title: 'Model Council 设置',
        actionText: this.saving ? '保存中…' : '保存',
        onAction: (): void => { this.saveAndBack(); },
        onBack: (): void => { this.requestBack(); },
      })

      if (this.saveError.length > 0) {
        Text(this.saveError)
          .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(ERROR)
          .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 4 })
      }

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family).fontColor(INK3)
          .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
      } else {
        Scroll(this.editorScroller) {
          Column() {
            PlainSection({ label: '运行参数' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ParamsCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '默认工具功率' })
            CouncilToolModePicker({ mode: this.toolMode, disabled: this.saving,
              onSelect: (mode: CouncilToolMode): void => { this.toolMode = mode; this.dirty = true; } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '运行前可单独选择本轮功率。工具来自默认助手（首个配置），席位模型仍独立配置；FULL 保留原审批设置。' })

            PlainSection({ label: '席位编辑' })
            if (this.seats.length > 0) {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.SeatsCard(); } })
                .margin({ left: GUTTER, right: GUTTER })
            }
            if (this.seats.length < this.maxSeats) {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.AddSeatCard(); } })
                .margin({ left: GUTTER, right: GUTTER, top: this.seats.length > 0 ? 12 : 0 })
            } else {
              FooterNote({ text: `已达最大席位数 ${this.maxSeats}` })
            }

            PlainSection({ label: '连通性' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.TestCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            PageSpacer()
          }
          .width('100%')
          .padding({ bottom: 20 })
        }
        .id('council-scroll')
        .onAreaChange((oldArea: Area, area: Area): void => {
          // 键盘缩小视口后再对齐:页面尾部在键盘出现前可滚动距离不足。
          if (Number(area.height) < Number(oldArea.height) && this.focusedPromptId.length > 0) {
            this.revealPrompt(this.focusedPromptId);
          }
        })
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  // 卡片行内分隔线(内缩 14,对齐 iOS AmberFormGroup)
  @Builder
  CardSep(): void {
    Row() {
      Row().width('100%').height(1).backgroundColor(LINE)
    }
    .width('100%')
    .padding({ left: 14, right: 14 })
  }

  @Builder
  TestCard(): void {
    CardRow({
      label: '连通性测试',
      desc: '逐席位发送最小请求验证模型可用性',
      valueText: this.testing ? '测试中…' : '开始',
      isLast: this.testLines.length === 0,
      onRowClick: (): void => { this.runConnectivityTest(); },
    })
    ForEach(this.testLines, (line: string, idx: number): void => {
      Column() {
        Row() {
          Row().width('100%').height(0.5).backgroundColor(LINE)
        }
        .width('100%').padding({ left: 14, right: 14 })
        Row() {
          Text(this.testField(line, 0))
            .fontSize(15).fontColor(INK).layoutWeight(1)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text((this.testField(line, 1) === 'ok' ? '✓ ' : '✗ ') + this.testField(line, 2))
            .fontSize(13)
            .fontColor(this.testField(line, 1) === 'ok' ? SUCCESS_FG : ERROR)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('100%').constraintSize({ minHeight: 44 })
        .padding({ left: 14, right: 14, top: 4, bottom: 4 })
      }
      .width('100%')
    }, (line: string, idx: number): string => `${idx}-${line}`)
  }

  private testField(line: string, index: number): string {
    const parts: string[] = line.split('|');
    return index < parts.length ? parts[index] : '';
  }

  @Builder
  ParamsCard(): void {
    Column() {
      CardToggleRow({
        label: '启用 Model Council',
        desc: '多模型合议:对关键决策获取多视角对比',
        checked: this.councilEnabled,
        onToggle: (): void => {
          this.councilEnabled = !this.councilEnabled;
          this.saveSetting();
        },
      })
      this.SeatCountRow()
      this.DefaultRoundsRow()
      this.MaxRoundsRow()
      CardRow({
        label: '席位超时',
        valueText: this.timeoutLabel(this.seatTimeoutMs),
        onRowClick: (): void => {
          const opts: number[] = [60000, 120000, 180000, 300000];
          const idx: number = opts.indexOf(this.seatTimeoutMs);
          this.seatTimeoutMs = opts[(idx + 1) % opts.length];
          this.saveSetting();
        },
      })
      CardRow({
        label: '总超时',
        valueText: this.timeoutLabel(this.totalTimeoutMs),
        onRowClick: (): void => {
          const opts: number[] = [180000, 300000, 600000, 900000];
          const idx: number = opts.indexOf(this.totalTimeoutMs);
          this.totalTimeoutMs = opts[(idx + 1) % opts.length];
          this.saveSetting();
        },
      })
      CardToggleRow({
        label: '显示席位输出',
        desc: '在结果中展示各席位的独立输出',
        checked: this.showSeatOutputs,
        isLast: true,
        onToggle: (): void => {
          this.showSeatOutputs = !this.showSeatOutputs;
          this.saveSetting();
        },
      })
    }
    .width('100%')
  }

  // 滑杆行(装卡片容器:标题 + accent 数值 + Slider;范围/落盘逻辑与原 sliderRow 一致)
  @Builder
  SeatCountRow(): void {
    Column() {
      Column() {
        Row() {
          Text('最大席位数')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .layoutWeight(1)
          Text(`${this.maxSeats}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.accentColorStore)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        Slider({ value: this.maxSeats, min: 2, max: 8, step: 1 })
          .selectedColor(this.accentColorStore)
          .trackColor(LINE2)
          .width('100%').margin({ top: 4 })
          .onChange((v: number): void => {
            this.maxSeats = Math.round(v);
            this.saveSetting();
          })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      this.CardSep()
    }
    .width('100%')
  }

  @Builder
  DefaultRoundsRow(): void {
    Column() {
      Column() {
        Row() {
          Text('默认轮次')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .layoutWeight(1)
          Text(`${this.defaultRounds}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.accentColorStore)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        Slider({ value: this.defaultRounds, min: 1, max: 5, step: 1 })
          .selectedColor(this.accentColorStore)
          .trackColor(LINE2)
          .width('100%').margin({ top: 4 })
          .onChange((v: number): void => {
            this.defaultRounds = Math.round(v);
            this.saveSetting();
          })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      this.CardSep()
    }
    .width('100%')
  }

  @Builder
  MaxRoundsRow(): void {
    Column() {
      Column() {
        Row() {
          Text('最大轮次')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .layoutWeight(1)
          Text(`${this.maxRounds}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.accentColorStore)
        }
        .width('100%').alignItems(VerticalAlign.Center)
        Slider({ value: this.maxRounds, min: 1, max: 10, step: 1 })
          .selectedColor(this.accentColorStore)
          .trackColor(LINE2)
          .width('100%').margin({ top: 4 })
          .onChange((v: number): void => {
            this.maxRounds = Math.round(v);
            this.saveSetting();
          })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      this.CardSep()
    }
    .width('100%')
  }

  @Builder
  SeatsCard(): void {
    Column() {
      ForEach(this.seats, (seat: ModelCouncilSeat, idx: number): void => {
        this.SeatEditorRow(seat, idx === this.seats.length - 1)
      }, (seat: ModelCouncilSeat): string => seat.seatId)
    }
    .width('100%')
  }

  // M3.3:单个席位编辑行(展开 → name + systemPrompt + 删除)
  @Builder
  SeatEditorRow(seat: ModelCouncilSeat, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(seat.name.length > 0 ? seat.name : '(未命名席位)')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(isCoreRole(seat.role) ? '核心席' : `视角席 · ${seat.role}`)
            .fontSize(12).lineHeight(lineHeightForSize(12)).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text('▸')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          .rotate({ angle: this.expandedSeatId === seat.seatId ? 90 : 0 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .onClick((): void => {
        const collapsing: boolean = this.expandedSeatId === seat.seatId;
        if (collapsing) this.saveSetting();
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.expandedSeatId = collapsing ? '' : seat.seatId;
        });
      })

      if (this.expandedSeatId === seat.seatId) {
        Column() {
          Text('席位名称')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ bottom: 4 })
          TextInput({ text: seat.name, placeholder: '席位名称' })
            .width('100%').fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatName(seat.seatId, v); })

          Text('系统提示词')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ top: 10, bottom: 4 })
          TextArea({ text: seat.systemPrompt, placeholder: '系统提示词' })
            .id(`council-prompt-${seat.seatId}`)
            .onFocus((): void => {
              this.focusedPromptId = `council-prompt-${seat.seatId}`;
              this.revealPrompt(this.focusedPromptId);
            })
            .onBlur((): void => { this.focusedPromptId = ''; })
            .width('100%').height(100).fontSize(TEXT_STYLES.body.size).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(8)
            .onChange((v: string): void => { this.updateSeatPrompt(seat.seatId, v); })

          if (!isCoreRole(seat.role)) {
            Text('删除席位')
              .fontSize(14).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(14))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(ERROR).margin({ top: 10 })
              .onClick((): void => { this.deleteSeat(seat.seatId); })
          }
        }
        .width('100%')
        .padding({ left: 14, right: 14, bottom: 12 })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      if (!isLast) {
        this.CardSep()
      }
    }
    .width('100%').clip(true)
  }

  // M3.3:添加视角席(从 LENS_PRESETS 选)
  @Builder
  AddSeatCard(): void {
    Column() {
      Row() {
        Text('添加视角席')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
          .layoutWeight(1)
        Text('▸')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          .rotate({ angle: this.showAddSeatMenu ? 90 : 0 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .onClick((): void => {
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.showAddSeatMenu = !this.showAddSeatMenu;
        });
      })

      if (this.showAddSeatMenu) {
        Column() {
          this.CardSep()
          ForEach(LENS_PRESETS, (preset: ModelCouncilRolePreset, idx: number): void => {
            Row() {
              Text(preset.name)
                .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
                .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
                .fontColor(INK).layoutWeight(1)
              Text('添加')
                .fontSize(15).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(15))
                .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
            }
            .width('100%').alignItems(VerticalAlign.Center)
            .constraintSize({ minHeight: 44 })
            .padding({ left: 32, right: 14, top: 8, bottom: 8 })
            .onClick((): void => { this.addLensSeat(preset.id); })
            if (idx < LENS_PRESETS.length - 1) {
              this.CardSep()
            }
          }, (preset: ModelCouncilRolePreset): string => preset.id)
        }
        .width('100%')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
  }

  // M3.3:更新席位 name
  private updateSeatName(seatId: string, name: string): void {
    if (this.seats.find((seat: ModelCouncilSeat): boolean => seat.seatId === seatId)?.name === name) return;
    this.seats = this.seats.map((s: ModelCouncilSeat): ModelCouncilSeat =>
      s.seatId === seatId ? { seatId: s.seatId, name, role: s.role, modelId: s.modelId,
        systemPrompt: s.systemPrompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature } : s,
    );
    this.dirty = true;
  }

  // M3.3:更新席位 systemPrompt
  private updateSeatPrompt(seatId: string, prompt: string): void {
    if (this.seats.find((seat: ModelCouncilSeat): boolean => seat.seatId === seatId)?.systemPrompt === prompt) return;
    this.seats = this.seats.map((s: ModelCouncilSeat): ModelCouncilSeat =>
      s.seatId === seatId ? { seatId: s.seatId, name: s.name, role: s.role, modelId: s.modelId,
        systemPrompt: prompt, outputBudgetChars: s.outputBudgetChars,
        reasoningLevel: s.reasoningLevel, temperature: s.temperature } : s,
    );
    this.dirty = true;
  }

  // M3.3:删除席位(核心席不可删)
  private deleteSeat(seatId: string): void {
    const seat: ModelCouncilSeat | undefined = this.seats.find(
      (s: ModelCouncilSeat): boolean => s.seatId === seatId);
    if (seat === undefined) return;
    if (isCoreRole(seat.role)) return;
    this.seats = this.seats.filter((s: ModelCouncilSeat): boolean => s.seatId !== seatId);
    this.saveSetting();
  }

  // M3.3:添加视角席(从 LENS_PRESETS 选)
  private addLensSeat(presetId: string): void {
    const preset = findRolePreset(presetId);
    if (preset === null) return;
    if (this.seats.length >= this.maxSeats) return;
    const seat: ModelCouncilSeat = makeSeat({
      name: preset.name, role: preset.id, systemPrompt: preset.prompt,
    });
    this.seats = [...this.seats, seat];
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.showAddSeatMenu = false;
    });
    this.saveSetting();
  }

  private timeoutLabel(ms: number): string {
    if (ms === 60000) return '1 分钟';
    if (ms === 120000) return '2 分钟';
    if (ms === 180000) return '3 分钟';
    if (ms === 300000) return '5 分钟';
    if (ms === 600000) return '10 分钟';
    if (ms === 900000) return '15 分钟';
    return `${ms / 1000} 秒`;
  }
}
