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
class SettingFilesPage {
rows: FileRow[] = [];
loaded: boolean = false;
cleaning: boolean = false;
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
}
module.exports = { SettingFilesPage };