// DeepRead exports use the saved article snapshot prepared by the caller.
import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { deviceInfo } from '@kit.BasicServicesKit';
import { parseMarkdown, orderedListLabel } from '@amber/chat-domain';
import type { InlineToken, MarkdownBlock } from '@amber/chat-domain';

const inlineText = (tokens: InlineToken[]): string => tokens.map((token: InlineToken): string => {
  if (token.type === 'link') return token.text === token.url ? token.url : `${token.text} (${token.url})`;
  if (token.type === 'image') return token.alt.length > 0 ? `${token.alt} (${token.url})` : token.url;
  if (token.type === 'math') return token.latex;
  return token.text;
}).join('');

export const deepReadMarkdownToText = (source: string): string => parseMarkdown(source).map((block: MarkdownBlock): string => {
  if (block.kind === 'code_block') return block.code;
  if (block.kind === 'hr') return '---';
  if (block.kind === 'list') {
    const depths: number[] = block.depths ?? [];
    return block.items.map((item: InlineToken[], index: number): string =>
      `${'  '.repeat(depths[index] ?? 0)}${block.ordered ? `${orderedListLabel(block.start, depths, index)}.` : '-'} ${inlineText(item)}`).join('\n');
  }
  if (block.kind === 'table') {
    const rows: string[] = [block.headers.map((cell: InlineToken[]): string => inlineText(cell)).join('\t')];
    for (const row of block.rows) rows.push(row.map((cell: InlineToken[]): string => inlineText(cell)).join('\t'));
    return rows.join('\n');
  }
  return inlineText(block.inlines);
}).join('\n\n');

export const isDeepReadPdfSupported = (): boolean =>
  deviceInfo.sdkApiVersion >= 14 && canIUse('SystemCapability.Web.Webview.Core');

const writeBytes = (path: string, bytes: Uint8Array): void => {
  const file: fileIo.File = fileIo.openSync(path,
    fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  try {
    let consumed: number = 0;
    while (consumed < bytes.byteLength) {
      const remaining: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset + consumed, bytes.byteOffset + bytes.byteLength);
      const written: number = fileIo.writeSync(file.fd, remaining);
      if (written <= 0 || written > remaining.byteLength) throw new Error('文件写入未完成');
      consumed += written;
    }
  } catch (error) {
    try { fileIo.closeSync(file.fd); } catch { /* Preserve the write error. */ }
    throw error instanceof Error ? error : new Error(String(error));
  }
  fileIo.closeSync(file.fd);
};

const removeShareFile = (path: string, directory: string): void => {
  try { fileIo.unlinkSync(path); } catch { /* Cleanup must not replace the export error. */ }
  try { fileIo.rmdirSync(directory); } catch { /* This invocation owns only its cache directory. */ }
};

const saveBytes = async (
  context: common.UIAbilityContext, fileName: string, bytes: Uint8Array,
  isActive: () => boolean,
): Promise<boolean> => {
  if (!isActive()) return false;
  const uris: string[] = await new picker.DocumentViewPicker(context).save({ newFileNames: [fileName] });
  if (uris.length === 0 || !isActive()) return false;
  writeBytes(uris[0], bytes);
  return true;
};

const shareBytes = async (
  context: common.UIAbilityContext, fileName: string, bytes: Uint8Array, utd: string,
  isActive: () => boolean,
): Promise<void> => {
  if (!isActive()) return;
  const directory: string = `${context.cacheDir}/deepread-share-${util.generateRandomUUID(false)}`;
  const path: string = `${directory}/${fileName}`;
  try {
    fileIo.mkdirSync(directory);
    writeBytes(path, bytes);
    if (!isActive()) { removeShareFile(path, directory); return; }
    const data: systemShare.SharedData = new systemShare.SharedData({
      utd, uri: fileUri.getUriFromPath(path), title: fileName,
    });
    const controller: systemShare.ShareController = new systemShare.ShareController(data);
    await controller.show(context, {});
    // show resolves when the sheet opens; recipients may still need to read the file.
  } catch (error) {
    removeShareFile(path, directory);
    throw error instanceof Error ? error : new Error(String(error));
  }
};

export const shareDeepReadText = async (
  context: common.UIAbilityContext, title: string, content: string,
  isActive: () => boolean = (): boolean => true,
): Promise<void> => {
  if (!isActive()) return;
  const data: systemShare.SharedData = new systemShare.SharedData({
    utd: uniformTypeDescriptor.UniformDataType.PLAIN_TEXT, title, content,
  });
  const controller: systemShare.ShareController = new systemShare.ShareController(data);
  await controller.show(context, {});
};

export const saveDeepReadTextFile = (
  context: common.UIAbilityContext, fileName: string, content: string,
  isActive: () => boolean = (): boolean => true,
): Promise<boolean> => saveBytes(context, fileName, util.TextEncoder.create('utf-8').encodeInto(content), isActive);

export const shareDeepReadTextFile = (
  context: common.UIAbilityContext, fileName: string, content: string,
  isActive: () => boolean = (): boolean => true,
): Promise<void> => shareBytes(context, fileName, util.TextEncoder.create('utf-8').encodeInto(content),
  uniformTypeDescriptor.UniformDataType.PLAIN_TEXT, isActive);

export const saveDeepReadMarkdown = (
  context: common.UIAbilityContext, fileName: string, content: string,
  isActive: () => boolean = (): boolean => true,
): Promise<boolean> => saveBytes(context, fileName, util.TextEncoder.create('utf-8').encodeInto(content), isActive);

export const shareDeepReadMarkdown = (
  context: common.UIAbilityContext, fileName: string, content: string,
  isActive: () => boolean = (): boolean => true,
): Promise<void> => shareBytes(context, fileName, util.TextEncoder.create('utf-8').encodeInto(content),
  uniformTypeDescriptor.UniformDataType.MARKDOWN, isActive);

export const saveDeepReadPdf = (
  context: common.UIAbilityContext, fileName: string, bytes: Uint8Array,
  isActive: () => boolean = (): boolean => true,
): Promise<boolean> => saveBytes(context, fileName, bytes, isActive);

export const shareDeepReadPdf = (
  context: common.UIAbilityContext, fileName: string, bytes: Uint8Array,
  isActive: () => boolean = (): boolean => true,
): Promise<void> => shareBytes(context, fileName, bytes, uniformTypeDescriptor.UniformDataType.PDF, isActive);
