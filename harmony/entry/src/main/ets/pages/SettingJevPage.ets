// SettingJevPage — Jev 快速判断设置页(C5-1;Android SettingJevPage 对齐)
//
// 模式:关 / 影子(只记录不影响行为)/ 主动(结果影响工具发现选择);
// 方言:TypeSafe 原生(/v1/systemone)/ Vercel AI Gateway(evaluation-model)。
// 新用途各自配置外发范围；审批分诊只显示中性事实，不改变批准决定。

import {
  loadJevSettings, saveJevSettings, makeJevSettings, decodeJevResponse, buildJevRequest,
  JEV_TYPESAFE_ENDPOINT,
  resolveJevPurposeMode, jevPurposeSettings,
} from '@amber/chat-domain';
import type { JevSettings, JevEvaluation, JevQuestion, JevMode, JevNewPurpose, JevNewPurposeSettings } from '@amber/chat-domain';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadJevRuntimeStatus, JevRuntimeStatus } from '../platform_impl/JevSupport.ets';
import {
  ACCENT, BG, ERROR, INK, INK3, INK4, SURFACE2, BODY, LABEL, META, TEXT_STYLES, GUTTER, MOTION_OVERLAY,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer, Seg,
} from '../components/PageTemplate.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { promptAction } from '@kit.ArkUI';
import { SSHUIColors } from '../components/SSHUIColors.ets';

const MODE_OPTIONS: string[] = ['关', '影子', '主动'];
const MODE_VALUES: string[] = ['off', 'shadow', 'active'];
const API_MODE_OPTIONS: string[] = ['TypeSafe', 'Vercel'];
const API_MODE_VALUES: string[] = ['typesafe', 'vercel'];

