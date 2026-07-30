// ChatInput — 聊天输入框组件(第二梯队 E)
//
// Android 基准: ai/ChatInput.kt(868 行)— 三段式 surface-2 药丸
//   左:attach chip(+ → × 展开,图片/相机/文件)
//   中:TextInputRow(多行文本输入)
//   右:send/stop 圆形键(accent 填充/stop 图标/长按 send-without-answer)
//
// Harmony 当前(ChatPage 2502-2547):单行 TextInput + 扁平「图片」文字 + 发送/停止
// 本组件抽出为独立 @Component,ChatPage 改为引用
//
// 功能(本切片 MVP):
//   ✅ 多行 TextArea + 发送门(text 非空或 hasImages)
//   ✅ 图片附件按钮(PhotoViewPicker 已有)
//   ✅ 发送/停止切换(sending 态 → 停止按钮)
//   ✅ 长按发送(sendWithoutAnswer)— 发送键和停止键均支持
//   ✅ 编辑取消(取消编辑按钮)+ 编辑态 placeholder
//   ❌ 斜杠命令面板(后续迭代)
//   ❌ 相机/文件/音频/视频 picker(后续迭代,依赖平台能力)
//   ❌ +→× 形变动画(后续迭代)
//   ❌ 用量面板(后续迭代)

import { INK, INK3, INK4, ACCENT, SURFACE, SURFACE2, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ChatInput {
  @Prop text: string = '';
  @Prop sending: boolean = false;
  @Prop editing: boolean = false;
  @Prop hasImages: boolean = false;
  onTextChange: (value: string) => void = (_v: string): void => {};
  onSend: () => void = (): void => {};
  onStop: () => void = (): void => {};
  onSendWithoutAnswer: () => void = (): void => {};
  onPickImage: () => void = (): void => {};
  onCancelEdit: () => void = (): void => {};

  build(): void {
    Column() {
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
          // 发送中 → 停止按钮(也支持长按插话 STEER)
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
          // 发送键(accent 填充,空文本且无图片时禁用)
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

  // 发送门:文本非空或有图片(D-038 纯图发送)
  private canSend(): boolean {
    return this.text.trim().length > 0 || this.hasImages;
  }
}
