// MiniAppListPage — 小应用列表页(2 列网格)
//
// 顶栏(返回/标题/设置入口)+ 空态 + 网格卡片(icon/title/description/
// runCount/pinned 角标);点击进 Runner;长按菜单(置顶/重命名/版本历史/
// 导出/删除);重命名与删除确认弹窗;版本历史弹窗(列表+恢复)。
// 导出:DocumentViewPicker 保存,失败降级写 filesDir/Download 并 toast 路径。

import { router, promptAction } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { fileIo, picker } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { MiniAppRecord, MiniAppVersionRecord } from '@amber/chat-domain';
import { ACCENT, ACCENT_LIGHT, BG, BODY, BODY2, INK, INK2, INK3, INK4, LINE, META, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2, TITLE } from '../design/tokens.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { getMiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { ConfirmDialog } from '../components/ui/ConfirmDialog.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'MiniApp.List';

interface RenameTarget {
  app: MiniAppRecord;
  title: string;
  description: string;
}

interface VersionTarget {
  app: MiniAppRecord;
  versions: MiniAppVersionRecord[];
}

@Entry
@Component
struct MiniAppListPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State apps: MiniAppRecord[] = [];
  @State loaded: boolean = false;
  @State renameTarget: RenameTarget | null = null;
  @State deleteTarget: MiniAppRecord | null = null;
  @State versionTarget: VersionTarget | null = null;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private platform: MiniAppPlatform | null = null;
  // 离页守卫:异步加载回调不再写 @State
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    getMiniAppPlatform().then((platform: MiniAppPlatform): void => {
      this.platform = platform;
      this.refresh();
    }).catch((e: Error): void => {
      this.toast(`加载失败:${e.message}`);
      this.loaded = true;
    });
  }

  onPageShow(): void {
    if (this.platform !== null) this.refresh();
  }

  aboutToDisappear(): void {
    this.alive = false;
  }

  onBackPress(): boolean {
    if (this.deleteTarget !== null) {
      this.deleteTarget = null;
      return true;
    }
    if (this.versionTarget !== null) {
      this.versionTarget = null;
      return true;
    }
    if (this.renameTarget !== null) {
      this.renameTarget = null;
      return true;
    }
    return false;
  }

  private handleBack(): void {
    if (!this.onBackPress()) router.back();
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 1800 });
  }

  private refresh(): void {
    if (this.platform === null) return;
    this.platform.repository.listAll().then((records: MiniAppRecord[]): void => {
      if (!this.alive) return;
      this.apps = records;
      this.loaded = true;
    }).catch((e: Error): void => {
      if (!this.alive) return;
      this.toast(`加载失败:${e.message}`);
      this.loaded = true;
    });
  }

  private openRunner(app: MiniAppRecord): void {
    router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: app.id } });
  }

  private openSettings(): void {
    router.pushUrl({ url: 'pages/MiniAppSettingsPage' });
  }

  // ===== 长按菜单(卡片 bindContextMenu 触发,见 gridCard)=====

  private togglePinned(app: MiniAppRecord): void {
    if (this.platform === null) return;
    this.platform.repository.setPinned(app.id, !app.pinned)
      .then((): void => this.refresh())
      .catch((e: Error): void => this.toast(e.message));
  }

  private beginRename(app: MiniAppRecord): void {
    this.renameTarget = { app: app, title: app.title, description: app.description };
  }

  private confirmRename(): void {
    const target: RenameTarget | null = this.renameTarget;
    if (target === null || this.platform === null) return;
    const title: string = target.title.trim();
    if (title.length === 0) return;
    this.platform.repository.rename(target.app.id, title, target.description)
      .then((): void => {
        this.renameTarget = null;
        this.refresh();
      })
      .catch((e: Error): void => this.toast(e.message));
  }

  private confirmDelete(): void {
    const target: MiniAppRecord | null = this.deleteTarget;
    if (target === null || this.platform === null) return;
    this.platform.repository.delete(target.id)
      .then((): void => {
        this.deleteTarget = null;
        this.refresh();
      })
      .catch((e: Error): void => this.toast(e.message));
  }

  private openVersions(app: MiniAppRecord): void {
    if (this.platform === null) return;
    this.platform.repository.listVersions(app.id)
      .then((versions: MiniAppVersionRecord[]): void => {
        this.versionTarget = { app: app, versions: versions };
      })
      .catch((e: Error): void => this.toast(e.message));
  }

  private restoreVersion(target: VersionTarget, version: MiniAppVersionRecord): void {
    if (this.platform === null) return;
    this.platform.repository.restoreVersion(target.app.id, version.versionNumber)
      .then((record: MiniAppRecord | null): void => {
        if (record === null) {
          this.toast('恢复失败:小应用不存在');
        } else {
          this.versionTarget = null;
          this.refresh();
        }
      })
      .catch((e: Error): void => this.toast(`恢复失败:${e.message}`));
  }

  // ===== 导出 =====

  private exportApp(app: MiniAppRecord): void {
    const ctx: Context = getContext(this) as Context;
    const safeName: string = app.title
      .replace(/[\\/:*?"<>|]+/g, '_')
      .slice(0, 48);
    const fileName: string = `${safeName.length > 0 ? safeName : 'miniapp'}.html`;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        this.writeToUri(uris[0], app.htmlContent, app.title);
      } else {
        this.exportToDownloadDir(app.htmlContent, app.title);
      }
    }).catch((): void => {
      this.exportToDownloadDir(app.htmlContent, app.title);
    });
  }

  private writeToUri(uri: string, content: string, title: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      this.toast('已导出 HTML');
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'picker write failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      this.exportToDownloadDir(content, title);
    }
  }

  private exportToDownloadDir(content: string, title: string): void {
    const ctx: Context = getContext(this) as Context;
    const dir: string = `${ctx.filesDir}/Download`;
    try {
      fileIo.mkdirSync(dir);
    } catch (_e) {
      // 已存在
    }
    // RunnerPage 导出同名规则:miniapp-<title 安全名>.html(多次导出不互相覆盖)
    const safe: string = title
      .replace(/[\\/:*?"<>|]+/g, '_')
      .slice(0, 48);
    const path: string = `${dir}/${safe.length > 0 ? safe : 'miniapp'}.html`;
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

  // ===== UI =====

  @Builder
  topBar(): void {
    CenteredTitleBar({ title: '小应用', actionText: '设置',
      onBack: (): void => { this.handleBack(); },
      onAction: (): void => { this.openSettings(); } })
  }

  @Builder
  gridCard(app: MiniAppRecord): void {
    Column() {
      Row() {
        Text(app.iconEmoji !== null && app.iconEmoji.length > 0 ? app.iconEmoji : '▣')
          .fontSize(20)
          .fontColor(INK2)
        Text(app.title)
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .margin({ left: 6 })
        if (app.pinned) {
          Text('📌').fontSize(12)
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Text(app.description)
        .fontSize(BODY2)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .margin({ top: 6 })

      Row() {
        Text(`运行 ${app.runCount} 次`)
          .fontSize(META)
          .fontColor(INK4)
      }
      .width('100%')
      .margin({ top: 8 })
    }
    .width('100%')
    .padding(14)
    .backgroundColor(SURFACE)
    .borderRadius(14)
    .border({ width: 1, color: LINE })
    .onClick((): void => { this.openRunner(app); })
    .bindContextMenu(this.cardMenu(app), ResponseType.LongPress)
  }

  @Builder
  cardMenu(app: MiniAppRecord): void {
    Menu() {
      MenuItem({ content: app.pinned ? '取消置顶' : '置顶' })
        .onClick((): void => { this.togglePinned(app); })
      MenuItem({ content: '版本历史' })
        .onClick((): void => { this.openVersions(app); })
      MenuItem({ content: '导出 HTML' })
        .onClick((): void => { this.exportApp(app); })
      MenuItem({ content: '重命名' })
        .onClick((): void => { this.beginRename(app); })
      MenuItem({ content: '删除' })
        .onClick((): void => { this.deleteTarget = app; })
    }
  }

  @Builder
  emptyState(): void {
    Column() {
      // 空态图标座:accent 淡底圆座 + 应用图标
      Row() {
        Icon({ name: 'hi_app_archive', iconSize: 30, color: ACCENT })
      }
      .width(76).height(76).borderRadius(38)
      .backgroundColor(ACCENT_LIGHT)
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .margin({ bottom: 18 })
      Text('还没有保存的小应用')
        .fontSize(BODY)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK2)
      Text('在聊天中让 Amber 生成小应用后会出现在这里')
        .fontSize(BODY2)
        .fontColor(INK4)
        .margin({ top: 8 })
    }
    .width('100%').layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .padding({ top: 48 })
  }

  // 重命名弹窗(TextInput 内嵌,Stack overlay;AlertDialog 无文本输入)
  @Builder
  renameDialog(target: RenameTarget): void {
    Stack() {
      Column() {
        Text('重命名小应用')
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .width('100%')
          .margin({ bottom: 12 })

        TextInput({ text: target.title, placeholder: '名称' })
          .fontSize(BODY)
          .fontColor(INK)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .width('100%')
          .maxLength(40)
          .onChange((v: string): void => {
            if (this.renameTarget !== null) {
              const next: RenameTarget = {
                app: this.renameTarget.app,
                title: v,
                description: this.renameTarget.description,
              };
              this.renameTarget = next;
            }
          })

        TextArea({ text: target.description, placeholder: '描述' })
          .fontSize(BODY2)
          .fontColor(INK)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .width('100%')
          .height(72)
          .maxLength(120)
          .margin({ top: 10 })
          .onChange((v: string): void => {
            if (this.renameTarget !== null) {
              const next: RenameTarget = {
                app: this.renameTarget.app,
                title: this.renameTarget.title,
                description: v,
              };
              this.renameTarget = next;
            }
          })

        Row() {
          Text('取消')
            .fontSize(BODY)
            .fontColor(INK3)
            .layoutWeight(1)
            .textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 4 })
            .onClick((): void => { this.renameTarget = null; })
          Text('保存')
            .fontSize(BODY)
            .fontColor(target.title.trim().length > 0 ? this.accentColorStore : INK4)
            .fontWeight(FontWeight.Medium)
            .layoutWeight(1)
            .textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 4 })
            .onClick((): void => {
              if (target.title.trim().length > 0) {
                this.confirmRename();
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
    .onClick((): void => { this.renameTarget = null; })
    .transition(TransitionEffect.OPACITY
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  versionHistoryDialog(target: VersionTarget): void {
    // 用 Stack overlay 模拟可滚动版本列表弹窗(AlertDialog 无法承载 List)
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
  deleteDialog(target: MiniAppRecord): void {
    // 退场动画仍可能刷新子组件，文案使用打开时的对象快照。
    ConfirmDialog({
      show: true,
      title: '删除小应用',
      message: `确定删除「${target.title}」吗?历史聊天里的小应用卡片会保留,但再次运行时会显示小应用不存在。`,
      confirmText: '删除',
      dismissText: '取消',
      onConfirm: (): void => { this.confirmDelete(); },
      onDismiss: (): void => { this.deleteTarget = null; },
    })
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        this.topBar()

        if (!this.loaded) {
          Column() {
            Text('加载中…').fontSize(BODY).fontColor(INK3)
          }
          .width('100%').layoutWeight(1)
          .justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
          .padding({ top: 48 })
        } else if (this.apps.length === 0) {
          this.emptyState()
        } else {
          Grid() {
            ForEach(this.apps, (app: MiniAppRecord): void => {
              GridItem() {
                this.gridCard(app)
              }
            }, (app: MiniAppRecord): string => app.id)
          }
          .columnsTemplate('1fr 1fr')
          .columnsGap(12)
          .rowsGap(12)
          .padding(16)
          .layoutWeight(1)
          .scrollBar(BarState.Off)
          .backgroundColor(BG)
        }
      }
      .width('100%').height('100%')
      .backgroundColor(BG)

      if (this.renameTarget !== null) {
        this.renameDialog(this.renameTarget)
      }
      if (this.versionTarget !== null) {
        this.versionHistoryDialog(this.versionTarget)
      }
      if (this.deleteTarget !== null) {
        this.deleteDialog(this.deleteTarget)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
