// SettingFilesPage — 文件管理(接 ManagedFileStore 真实后端)
//
// Android 基准: SettingFilesPage.kt — 按文件夹浏览/删除/清理。
//   鸿蒙接 chat-domain FilesRepository(entry ManagedFileStore.ets):
//   四个 folder(upload/skills/chat_images/images)列表 + 删除(DB+磁盘)
//   + 孤立文件清理(磁盘有、DB 无 → unlink)。

import { promptAction } from '@kit.ArkUI';
import type { FilesRepository, ManagedFileEntity } from '@amber/chat-domain';
import {
  getFilesRepository, getDeleteChatFilesDeps, MANAGED_FILE_FOLDERS,
  scanManagedOrphans, deleteManagedOrphans, OrphanScanResult,
} from '../platform_impl/ManagedFileStore.ets';
import { getChatRepository } from '../di/AppContainer.ets';
import {
  BG, SURFACE2, INK, INK3, INK4, ACCENT, ERROR,
  BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, PaperSeparator, IconSeat } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

interface FileRow {
  folder: string;
  file: ManagedFileEntity;
}

@Component
struct FilePaperRow {
  @Prop displayName: string = '';
  @Prop folderText: string = '';
  @Prop sizeText: string = '';
  @Prop mimeType: string = '';
  @Prop isLast: boolean = false;
  @State private pressed: boolean = false;
  onDelete: () => void = (): void => {};

  build(): void {
    Column() {
      Row() {
        IconSeat({ iconName: this.mimeType.toLowerCase().indexOf('image') >= 0 ? 'image' : 'doc' })
        Column() {
          Text(this.displayName)
            .fontSize(15)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(`${this.folderText} · ${this.sizeText}`)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(500)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 4 })
          Text(this.mimeType)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(500)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })
        Row() {
          Icon({ name: 'trash', iconSize: 15, color: ERROR })
        }
        .width(44)
        .height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { this.onDelete(); })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressed = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressed = false;
      })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

