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

import { MOTION_OVERLAY, ACCENT, ACCENT_INK, BODY, INK, INK2, INK3, INK4, LABEL, LINE, META, SURFACE, SURFACE2, lineHeightForSize, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS, FONT_MONO } from '../design/fonts.ets';
import { SlashCommandPanel, SlashCommand, QuickMessage, buildSlashCommands } from './SlashCommandPanel.ets';
import { Icon } from './ui/Icon.ets';
import { MentionPanel } from './ui/MentionPanel.ets';
import type { MentionRoleItem } from '@amber/chat-domain';
import { detectMentionContext, replaceMention, filterMentionRoleItems } from '@amber/chat-domain';
import type { MentionContext } from '@amber/chat-domain';

@Component
export struct ChatInput {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop text: string = '';
  @Prop sending: boolean = false;
  @Prop disabled: boolean = false;
  @Prop editing: boolean = false;
  @Prop hasImages: boolean = false;
  @Prop queueCount: number = 0;
  @Prop placeholder: string = '输入消息…';
  @Prop showAttachments: boolean = true;
  @Prop showCoreSlashCommands: boolean = true;
  @Prop subAgentEnabled: boolean = false;
  @Prop subAgentMode: string = 'roster';
  // G2-S4:动态 skill 斜杠命令(由 ChatPage 从 SkillManager 装配)
  @Prop skillCommands: SlashCommand[] = [];
  @Prop quickMessages: QuickMessage[] = [];
  // 显示与字体设置:回车发送 / 长文本粘贴为文件(Android DisplaySetting 对齐)
  @Prop sendOnEnter: boolean = false;
  @Prop pasteLongTextAsFile: boolean = false;
  @Prop pasteLongTextThreshold: number = 1000;
  // D0-8:@提及角色候选(ChatPage 装配;空数组 = 不启用面板)
  @Prop mentionRoles: MentionRoleItem[] = [];
  // D0-9:输入区联网搜索开关(与设置页同一 KV,双向同步)
  @Prop webSearchOn: boolean = false;
  onToggleWebSearch: () => void = (): void => {};
  onTextChange: (value: string) => void = (_v: string): void => {};
  onSend: () => void = (): void => {};
  onStop: () => void = (): void => {};
  onSendWithoutAnswer: () => void = (): void => {};
  onQueueSend: (mode: string) => void = (_mode: string): void => {};
  onOpenQueue: () => void = (): void => {};
  onPickImage: () => void = (): void => {};
  onTakePhoto: () => void = (): void => {};
  onPickFile: () => void = (): void => {};
  onPasteAsFile: (text: string) => void = (_t: string): void => {};
  @State attachExpanded: boolean = false;
  onCancelEdit: () => void = (): void => {};
  onClearInput: () => void = (): void => {};
  onCompactContext: () => void = (): void => {};
  onInsertText: (text: string) => void = (_t: string): void => {};

