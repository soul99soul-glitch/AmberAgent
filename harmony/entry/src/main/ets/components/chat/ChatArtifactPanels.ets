import type { ConversationArtifactSource, UIMessagePartDocument } from '@amber/chat-domain';
import { ACCENT, BG, INK } from '../../design/tokens.ets';
import { ChatArtifactShelf } from '../ChatArtifactShelf.ets';
import { ChatArtifactSession } from '../../platform_impl/chat/ChatArtifactSession.ets';

@Component
export struct ChatArtifactPanels {
  @ObjectLink session: ChatArtifactSession;
  onDraft: (text: string) => void = (_text: string): void => {};
  onRunMiniApp: (appId: string) => void = (_appId: string): void => {};

  @Builder
  private artifactShelfSheet() {
    ChatArtifactShelf({ artifacts: this.session.shelfArtifacts,
      onDismiss: (): void => { this.session.artifactShelfOpen = false; },
      onOpenDocument: (part: UIMessagePartDocument): void => {
        this.session.artifactShelfOpen = false;
        this.session.openDocumentPreview(part);
      },
      onOpenDiff: (name: string): void => {
        this.session.artifactShelfOpen = false;
        this.session.openArtifactDiff(name);
      },
      onRunMiniApp: (appId: string): void => {
        this.session.artifactShelfOpen = false;
        this.onRunMiniApp(appId);
      },
      onSource: (source: ConversationArtifactSource): void => {
        this.session.artifactShelfOpen = false;
        this.session.openArtifactSource(source);
      },
      onAction: (prompt: string): void => {
        this.onDraft(prompt);
        this.session.artifactShelfOpen = false;
      },
    })
  }

  @Builder
  private documentPreviewSheet() {
    Column() {
      Row() {
        Text(this.session.documentPreviewTitle).fontSize(18).fontColor(INK).layoutWeight(1).maxLines(2)
        Text('关闭').fontSize(14).fontColor(ACCENT).padding(12)
          .onClick((): void => { this.session.documentPreviewOpen = false; })
      }.width('100%')
      Scroll() {
        Text(this.session.documentPreviewText).fontSize(15).fontColor(INK)
          .copyOption(CopyOptions.InApp).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.width('100%').height('100%').padding(20).backgroundColor(BG)
  }

  @Builder
  private artifactDiffSheet() {
    Column() {
      Row() {
        Text(this.session.artifactDiffTitle).fontSize(18).fontColor(INK).layoutWeight(1).maxLines(2)
        Text('关闭').fontSize(14).fontColor(ACCENT).padding(12)
          .onClick((): void => { this.session.artifactDiffOpen = false; })
      }.width('100%')
      Scroll() {
        Text(this.session.artifactDiffText).fontSize(14).fontColor(INK)
          .copyOption(CopyOptions.InApp).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.width('100%').height('100%').padding(20).backgroundColor(BG)
  }

  build() {
    Column() {
      // bindSheet 按宿主节点注册；每张 sheet 保持独立且稳定的宿主。
      Column() {}
        .width(0).height(0)
        .hitTestBehavior(HitTestMode.None)
        .bindSheet($$this.session.documentPreviewOpen, this.documentPreviewSheet(), {
          height: '85%', showClose: false,
        })
      Column() {}
        .width(0).height(0)
        .hitTestBehavior(HitTestMode.None)
        .bindSheet($$this.session.artifactShelfOpen, this.artifactShelfSheet(), {
          height: SheetSize.FIT_CONTENT, showClose: false,
        })
      Column() {}
        .width(0).height(0)
        .hitTestBehavior(HitTestMode.None)
        .bindSheet($$this.session.artifactDiffOpen, this.artifactDiffSheet(), {
          height: '85%', showClose: false,
        })
    }.width(0).height(0)
  }
}