@Entry
@Component
struct SettingFilesPage {
  @State rows: FileRow[] = [];
  @State loaded: boolean = false;
  @State cleaning: boolean = false;
  // 非空 = 登记库读取/解析失败:禁用破坏性清理,并展示原因(不再伪装成「暂无托管文件」)
  @State registryError: string = '';
  @State pendingOrphans: string[] = [];
  private repo: FilesRepository = getFilesRepository();

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    const folders: string[] = [...MANAGED_FILE_FOLDERS];
    const acc: FileRow[] = [];
    let failure: string = '';
    const step = (i: number): void => {
      if (i >= folders.length) {
        this.rows = acc;
        this.registryError = failure;
        this.loaded = true;
        return;
      }
      const f: string = folders[i];
      this.repo.listByFolder(f).then((list: ManagedFileEntity[]): void => {
        for (const file of list) acc.push({ folder: f, file });
        step(i + 1);
      }).catch((e: Error): void => {
        // 单 folder 失败不断链,但必须记住错误:此时 acc 不完整,绝不可当空库
        if (failure.length === 0) failure = e.message;
        step(i + 1);
      });
    };
    step(0);
  }

  private canClean(): boolean {
    return this.loaded && this.registryError.length === 0 && !this.cleaning;
  }

  private fmtSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  private deleteRow(row: FileRow): void {
    AlertDialog.show({
      title: '删除文件',
      message: `删除「${row.file.displayName}」？磁盘与登记将一并移除。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => {
          const deps = getDeleteChatFilesDeps();
          deps.deleteFile(row.file.relativePath).then((): void => {
            this.repo.deleteByPath(row.file.relativePath)
              .then((): void => { this.reload(); })
              .catch((): void => { this.reload(); });
          }).catch((): void => { this.reload(); });
        },
      },
    });
  }

  // 孤立文件:磁盘存在但登记无记录且无任何会话引用 → 先预览候选,确认后删除
  // 安全边界(UI-F20):
  //   1) 登记库必须健康;损坏/缺失/读失败一律拒绝清理(空快照不是删除依据);
  //   2) scanManagedOrphans 内部重新拉取健康登记 + 遍历全部会话引用;
  //   3) 执行时 deleteManagedOrphans 再次核对登记与引用,只删仍然确认的文件。
  private cleanOrphans(): void {
    if (this.cleaning || !this.loaded) return;
    if (this.registryError.length > 0) {
      promptAction.showToast({ message: '登记库不可用,已禁用清理', duration: 2200 });
      return;
    }
    this.cleaning = true;
    scanManagedOrphans(getChatRepository()).then((result: OrphanScanResult): void => {
      this.cleaning = false;
      if (result.error.length > 0) {
        this.registryError = result.error;
        promptAction.showToast({ message: `登记库不可用,已取消清理:${result.error}`, duration: 2600 });
        return;
      }
      if (result.candidates.length === 0) {
        promptAction.showToast({ message: '没有可清理的孤立文件', duration: 2000 });
        this.reload();
        return;
      }
      const preview: string = result.candidates.slice(0, 5).join('\n');
      const more: string = result.candidates.length > 5
        ? `\n…另有 ${result.candidates.length - 5} 个` : '';
      this.pendingOrphans = result.candidates;
      AlertDialog.show({
        title: '清理孤立文件',
        message: `以下 ${result.candidates.length} 个文件未登记、也未被任何会话引用,`
          + `将被永久删除:\n${preview}${more}`,
        primaryButton: {
          value: '取消',
          action: (): void => { this.pendingOrphans = []; },
        },
        secondaryButton: {
          value: '删除',
          fontColor: ERROR,
          action: (): void => { this.confirmCleanOrphans(); },
        },
      });
    }).catch((e: Error): void => {
      this.cleaning = false;
      promptAction.showToast({ message: `清理前检查失败:${e.message}`, duration: 2200 });
    });
  }

  private confirmCleanOrphans(): void {
    const planned: string[] = this.pendingOrphans;
    this.pendingOrphans = [];
    if (planned.length === 0) return;
    this.cleaning = true;
    deleteManagedOrphans(getChatRepository(), planned).then((removed: number): void => {
      this.cleaning = false;
      promptAction.showToast({ message: `已清理 ${removed} 个孤立文件`, duration: 2000 });
      this.reload();
    }).catch((e: Error): void => {
      this.cleaning = false;
      this.registryError = e.message;
      promptAction.showToast({ message: `清理失败:${e.message}`, duration: 2200 });
      this.reload();
    });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '文件管理' })

      Row() {
        Text(`${this.rows.length} 个托管文件`)
          .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3).layoutWeight(1)
        Text(this.cleaning ? '清理中…' : '清理孤立文件')
          .fontSize(BODY2).fontWeight(500).lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(this.canClean() ? ACCENT : INK4)
          .onClick((): void => { this.cleanOrphans(); })
      }
      .width('100%').padding({ left: 16, right: 16, top: 10, bottom: 4 })

      if (this.loaded && this.registryError.length > 0) {
        Column() {
          Text('// 登记库不可用')
            .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(ERROR)
          Text(this.registryError)
            .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3)
            .margin({ top: 8 }).textAlign(TextAlign.Center)
          Text('已禁用「清理孤立文件」,以免误删仍被引用的附件')
            .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 60, left: 24, right: 24 }).alignItems(HorizontalAlign.Center)
      } else if (this.loaded && this.rows.length === 0) {
        Column() {
          Text('// 暂无托管文件')
            .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          Text('对话中的图片/附件会登记在这里')
            .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 60 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.rows, (row: FileRow, index: number): void => {
            ListItem() {
              FilePaperRow({
                displayName: row.file.displayName,
                folderText: row.folder,
                sizeText: this.fmtSize(row.file.sizeBytes),
                mimeType: row.file.mimeType,
                isLast: index === this.rows.length - 1,
                onDelete: (): void => { this.deleteRow(row); },
              })
            }
          }, (row: FileRow): string => row.file.relativePath)
        }
        .width('100%').layoutWeight(1).padding({ top: 6 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
