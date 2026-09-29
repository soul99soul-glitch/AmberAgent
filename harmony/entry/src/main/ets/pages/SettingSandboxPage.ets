// SettingSandboxPage — 沙盒执行设置(接真实运行时偏好)
// 工作区根 = filesDir 镜像或用户自选目录；本机 CPython，远端 SSH/Mosh。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1986「运行环境」):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow/FooterNote),逻辑零改动。
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import {
  loadAgentRuntimeSnapshot, updateSpeculativeToolExecution, updateAgentRuntimeExecution,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentOperationPreviewMode } from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore, resetWorkspaceManager } from '../di/AppContainer.ets';
import {
  workspaceRootAbs, setWorkspaceCustomDir, currentWorkspaceCustomDir,
  hydrateWorkspaceCustomDir, activateWorkspaceCustomDir,
} from '../platform_impl/WorkspaceStore.ets';
import { picker, fileShare } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { promptAction, router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import {
  BG, INK, INK3, LINE, TEXT_STYLES, GUTTER, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow,
  FooterNote, BtnGhost, PageSpacer, Seg,
} from '../components/PageTemplate.ets';

const TOOL_LOOP_STEP_OPTIONS: number[] = [64, 128, 256, 384, 512];
const RETRY_COUNT_OPTIONS: number[] = [1, 2, 3, 5];
const PREVIEW_MODE_OPTIONS: AgentOperationPreviewMode[] = ['always', 'auto', 'hidden'];
const PREVIEW_MODE_LABELS: string[] = ['常驻', '自动', '隐藏'];

const coerceIn = (v: number, lo: number, hi: number): number =>
  Math.min(Math.max(v, lo), hi);

@Entry
@Component
struct SettingSandboxPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State snap: AgentRuntimeSnapshot | null = null;
  // 原始类型代际计数:设备实测对象型 @State 的 Promise 回调赋值在已建页面上
  //   不触发重建(字符串可以),卡片读取本计数即可靠获得重建
  @State snapVersion: number = 0;
  @State loadError: boolean = false;
  @State workspaceRoot: string = '';
  @State workspaceCustom: boolean = false;
  @State workspaceNote: string = '';

  aboutToAppear(): void {
    hydrateWorkspaceCustomDir(getChatKvStore()).then((): Promise<void> => {
      this.workspaceRoot = workspaceRootAbs();
      this.workspaceCustom = currentWorkspaceCustomDir().length > 0;
      return activateWorkspaceCustomDir(getChatKvStore());
    }).catch((): void => {});
    this.workspaceRoot = workspaceRootAbs();
    this.workspaceCustom = currentWorkspaceCustomDir().length > 0;
    this.loadSnapshot();
  }

  // D2-1:自选工作区目录(FOLDER picker;授权持久化尽力而为,失败诚实注明)
  private async pickWorkspaceDir(): Promise<void> {
    try {
      const ctx: common.UIAbilityContext =
        AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.selectMode = picker.DocumentSelectMode.FOLDER;
      options.maxSelectNumber = 1;
      const uris: string[] = await new picker.DocumentViewPicker(ctx).select(options);
      if (uris.length === 0) return;
      const uri: string = uris[0];
      // 尽力持久化授权(失败 = 进程重启后需重新选择;不阻断本次使用)
      let persisted: boolean = true;
      try {
        const policy: fileShare.PolicyInfo = {
          uri: uri,
          operationMode: fileShare.OperationMode.READ_MODE | fileShare.OperationMode.WRITE_MODE,
        };
        await fileShare.persistPermission([policy]);
      } catch (_e) {
        persisted = false;
      }
      // URI 形如 file://docs/storage/Users/... — 剥协议+authority 得真实路径
      const dir: string = uri.replace(/^file:\/\/(docs)?/, '');
      await setWorkspaceCustomDir(getChatKvStore(), dir, uri);
      resetWorkspaceManager();
      this.workspaceRoot = workspaceRootAbs();
      this.workspaceCustom = true;
      this.workspaceNote = persisted ? '' : '当前系统未允许授权持久化,重启后需重新选择目录';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      promptAction.showToast({ message: `选择目录失败:${msg}`, duration: 2000 });
    }
  }

  private async resetWorkspaceDir(): Promise<void> {
    await setWorkspaceCustomDir(getChatKvStore(), '', '');
    resetWorkspaceManager();
    this.workspaceRoot = workspaceRootAbs();
    this.workspaceCustom = false;
    this.workspaceNote = '';
  }

  private applySnap(s: AgentRuntimeSnapshot): void {
    this.snap = s;
    this.snapVersion++;
  }

  private loadSnapshot(): void {
    this.loadError = false;
    loadAgentRuntimeSnapshot(getChatKvStore()).then((s: AgentRuntimeSnapshot): void => {
      this.applySnap(s);
    }).catch((): void => {
      this.snap = null;
      this.loadError = true;
    });
  }

  private adjustConcurrent(delta: number): void {
    const current: number = this.snap !== null ? this.snap.speculativeToolExecution.maxConcurrentTools : 4;
    const next: number = Math.min(Math.max(current + delta, 1), 8);
    updateSpeculativeToolExecution(getChatKvStore(), { maxConcurrentTools: next })
      .then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
  }

  // ── 执行参数/生成稳定性(原「运行与显示」页并入;运行环境 = 执行设置统一入口) ──

  private setPreviewMode(mode: AgentOperationPreviewMode): void {
    updateAgentRuntimeExecution(getChatKvStore(), { operationPreviewMode: mode })
      .then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
  }

  private setToolLoopSteps(steps: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { maxToolLoopSteps: steps })
      .then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
  }

  private setRetryEnabled(checked: boolean): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryEnabled: checked })
      .then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
  }

  private setRetryMaxRetries(maxRetries: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryMaxRetries: maxRetries })
      .then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
  }

  private numericLabels(options: number[], suffix: string): string[] {
    return options.map((opt: number): string => `${opt} ${suffix}`);
  }

  private optionIndex(options: number[], selected: number): number {
    const idx: number = options.indexOf(selected);
    return idx >= 0 ? idx : 0;
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  @Builder
  WorkspaceCard(): void {
    Column() {
      CardRow({
        label: 'Workspace',
        desc: this.workspaceCustom
          ? 'AmberAgent 将你选择的目录作为文件工具的 /workspace。'
          : 'AmberAgent 将应用沙箱目录作为文件工具的 /workspace。',
        iconName: 'folder', plateSize: 28,
        showChevron: false, isLast: true,
      })
      Text(this.workspaceRoot)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(TEXT_STYLES.meta.weight)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        // 与行标题对齐:行 padding 14 + 图标座 28 + 间距 12
        .padding({ left: 54, right: 14, bottom: 4 })
      if (this.workspaceNote.length > 0) {
        Text(this.workspaceNote)
          .fontSize(TEXT_STYLES.meta.size)
          .fontColor(INK3)
          .width('100%')
          .padding({ left: 54, right: 14, bottom: 4 })
      }
      Row() {
        BtnGhost({ label: this.workspaceCustom ? '更改目录' : '选择目录',
          onTap: (): void => { this.pickWorkspaceDir().catch((): void => {}); } })
        if (this.workspaceCustom) {
          BtnGhost({ label: '恢复默认',
            onTap: (): void => { this.resetWorkspaceDir().catch((): void => {}); } })
            .margin({ left: 8 })
        }
      }
      .width('100%')
      .padding({ left: 54, right: 14, bottom: 12, top: 4 })
    }
    .width('100%')
  }

  @Builder
  TerminalCard(): void {
    CardRow({
      label: '远端主机配置',
      desc: 'SSH 与 Mosh 共用可信主机、指纹和登录凭据。',
      valueText: 'SSH / Mosh',
      iconName: 'cpu', plateSize: 28,
      isLast: false,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingSSHProfilesPage' }); },
    })
    CardRow({
      label: '打开终端', desc: '默认使用 SSH；可切换 Mosh 交互会话，服务端须支持 Mosh 和 UDP。',
      iconName: 'cpu', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/TerminalPage' }); },
    })
  }

  @Builder
  PythonCard(): void {
    CardRow({
      label: '本机 Python', desc: '内嵌 CPython 数据处理，stdin / 输出可串联 Workspace 文件工具。',
      valueText: 'CPython', iconName: 'cpu', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/PythonPage' }); },
    })
  }

  @Builder
  RecipesCard(): void {
    CardRow({
      label: 'Recipes', desc: '安装声明式步骤，用现有工具逐步执行和审批。',
      iconName: 'cpu', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/RecipesPage' }); },
    })
  }

  @Builder
  PluginsCard(): void {
    CardRow({
      label: '插件中心', desc: '预览、试跑和安装插件，按实际操作审批权限。',
      iconName: 'cpu', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/PluginsPage' }); },
    })
  }

  // 卡组内发丝分隔线(内缩至行文字起点,同 CardRow 分隔规则)
  @Builder
  CardHairline(): void {
    Row() {
      Row()
        .width('100%')
        .height(1)
        .backgroundColor(LINE)
    }
    .width('100%')
    .padding({ left: 14, right: 14 })
  }

  // 卡内选择块:标题/描述 + Seg(与「并发工具」组同卡规格)
  @Builder
  SegRow(title: string, desc: string, labels: string[], selected: number,
    onSelect: (idx: number) => void): void {
    Column() {
      Text(title)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK)
        .width('100%')
      Text(desc)
        .fontSize(12)
        .lineHeight(lineHeightForSize(12))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 2 })
        .width('100%')
      Seg({
        options: labels,
        selected: selected,
        onSelect: (idx: number): void => { onSelect(idx); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  // 「执行参数」组:操作预览 + 工具循环上限
  //   状态必须在本 builder 内直接读取(this.snap),参数透传形参的写法在
  //   CardGroup content 嵌套下不会随快照更新重渲染(真机复现:写入成功但 UI 不变)
  @Builder
  ExecutionCard(): void {
    if (this.snapVersion >= 0 && this.snap !== null) {
      this.SegRow('操作预览', '控制输入框上方的 Agent 实时操作预览显示方式。',
        PREVIEW_MODE_LABELS, PREVIEW_MODE_OPTIONS.indexOf(this.snap.operationPreviewMode),
        (idx: number): void => { this.setPreviewMode(PREVIEW_MODE_OPTIONS[idx]); })
      this.CardHairline()
      this.SegRow('工具循环上限', '单次回复最多调用多少轮工具。',
        this.numericLabels(TOOL_LOOP_STEP_OPTIONS, '步'),
        this.optionIndex(TOOL_LOOP_STEP_OPTIONS, coerceIn(this.snap.maxToolLoopSteps, 16, 512)),
        (idx: number): void => { this.setToolLoopSteps(TOOL_LOOP_STEP_OPTIONS[idx]); })
    }
  }

  // 「生成稳定性」组:自动重试 + 重试上限
  @Builder
  StabilityCard(): void {
    if (this.snapVersion >= 0 && this.snap !== null) {
      CardToggleRow({
        label: '自动重试生成', desc: '网络或服务临时中断时自动重试。',
        checked: this.snap.generationRetry.enabled,
        onToggle: (): void => { this.setRetryEnabled(!this.snap!.generationRetry.enabled); },
      })
      this.SegRow('重试上限', '单次模型调用最多重试次数。',
        this.numericLabels(RETRY_COUNT_OPTIONS, '次'),
        this.optionIndex(RETRY_COUNT_OPTIONS, coerceIn(this.snap.generationRetry.maxRetries, 1, 5)),
        (idx: number): void => { this.setRetryMaxRetries(RETRY_COUNT_OPTIONS[idx]); })
    }
  }

  @Builder
  ErrorCard(): void {
    Column() {
      Text('运行时配置加载失败，未应用默认值以避免覆盖真实配置')
        .fontSize(12)
        .lineHeight(lineHeightForSize(12))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .width('100%')
        .padding({ left: 14, right: 14, top: 12 })
      Row() {
        BtnGhost({ label: '重试', onTap: (): void => { this.loadSnapshot(); } })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  ConcurrencyCard(): void {
    if (this.snapVersion >= 0 && this.snap !== null) {
      Column() {
        CardToggleRow({
          label: '推测式并发工具执行',
          desc: '仅对完整闭合且无需审批的只读工具生效，默认关闭。',
          checked: this.snap.speculativeToolExecution.enabled,
          onToggle: (): void => {
            updateSpeculativeToolExecution(getChatKvStore(), {
              enabled: !this.snap!.speculativeToolExecution.enabled,
            }).then((s: AgentRuntimeSnapshot): void => { this.applySnap(s); });
          },
        })
      Row() {
        Column() {
          Text('最大并发工具数')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
          Text('限制同时执行的工具数量。')
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Row() {
          Row() {
            Text('−')
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustConcurrent(-1); })
          Text(`${this.snap.speculativeToolExecution.maxConcurrentTools}`)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .width(28)
            .textAlign(TextAlign.Center)
          Row() {
            Text('+')
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(TEXT_STYLES.body.weight)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(TEXT_STYLES.body.family)
              .fontColor(INK3)
          }
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.adjustConcurrent(1); })
        }
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 12 })
    }
      .width('100%')
    }
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '运行环境' })
      Scroll() {
        Column() {
          PlainSection({ label: '工作区' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.WorkspaceCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '终端运行时' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.TerminalCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '本机执行' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.PythonCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '工具编排' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.RecipesCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.PluginsCard(); } })
            .margin({ left: GUTTER, right: GUTTER, top: 8 })

          if (!this.loadError && this.snap !== null) {
            PlainSection({ label: '执行参数' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ExecutionCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          }

          PlainSection({ label: '并发工具' })
          if (this.loadError) {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ErrorCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          } else if (this.snap !== null) {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ConcurrencyCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          }

          if (!this.loadError && this.snap !== null) {
            PlainSection({ label: '生成稳定性' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.StabilityCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          }
          FooterNote({ text: '输出上限随工具类型内置，无独立设置面' })
          PageSpacer()
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
