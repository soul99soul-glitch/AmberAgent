// AssistantPicker — 助手切换 sheet(ai/AssistantPicker.kt:246 行)
// Android 基准: NavigationDrawerItem → ModalBottomSheet(标签过滤 + 助手 Card 列表)
// ArkUI MVP: 触发行(当前助手名 + avatar)→ bindSheet(助手列表)
import type { Assistant } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';
import { UIAvatar } from '../UIAvatar.ets';

@Component
export struct AssistantPicker {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop assistants: Assistant[] = [];
  @Prop currentAssistantId: string = '';
  @State sheetOpen: boolean = false;
  onSelect: (id: string) => void = (_id: string): void => {};

  private currentName(): string {
    for (const a of this.assistants) {
      if (a.id === this.currentAssistantId) return a.name;
    }
    return '(未选择)';
  }

  build(): void {
    Row() {
      UIAvatar({
        avatar: this.assistants.length > 0 ? this.assistants[0].avatar : { type: 'dummy' },
        avatarSize: 24,
      })
      Text(this.currentName())
        .fontSize(BODY).fontColor(INK2).margin({ left: 8 }).layoutWeight(1)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('▾').fontSize(LABEL).fontColor(INK3)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(SURFACE)
    .borderRadius(12)
    .border({ width: 1, color: LINE })
    .onClick((): void => { this.sheetOpen = true; })
    .bindSheet(this.sheetOpen, this.PickerSheet(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.sheetOpen = false; },
    })
  }

  @Builder PickerSheet(): void {
    Column() {
      Text('选择助手').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 12 })

      Scroll() {
        Column() {
          ForEach(this.assistants, (a: Assistant): void => {
            Row() {
              UIAvatar({ avatar: a.avatar, avatarSize: 28 })
              Text(a.name)
                .fontSize(BODY)
                .fontColor(a.id === this.currentAssistantId ? ACCENT : INK2)
                .margin({ left: 10 }).layoutWeight(1)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              if (a.id === this.currentAssistantId) {
                Text('✓').fontSize(LABEL).fontColor(ACCENT)
              }
            }
            .width('100%')
            .padding({ left: 20, right: 20, top: 12, bottom: 12 })
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            .onClick((): void => {
              this.onSelect(a.id);
              this.sheetOpen = false;
            })
          }, (a: Assistant): string => a.id)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE)
  }
}
