// SettingSandboxPage — 沙盒执行设置(接真实运行时偏好)
// 工作区根 = filesDir(真值)；终端运行时 = builtin(鸿蒙无 shell 运行时)。
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import {
  loadAgentRuntimeSnapshot, updateSpeculativeToolExecution, defaultAgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, INK, INK3, INK4, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import {
  PageTitleBar, SectionHeader, NavRow, ToggleRow,
  PageSpacer,
} from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingSandboxPage {
  @State snap: AgentRuntimeSnapshot | null = null;
  @State workspaceRoot: string = '';

  aboutToAppear(): void {
    const ctx = AppStorage.get<Context>('abilityContext') as Context;
    this.workspaceRoot = ctx.filesDir;
    loadAgentRuntimeSnapshot(getChatKvStore()).then((s: AgentRuntimeSnapshot): void => {
      this.snap = s;
    }).catch((): void => { this.snap = defaultAgentRuntimeSnapshot(); });
  }

  @Builder
  workspaceBlock(): void {
    Column() {
      Row() {
        Column() {
          Text('Workspace')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK)
          Text('AmberAgent 将应用沙箱目录作为文件工具和命令运行的 /workspace。')
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 4 })
      Text(this.workspaceRoot)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(TEXT_STYLES.meta.weight)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .padding({ left: 16, right: 16, top: 4, bottom: 14 })
    }
    .width('100%')
    .backgroundColor(Color.Transparent)
  }

  private adjustConcurrent(delta: number): void {
    const current: number = this.snap !== null ? this.snap.speculativeToolExecution.maxConcurrentTools : 4;
    const next: number = Math.min(Math.max(current + delta, 1), 8);
    updateSpeculativeToolExecution(getChatKvStore(), { maxConcurrentTools: next })
      .then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
  }

  @Builder
  concurrencyBlock(snapshot: AgentRuntimeSnapshot): void {
    Column() {
      ToggleRow({
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
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK)
          Text('限制同时执行的工具数量。')
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Row() {
          Text('−')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK3)
            .padding({ left: 8, right: 8 })
            .onClick((): void => { this.adjustConcurrent(-1); })
          Text(`${snapshot.speculativeToolExecution.maxConcurrentTools}`)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .width(28)
            .textAlign(TextAlign.Center)
          Text('+')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK3)
            .padding({ left: 8, right: 8 })
            .onClick((): void => { this.adjustConcurrent(1); })
        }
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    }
    .width('100%')
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '沙盒设置' })
      Scroll() {
        Column() {
          SectionHeader({ label: '工作区' })
          this.workspaceBlock()
          SectionHeader({ label: '终端运行时' })
          NavRow({
            label: '终端运行时',
            desc: '鸿蒙当前不提供 shell 运行时。',
            valueText: 'builtin',
            isLast: true,
            showChevron: false,
          })
          SectionHeader({ label: '并发工具' })
          if (this.snap !== null) {
            this.concurrencyBlock(this.snap)
          }
          Text('// 输出上限随工具类型内置，无独立设置面')
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO)
            .fontColor(INK4)
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 10 })
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