  // 1:1 原型 .pressable:active scale(.975) .14s ease-out
  @State attachScale: number = 1;
  @State sendScale: number = 1;
  // D0-7:全屏编辑(AmberTextArea.FullScreenEditor 同款 sheet 模式)
  @State fullScreenOpen: boolean = false;
  @State fullScreenText: string = '';
  @State queueMode: string = 'FOLLOWUP';
  @State selectionStart: number = 0;
  @State selectionEnd: number = 0;
  private inputController: TextAreaController = new TextAreaController();

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
    return !this.disabled && !this.editing &&
      this.allSlashCommands().length > 0 &&
      (this.text.startsWith('/') || this.text.startsWith('／')) &&
      this.text.indexOf(' ') < 0;
  }

  private allSlashCommands(): SlashCommand[] {
    const commands: SlashCommand[] = buildSlashCommands(
      this.subAgentEnabled, this.subAgentMode, this.skillCommands, this.quickMessages);
    if (this.showCoreSlashCommands) return commands;
    return commands.filter((cmd: SlashCommand): boolean => !cmd.id.startsWith('core.'));
  }

  // 过滤匹配当前输入的命令(输入 /c → 匹配 clear/compact)
  private getSlashCommands(): SlashCommand[] {
    const all: SlashCommand[] = this.allSlashCommands();
    if (!this.isSlashVisible()) return [];
    // 去掉前导 / 或 ／ 得到查询
    const query: string = this.text.substring(1).toLowerCase();
    if (query.length === 0) return all;
    return all.filter((cmd: SlashCommand): boolean =>
      cmd.title.toLowerCase().startsWith(query));
  }

  // 文本选区非空时不弹候选；按 TextArea 实际光标检测，保留光标后文本。
  private mentionContext(): MentionContext | null {
    if (this.disabled || this.editing || this.mentionRoles.length === 0) return null;
    if (this.isSlashVisible()) return null;
    if (this.selectionStart !== this.selectionEnd) return null;
    return detectMentionContext(this.text, this.selectionEnd);
  }

  private mentionMatches(): MentionRoleItem[] {
    const ctx: MentionContext | null = this.mentionContext();
    if (ctx === null) return [];
    return filterMentionRoleItems(this.mentionRoles, ctx.query);
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
    } else if (cmd.id.startsWith('quick.')) {
      this.onInsertText(cmd.insertText === undefined ? cmd.description : cmd.insertText);
    } else if (cmd.id.startsWith('skill.')) {
      // G2-S4:/skill-* → 插入 use_skill 引导(ChatInputComposers.kt:895-899)
      const skillId: string = cmd.id.substring('skill.'.length);
      this.onInsertText(cmd.insertText === undefined
        ? `请先调用 use_skill(skill_id="${skillId}") 执行技能，然后根据结果回答：`
        : cmd.insertText);
    }
  }

  build(): void {
    Column() {
      // 编辑取消条
      if (this.editing) {
        Row() {
          Text('取消编辑').fontSize(META).fontFamily(FONT_SANS).fontColor(INK3)
          Blank()
          Text('编辑中').fontSize(LABEL).fontFamily(FONT_MONO).fontColor(INK4)
        }
        .width('100%')
        .height(44).padding({ left: 20, right: 20 })
        .onClick((): void => { this.onCancelEdit(); })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      // D0-8:@提及面板(inline 于输入区上方,与 composer 左右对齐)
      if (this.mentionContext() !== null) {
        MentionPanel({
          roles: this.mentionMatches(),
          onSelect: (roleId: string): void => {
            const ctx: MentionContext | null = this.mentionContext();
            if (ctx === null) return;
            this.onTextChange(replaceMention(this.text, ctx, roleId));
            const cursor: number = ctx.atIndex + roleId.length + 2;
            this.selectionStart = cursor;
            this.selectionEnd = cursor;
            // 等待父组件回写 text 后，再把光标落在新提及后而非文本末尾。
            setTimeout((): void => { this.inputController.caretPosition(cursor); }, 0);
          },
        })
          .padding({ left: 20, right: 20 })
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      if ((this.sending && this.hasDraft()) || this.queueCount > 0) {
        this.QueueControls()
      }

      // 候选面板参与根 Column 测量，使用与 @ 提及相同的输入区上方布局。
      if (this.isSlashVisible()) {
        SlashCommandPanel({
          commands: this.getSlashCommands(),
          visible: true,
          onSelect: (cmd: SlashCommand): void => { this.handleSlashCommand(cmd); },
        })
          .padding({ left: 20, right: 20 })
      }

      // Composer 沉浸式(原型验收规格):无底盘底色、无顶部分隔线,
      //   + / 胶囊 / 发送键严格等高 44(amber-zhenji 规格),胶囊 bg surface、圆角随输入增高 999↔24。
      Stack() {
        Row() {
        // Attach capsule:rest 44 纯圆;展开时动作组以宽度+透明度推入(220ms),
        //   组件常驻(不做 if 增删),避免宽度跳变无动效、收起无动画。
        if (this.showAttachments) {
          Row() {
            Row() {
              Icon({ name: 'plus', iconSize: 20, color: INK2 })
                .rotate({ angle: this.attachExpanded ? 45 : 0 })
                .animation({ duration: 220, curve: Curve.EaseOut })
            }
            .width(44).height(44)
            .borderRadius(999)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.attachExpanded = !this.attachExpanded; })
            .accessibilityText('添加附件')

            Row({ space: 2 }) {
              Row() {
                Icon({ name: 'hi_camera', iconSize: 23, color: INK2 })
              }
              .width(44).height(44).borderRadius(999)
              .justifyContent(FlexAlign.Center)
              .onClick((): void => { this.attachExpanded = false; this.onTakePhoto(); })
              .accessibilityText('拍照')

              Row() {
                Icon({ name: 'hi_photos', iconSize: 23, color: INK2 })
              }
              .width(44).height(44).borderRadius(999)
              .justifyContent(FlexAlign.Center)
              .onClick((): void => { this.attachExpanded = false; this.onPickImage(); })
              .accessibilityText('选择图片')

              Row() {
                Icon({ name: 'hi_files', iconSize: 23, color: INK2 })
              }
              .width(44).height(44).borderRadius(999)
              .justifyContent(FlexAlign.Center)
              .onClick((): void => { this.attachExpanded = false; this.onPickFile(); })
              .accessibilityText('选择文件')

              // D0-9:联网搜索开关(与设置页同一状态源;开 = accent 浅底 + accent 图标)
              Column({ space: 1 }) {
                Icon({ name: 'hi_global', iconSize: 18,
                  color: this.webSearchOn ? this.accentColorStore : INK2 })
                Text(this.webSearchOn ? '联网已开' : '联网已关')
                  .fontSize(10).fontFamily(FONT_SANS)
                  .fontColor(this.webSearchOn ? this.accentColorStore : INK3)
              }
              .width(44).height(44).borderRadius(999)
              .justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
              .backgroundColor(this.webSearchOn ? accentSoftBg(this.accentColorStore) : Color.Transparent)
              .onClick((): void => { this.onToggleWebSearch(); })
              .accessibilityText(this.webSearchOn ? '关闭联网搜索' : '开启联网搜索')
            }
            .width(this.attachExpanded ? 182 : 0)
            .opacity(this.attachExpanded ? 1 : 0)
            .clip(true)
            .alignItems(VerticalAlign.Center)
            .animation({ duration: 220, curve: Curve.EaseOut })
          }
          .width(this.attachExpanded ? 226 : 44).height(44)
          .animation({ duration: 220, curve: Curve.EaseOut })
          .borderRadius(999)
          .backgroundColor(SURFACE)
          .border({ width: 1, color: LINE })
          .alignItems(VerticalAlign.Center)
          .opacity(this.sending ? 0.4 : 1)
          .scale({ x: this.attachScale, y: this.attachScale })
          .onTouch((e: TouchEvent): void => { this.pressTouch(true, e); })
          .enabled(!this.disabled)
          .margin({ right: 8 })
        }

        // Center pill:flex 1, radius 26, min-height 48, bg SURFACE2, border 1px LINE
        Row() {
          TextArea({ text: this.text,
            controller: this.inputController,
            placeholder: this.editing ? '编辑消息,发送将重发…' : this.placeholder })
            .layoutWeight(1)
            .fontSize(BODY).fontColor(INK)
            .fontFamily(FONT_SANS)
            .placeholderColor(INK4)
            .placeholderFont({ size: BODY })
            .backgroundColor(Color.Transparent)
            .borderRadius(this.text.length > 0 ? 24 : 999)
            .padding({ top: 11, bottom: 11 })
            .maxLines(this.attachExpanded ? 1 : 4)
            .constraintSize({ minHeight: 44, maxHeight: this.attachExpanded ? 44 : 111 })
            .lineHeight(lineHeightForSize(BODY))
            .enabled(!this.disabled)
            .onChange((v: string): void => { if (!this.disabled) this.onTextChange(v); })
            .onTextSelectionChange((start: number, end: number): void => {
              this.selectionStart = start;
              this.selectionEnd = end;
            })
            // 回车发送:enterKeyType=Send 时 onSubmit 才触发(NEW_LINE 不触发,API 11+)
            .enterKeyType(this.sendOnEnter ? EnterKeyType.Send : EnterKeyType.NEW_LINE)
            .onSubmit((enterKey: EnterKeyType): void => {
              if (enterKey === EnterKeyType.Send && this.sendOnEnter && this.canSend()) {
                if (this.sending) this.onQueueSend(this.queueMode); else this.onSend();
              }
            })
            // 长文本粘贴为文件:超阈值拦截系统默认粘贴,转附件(Android pasteLongTextAsFile)
            .onPaste((value: string, event: PasteEvent): void => {
              if (this.pasteLongTextAsFile && value.length >= this.pasteLongTextThreshold) {
                if (event.preventDefault !== undefined) event.preventDefault();
                this.onPasteAsFile(value);
              }
            })
          // D0-7:全屏编辑入口(有内容时胶囊内右侧 ⛶)
          if (this.text.length > 0 && !this.attachExpanded && !this.disabled) {
            Text('⛶')
              .fontSize(15).fontColor(INK3)
              .padding(6)
              .constraintSize({ minWidth: 32, minHeight: 32 })
              .textAlign(TextAlign.Center)
              .onClick((): void => {
                this.fullScreenText = this.text;
                this.fullScreenOpen = true;
              })
              .accessibilityText('全屏编辑')
          }
        }
        .layoutWeight(1)
        .constraintSize({ minHeight: 44, maxHeight: this.attachExpanded ? 44 : 111 })
        .borderRadius(this.text.length > 0 ? 24 : 999)
        .backgroundColor(SURFACE)
        .border({ width: 1, color: LINE })
        .padding({ left: 18, right: 18 })
        .alignItems(VerticalAlign.Center)
        .margin({ right: 8 })

        // Send/Stop button:48x48, radius 999(stop 用纯白方块 glyph,1:1 原型)
        if (this.sending) {
          Row() {
            Column().width(12).height(12).backgroundColor(SURFACE).borderRadius(3)
          }
          .width(44).height(44)
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
            Icon({ name: 'arrow_up', iconSize: 20, color: this.canSend() ? ACCENT_INK : INK2 })
          }
          .width(44).height(44)
          .borderRadius(999)
          .backgroundColor(this.canSend() ? this.accentColorStore : SURFACE2)
          .border({ width: 1, color: this.canSend() ? this.accentColorStore : LINE })
          .shadow(this.canSend()
            ? { radius: 12, color: this.accentShadow(), offsetY: 4 }
            : { radius: 0, color: Color.Transparent, offsetY: 0 })
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .scale({ x: this.sendScale, y: this.sendScale })
          .onTouch((e: TouchEvent): void => { this.pressTouch(false, e); })
          .gesture(
            GestureGroup(
              GestureMode.Exclusive,
              LongPressGesture().onAction((): void => {
                if (!this.disabled && this.canSend()) this.onSendWithoutAnswer();
              }),
              TapGesture().onAction((): void => {
                if (!this.disabled && this.canSend()) this.onSend();
              }),
            )
          )
          .accessibilityText('发送消息，长按仅发送不等待回复')
        }
        }
        .width('100%')
        .enabled(!this.disabled || this.sending)
        .opacity(this.disabled ? 0.55 : 1)
        .padding({ left: 20, right: 20, top: 10, bottom: 14 })
        .alignItems(VerticalAlign.Center)

      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .bindSheet(this.fullScreenOpen, this.FullScreenEditor(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.fullScreenOpen = false; },
    })
  }

  @Builder private QueueControls(): void {
    Column() {
      Row({ space: 8 }) {
        if (this.sending && this.hasDraft()) {
          Row() {
            this.QueueModeButton('FOLLOWUP', '排队')
            this.QueueModeButton('STEER', '插话')
            this.QueueModeButton('COLLECT', '合并')
          }
          .layoutWeight(1)
          .borderRadius(22)
          .backgroundColor(SURFACE2)
          .border({ width: 1, color: LINE })
          .enabled(!this.disabled)
        } else {
          Blank()
        }
        if (this.queueCount > 0) {
          Text(`队列 ${this.queueCount}`)
            .fontSize(META).fontFamily(FONT_SANS).fontColor(INK2)
            .height(44).constraintSize({ minWidth: 60 })
            .textAlign(TextAlign.Center)
            .onClick((): void => { this.onOpenQueue(); })
            .accessibilityText(`管理待发消息，共 ${this.queueCount} 条`)
        }
        if (this.sending && this.hasDraft()) {
          Row() {
            Icon({ name: 'arrow_up', iconSize: 19,
              color: this.canSend() ? ACCENT_INK : INK3 })
          }
          .width(44).height(44).borderRadius(22)
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .backgroundColor(this.canSend() ? this.accentColorStore : SURFACE2)
          .border({ width: 1, color: this.canSend() ? this.accentColorStore : LINE })
          .enabled(this.canSend())
          .onClick((): void => { this.onQueueSend(this.queueMode); })
          .accessibilityText(this.queueSendLabel())
        }
      }
      .width('100%')
      if (this.sending && this.hasDraft()) {
        Text(this.queueModeDescription())
          .fontSize(LABEL).fontFamily(FONT_SANS).fontColor(INK3)
          .width('100%').margin({ top: 3 })
      }
    }
    .width('100%').padding({ left: 20, right: 20, top: 4 })
  }

  @Builder private QueueModeButton(mode: string, title: string): void {
    Text(title)
      .fontSize(META).fontFamily(FONT_SANS)
      .fontColor(this.queueMode === mode ? this.accentColorStore : INK3)
      .height(44).layoutWeight(1).textAlign(TextAlign.Center)
      .borderRadius(22)
      .backgroundColor(this.queueMode === mode ? accentSoftBg(this.accentColorStore) : Color.Transparent)
      .onClick((): void => { this.queueMode = mode; })
      .accessibilityText(`${title}${this.queueMode === mode ? '，已选中' : ''}`)
  }

  private queueModeDescription(): string {
    if (this.queueMode === 'STEER') return '插话：在下一次可插入的步骤补充';
    if (this.queueMode === 'COLLECT') return '合并：连续补充合成一条后发送';
    return '排队：本轮完成后发送';
  }

  private queueSendLabel(): string {
    if (this.queueMode === 'STEER') return '发送插话';
    if (this.queueMode === 'COLLECT') return '加入合并队列';
    return '加入待发队列';
  }

  // D0-7:全屏编辑器(AmberTextArea.FullScreenEditor 同款:取消/保存 + 通栏 TextArea)
  @Builder FullScreenEditor(): void {
    Column() {
      Row() {
        Text('取消').fontSize(BODY).fontColor(INK3)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.fullScreenOpen = false; })
        Blank()
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => {
            this.onTextChange(this.fullScreenText);
            this.fullScreenOpen = false;
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      TextArea({ text: this.fullScreenText, placeholder: this.placeholder })
        .fontSize(BODY).fontColor(INK)
        .fontFamily(FONT_SANS)
        .placeholderColor(INK4)
        .backgroundColor(SURFACE2)
        .borderRadius(12)
        .padding(16)
        .width('100%')
        .layoutWeight(1)
        .onChange((v: string): void => { this.fullScreenText = v; })
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE2)
  }

  private hasDraft(): boolean {
    return this.text.trim().length > 0 || this.hasImages;
  }

  private canSend(): boolean {
    return !this.disabled && this.hasDraft();
  }

  // accent 35% 透明阴影色(发送键可发态浮起)
  private accentShadow(): string {
    const value: string = this.accentColorStore.replace('#', '');
    if (value.length !== 6) return '#33000000';
    const r: number = parseInt(value.substring(0, 2), 16);
    const g: number = parseInt(value.substring(2, 4), 16);
    const b: number = parseInt(value.substring(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, 0.35)`;
  }
}
