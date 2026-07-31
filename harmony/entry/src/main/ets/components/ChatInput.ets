// ChatInput — 聊天输入框组件(第二梯队 E + 斜杠命令接入)
//
// Android 基准: ai/ChatInput.kt(868 行)— 三段式 surface-2 药丸
//   左:attach chip(+ → × 展开,图片/相机/文件)
//   中:TextInputRow(多行文本输入)
//   右:send/stop 圆形键(accent 填充/stop 图标/长按 send-without-answer)
//
// 功能:
//   ✅ 多行 TextArea + 发送门(text 非空或 hasImages)
//   ✅ 图片附件按钮(PhotoViewPicker 已有)
//   ✅ 发送/停止切换(sending 态 → 停止按钮)
//   ✅ 长按发送(sendWithoutAnswer)— 发送键和停止键均支持
//   ✅ 编辑取消(取消编辑按钮)+ 编辑态 placeholder
//   ✅ 斜杠命令面板(输入 / 触发,点击执行)
//   ❌ 相机/文件/音频/视频 picker(后续迭代,依赖平台能力)
//   ❌ +→× 形变动画(后续迭代)
//   ❌ 用量面板(后续迭代)

import { INK, INK3, INK4, ACCENT, SURFACE, SURFACE2, BODY, META, LABEL } from '../design/tokens.ets';
import { SlashCommandPanel, SlashCommand, buildSlashCommands } from './SlashCommandPanel.ets';

@Component
export struct ChatInput {
  @Prop text: string = '';
  @Prop sending: boolean = false;
  @Prop editing: boolean = false;
  @Prop hasImages: boolean = false;
  @Prop subAgentEnabled: boolean = false;
  @Prop subAgentMode: string = 'roster';
  onTextChange: (value: string) => void = (_v: string): void => {};
  onSend: () => void = (): void => {};
  onStop: () => void = (): void => {};
  onSendWithoutAnswer: () => void = (): void => {};
  onPickImage: () => void = (): void => {};
  onCancelEdit: () => void = (): void => {};
  onClearInput: () => void = (): void => {};
  onCompactContext: () => void = (): void => {};
  onInsertText: (text: string) => void = (_t: string): void => {};

  // 斜杠命令可见:text 以 / 开头且非编辑态
  private isSlashVisible(): boolean {
    return !this.editing && this.text.startsWith('/') && this.text.indexOf(' ') < 0;
  }

  private getSlashCommands(): SlashCommand[] {
    return buildSlashCommands(this.subAgentEnabled, this.subAgentMode);
  }

  private handleSlashCommand(cmd: SlashCommand): void {
    if (cmd.id === 'core.clear') {
      this.onClearInput();
    } else if (cmd.id === 'core.compact') {
      this.onCompactContext();
      this.onClearInput();
    } else if (cmd.id === 'core.deepread') {
      this.onInsertText('/deepread ');
    } else if (cmd.id === 'core.subagent') {
      // 根据 mode 插入引导文本
      if (this.subAgentMode === 'smart_dynamic') {
        this.onInsertText('请根据这个任务的复杂度，判断是否需要用 custom_subagent 临时创建 SubAgent，不要使用 subagent_id；名称可省略由应用分配英文名；纯创作或无需外部读取时设 tool_profile=none，需要资料时再选择只读 profile，写清楚 prompt、边界和输出格式，等待返回后再综合结论：');
      } else {
        this.onInsertText('请根据这个任务的复杂度，主动拆分并灵活调用合适的 subagent 并行处理；等待它们返回后，再综合成一个可执行的结论：');
      }
    }
  }

  build(): void {
    Column() {
      // 斜杠命令面板(输入 / 时显示)
      SlashCommandPanel({
        commands: this.getSlashCommands(),
        visible: this.isSlashVisible(),
        onSelect: (cmd: SlashCommand): void => { this.handleSlashCommand(cmd); },
      })

      // 编辑取消条
      if (this.editing) {
        Row() {
          Text('取消编辑').fontSize(META).fontColor(INK3)
          Blank()
          Text('编辑中').fontSize(LABEL).fontColor(INK4)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 4, bottom: 4 })
        .onClick((): void => { this.onCancelEdit(); })
      }

      Row() {
        // 左:图片附件按钮(sending 态灰显视觉反馈)
        Text('📎').fontSize(18)
          .width(36).height(36)
          .textAlign(TextAlign.Center)
          .opacity(this.sending ? 0.4 : 1)
          .onClick((): void => { if (!this.sending) this.onPickImage(); })

        // 中:多行文本输入(编辑态切换 placeholder)
        TextArea({ text: this.text,
          placeholder: this.editing ? '编辑消息,发送将重发…' : '输入消息…' })
          .layoutWeight(1)
          .fontSize(BODY).fontColor(INK)
          .backgroundColor(SURFACE)
          .borderRadius(20)
          .padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .margin({ left: 6, right: 6 })
          .maxLines(4)
          .onChange((v: string): void => { this.onTextChange(v); })

        // 右:发送/停止
        if (this.sending) {
          Text('⏹').fontSize(16)
            .width(36).height(36)
            .textAlign(TextAlign.Center)
            .fontColor(SURFACE)
            .backgroundColor(INK)
            .borderRadius(18)
            .onClick((): void => { this.onStop(); })
            .gesture(
              LongPressGesture()
                .onAction((): void => { this.onSendWithoutAnswer(); })
            )
        } else {
          Text('↑').fontSize(18).fontWeight(FontWeight.Bold)
            .width(36).height(36)
            .textAlign(TextAlign.Center)
            .fontColor(this.canSend() ? SURFACE : INK4)
            .backgroundColor(this.canSend() ? ACCENT : SURFACE2)
            .borderRadius(18)
            .onClick((): void => {
              if (this.canSend()) this.onSend();
            })
            .gesture(
              LongPressGesture()
                .onAction((): void => { this.onSendWithoutAnswer(); })
            )
        }
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
      .backgroundColor(SURFACE2)
      .borderRadius(24)
    }
    .width('100%')
  }

  private canSend(): boolean {
    return this.text.trim().length > 0 || this.hasImages;
  }
}
