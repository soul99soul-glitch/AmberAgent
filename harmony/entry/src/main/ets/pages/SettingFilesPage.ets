// SettingFilesPage — 文件管理(接 ManagedFileStore 真实后端)
//
// Android 基准: SettingFilesPage.kt — 按文件夹浏览/删除/清理。
//   鸿蒙接 chat-domain FilesRepository(entry ManagedFileStore.ets):
//   四个 folder(upload/skills/chat_images/images)列表 + 删除(DB+磁盘)
//   + 孤立文件清理(磁盘有、DB 无 → unlink)。
// 视觉:2026-09-27 二级页 iOS 对齐(基准截图 IMG_1994「Workspace」)——
//   居中标题 + caption 副标题 + 右侧 accent 文本动作(清理)、iOS 样式统计卡、
//   PlainSection 组标、r18 发丝描边卡组、文件行(图标座/名称/mono 副标题/删除);
//   逻辑(四 folder 列表/删除 DB+磁盘/孤立文件清理/登记库错误态)零改动。

import { promptAction } from '@kit.ArkUI';
import type { FilesRepository, ManagedFileEntity, Conversation, ConversationSummary } from '@amber/chat-domain';
import {
  getFilesRepository, getDeleteChatFilesDeps, MANAGED_FILE_FOLDERS,
  scanManagedOrphans, deleteManagedOrphans, OrphanScanResult, cleanupDeletedConversation,
} from '../platform_impl/ManagedFileStore.ets';
import { getChatRepository, getChatKvStore } from '../di/AppContainer.ets';
import {
  ACCENT, BG, ERROR, INK, INK3, LINE, accentSoftBg, lineHeightForSize,
} from '../design/tokens.ets';
import {
  FONT_MONO_MEDIUM, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD, FONT_SANS_BOLD,
} from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

interface FileRow {
  folder: string;
  file: ManagedFileEntity;
}

