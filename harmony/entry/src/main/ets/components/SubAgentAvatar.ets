// SubAgentAvatar — 子代理头像(第三梯队)
// Android 基准: ui/SubAgentAvatar.kt(289 行)— ASCII 动画帧 + 状态色
//   根据 subagentId 生成稳定的首字母头像;running 时绿点;failed 时红点
// Harmony MVP:首字母 + 状态色(ASCII 动画帧需设备验证)
import { INK, INK3, ACCENT, SURFACE, SIGNAL, ERROR } from '../design/tokens.ets';

@Component
export struct SubAgentAvatar {
  @Prop subagentId: string = '';
  @Prop name: string = '';
  @Prop avatarSize: number = 24;
  @Prop status: string = ''; // running/completed/failed/cancelled/...
  build(): void {
    Stack({ alignContent: Alignment.BottomEnd }) {
      // 头像主体
      Text(this.initial())
        .fontSize(this.avatarSize * 0.4).fontWeight(FontWeight.Bold)
        .fontColor('#FFFFFF')
        .width(this.avatarSize).height(this.avatarSize)
        .borderRadius(this.avatarSize / 2)
        .backgroundColor(this.avatarColor())
        .textAlign(TextAlign.Center)

      // 状态点(running=绿;failed=红;其他=不显示)
      if (this.status === 'running' || this.status === 'failed') {
        Circle({ width: this.avatarSize * 0.3, height: this.avatarSize * 0.3 })
          .fill(this.status === 'running' ? SIGNAL : ERROR)
          .stroke(SURFACE)
          .strokeWidth(1.5)
      }
    }
    .width(this.avatarSize + 2).height(this.avatarSize + 2)
  }

  private initial(): string {
    const display: string = this.name.length > 0 ? this.name : this.subagentId;
    if (display.length === 0) return '?';
    return display.charAt(0).toUpperCase();
  }

  private avatarColor(): string {
    const colors: string[] = ['#B8623A', '#5E9C6E', '#6B5B95', '#88A096', '#A0795E'];
    const key: string = this.subagentId.length > 0 ? this.subagentId : this.name;
    if (key.length === 0) return colors[0];
    let hash: number = 0;
    for (let i: number = 0; i < key.length; i++) {
      hash = key.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  }
}
