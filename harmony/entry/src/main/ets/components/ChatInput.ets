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
//   ✅ 长按发送(sendWithoutAnswer)— 仅发送键支持,停止键保持单击停止
//   ✅ 编辑取消(取消编辑按钮)+ 编辑态 placeholder
//   ✅ 斜杠命令面板(输入 / 触发,点击执行)
//   ❌ 相机/文件/音频/视频 picker(后续迭代,依赖平台能力)
//   ❌ +→× 形变动画(后续迭代)
//   ❌ 用量面板(后续迭代)

import { ACCENT, ACCENT_INK, BODY, INK, INK2, INK3, INK4, LABEL, LINE, META, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { SlashCommandPanel, SlashCommand, buildSlashCommands } from './SlashCommandPanel.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatInput {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop text: string = '';
  @Prop sending: boolean = false;
  @Prop editing: boolean = false;
  @Prop hasImages: boolean = false;
  @Prop subAgentEnabled: boolean = false;
  @Prop subAgentMode: string = 'roster';
  // G2-S4:动态 skill 斜杠命令(由 ChatPage 从 SkillManager 装配)
  @Prop skillCommands: SlashCommand[] = [];
  onTextChange: (value: string) => void = (_v: string): void => {};
  onSend: () => void = (): void => {};
  onStop: () => void = (): void => {};
  onSendWithoutAnswer: () => void = (): void => {};
  onPickImage: () => void = (): void => {};
  onCancelEdit: () => void = (): void => {};
  onClearInput: () => void = (): void => {};
  onCompactContext: () => void = (): void => {};
  onInsertText: (text: string) => void = (_t: string): void => {};

  // 1:1 原型 .pressable:active scale(.975) .14s ease-out
  @State attachScale: number = 1;
  @State sendScale: number = 1;

  private pressTouch(which: boolean, e: TouchEvent): void {
    const down: boolean = e.type === TouchType.Down;
    const v: number = down ? 0.975 : 1;
    animateTo({ duration: 140, curve: Curve.EaseOut }, (): void => {
      if (which) this.attachScale = v; else this.sendScale = v;
    });
  }

  // 斜杠命令可见:text 以 / 开头且非编辑态
  private isSlashVisible(): boolean {
    // 支持半角 / 和全角 ／(中文输入法兼容,Android ChatInputComposers.kt:765-771)
    return !this.editing &&
      (this.text.startsWith('/') || this.text.startsWith('／')) &&
      this.text.indexOf(' ') < 0;
  }

  // 过滤匹配当前输入的命令(输入 /c → 匹配 clear/compact)
  private getSlashCommands(): SlashCommand[] {
    const all: SlashCommand[] = buildSlashCommands(this.subAgentEnabled, this.subAgentMode, this.skillCommands);
    if (!this.isSlashVisible()) return [];
    // 去掉前导 / 或 ／ 得到查询
    const query: string = this.text.substring(1).toLowerCase();
    if (query.length === 0) return all;
    return all.filter((cmd: SlashCommand): boolean =>
      cmd.title.toLowerCase().startsWith(query));
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
      if (this.subAgentMode === 'smart_dynamic') {
        this.onInsertText('请根据这个任务的复杂度，判断是否需要用 custom_subagent 临时创建 SubAgent，不要使用 subagent_id；名称可省略由应用分配英文名；纯创作或无需外部读取时设 tool_profile=none，需要资料时再选择只读 profile，写清楚 prompt、边界和输出格式，等待返回后再综合结论：');
      } else {
        this.onInsertText('请根据这个任务的复杂度，主动拆分并灵活调用合适的 subagent 并行处理；等待它们返回后，再综合成一个可执行的结论：');
      }
    } else if (cmd.id.startsWith('skill.')) {
      // G2-S4:/skill-* → 插入 use_skill 引导(ChatInputComposers.kt:895-899)
      const skillId: string = cmd.id.substring('skill.'.length);
      this.onInsertText(`请先调用 use_skill(skill_id="${skillId}") 执行技能，然后根据结果回答：`);
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
        .padding({ left: 20, right: 20, top: 4, bottom: 4 })
        .onClick((): void => { this.onCancelEdit(); })
      }

      // Composer 沉浸式(原型验收规格):无底盘底色、无顶部分隔线,
      //   + / 胶囊 / 发送键严格等高 48,容器 alignItems:center,胶囊固定 height 48。
      Row() {
        // Attach capsule:48x48, radius 999, bg SURFACE2, border 1px LINE, plus 22/INK2
        Row() {
          Icon({ name: 'plus', iconSize: 22, color: INK2 })
        }
        .width(48).height(48)
        .borderRadius(999)
        .backgroundColor(SURFACE2)
        .border({ width: 1, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .opacity(this.sending ? 0.4 : 1)
        .scale({ x: this.attachScale, y: this.attachScale })
        .onTouch((e: TouchEvent): void => { this.pressTouch(true, e); })
        .margin({ right: 9 })
        .onClick((): void => { if (!this.sending) this.onPickImage(); })
        .accessibilityText('添加图片')

        // Center pill:flex 1, radius 26, min-height 48, bg SURFACE2, border 1px LINE
        Row() {
          TextArea({ text: this.text,
            placeholder: this.editing ? '编辑消息,发送将重发…' : '输入消息…' })
            .layoutWeight(1)
            .fontSize(BODY).fontColor(INK)
            .placeholderColor(INK4)
            .placeholderFont({ size: BODY })
            .backgroundColor(Color.Transparent)
            .borderRadius(26)
            .padding({ top: 13, bottom: 14 })
            .maxLines(4)
            .constraintSize({ minHeight: 48, maxHeight: 111 })
            .lineHeight(21)
            .onChange((v: string): void => { this.onTextChange(v); })
        }
        .layoutWeight(1)
        .constraintSize({ minHeight: 48, maxHeight: 111 })
        .borderRadius(26)
        .backgroundColor(SURFACE2)
        .border({ width: 1, color: LINE })
        .padding({ left: 18, right: 18 })
        .alignItems(VerticalAlign.Center)
        .margin({ right: 9 })

        // Send/Stop button:48x48, radius 999(stop 用纯白方块 glyph,1:1 原型)
        if (this.sending) {
          Row() {
            Column().width(14).height(14).backgroundColor(SURFACE).borderRadius(3)
          }
          .width(48).height(48)
          .borderRadius(999)
          .backgroundColor(INK)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .scale({ x: this.sendScale, y: this.sendScale })
          .onTouch((e: TouchEvent): void => { this.pressTouch(false, e); })
          .onClick((): void => { this.onStop(); })
          .accessibilityText('停止生成')
        } else {
          Row() {
            Icon({ name: 'arrow_up', iconSize: 21, color: this.canSend() ? ACCENT_INK : INK2 })
          }
          .width(48).height(48)
          .borderRadius(999)
          .backgroundColor(this.canSend() ? this.accentColorStore : SURFACE2)
          .border({ width: 1, color: this.canSend() ? this.accentColorStore : LINE })
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .scale({ x: this.sendScale, y: this.sendScale })
          .onTouch((e: TouchEvent): void => { this.pressTouch(false, e); })
          .gesture(
            GestureGroup(
              GestureMode.Exclusive,
              LongPressGesture().onAction((): void => {
                if (this.canSend()) this.onSendWithoutAnswer();
              }),
              TapGesture().onAction((): void => {
                if (this.canSend()) this.onSend();
              }),
            )
          )
          .accessibilityText('发送消息，长按仅发送不等待回复')
        }
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 10, bottom: 14 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
  }

  private canSend(): boolean {
    return this.text.trim().length > 0 || this.hasImages;
  }
}
