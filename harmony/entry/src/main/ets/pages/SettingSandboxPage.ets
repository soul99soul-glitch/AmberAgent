// SettingSandboxPage — 沙盒执行设置(接真实运行时偏好)
// 工作区根 = filesDir(真值)；终端运行时 = builtin(鸿蒙无 shell 运行时)。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1986「运行环境」):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow/FooterNote),逻辑零改动。
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import {
  loadAgentRuntimeSnapshot, updateSpeculativeToolExecution,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, INK, INK3, TEXT_STYLES, GUTTER, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow,
  FooterNote, BtnGhost, PageSpacer,
} from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingSandboxPage {
  @State snap: AgentRuntimeSnapshot | null = null;
  @State loadError: boolean = false;
  @State workspaceRoot: string = '';

  aboutToAppear(): void {
    const ctx = AppStorage.get<Context>('abilityContext') as Context;
    this.workspaceRoot = ctx.filesDir;
    this.loadSnapshot();
  }

  private loadSnapshot(): void {
    this.loadError = false;
    loadAgentRuntimeSnapshot(getChatKvStore()).then((s: AgentRuntimeSnapshot): void => {
      this.snap = s;
    }).catch((): void => {
      this.snap = null;
      this.loadError = true;
    });
  }

  private adjustConcurrent(delta: number): void {
    const current: number = this.snap !== null ? this.snap.speculativeToolExecution.maxConcurrentTools : 4;
    const next: number = Math.min(Math.max(current + delta, 1), 8);
    updateSpeculativeToolExecution(getChatKvStore(), { maxConcurrentTools: next })
      .then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  @Builder
  WorkspaceCard(): void {
    Column() {
      CardRow({
        label: 'Workspace',
        desc: 'AmberAgent 将应用沙箱目录作为文件工具和命令运行的 /workspace。',
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
        .padding({ left: 54, right: 14, bottom: 12 })
    }
    .width('100%')
  }

  @Builder
  TerminalCard(): void {
    CardRow({
      label: '终端运行时',
      desc: '鸿蒙当前不提供 shell 运行时。',
      valueText: 'builtin',
      iconName: 'cpu', plateSize: 28,
      showChevron: false, isLast: true,
    })
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
  ConcurrencyCard(snapshot: AgentRuntimeSnapshot): void {
    Column() {
      CardToggleRow({
        label: '推测式并发工具执行',
        desc: '仅对完整闭合且无需审批的只读工具生效，默认关闭。',
        checked: snapshot.speculativeToolExecution.enabled,
        onToggle: (): void => {
          updateSpeculativeToolExecution(getChatKvStore(), {
            enabled: !snapshot.speculativeToolExecution.enabled,
          }).then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
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
          Text(`${snapshot.speculativeToolExecution.maxConcurrentTools}`)
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

  build(): void {
    Column() {
      CenteredTitleBar({ title: '运行环境' })
      Scroll() {
        Column() {
          PlainSection({ label: '工作区' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.WorkspaceCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '终端运行时' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.TerminalCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '并发工具' })
          if (this.loadError) {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ErrorCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          } else if (this.snap !== null) {
            CardGroup({ radius: 18, stroke: true, content: (): void => {
              const s = this.snap;
              if (s !== null) { this.ConcurrencyCard(s); }
            } })
              .margin({ left: GUTTER, right: GUTTER })
          }
          FooterNote({ text: '输出上限随工具类型内置，无独立设置面' })
          PageSpacer()
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
