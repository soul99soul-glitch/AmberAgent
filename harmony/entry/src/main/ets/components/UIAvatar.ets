// UIAvatar — 通用头像组件(第三梯队)
// Android 基准: ui/UIAvatar.kt(410 行)— TextAvatar + 框 + 内容
//   支持:首字母/emoji/图片 URL 三种模式;圆形/圆角;尺寸可配
// Harmony MVP:首字母 + emoji 模式(图片 URL 需设备验证 Image 组件)
import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LABEL } from '../design/tokens.ets';

@Component
export struct UIAvatar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop name: string = '';
  @Prop emoji: string = '';
  @Prop imageUrl: string = '';
  @Prop avatarSize: number = 36;
  @Prop circular: boolean = true;
  // 可选强调色覆盖:传入则用强调色而非按名字派生的调色板
  @Prop accentColor: string = '';
  build(): void {
    if (this.themeEpoch >= 0 && this.imageUrl.length > 0) {
      Image(this.imageSrc())
        .width(this.avatarSize).height(this.avatarSize)
        .borderRadius(this.circular ? this.avatarSize / 2 : 8)
        .objectFit(ImageFit.Cover)
    } else {
      Stack() {
        if (this.emoji.length > 0) {
          Text(this.emoji).fontSize(this.avatarSize * 0.5)
        } else {
          Text(this.initial())
            .fontSize(this.avatarSize * 0.4).fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
        }
      }
      .width(this.avatarSize).height(this.avatarSize)
      .borderRadius(this.circular ? this.avatarSize / 2 : 8)
      .backgroundColor(this.avatarColor())
    }
  }

  // 沙箱绝对路径补 file:// 前缀(Image 组件要求);网络/data:/file:/rawfile 原样
  private imageSrc(): string {
    const u: string = this.imageUrl;
    if (u.startsWith('http') || u.startsWith('data:') || u.startsWith('file://')
      || u.startsWith('resource:') || u.startsWith('$rawfile')) {
      return u;
    }
    return 'file://' + u;
  }

  private initial(): string {
    if (this.name.length === 0) return '?';
    return this.name.charAt(0).toUpperCase();
  }

  // 强调色优先;否则按名称首字符生成稳定调色板(会话头像多色区分)
  private avatarColor(): string {
    if (this.accentColor.length > 0) return this.accentColor;
    const colors: string[] = ['#B8623A', '#5E9C6E', '#6B5B95', '#88A096', '#A0795E', '#7B6D8D'];
    if (this.name.length === 0) return colors[0];
    const code: number = this.name.charCodeAt(0);
    return colors[code % colors.length];
  }
}
