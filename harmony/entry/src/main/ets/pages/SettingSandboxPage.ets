// SettingSandboxPage — 沙盒执行设置(接真实运行时偏好)
//
// Android 基准: SettingSandboxPage.kt — 工作区沙盒/终端运行时/并发/输出上限。
//   鸿蒙真实面:工作区根 = filesDir(真值);终端运行时 = builtin(鸿蒙无 shell
//   运行时,诚实只读);并发工具门 = speculativeToolExecution(enabled +
//   maxConcurrentTools)经 updateSpeculativeToolExecution 持久化,tool_loop 消费。

import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import {
  loadAgentRuntimeSnapshot, updateSpeculativeToolExecution, defaultAgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE, ACCENT,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';

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

  build(): void {
    Column() {
      PageTitleBar({ title: '沙盒设置' })

      // 工作区(真值)
      Column() {
        Text('工作区根目录').fontSize(BODY2).fontColor(INK).width('100%')
        Text(this.workspaceRoot).fontSize(MONO_SMALL).fontColor(INK3)
          .fontFamily('JetBrainsMono').margin({ top: 4 }).width('100%')
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .padding(14)
      .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
      .margin({ left: 18, right: 18, top: 12 })
      .alignItems(HorizontalAlign.Start)

      // 终端运行时(鸿蒙无 shell 运行时,诚实只读)
      Row() {
        Text('终端运行时').fontSize(BODY2).fontColor(INK).layoutWeight(1)
        Text('builtin').fontSize(MONO_SMALL).fontColor(INK3).fontFamily('JetBrainsMono')
      }
      .padding(14)
      .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
      .margin({ left: 18, right: 18, top: 10 })

      // 并发工具门(真实持久化,tool_loop 消费)
      if (this.snap !== null) {
        Column() {
          Row() {
            Text('推测式并发工具执行').fontSize(BODY2).fontColor(INK).layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.snap.speculativeToolExecution.enabled })
              .selectedColor(ACCENT)
              .onChange((on: boolean): void => {
                updateSpeculativeToolExecution(getChatKvStore(), { enabled: on })
                  .then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
              })
          }
          .width('100%')

          Row() {
            Text('最大并发工具数').fontSize(BODY2).fontColor(INK).layoutWeight(1)
            Text('−').fontSize(BODY).fontColor(INK3).padding(6)
              .onClick((): void => {
                const cur: number = this.snap !== null ? this.snap.speculativeToolExecution.maxConcurrentTools : 4;
                const next: number = cur > 1 ? cur - 1 : 1;
                updateSpeculativeToolExecution(getChatKvStore(), { maxConcurrentTools: next })
                  .then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
              })
            Text(`${this.snap.speculativeToolExecution.maxConcurrentTools}`)
              .fontSize(BODY).fontColor(INK).fontFamily('JetBrainsMono')
              .width(28).textAlign(TextAlign.Center)
            Text('+').fontSize(BODY).fontColor(INK3).padding(6)
              .onClick((): void => {
                const cur: number = this.snap !== null ? this.snap.speculativeToolExecution.maxConcurrentTools : 4;
                const next: number = cur < 8 ? cur + 1 : 8;
                updateSpeculativeToolExecution(getChatKvStore(), { maxConcurrentTools: next })
                  .then((s: AgentRuntimeSnapshot): void => { this.snap = s; });
              })
          }
          .width('100%').margin({ top: 10 })
        }
        .padding(14)
        .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
        .margin({ left: 18, right: 18, top: 10 })
        .alignItems(HorizontalAlign.Start)
      }

      Text('// 输出上限随工具类型内置,无独立设置面')
        .fontSize(META).fontColor(INK4).fontFamily('JetBrainsMono')
        .margin({ left: 18, right: 18, top: 14 })

      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
