// MiniAppChatCard — 聊天流内小应用卡片(MiniAppChatCard.kt 对齐)
//
// icon/title/description + 运行按钮(router 到 Runner)+ 修改按钮(组装修订
// prompt 回填输入框,格式对齐 MiniAppChatCard.kt:247-257)+ 菜单(源码
// [showSourceButton 门控]/打开列表/导出/版本历史)。
// 回调(onRun/onOpenList/onModify)从 ChatPage 注入;源码/版本历史/导出在
// 卡片内自足(经 getMiniAppPlatform 取记录,与 Android koinInject 同口径)。

import { router, promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  MINI_APP_STREAM_PREVIEW_LIMIT, MiniAppStreamingCodePreview,
} from '@amber/chat-domain';
import type { UIMessagePartMiniApp, MiniAppRecord, MiniAppVersionRecord } from '@amber/chat-domain';
import { ACCENT, ACCENT_INK, BODY, BODY2, INK, INK2, INK3, INK4, LINE, META, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { getMiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { InlineMiniAppRuntime } from './InlineMiniAppRuntime.ets';
import { VisualArtifactPreview } from './VisualArtifactPreview.ets';
import { Icon } from './ui/Icon.ets';
import type { MessageRowViewState } from './MessageRowViewState.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'MiniApp.ChatCard';

interface ChatCardVersionTarget {
  app: MiniAppRecord;
  versions: MiniAppVersionRecord[];
}

@Component
export struct MiniAppChatCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onPartChanged') part: UIMessagePartMiniApp | null = null;
  @Prop @Watch('onStreamTextChanged') streamText: string = '';
  @Prop canRun: boolean = true;
  @Prop canModify: boolean = true;
  @Prop canOpenList: boolean = true;
  viewState: MessageRowViewState | null = null;
  viewStateKey: string = '';
  onRun: (appId: string) => void = (_appId: string): void => {};
  onOpenList: () => void = (): void => {};
  onContentGrow: () => void = (): void => {};
  onModify: (prompt: string) => boolean = (_prompt: string): boolean => false;
  @State showCode: boolean = false;
  @State sourceAllowed: boolean = false;
  @State streamCode: string = '';
  @State sourceDialogOpen: boolean = false;
  @State sourceTarget: string = '';
  @State versionTarget: ChatCardVersionTarget | null = null;
  @State modifyDialogOpen: boolean = false;
  @State modifyRequest: string = '';
  @State modifyApp: MiniAppRecord | null = null;
  @State menuOpen: boolean = false;
  @State previewHtml: string = '';
  @State previewApp: MiniAppRecord | null = null;
  @State revealEligible: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private platform: MiniAppPlatform | null = null;
  // 离页守卫:异步回调在组件销毁后不再写 @State/弹对话框
  private alive: boolean = false;
  private previewRevision: number = 0;
  private streamPreview: MiniAppStreamingCodePreview = new MiniAppStreamingCodePreview();
  private previewAppId: string = '';
  private streamPreviewActive: boolean = false;
  private codeScroller: Scroller = new Scroller();

  aboutToAppear(): void {
    this.alive = true;
    if (this.viewState !== null && this.viewStateKey.length > 0) {
      this.modifyRequest = this.viewState.miniAppModifyRequest.get(this.viewStateKey) ?? '';
      // Saved apps must recheck the current source setting before restoring code.
      this.showCode = this.part === null && this.savedShowCode();
    }
    this.loadPreview();
  }

  private savedShowCode(): boolean {
    return this.viewState !== null && this.viewStateKey.length > 0
      && this.viewState.miniAppShowCode.get(this.viewStateKey) === true;
  }

  private saveViewState(): void {
    if (this.viewState === null || this.viewStateKey.length === 0) return;
    if (this.part === null || this.sourceAllowed) {
      this.viewState.miniAppShowCode.set(this.viewStateKey, this.showCode);
    }
    this.viewState.miniAppModifyRequest.set(this.viewStateKey, this.modifyRequest);
  }

  private updateModifyRequest(value: string): void {
    this.modifyRequest = value;
    if (this.viewState !== null && this.viewStateKey.length > 0) {
      this.viewState.miniAppModifyRequest.set(this.viewStateKey, value);
    }
  }

  private onPartChanged(): void {
    if (this.alive) this.loadPreview();
  }

  private onStreamTextChanged(): void {
    if (this.alive) this.refreshStreamPreview();
  }

  private refreshStreamPreview(): void {
    this.streamPreview.update(this.streamText);
    this.streamCode = this.streamPreview.displayText();
    const html: string = this.streamPreview.htmlText();
    if (this.part === null) {
      this.previewHtml = html;
      this.streamPreviewActive = true;
    } else if (this.streamPreviewActive && html.length > 0) {
      // Keep the last stream frame visible while the repository lookup completes.
      this.previewHtml = html;
    }
  }

  private loadPreview(): void {
    const revision: number = ++this.previewRevision;
    const part: UIMessagePartMiniApp | null = this.part;
    const appId: string = part === null ? '' : part.appId;
    if (part !== null && this.previewAppId.length > 0 && this.previewAppId !== appId) {
      this.previewHtml = '';
      this.previewApp = null;
      this.revealEligible = false;
      this.streamPreviewActive = false;
    }
    if (part === null) {
      this.previewAppId = '';
      this.previewApp = null;
      this.revealEligible = false;
      this.refreshStreamPreview();
      return;
    }
    this.previewAppId = appId;
    this.refreshStreamPreview();
    const generatedFromStream: boolean = this.streamPreviewActive;
    this.revealEligible = false;
    getMiniAppPlatform().then((platform: MiniAppPlatform): void => {
      if (!this.alive || revision !== this.previewRevision || this.part?.appId !== appId) return;
      this.platform = platform;
      this.sourceAllowed = platform.settingSnapshot().showSourceButton;
      if (!this.sourceAllowed) this.showCode = false;
      else if (this.viewState !== null && this.viewStateKey.length > 0) this.showCode = this.savedShowCode();
      platform.repository.getById(appId).then((app: MiniAppRecord | null): void => {
        if (!this.alive || revision !== this.previewRevision || this.part?.appId !== appId) return;
        this.previewApp = app;
        this.previewHtml = app === null ? '' : app.htmlContent;
        this.revealEligible = app !== null && generatedFromStream;
        this.streamPreviewActive = false;
      }).catch((e: Error): void => {
        if (!this.alive || revision !== this.previewRevision || this.part?.appId !== appId) return;
        this.previewHtml = '';
        this.revealEligible = false;
        this.streamPreviewActive = false;
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'preview load failed: %{public}s', e.message);
      });
    }).catch((e: Error): void => {
      if (!this.alive || revision !== this.previewRevision || this.part?.appId !== appId) return;
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'platform load failed: %{public}s', e.message);
    });
  }

  aboutToDisappear(): void {
    this.saveViewState();
    this.alive = false;
    this.previewRevision++;
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 1600 });
  }

  private withCurrentApp(action: (app: MiniAppRecord) => void): void {
    const part: UIMessagePartMiniApp | null = this.part;
    if (part === null) return;
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) {
      this.toast('小应用服务未就绪');
      return;
    }
    const appId: string = part.appId;
    platform.repository.getById(appId).then((app: MiniAppRecord | null): void => {
      if (!this.alive || this.part?.appId !== appId) return;
      if (app === null) {
        this.toast('小应用不存在');
      } else {
        action(app);
      }
    }).catch((e: Error): void => {
      if (!this.alive) return;
      this.toast(e.message);
    });
  }

  private beginModify(): void {
    this.withCurrentApp((app: MiniAppRecord): void => {
      this.modifyApp = app;
      this.modifyRequest = this.viewState !== null && this.viewStateKey.length > 0
        ? this.viewState.miniAppModifyRequest.get(this.viewStateKey) ?? '' : '';
      this.modifyDialogOpen = true;
    });
  }

  private confirmModify(): void {
    const app: MiniAppRecord | null = this.modifyApp;
    const request: string = this.modifyRequest.trim();
    if (app === null || this.part === null || app.id !== this.part.appId || request.length === 0) return;
    // MiniAppChatCard.kt:247-257 toRevisionPrompt 逐字
    const prompt: string = [
      '修改小应用',
      `appId: ${app.id}`,
      `currentVersion: ${app.version}`,
      `title: ${app.title}`,
      '',
      '用户修改意见：',
      request,
      '',
      '请基于这个已保存小应用生成新版，并保留适合的能力声明。',
    ].join('\n');
    if (this.onModify(prompt)) {
      this.updateModifyRequest('');
      this.modifyDialogOpen = false;
    }
  }

  private openVersions(): void {
    this.withCurrentApp((app: MiniAppRecord): void => {
      const platform: MiniAppPlatform | null = this.platform;
      if (platform === null) return;
      platform.repository.listVersions(app.id).then((versions: MiniAppVersionRecord[]): void => {
        this.versionTarget = { app: app, versions: versions };
      }).catch((e: Error): void => this.toast(e.message));
    });
  }

  private restoreVersion(target: ChatCardVersionTarget, version: MiniAppVersionRecord): void {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null || this.part === null || this.part.appId !== target.app.id) return;
    platform.repository.restoreVersion(target.app.id, version.versionNumber)
      .then((record: MiniAppRecord | null): void => {
        if (!this.alive || this.versionTarget !== target || this.part?.appId !== target.app.id) return;
        if (record === null) {
          this.toast('恢复失败:小应用不存在');
        } else {
          this.previewApp = record;
          this.previewHtml = record.htmlContent;
          this.revealEligible = false;
          this.streamPreviewActive = false;
          this.versionTarget = null;
        }
      })
      .catch((e: Error): void => {
        if (this.alive && this.versionTarget === target) this.toast(`恢复失败:${e.message}`);
      });
  }

  private exportApp(app: MiniAppRecord): void {
    const ctx: Context = getContext(this) as Context;
    const safeName: string = app.title
      .replace(/[\\/:*?"<>|]+/g, '_')
      .slice(0, 48);
    const fileName: string = `${safeName.length > 0 ? safeName : 'miniapp'}.html`;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        this.writeToUri(uris[0], app.htmlContent);
      } else {
        this.exportToDownloadDir(app.htmlContent);
      }
    }).catch((): void => {
      this.exportToDownloadDir(app.htmlContent);
    });
  }

  private writeToUri(uri: string, content: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      this.toast('已导出 HTML');
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'picker write failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      this.exportToDownloadDir(content);
    }
  }

  private exportToDownloadDir(content: string): void {
    const ctx: Context = getContext(this) as Context;
    const dir: string = `${ctx.filesDir}/Download`;
    try {
      fileIo.mkdirSync(dir);
    } catch (_e) {
      // 已存在
    }
    const path: string = `${dir}/miniapp.html`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      this.toast(`已导出到 ${path}`);
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'download export failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      this.toast('导出失败');
    }
  }

  private cardTitle(): string {
    if (this.showCode) return '小应用源码';
    const part: UIMessagePartMiniApp | null = this.part;
    return part === null || part.title.length === 0 ? '正在生成小应用' : part.title;
  }

  private cardDescription(): string {
    const part: UIMessagePartMiniApp | null = this.part;
    return part === null ? '正在生成可视化内容…' : part.description;
  }

  private codeText(): string {
    const source: string = this.previewHtml.length > 0 ? this.previewHtml : this.streamCode;
    if (source.length <= MINI_APP_STREAM_PREVIEW_LIMIT) return source;
    return `已省略 ${source.length - MINI_APP_STREAM_PREVIEW_LIMIT} 字\n` +
      source.slice(-MINI_APP_STREAM_PREVIEW_LIMIT);
  }

  private toggleCode(): void {
    if (this.part !== null && !this.sourceAllowed) return;
    this.showCode = !this.showCode;
    if (this.viewState !== null && this.viewStateKey.length > 0) {
      this.viewState.miniAppShowCode.set(this.viewStateKey, this.showCode);
    }
  }

  @Builder
  private CodeBody(): void {
    Scroll(this.codeScroller) {
      Text(this.codeText().length > 0 ? this.codeText() : ' ')
        .fontSize(11).fontColor(INK2).fontFamily('JetBrainsMono')
        .width('100%')
        .onSizeChange((): void => { this.codeScroller.scrollEdge(Edge.Bottom); })
    }
    .width('100%').height(200).padding(10).align(Alignment.TopStart)
    .scrollBar(BarState.Off).backgroundColor(SURFACE2).borderRadius(12)
  }

  // ===== UI =====

  @Builder
  sourceDialog(): void {
    if (this.sourceDialogOpen) {
      Stack() {
        Column() {
          Row() {
            Text('源码').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
            Text('关闭')
              .fontSize(BODY).fontColor(this.accentColorStore)
              .padding({ left: 16, right: 8, top: 8, bottom: 8 })
              .onClick((): void => { this.sourceDialogOpen = false; })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          Scroll() {
            Text(this.sourceTarget)
              .fontSize(11)
              .fontColor(INK2)
              .fontFamily('JetBrainsMono')
              .width('100%')
          }
          .layoutWeight(1)
          .align(Alignment.Top)
        }
        .width('88%').height('70%')
        .padding(16)
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .border({ width: 1, color: LINE })
        .onClick((): void => {})
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.sourceDialogOpen = false; })
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  versionDialog(target: ChatCardVersionTarget): void {
    Stack() {
      Column() {
        Row() {
          Text('版本历史').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
          Text('关闭')
            .fontSize(BODY).fontColor(this.accentColorStore)
            .padding({ left: 16, right: 8, top: 8, bottom: 8 })
            .onClick((): void => { this.versionTarget = null; })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .margin({ bottom: 4 })
        Scroll() {
          Column() {
            ForEach(target.versions, (v: MiniAppVersionRecord): void => {
              Row() {
                Column() {
                  Text(`v${v.versionNumber}`)
                    .fontSize(BODY)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(INK)
                  Text(v.changeNote !== null && v.changeNote.length > 0
                    ? v.changeNote : (v.versionNumber === target.app.version ? '当前版本' : '历史版本'))
                    .fontSize(META)
                    .fontColor(INK3)
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)
                Text(v.versionNumber === target.app.version ? '当前' : '恢复')
                  .fontSize(BODY)
                  .fontColor(v.versionNumber === target.app.version ? INK4 : this.accentColorStore)
                  .padding({ left: 14, top: 8, bottom: 8 })
                  .onClick((): void => {
                    if (v.versionNumber !== target.app.version) {
                      this.restoreVersion(target, v);
                    }
                  })
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
              .padding({ left: 4, right: 4, top: 10, bottom: 10 })
              .border({ width: { bottom: 0.5 }, color: LINE })
            }, (v: MiniAppVersionRecord): string => `${v.appId}-${v.versionNumber}`)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
      .width('86%').height('62%')
      .padding(18)
      .backgroundColor(SURFACE)
      .borderRadius(14)
      .border({ width: 1, color: LINE })
      .onClick((): void => {})
      .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%')
    .backgroundColor(OVERLAY)
    .onClick((): void => { this.versionTarget = null; })
    .transition(TransitionEffect.OPACITY
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  modifyDialog(app: MiniAppRecord): void {
    Stack() {
      Column() {
        Text('修改小应用')
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .width('100%')
          .margin({ bottom: 8 })

        Text(`基于「${app.title}」v${app.version} 继续迭代。写下你想改什么,Amber 会生成新版并写入版本历史。`)
          .fontSize(BODY2)
          .fontColor(INK3)
          .width('100%')
          .lineHeight(20)

          TextArea({ text: this.modifyRequest, placeholder: '例如:把按钮改小一点,增加今日统计,并支持联网刷新新闻' })
            .fontSize(BODY2)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(8)
            .width('100%')
            .height(88)
            .margin({ top: 12 })
            .onChange((v: string): void => { this.updateModifyRequest(v); })

          Row() {
            Text('取消')
              .fontSize(BODY)
              .fontColor(INK3)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 4 })
              .onClick((): void => { this.modifyDialogOpen = false; })
            Text('发送修改')
              .fontSize(BODY)
              .fontColor(this.modifyRequest.trim().length > 0 ? this.accentColorStore : INK4)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 12, bottom: 4 })
              .onClick((): void => {
                if (this.modifyRequest.trim().length > 0) {
                  this.confirmModify();
                }
              })
          }
          .width('100%')
          .margin({ top: 8 })
          .border({ width: { top: 0.5 }, color: LINE })
        }
        .width('84%')
        .padding(20)
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .border({ width: 1, color: LINE })
        .onClick((): void => {})
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.modifyDialogOpen = false; })
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }

  build(): void {
    Stack({ alignContent: Alignment.TopStart }) {
      Column() {
        Row() {
          Text(this.part !== null && this.part.iconEmoji !== null && this.part.iconEmoji.length > 0
            ? this.part.iconEmoji : '▣')
            .fontSize(16)
          Text(this.cardTitle())
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .margin({ left: 6 })
          Row() {
            Icon({ name: this.showCode ? 'spark' : 'doc', iconSize: 14, color: this.accentColorStore })
          }
          .width(44).height(44)
          .visibility(this.part === null || this.sourceAllowed ? Visibility.Visible : Visibility.None)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .accessibilityText(this.showCode ? '显示视觉预览' : '显示源码')
          .onClick((): void => { this.toggleCode(); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        Text(this.cardDescription())
          .fontSize(BODY2)
          .fontColor(INK3)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 4 })

        if (this.showCode) {
          this.CodeBody()
        } else if (this.canRun && this.previewApp !== null && this.part !== null) {
          InlineMiniAppRuntime({
            app: this.previewApp,
            complete: true,
            revealEligible: this.revealEligible,
            onContentGrow: (): void => { this.onContentGrow(); },
          })
        } else if (this.previewHtml.length > 0) {
          // During generation the HTML is only a safe, script-free preview.
          VisualArtifactPreview({
            html: this.previewHtml,
            renderer: 'miniapp',
            complete: this.part !== null,
            revealKey: this.previewApp === null || this.part === null
              ? '' : `miniapp:${this.previewApp.id}:${this.previewApp.version}`,
            revealEligible: this.previewApp !== null && this.part !== null && this.revealEligible,
            onContentGrow: (): void => { this.onContentGrow(); },
          })
        }

        if (this.part !== null && (this.canRun || this.canModify || this.canOpenList)) {
          Row() {
            if (this.canRun) {
              Row() {
                Text('运行')
                  .fontSize(BODY)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(ACCENT_INK)
                  .padding({ left: 16, right: 16, top: 7, bottom: 7 })
              }
              .backgroundColor(ACCENT)
              .borderRadius(8)
              .onClick((): void => {
                if (this.part !== null) this.onRun(this.part.appId);
              })
            }

            if (this.canModify) {
              Row() {
                Text('修改')
                  .fontSize(BODY)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(this.accentColorStore)
                  .padding({ left: 16, right: 16, top: 7, bottom: 7 })
              }
              .backgroundColor(SURFACE2)
              .borderRadius(8)
              .margin({ left: this.canRun ? 8 : 0 })
              .onClick((): void => { this.beginModify(); })
            }

            if (this.canOpenList) {
              Row() {
                Text('⋯').fontSize(18).fontColor(INK3)
              }
              .width(40).height(36)
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)
              .margin({ left: 4 })
              .bindContextMenu(this.cardMenu(), ResponseType.LongPress)
            }
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 8 })
        }
      }
      .width('100%')
      .padding(12)
      .backgroundColor(SURFACE)
      .borderRadius(16)
      .border({ width: 1, color: this.part === null ? this.accentColorStore : LINE })
      .margin({ bottom: 6 })

      if (this.sourceDialogOpen) {
        this.sourceDialog()
      }
      if (this.versionTarget !== null) {
        this.versionDialog(this.versionTarget)
      }
      if (this.modifyDialogOpen && this.modifyApp !== null) {
        this.modifyDialog(this.modifyApp)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .onSizeChange((): void => { this.onContentGrow(); })
  }

  @Builder
  cardMenu(): void {
    Menu() {
      if (this.showSourceButton()) {
        MenuItem({ content: '查看源码' })
          .onClick((): void => {
            this.withCurrentApp((app: MiniAppRecord): void => {
              this.sourceTarget = app.htmlContent;
              this.sourceDialogOpen = true;
            });
          })
      }
      MenuItem({ content: '打开列表' })
        .onClick((): void => { this.onOpenList(); })
      MenuItem({ content: '导出 HTML' })
        .onClick((): void => {
          this.withCurrentApp((app: MiniAppRecord): void => { this.exportApp(app); });
        })
      MenuItem({ content: '版本历史' })
        .onClick((): void => { this.openVersions(); })
    }
  }

  private showSourceButton(): boolean {
    return this.platform !== null && this.platform.settingSnapshot().showSourceButton;
  }
}
