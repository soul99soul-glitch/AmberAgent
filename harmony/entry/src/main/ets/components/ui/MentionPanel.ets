// MentionPanel — @mention 角色选择面板(ChatInputComposers.kt:936-1023)
// Android 基准: Surface 浮层 + MentionRow 列表 + 空匹配行
// ArkUI: Column 列表 + 条件渲染(无 Popup 浮层,inline 渲染)
import type { MentionRoleItem } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';

const MAX_MENTIONS: number = 8;

@Component
export struct MentionPanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop roles: MentionRoleItem[] = [];
  onSelect: (roleId: string) => void = (_id: string): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.roles.length > 0) {
      Column() {
        ForEach(this.roles.slice(0, MAX_MENTIONS), (role: MentionRoleItem, i: number): void => {
          this.MentionRow(role)
          if (i < Math.min(this.roles.length, MAX_MENTIONS) - 1) {
            Divider().color(LINE).strokeWidth(0.5).margin({ left: 58 })
          }
        }, (role: MentionRoleItem, i: number): string => `${role.id}-${i}`)
      }
      .width('100%')
      .backgroundColor(SURFACE)
      .borderRadius(14)
      .border({ width: 1, color: LINE })
      .margin({ bottom: 4 })
    } else {
      Column() {
        Text('无匹配角色').fontSize(LABEL).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 16, top: 10, bottom: 10 })
      .backgroundColor(SURFACE)
      .borderRadius(14)
      .border({ width: 1, color: LINE })
    }
  }

  @Builder MentionRow(role: MentionRoleItem): void {
    Row() {
      // @ badge
      Text('@')
        .fontSize(LABEL).fontColor(INK3)
        .width(30).height(30)
        .textAlign(TextAlign.Center)
        .backgroundColor(SURFACE2)
        .borderRadius(15)
        .border({ width: 1, color: LINE })
      Column() {
        Text(`@${role.id}`)
          .fontSize(BODY).fontColor(INK2)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(role.description)
          .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 8 })
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .onClick((): void => { this.onSelect(role.id); })
  }
}
