// SettingFilesPage — 文件管理(接 ManagedFileStore 真实后端)
//
// Android 基准: SettingFilesPage.kt — 按文件夹浏览/删除/清理。
//   鸿蒙接 chat-domain FilesRepository(entry ManagedFileStore.ets):
//   四个 folder(upload/skills/chat_images/images)列表 + 删除(DB+磁盘)
//   + 孤立文件清理(磁盘有、DB 无 → unlink)。

import { promptAction } from '@kit.ArkUI';
import { fileIo } from '@kit.CoreFileKit';
import type { FilesRepository, ManagedFileEntity } from '@amber/chat-domain';
import {
  FILE_FOLDERS_UPLOAD, FILE_FOLDERS_SKILLS, FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES,
} from '@amber/chat-domain';
import { getFilesRepository, getDeleteChatFilesDeps } from '../platform_impl/ManagedFileStore.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

interface FileRow {
  folder: string;
  file: ManagedFileEntity;
}

@Entry
@Component
struct SettingFilesPage {
  @State rows: FileRow[] = [];
  @State loaded: boolean = false;
  @State cleaning: boolean = false;
  private repo: FilesRepository = getFilesRepository();

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    const folders: string[] =
      [FILE_FOLDERS_UPLOAD, FILE_FOLDERS_SKILLS, FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES];
    const acc: FileRow[] = [];
    const step = (i: number): void => {
      if (i >= folders.length) {
        this.rows = acc;
        this.loaded = true;
        return;
      }
      const f: string = folders[i];
      this.repo.listByFolder(f).then((list: ManagedFileEntity[]): void => {
        for (const file of list) acc.push({ folder: f, file });
        step(i + 1);
      }).catch((): void => { step(i + 1); }); // 单 folder 失败不断链
    };
    step(0);
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

  // 孤立文件:磁盘存在但 DB 无记录 → unlink(对齐 FilesManager 清理语义)
  private cleanOrphans(): void {
    // !loaded 时 rows 为空,会把已登记文件全当孤儿 → 必须守卫
    if (this.cleaning || !this.loaded) return;
    this.cleaning = true;
    const ctx = AppStorage.get<Context>('abilityContext') as Context;
    const base: string = ctx.filesDir;
    const known: string[] = [];
    for (const r of this.rows) known.push(r.file.relativePath);
    const folders: string[] =
      [FILE_FOLDERS_UPLOAD, FILE_FOLDERS_SKILLS, FILE_FOLDERS_CHAT_IMAGES, FILE_FOLDERS_IMAGES];
    let removed: number = 0;
    const step = (i: number): void => {
      if (i >= folders.length) {
        this.cleaning = false;
        promptAction.showToast({ message: `已清理 ${removed} 个孤立文件`, duration: 2000 });
        this.reload();
        return;
      }
      const folder: string = folders[i];
      try {
        const names: string[] = fileIo.listFileSync(`${base}/${folder}`);
        const next = (j: number): void => {
          if (j >= names.length) { step(i + 1); return; }
          const rel: string = `${folder}/${names[j]}`;
          if (known.indexOf(rel) < 0) {
            try {
              fileIo.unlinkSync(`${base}/${rel}`);
              removed++;
            } catch (e) { /* 单文件失败跳过 */ }
          }
          next(j + 1);
        };
        next(0);
      } catch (e) {
        step(i + 1); // 目录不存在 → 跳过
      }
    };
    step(0);
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '文件管理' })

      Row() {
        Text(`${this.rows.length} 个托管文件`)
          .fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono').layoutWeight(1)
        Text(this.cleaning ? '清理中…' : '清理孤立文件')
          .fontSize(BODY2).fontColor(ACCENT)
          .onClick((): void => { this.cleanOrphans(); })
      }
      .width('100%').padding({ left: 18, right: 18, top: 10, bottom: 4 })

      if (this.loaded && this.rows.length === 0) {
        Column() {
          Text('// 暂无托管文件').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          Text('对话中的图片/附件会登记在这里')
            .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 60 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.rows, (row: FileRow): void => {
            ListItem() {
              Row() {
                Icon({ name: 'doc', iconSize: 18, color: INK3 })
                Column() {
                  Text(row.file.displayName).fontSize(BODY2).fontColor(INK)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(`${row.folder} · ${this.fmtSize(row.file.sizeBytes)}`)
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono').margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
                Icon({ name: 'trash', iconSize: 15, color: ERROR })
                  .onClick((): void => { this.deleteRow(row); })
              }
              .padding(12)
              .backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(12)
              .margin({ left: 18, right: 18, bottom: 8 })
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