@Entry
@Component
struct SettingFilesPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State rows: FileRow[] = [];
  @State loaded: boolean = false;
  @State cleaning: boolean = false;
  // 非空 = 登记库读取/解析失败:禁用破坏性清理,并展示原因(不再伪装成「暂无托管文件」)
  @State registryError: string = '';
  @State pendingOrphans: string[] = [];
  // 对话清理(对齐双端「对话存储」:30 天前未置顶,置顶豁免)
  @State convCleaning: boolean = false;
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

  // 30 天前未置顶对话清理:先列目标弹确认,再逐个删除并级联清理托管文件
  private previewConversationCleanup(): void {
    if (this.convCleaning) return;
    const cutoff: number = Date.now() - 30 * 24 * 60 * 60 * 1000;
    getChatRepository().list().then((list: ConversationSummary[]): void => {
      const targets: ConversationSummary[] = list.filter((s: ConversationSummary): boolean =>
        !s.isPinned && new Date(s.updateAt).getTime() < cutoff);
      if (targets.length === 0) {
        promptAction.showToast({ message: '没有可清理的对话', duration: 1800 });
        return;
      }
      AlertDialog.show({
        title: '清理旧对话',
        message: `将永久删除 ${targets.length} 个 30 天前未置顶的对话(不可恢复),置顶对话不受影响。`,
        primaryButton: { value: '取消', action: (): void => {} },
        secondaryButton: {
          value: '删除',
          fontColor: ERROR,
          action: (): void => { this.confirmConversationCleanup(targets); },
        },
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `读取失败:${e.message}`, duration: 2000 });
    });
  }

  private confirmConversationCleanup(targets: ConversationSummary[]): void {
    this.convCleaning = true;
    const repo = getChatRepository();
    let removed: number = 0;
    const step = (i: number): void => {
      if (i >= targets.length) {
        this.convCleaning = false;
        const msg: string = removed === targets.length
          ? `已清理 ${removed} 个对话`
          : `已清理 ${removed}/${targets.length} 个对话,部分删除失败`;
        promptAction.showToast({ message: msg, duration: 2200 });
        return;
      }
      repo.getById(targets[i].id).then((conv: Conversation | null): Promise<void> => {
        if (conv === null) return Promise.resolve();
        return repo.delete(conv.id).then((): Promise<void> =>
          cleanupDeletedConversation(conv, repo, getChatKvStore()));
      }).then((): void => {
        removed++;
        step(i + 1);
      }).catch((): void => { step(i + 1); });
    };
    step(0);
  }

  @Builder
  conversationCleanContent(): void {
    CardRow({
      label: this.convCleaning ? '正在清理对话…' : '清理 30 天前未置顶的对话',
      desc: '不可恢复;置顶对话不受影响',
      iconName: 'trash',
      isLast: true,
      onRowClick: (): void => { this.previewConversationCleanup(); },
    })
  }

  // ===== UI(iOS 二级页骨架) =====

  // 统计卡(IMG_1994:accent 图标座 + 大数字 + 标签;鸿蒙仅有文件数,单卡呈现)
  @Builder
  statCardContent(): void {
    Row() {
      Row() {
        Icon({ name: 'doc', iconSize: 19, color: this.accentColorStore })
      }
      .width(44).height(44)
      .borderRadius(12)
      .backgroundColor(accentSoftBg(this.accentColorStore))
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      Column() {
        Text(`${this.rows.length}`)
          .fontSize(22)
          .fontWeight(FontWeight.Bold)
          .lineHeight(lineHeightForSize(22))
          .fontFamily(FONT_SANS_BOLD)
          .fontColor(INK)
        Text('托管文件')
          .fontSize(12)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3)
          .margin({ top: 1 })
      }
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 12 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  // 文件行(IMG_1994 行解剖:图标座 / 名称 / mono「folder · 大小 · 类型」/ 右侧动作)
  // 右侧保留删除钮(iOS 截图的箭头位);删除语义(DB+磁盘)零改动
  @Builder
  fileRow(row: FileRow, index: number) {
    Column() {
      Row() {
        Row() {
          Icon({
            name: row.file.mimeType.toLowerCase().indexOf('image') >= 0 ? 'image' : 'doc',
            iconSize: 15,
            color: this.accentColorStore,
          })
        }
        .width(28).height(28)
        .borderRadius(7)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        Column() {
          Text(row.file.displayName)
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(`${row.folder} · ${this.fmtSize(row.file.sizeBytes)} · ${row.file.mimeType}`)
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
            .width('100%')
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
        .margin({ right: -8 })
        .accessibilityText('删除文件')
        .onClick((): void => { this.deleteRow(row); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
      if (index < this.rows.length - 1) {
        // 分隔线内缩对齐文字起点(行 padding 14 + 图标座 28 + 间距 12 = 54)
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 54, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  fileRowsContent(): void {
    ForEach(this.rows, (row: FileRow, index: number): void => {
      this.fileRow(row, index)
    }, (row: FileRow): string => row.file.relativePath)
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({
        title: '文件管理',
        subtitle: '文件上下文与生成结果',
        // 登记库错误态禁用清理入口(canClean 语义;错误分支在下方独立展示原因)
        actionText: this.cleaning ? '清理中…' : (this.canClean() ? '清理' : ''),
        onAction: (): void => { this.cleanOrphans(); },
      })

      // ── 对话清理(始终可见,不受文件登记库状态影响)──
      PlainSection({ label: '对话' })
      Column() {
        CardGroup({ radius: 18, stroke: true, content: (): void => { this.conversationCleanContent(); } })
      }
      .width('100%').padding({ left: 16, right: 16 })

      if (this.loaded && this.registryError.length > 0) {
        // 登记库错误态:禁用破坏性清理,展示原因(逻辑零改动,仅去 Android '// ' 样式)
        Column() {
          Text('登记库不可用')
            .fontSize(15)
            .fontWeight(600)
            .lineHeight(lineHeightForSize(15))
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(ERROR)
          Text(this.registryError)
            .fontSize(13)
            .lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 8 }).textAlign(TextAlign.Center)
          Text('已禁用「清理孤立文件」,以免误删仍被引用的附件')
            .fontSize(13)
            .lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 8 })
        }
        .width('100%').padding({ top: 60, left: 24, right: 24 }).alignItems(HorizontalAlign.Center)
      } else if (this.loaded && this.rows.length === 0) {
        Column() {
          Text('暂无托管文件')
            .fontSize(15)
            .fontWeight(600)
            .lineHeight(lineHeightForSize(15))
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK3)
          Text('对话中的图片/附件会登记在这里')
            .fontSize(13)
            .lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 8 })
        }
        .width('100%').padding({ top: 60 }).alignItems(HorizontalAlign.Center)
      } else if (!this.loaded) {
        Text('加载中…')
          .fontSize(13)
          .lineHeight(lineHeightForSize(13))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3)
          .margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            // ── 统计卡(IMG_1994 顶部统计;鸿蒙仅有文件数 → 单卡)──
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.statCardContent(); } })
            }
            .width('100%').padding({ left: 16, right: 16 })

            // ── 文件组 ──
            PlainSection({ label: '文件' })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.fileRowsContent(); } })
            }
            .width('100%').padding({ left: 16, right: 16 })

            Column().height(24)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
      PageSpacer()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
