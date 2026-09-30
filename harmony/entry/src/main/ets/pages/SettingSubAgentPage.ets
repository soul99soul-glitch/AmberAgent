// SettingSubAgentPage — SubAgent 实验设置页(第三梯队)
// Android 基准: SettingExperimentalSubAgentPage.kt(588 行)
//   配置 SubAgentRuntimeSetting:enabled/mode/allowDynamic/maxConcurrentRuns/
//   maxTurns/timeout/outputBudget + 每个 built-in role 的 override
//
// Harmony 后端: agent_prompt_config.ts(SubAgentRuntimeSetting)
//   + PromptConfigStore.ets(createEntryAgentPromptSettingsPort RMW)
//   + AppContainer getAgentPromptSettingsPort/refreshSubAgentSetting
//
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1993「子代理」):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow/
//  步进器:accent 数值 + −/+ 44 热区),逻辑零改动。
//
// 功能(MVP):
//   ✅ enabled 开关
//   ✅ mode 选择(roster/smart_dynamic)
//   ✅ maxConcurrentRuns/maxTurns 步进
//   ✅ timeout/outputBudget 选择
//   ❌ per-role override 编辑(后续迭代)
//   ❌ custom definition 编辑(后续迭代)

import { getAgentPromptSettingsPort, refreshSubAgentSetting, getChatKvStore } from '../di/AppContainer.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import type { SubAgentRuntimeSetting, SubAgentMode, SubAgentOverride, SubAgentDefinition, ProviderSetting } from '@amber/chat-domain';
import {
  DEFAULT_SUB_AGENT_TIMEOUT_MS, DEFAULT_SUB_AGENT_MAX_TURNS,
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  SUB_AGENT_BUILT_INS, loadProviders,
} from '@amber/chat-domain';
import { ACCENT, BG, ERROR, GUTTER, INK, INK3, LINE, MOTION_OVERLAY, SURFACE2, TEXT_STYLES, lineHeightForSize } from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, Seg, PageSpacer, BtnGhost,
} from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingSubAgentPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State subAgentEnabled: boolean = false;
  @State mode: string = 'roster';
  @State allowDynamic: boolean = true;
  @State maxConcurrentRuns: number = DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS;
  @State maxTurns: number = DEFAULT_SUB_AGENT_MAX_TURNS;
  @State timeoutMs: number = DEFAULT_SUB_AGENT_TIMEOUT_MS;
  @State outputBudgetChars: number = DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS;
  // M3.2:per-role overrides(built-in role id → override)
  @State overrides: Record<string, SubAgentOverride> = {};
  @State expandedRoleId: string = '';
  // D5-2:模型池(全部启用服务商的 chat 模型;'' = 跟随默认)+ 展开中的模型选择
  @State modelPool: string[][] = [];   // [modelUuid, 'provider/modelId']
  @State expandedModelFor: string = '';
  @State loaded: boolean = false;
  // F21:文本编辑未落盘标记 + 显式保存状态(唯一机制:显式保存 + 脏返回确认;
  //   不加 aboutToDisappear flush,也不每键自动保存)
  @State dirty: boolean = false;
  @State saving: boolean = false;
  @State saveError: string = '';

  private editorScroller: Scroller = new Scroller();
  private focusedPromptId: string = '';

  private revealPrompt(id: string): void {
    const context = this.getUIContext();
    const components = context.getComponentUtils();
    const prompt = components.getRectangleById(id);
    const viewport = components.getRectangleById('subagent-scroll');
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
      const setting: SubAgentRuntimeSetting = await port.getSubAgentSetting();
      this.subAgentEnabled = setting.enabled;
      this.mode = setting.mode;
      this.allowDynamic = setting.allowDynamicSubAgents;
      this.maxConcurrentRuns = setting.maxConcurrentRuns;
      this.maxTurns = setting.maxTurns;
      this.timeoutMs = setting.timeoutMs;
      this.outputBudgetChars = setting.outputBudgetChars;
      // M3.2:加载 overrides(Map → Record 便于 @State 响应式)
      const ov: Record<string, SubAgentOverride> = {};
      setting.overrides.forEach((v: SubAgentOverride, k: string): void => { ov[k] = v; });
      this.overrides = ov;
      // D5-2:模型池(全部启用服务商的 chat 模型)
      const providers: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
      const pool: string[][] = [];
      for (const p of providers) {
        if (!p.enabled) continue;
        for (const m of p.models) {
          if (m.type === 'chat') pool.push([m.id, `${p.name}/${m.modelId}`]);
        }
      }
      this.modelPool = pool;
      this.loaded = true;
    } catch (_e) {
      this.loaded = true;
    }
  }

  // F21:返回是否成功,供「保存并返回」在落盘成功后才 back,失败留草稿在页。
  //   注意:saveSetting 写整份 @State,故任何一次成功保存都会把文本编辑一并落盘。
  private async saveSetting(): Promise<boolean> {
    try {
      const port = getAgentPromptSettingsPort();
      const current: SubAgentRuntimeSetting = await port.getSubAgentSetting();
      const next: SubAgentRuntimeSetting = {
        enabled: this.subAgentEnabled,
        mode: this.mode as SubAgentMode,
        allowDynamicSubAgents: this.allowDynamic,
        maxConcurrentRuns: this.maxConcurrentRuns,
        timeoutMs: this.timeoutMs,
        maxTurns: this.maxTurns,
        outputBudgetChars: this.outputBudgetChars,
        // M3.2:写回 overrides(Record → Map,过滤空 override)
        overrides: this.buildOverridesMap(),
        customDefinitions: current.customDefinitions,
      };
      await port.updateSubAgentSetting(next);
      await refreshSubAgentSetting();
      this.dirty = false;
      this.saveError = '';
      return true;
    } catch (_e) {
      // F21:不再静默吞错;失败保留草稿并给出可见反馈
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
      message: '角色提示词修改尚未保存,是否保存后返回?',
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

  // 步进(原 slider 1..5 / 2..8,边界逻辑不变;每步落盘同 slider onChange)
  private adjustMaxConcurrent(delta: number): void {
    const next: number = Math.min(Math.max(this.maxConcurrentRuns + delta, 1), 5);
    if (next === this.maxConcurrentRuns) return;
    this.maxConcurrentRuns = next;
    this.saveSetting();
  }

  private adjustMaxTurns(delta: number): void {
    const next: number = Math.min(Math.max(this.maxTurns + delta, 2), 8);
    if (next === this.maxTurns) return;
    this.maxTurns = next;
    this.saveSetting();
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({
        title: '子代理',
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
            Text('把独立任务交给子代理,在当前聊天中继续沟通。任务进度和结果会回到原会话。')
              .fontSize(13).lineHeight(lineHeightForSize(13)).fontFamily(FONT_SANS_MEDIUM)
              .fontColor(INK3)
              .width('100%').padding({ left: 16, right: 16, top: 2, bottom: 6 })

            PlainSection({ label: '编排' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.OrchestrationCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '执行' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ExecutionCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '角色' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.RolesCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            PageSpacer()
          }
          .width('100%')
          .padding({ bottom: 20 })
        }
        .id('subagent-scroll')
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

  @Builder
  OrchestrationCard(): void {
    Column() {
      CardToggleRow({
        label: '启用子代理',
        desc: '允许主 Agent 委派子任务给隔离的子代理',
        checked: this.subAgentEnabled,
        onToggle: (): void => {
          this.subAgentEnabled = !this.subAgentEnabled;
          this.saveSetting();
        },
      })
      this.ModeRow()
      if (this.mode !== 'smart_dynamic') {
        CardToggleRow({
          label: '允许动态子代理',
          desc: '允许创建临时自定义子代理角色',
          checked: this.allowDynamic,
          isLast: true,
          onToggle: (): void => {
            this.allowDynamic = !this.allowDynamic;
            this.saveSetting();
          },
        })
          .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
  }

  @Builder
  ModeRow(): void {
    Column() {
      Row() {
        Column() {
          Text('模式')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Text(this.mode === 'smart_dynamic' ? '智能动态:隐藏内置角色,按需创建临时子代理' : '名册:使用预设的内置子代理角色')
            .fontSize(12).lineHeight(lineHeightForSize(12)).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3).margin({ top: 2 })
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Seg({ options: ['名册', '智能动态'], selected: this.mode === 'smart_dynamic' ? 1 : 0,
          onSelect: (idx: number): void => {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.mode = idx === 1 ? 'smart_dynamic' : 'roster';
            });
            this.saveSetting();
          } })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      // 分隔线按状态内绘:smart_dynamic 时本行即组末行,不发丝线
      if (this.mode !== 'smart_dynamic') {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  ExecutionCard(): void {
    Column() {
      this.ConcurrentStepperRow()
      this.TurnsStepperRow()
      CardRow({
        label: '超时时间',
        valueText: this.timeoutLabel(),
        onRowClick: (): void => {
          const options: number[] = [60000, 180000, 300000, 600000];
          const idx: number = options.indexOf(this.timeoutMs);
          this.timeoutMs = options[(idx + 1) % options.length];
          this.saveSetting();
        },
      })
      CardRow({
        label: '输出预算',
        valueText: this.outputBudgetLabel(),
        isLast: true,
        onRowClick: (): void => {
          const options: number[] = [8000, 12000, 24000, 48000];
          const idx: number = options.indexOf(this.outputBudgetChars);
          this.outputBudgetChars = options[(idx + 1) % options.length];
          this.saveSetting();
        },
      })
    }
    .width('100%')
  }

  // iOS 步进行:标题 + accent 数值 + −/+ 44 热区(基准 IMG_1993 最大并发)
  @Builder
  ConcurrentStepperRow(): void {
    Column() {
      Row() {
        Text('最大并发数')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .layoutWeight(1)
        Row() {
          Row() {
            Text('−')
              .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustMaxConcurrent(-1); })
          Text(`${this.maxConcurrentRuns}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.accentColorStore)
            .width(28).textAlign(TextAlign.Center)
          Row() {
            Text('+')
              .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustMaxConcurrent(1); })
        }
        .alignItems(VerticalAlign.Center)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
    }
    .width('100%')
  }

  @Builder
  TurnsStepperRow(): void {
    Column() {
      Row() {
        Text('最大轮次')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .layoutWeight(1)
        Row() {
          Row() {
            Text('−')
              .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustMaxTurns(-1); })
          Text(`${this.maxTurns}`)
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(this.accentColorStore)
            .width(28).textAlign(TextAlign.Center)
          Row() {
            Text('+')
              .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustMaxTurns(1); })
        }
        .alignItems(VerticalAlign.Center)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
    }
    .width('100%')
  }

  @Builder
  RolesCard(): void {
    Column() {
      ForEach(SUB_AGENT_BUILT_INS, (def: SubAgentDefinition, idx: number): void => {
        this.roleOverrideRow(def, idx === SUB_AGENT_BUILT_INS.length - 1)
      }, (def: SubAgentDefinition): string => def.id)
    }
    .width('100%')
  }

  // M3.2:单个内置角色的 override 编辑行(展开 → systemPrompt textarea + 重置)
  @Builder
  roleOverrideRow(def: SubAgentDefinition, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(def.name)
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          Text(def.description)
            .fontSize(12).lineHeight(lineHeightForSize(12)).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text('▸')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          .rotate({ angle: this.expandedRoleId === def.id ? 90 : 0 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut })
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .id(`subagent-role-${def.id}`)
      .onClick((): void => {
        // M3.2:折叠时保存(展开 → 折叠 = 提交编辑)
        const collapsing: boolean = this.expandedRoleId === def.id;
        if (collapsing) this.saveSetting();
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.expandedRoleId = collapsing ? '' : def.id;
          this.expandedModelFor = '';
        });
      })

      if (this.expandedRoleId === def.id) {
        Column() {
          Text('自定义提示词(留空 = 继承默认)')
            .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3).width('100%').margin({ bottom: 4 })
          TextArea({
            text: this.getOverride(def.id).systemPrompt ?? '',
            placeholder: def.systemPrompt,
          })
            .width('100%').height(120).fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family).fontColor(INK).placeholderColor(INK3)
            .backgroundColor(SURFACE2).borderRadius(8)
            .id(`subagent-prompt-${def.id}`)
            .onFocus((): void => {
              this.focusedPromptId = `subagent-prompt-${def.id}`;
              this.revealPrompt(this.focusedPromptId);
            })
            .onBlur((): void => { this.focusedPromptId = ''; })
            .onChange((v: string): void => { this.updateRolePrompt(def.id, v); })
          Column() {
            Row() {
              Text('模型(跟随默认 = 当前聊天模型)')
                .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
                .fontColor(INK3).layoutWeight(1)
              Text(this.modelLabel(this.getOverride(def.id).modelId))
                .fontSize(TEXT_STYLES.meta.size).fontColor(this.accentColorStore)
                .padding({ left: 8, top: 4, bottom: 4 })
            }
            .width('100%').alignItems(VerticalAlign.Center)
            .margin({ top: 10 })
            .onClick((): void => {
              this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.expandedModelFor = this.expandedModelFor === def.id ? '' : def.id;
              });
            })
            if (this.expandedModelFor === def.id) {
              Column() {
                this.modelOptionRow(def.id, null, '跟随默认')
                ForEach(this.modelPool, (entry: string[]): void => {
                  this.modelOptionRow(def.id, entry[0], entry[1])
                }, (entry: string[]): string => entry[0])
              }
              .width('100%')
              .backgroundColor(SURFACE2)
              .borderRadius(8)
              .padding({ left: 10, right: 10, top: 4, bottom: 4 })
              .margin({ top: 4 })
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            }
          }
          .width('100%').clip(true)
          Row() {
            BtnGhost({ label: '恢复默认', onTap: (): void => { this.resetRole(def.id); } })
          }
          .width('100%').margin({ top: 8 })
        }
        .width('100%')
        .padding({ left: 14, right: 14, bottom: 12 })
        .id(`subagent-editor-${def.id}`)
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%').clip(true)
  }

  // M3.2:构建 overrides Map(Record → Map;空 override 不写)
  private buildOverridesMap(): Map<string, SubAgentOverride> {
    const m: Map<string, SubAgentOverride> = new Map();
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) {
      const o: SubAgentOverride = this.overrides[k];
      if (o.systemPrompt !== null || o.modelId !== null || o.temperature !== null
        || o.reasoningLevel !== null || o.maxTurnsOverride !== null
        || o.timeoutMsOverride !== null || o.outputBudgetOverride !== null) {
        m.set(k, o);
      }
    }
    return m;
  }

  // M3.2:获取某 role 的 override(无则空 override)
  private getOverride(roleId: string): SubAgentOverride {    const o: SubAgentOverride | undefined = this.overrides[roleId];
    if (o !== undefined) return o;
    return {
      systemPrompt: null, modelId: null, temperature: null,
      reasoningLevel: null, maxTurnsOverride: null,
      timeoutMsOverride: null, outputBudgetOverride: null,
    };
  }

  // D5-2:模型选项行(单选语义,选中高亮)
  @Builder
  modelOptionRow(roleId: string, modelUuid: string | null, label: string): void {
    Row() {
      Text(label)
        .fontSize(13).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(this.getOverride(roleId).modelId === modelUuid ? this.accentColorStore : INK)
        .layoutWeight(1)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (this.getOverride(roleId).modelId === modelUuid) {
        Icon({ name: 'check', iconSize: 14, color: this.accentColorStore })
      }
    }
    .width('100%').alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 52 })
    .onClick((): void => { this.updateRoleModel(roleId, modelUuid); })
  }

  private modelLabel(modelUuid: string | null): string {
    if (modelUuid === null) return '跟随默认';
    for (const entry of this.modelPool) {
      if (entry[0] === modelUuid) return entry[1];
    }
    return modelUuid;
  }

  // D5-2:更新某 role 的 modelId override(null = 跟随默认)
  private updateRoleModel(roleId: string, modelUuid: string | null): void {
    const o: SubAgentOverride = this.getOverride(roleId);
    const next: SubAgentOverride = {
      systemPrompt: o.systemPrompt, modelId: modelUuid, temperature: o.temperature,
      reasoningLevel: o.reasoningLevel, maxTurnsOverride: o.maxTurnsOverride,
      timeoutMsOverride: o.timeoutMsOverride, outputBudgetOverride: o.outputBudgetOverride,
    };
    const ov: Record<string, SubAgentOverride> = {};
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) ov[k] = this.overrides[k];
    ov[roleId] = next;
    this.overrides = ov;
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.expandedModelFor = '';
    });
    this.dirty = true;
  }

  // M3.2:更新某 role 的 systemPrompt override
  private updateRolePrompt(roleId: string, prompt: string): void {
    const o: SubAgentOverride = this.getOverride(roleId);
    if ((o.systemPrompt ?? '') === prompt) return;
    const next: SubAgentOverride = {
      systemPrompt: prompt, modelId: o.modelId, temperature: o.temperature,
      reasoningLevel: o.reasoningLevel, maxTurnsOverride: o.maxTurnsOverride,
      timeoutMsOverride: o.timeoutMsOverride, outputBudgetOverride: o.outputBudgetOverride,
    };
    const ov: Record<string, SubAgentOverride> = {};
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) ov[k] = this.overrides[k];
    ov[roleId] = next;
    this.overrides = ov;
    this.dirty = true;
  }

  // M3.2:清除某 role 的 override(恢复默认)
  private resetRole(roleId: string): void {
    const ov: Record<string, SubAgentOverride> = {};
    const keys: string[] = Object.keys(this.overrides);
    for (const k of keys) {
      if (k !== roleId) ov[k] = this.overrides[k];
    }
    this.overrides = ov;
    this.saveSetting();
  }

  private timeoutLabel(): string {
    if (this.timeoutMs === 60000) return '1 分钟';
    if (this.timeoutMs === 180000) return '3 分钟';
    if (this.timeoutMs === 300000) return '5 分钟';
    if (this.timeoutMs === 600000) return '10 分钟';
    return `${this.timeoutMs / 1000} 秒`;
  }

  private outputBudgetLabel(): string {
    if (this.outputBudgetChars === 8000) return '8K 字符';
    if (this.outputBudgetChars === 12000) return '12K 字符';
    if (this.outputBudgetChars === 24000) return '24K 字符';
    if (this.outputBudgetChars === 48000) return '48K 字符';
    return `${this.outputBudgetChars} 字符`;
  }
}