@Entry
@Component
struct SettingJevPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State settings: JevSettings = makeJevSettings();
  @State status: JevRuntimeStatus = {
    mode: 'off', apiMode: 'typesafe', todayCount: 0, budget: 200, cooldownUntil: 0, lastError: '',
  };
  @State loaded: boolean = false;
  @State testing: boolean = false;
  @State testMsg: string = '';
  @State saveError: string = '';
  @State loadError: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  private persistTail: Promise<void> = Promise.resolve();
  private alive: boolean = false;
  private loadRevision: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.refresh();
  }

  aboutToDisappear(): void { this.alive = false; this.loadRevision++; }
  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private refresh(): void {
    const revision: number = ++this.loadRevision;
    this.loadError = '';
    loadJevSettings(getChatKvStore()).then((s: JevSettings): void => {
      if (!this.alive || revision !== this.loadRevision) return;
      this.settings = s;
      this.loaded = true;
    }).catch((): void => {
      if (this.alive && revision === this.loadRevision) this.loadError = '设置读取失败，请重试。';
    });
    loadJevRuntimeStatus().then((st: JevRuntimeStatus): void => {
      if (!this.alive || revision !== this.loadRevision) return;
      this.status = st;
    }).catch((): void => {});
  }

  private persist(): void {
    const snapshot: JevSettings = this.settings;
    this.persistTail = this.persistTail.then(async (): Promise<void> => {
      try {
        await saveJevSettings(getChatKvStore(), snapshot);
        if (this.alive) this.saveError = '';
      } catch (_error) {
        if (this.alive) this.saveError = '保存失败，修改尚未完整写入。草稿已保留，可重试。';
        return;
      }
      loadJevRuntimeStatus().then((status: JevRuntimeStatus): void => {
        if (this.alive) this.status = status;
      }).catch((): void => {});
    });
  }

  private modeIndex(): number {
    const idx: number = MODE_VALUES.indexOf(this.settings.mode);
    return idx >= 0 ? idx : 0;
  }

  private apiModeIndex(): number {
    const idx: number = API_MODE_VALUES.indexOf(this.settings.apiMode);
    return idx >= 0 ? idx : 0;
  }

  private setMode(idx: number): void {
    this.settings = makeJevSettings({
      mode: MODE_VALUES[idx] === 'shadow' ? 'shadow' : (MODE_VALUES[idx] === 'active' ? 'active' : 'off'),
      apiMode: this.settings.apiMode,
      apiKey: this.settings.apiKey,
      baseUrl: this.settings.baseUrl,
      model: this.settings.model,
      approvalTriage: this.settings.approvalTriage,
      webAutomation: this.settings.webAutomation,
      toolContextSelection: this.settings.toolContextSelection,
      contextRetention: this.settings.contextRetention,
      autoApproval: this.settings.autoApproval,
      completionCheck: this.settings.completionCheck,
    });
    this.persist();
  }

  private setApiMode(idx: number): void {
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.settings = makeJevSettings({
        mode: this.settings.mode,
        apiMode: API_MODE_VALUES[idx] === 'vercel' ? 'vercel' : 'typesafe',
        apiKey: this.settings.apiKey,
        baseUrl: this.settings.baseUrl,
        model: this.settings.model,
        approvalTriage: this.settings.approvalTriage,
        webAutomation: this.settings.webAutomation,
        toolContextSelection: this.settings.toolContextSelection,
        contextRetention: this.settings.contextRetention,
        autoApproval: this.settings.autoApproval,
        completionCheck: this.settings.completionCheck,
      });
    });
    this.persist();
  }

  private patchText(patch: Partial<JevSettings>): void {
    this.settings = makeJevSettings({
      mode: this.settings.mode,
      apiMode: patch.apiMode ?? this.settings.apiMode,
      apiKey: patch.apiKey ?? this.settings.apiKey,
      baseUrl: patch.baseUrl ?? this.settings.baseUrl,
      model: patch.model ?? this.settings.model,
      approvalTriage: this.settings.approvalTriage,
      webAutomation: this.settings.webAutomation,
      toolContextSelection: this.settings.toolContextSelection,
      contextRetention: this.settings.contextRetention,
      autoApproval: this.settings.autoApproval,
      completionCheck: this.settings.completionCheck,
    });
    this.persist();
  }

  private purposeSettings(purpose: JevNewPurpose): JevNewPurposeSettings {
    return jevPurposeSettings(this.settings, purpose) ?? this.settings.approvalTriage;
  }
  private patchPurpose(purpose: JevNewPurpose, patch: Partial<JevNewPurposeSettings>): void {
    const current: JevNewPurposeSettings = this.purposeSettings(purpose);
    const next: JevNewPurposeSettings = {
      mode: patch.mode ?? current.mode,
      allowToolMetadata: patch.allowToolMetadata ?? current.allowToolMetadata,
      allowTaskText: patch.allowTaskText ?? current.allowTaskText,
      allowPageContent: patch.allowPageContent ?? current.allowPageContent,
      allowToolOutput: patch.allowToolOutput ?? current.allowToolOutput,
    };
    this.settings = makeJevSettings({ mode: this.settings.mode, apiMode: this.settings.apiMode,
      apiKey: this.settings.apiKey, baseUrl: this.settings.baseUrl, model: this.settings.model,
      approvalTriage: purpose === 'approval_triage' ? next : this.settings.approvalTriage,
      webAutomation: purpose === 'web_automation' ? next : this.settings.webAutomation,
      toolContextSelection: purpose === 'tool_context_selection' ? next : this.settings.toolContextSelection,
      contextRetention: purpose === 'context_retention' ? next : this.settings.contextRetention,
      autoApproval: purpose === 'auto_approval' ? next : this.settings.autoApproval,
      completionCheck: purpose === 'completion_check' ? next : this.settings.completionCheck });
    this.persist();
  }
  private purposeModeIndex(purpose: JevNewPurpose): number {
    return MODE_VALUES.indexOf(this.purposeSettings(purpose).mode);
  }
  private modeValue(index: number): JevMode {
    return MODE_VALUES[index] === 'active' ? 'active' : (MODE_VALUES[index] === 'shadow' ? 'shadow' : 'off');
  }
  private effectivePurpose(purpose: JevNewPurpose): string {
    const effective: JevMode = resolveJevPurposeMode(this.settings, purpose);
    const label: string = effective === 'active' ? '主动' : (effective === 'shadow' ? '影子' : '关');
    return `当前生效：${label}${effective !== this.purposeSettings(purpose).mode ? '（受全局模式限制）' : ''}`;
  }

  @Builder
  private AllowanceRow(label: string, description: string, checked: boolean, onToggle: () => void): void {
    Row({ space: 12 }) {
      Column({ space: 3 }) {
        Text(label).fontSize(BODY).fontColor(this.colors.ink).width('100%')
        Text(description).fontSize(LABEL).fontColor(this.colors.muted).width('100%')
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text(checked ? '已允许' : '未允许').fontSize(LABEL)
        .fontColor(checked ? this.accentColorStore : this.colors.muted)
        .constraintSize({ minWidth: 56, minHeight: 44 }).textAlign(TextAlign.Center)
        .onClick(onToggle)
    }.width('100%').constraintSize({ minHeight: 52 }).padding({ left: 14, right: 14, top: 8, bottom: 8 })
  }

  @Builder
  private PurposeCard(purpose: JevNewPurpose): void {
    Column() {
      Column({ space: 8 }) {
        Row({ space: 4 }) {
          ForEach(MODE_OPTIONS, (label: string, index: number): void => {
            Text(label).fontSize(META).textAlign(TextAlign.Center).layoutWeight(1)
              .constraintSize({ minHeight: 44 }).fontColor(this.colors.ink)
              .backgroundColor(this.purposeModeIndex(purpose) === index ? this.colors.inset : Color.Transparent)
              .borderRadius(8).onClick((): void => { this.patchPurpose(purpose, { mode: this.modeValue(index) }); })
          }, (label: string): string => label)
        }.width('100%')
        Text(this.effectivePurpose(purpose)).fontSize(LABEL).fontColor(this.colors.muted).width('100%')
      }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
      this.AllowanceRow('允许工具元数据', purpose === 'auto_approval'
        ? '发送工具名称、有限参数或命令以及权限信息，用于复核自动批准。'
        : purpose === 'completion_check' ? '发送有限修改文件路径与检查状态，不发送工具输出正文。'
        : purpose === 'context_retention' || purpose === 'tool_context_selection'
          ? '发送有限工具名称与参数，帮助判断输出内容。'
          : '发送有限工具摘要；不发送原始脚本、凭据或完整参数。',
        this.purposeSettings(purpose).allowToolMetadata,
        (): void => { this.patchPurpose(purpose, { allowToolMetadata: !this.purposeSettings(purpose).allowToolMetadata }); })
      this.AllowanceRow(purpose === 'approval_triage' || purpose === 'web_automation' ? '允许任务意图摘要' : '允许任务文本',
        purpose === 'completion_check' ? '发送有限用户任务文本和助手最终回复，用于判断是否宣称完成或验证。'
          : purpose === 'approval_triage' || purpose === 'web_automation' ? '只发送有限意图分类，不包含任务原句。'
          : '发送有限近期用户任务文本，可能包含原句。',
        this.purposeSettings(purpose).allowTaskText,
        (): void => { this.patchPurpose(purpose, { allowTaskText: !this.purposeSettings(purpose).allowTaskText }); })
      if (purpose === 'tool_context_selection' || purpose === 'context_retention') {
        this.AllowanceRow('允许工具输出摘录', '发送有限工具输出内容摘录，用于判断哪些内容与任务相关。',
          this.purposeSettings(purpose).allowToolOutput,
          (): void => { this.patchPurpose(purpose, { allowToolOutput: !this.purposeSettings(purpose).allowToolOutput }); })
      }
      if (purpose === 'web_automation') {
        this.AllowanceRow('允许页面内容', '发送有限页面摘要；仅在真实浏览器目标循环接通后使用。',
          this.purposeSettings(purpose).allowPageContent,
          (): void => { this.patchPurpose(purpose, { allowPageContent: !this.purposeSettings(purpose).allowPageContent }); })
      }
    }.width('100%')
  }

  // 连接测试:一道 noul 是非题(真实往返;不伪造成功)
  private testConnection(): void {
    if (this.testing) return;
    this.testing = true;
    this.testMsg = '正在测试…';
    const questions: Record<string, JevQuestion> = {
      'ping': {
        kind: 'noul',
        instructions: '回答：2 加 2 等于 4 吗？',
        trueCriteria: '数学上正确',
        falseCriteria: '数学上错误',
      },
    };
    const endpoint: string = this.settings.apiMode === 'vercel'
      ? this.settings.baseUrl : JEV_TYPESAFE_ENDPOINT;
    try {
      const spec = buildJevRequest(
        this.settings.apiMode, endpoint, this.settings.apiKey, this.settings.model,
        { ping: 'pong' }, questions);
      getAppContainer().httpClient.fetch({
        url: spec.url, method: 'POST', headers: spec.headers, body: spec.body,
      }, { signal: undefined }).then((resp): void => {
        this.testing = false;
        if (resp.status < 200 || resp.status >= 300) {
          this.testMsg = `失败:HTTP ${resp.status}`;
          return;
        }
        try {
          const evaluation: JevEvaluation = decodeJevResponse(resp.body, questions, this.settings.apiMode);
          const answer = evaluation.answers['ping'];
          if (answer !== undefined && answer.kind === 'noul') {
            this.testMsg = `连接正常(p=${answer.probability.toFixed(2)})`;
          } else {
            this.testMsg = '连接正常(应答格式已校验)';
          }
        } catch (e) {
          this.testMsg = `应答校验失败:${e instanceof Error ? e.message : String(e)}`;
        }
      }).catch((e: Error): void => {
        this.testing = false;
        this.testMsg = `失败:${e.message}`;
      });
    } catch (e) {
      this.testing = false;
      this.testMsg = `失败:${(e as Error).message}`;
    }
  }

  @Builder
  ModeCard(): void {
    Column() {
      Row() {
        Text('模式')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
      }
      .width('100%')
      Seg({
        options: MODE_OPTIONS,
        selected: this.modeIndex(),
        onSelect: (idx: number): void => { this.setMode(idx); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  ApiCard(): void {
    Column() {
      Row() {
        Text('调用方式')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
      }
      .width('100%')
      Seg({
        options: API_MODE_OPTIONS,
        selected: this.apiModeIndex(),
        onSelect: (idx: number): void => { this.setApiMode(idx); },
      })
        .margin({ top: 12 })
      TextInput({ text: this.settings.model, placeholder: '模型(如 systemone-default)' })
        .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10)
        .padding({ left: 14, right: 14 })
        .margin({ top: 12 })
        .onChange((v: string): void => { this.patchText({ model: v.trim() }); })
      TextInput({ text: this.settings.apiKey, placeholder: 'API Key' })
        .type(InputType.Password)
        .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10)
        .padding({ left: 14, right: 14 })
        .margin({ top: 12 })
        .onChange((v: string): void => { this.patchText({ apiKey: v.trim() }); })
      if (this.settings.apiMode === 'vercel') {
        TextInput({ text: this.settings.baseUrl, placeholder: '网关 Base URL' })
          .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 14, right: 14 })
          .margin({ top: 12 })
          .onChange((v: string): void => { this.patchText({ baseUrl: v.trim() }); })
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      Row() {
        Text(this.testMsg)
          .fontSize(META)
          .fontColor(this.testMsg.startsWith('失败') || this.testMsg.indexOf('校验失败') >= 0
            ? ERROR : INK3)
          .layoutWeight(1)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.testing ? '测试中…' : '连接测试')
          .fontSize(META).fontColor(this.testing ? INK3 : this.accentColorStore)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.testConnection(); })
      }
      .width('100%').margin({ top: 10 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  StatusCard(): void {
    Column() {
      Row() {
        Text('运行状态')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
          .layoutWeight(1)
        Text(`${this.status.todayCount}/${this.status.budget}`)
          .fontSize(META).fontFamily(FONT_MONO).fontColor(INK3)
      }
      .width('100%')
      if (this.status.cooldownUntil > Date.now()) {
        Text(`错误冷却中,约 ${Math.ceil((this.status.cooldownUntil - Date.now()) / 60000)} 分钟后恢复`)
          .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 8 })
      }
      if (this.status.lastError.length > 0) {
        Text(`最近错误:${this.status.lastError}`)
          .fontSize(LABEL).fontColor(INK3).width('100%').margin({ top: 8 })
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: 'Jev 快速判断' })
      if (this.loadError.length > 0 || this.saveError.length > 0) {
        Text(this.loadError.length > 0 ? this.loadError : this.saveError).fontSize(LABEL)
          .fontColor(this.colors.error).width('100%').padding({ left: GUTTER, right: GUTTER, top: 8 })
        Text('重试').fontSize(META).fontColor(this.accentColorStore).constraintSize({ minHeight: 44 })
          .onClick((): void => { if (this.loaded) this.persist(); else this.refresh(); })
      }
      if (this.loaded) {
        Scroll() {
          Column() {
            PlainSection({ label: '模式' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModeCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '全局关闭会关闭全部用途；全局影子限制新增用途最多为影子。影子只记录评估，不改变行为。' })

            PlainSection({ label: '审批分诊' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('approval_triage'); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '主动模式只补充“只读、可逆、与任务相关”三个中性事实，仍由你批准、拒绝或回答；未知事实不会变成批准建议。' })

            PlainSection({ label: '工具结果筛选' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('tool_context_selection'); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: '工具结果保留' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('context_retention'); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: '自动批准复核' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('auto_approval'); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: '完成校验' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('completion_check'); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: 'Web 自动化' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.PurposeCard('web_automation'); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '当前只提供有限候选选择接缝，浏览器目标循环尚未接入；不会执行网页动作。接通后仍遵守站点权限与原审批。' })

            PlainSection({ label: '服务' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ApiCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '状态' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.StatusCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '预算：每日 200 次评估；连续错误进入 5 分钟冷却。已接用途：工具发现、记忆召回、会话标题选择、模型路由、审批分诊、工具结果筛选与保留、自动批准复核、完成校验；Web 自动化消费者待下一阶段接入。' })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
