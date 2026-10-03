// Chat document imports use the same upload directory and registry as other
// app-owned attachments. Persist the returned part in history, not its data URL.
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import {
  newId, trackUploadFile, deleteChatFiles, FILE_FOLDERS_UPLOAD,
  makeUIMessage, makeMessageNode, patchConversation,
} from '@amber/chat-domain';
import type { UIMessage, UIMessagePart, UIMessagePartDocument, MessageNode, Conversation } from '@amber/chat-domain';
import { getFilesRepository, getDeleteChatFilesDeps } from './ManagedFileStore.ets';

export const MAX_DOCUMENT_ATTACHMENT_BYTES: number = 10 * 1024 * 1024;

export const documentAttachmentMime = (fileName: string, mime: string): string => {
  const extension: string = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
  if (extension === 'pdf') return 'application/pdf';
  if (extension === 'docx') return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
  if (extension === 'pptx') return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
  if (extension === 'epub') return 'application/epub+zip';
  return mime;
};

const decodeDocument = (url: string): Uint8Array => {
  const comma: number = url.indexOf(',');
  if (comma < 0 || !url.substring(0, comma).toLowerCase().endsWith(';base64')) {
    throw new Error('文件附件不是有效的 base64 数据');
  }
  const payload: string = url.substring(comma + 1);
  // Picker and long-paste documents are unescaped base64. Bound allocation
  // before decoding, with the same 10 MiB limit as the picker.
  if (payload.length > Math.ceil(MAX_DOCUMENT_ATTACHMENT_BYTES / 3) * 4) {
    throw new Error('文件超过 10MB');
  }
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(payload);
  if (bytes.byteLength > MAX_DOCUMENT_ATTACHMENT_BYTES) throw new Error('文件超过 10MB');
  return bytes;
};

export const materializeDocumentAttachment = async (
  document: UIMessagePartDocument,
): Promise<UIMessagePartDocument> => {
  if (!document.url.startsWith('data:')) return document;
  const bytes: Uint8Array = decodeDocument(document.url);
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  const directory: string = `${context.filesDir}/${FILE_FOLDERS_UPLOAD}`;
  if (!fileIo.accessSync(directory)) fileIo.mkdirSync(directory, true);
  const dot: number = document.fileName.lastIndexOf('.');
  const extension: string = dot >= 0 ? document.fileName.substring(dot + 1).toLowerCase() : '';
  const suffix: string = /^[a-z0-9]{1,12}$/.test(extension) ? extension : 'bin';
  const fileName: string = `${newId()}.${suffix}`;
  const path: string = `${directory}/${fileName}`;
  const mime: string = documentAttachmentMime(document.fileName, document.mime);
  try {
    const file: fileIo.File = fileIo.openSync(path,
      fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const buffer: ArrayBuffer = bytes.buffer.slice(
        bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      if (buffer.byteLength > 0) fileIo.writeSync(file.fd, buffer);
    } finally {
      fileIo.closeSync(file);
    }
    await trackUploadFile(getFilesRepository(), {
      relativePath: `${FILE_FOLDERS_UPLOAD}/${fileName}`,
      displayName: document.fileName,
      mimeType: mime,
      sizeBytes: bytes.byteLength,
    });
  } catch (error) {
    // A failed write/registration never publishes a URL pointing at an
    // untracked file. This path was created by this import only.
    if (fileIo.accessSync(path)) fileIo.unlinkSync(path);
    throw error instanceof Error ? error : new Error(String(error));
  }
  const result: UIMessagePartDocument = {
    type: 'document', url: `file://${path}`, fileName: document.fileName,
    mime: mime, metadata: document.metadata,
  };
  return result;
};

export const materializeDocumentParts = async (
  parts: UIMessagePart[],
): Promise<UIMessagePart[]> => {
  const result: UIMessagePart[] = [];
  const createdUris: string[] = [];
  try {
    for (const part of parts) {
      const prepared: UIMessagePart = part.type === 'document' ? await materializeDocumentAttachment(part) : part;
      result.push(prepared);
      if (part.type === 'document' && part.url.startsWith('data:') && prepared.type === 'document') {
        createdUris.push(prepared.url);
      }
    }
  } catch (error) {
    await deleteChatFiles(getDeleteChatFilesDeps(), createdUris);
    throw error instanceof Error ? error : new Error(String(error));
  }
  return result;
};

// Legacy conversation documents are converted at the service's serialized
// load boundary. Include unselected variants; retain message metadata and all
// conversation fields. Already materialized history returns its original ref.
export const materializeConversationDocuments = async (
  conversation: Conversation,
): Promise<Conversation> => {
  const nodes: MessageNode[] = [];
  const createdUris: string[] = [];
  let changed: boolean = false;
  try {
    for (const node of conversation.messageNodes) {
      const messages: UIMessage[] = [];
      let nodeChanged: boolean = false;
      for (const message of node.messages) {
        const hasDataDocument: boolean = message.parts.some((part: UIMessagePart): boolean =>
          part.type === 'document' && part.url.startsWith('data:'));
        if (!hasDataDocument) {
          messages.push(message);
          continue;
        }
        const parts: UIMessagePart[] = await materializeDocumentParts(message.parts);
        for (let index: number = 0; index < parts.length; index++) {
          const original: UIMessagePart = message.parts[index];
          const prepared: UIMessagePart = parts[index];
          if (original.type === 'document' && original.url.startsWith('data:') && prepared.type === 'document') {
            createdUris.push(prepared.url);
          }
        }
        messages.push(makeUIMessage(message.role, parts, message));
        nodeChanged = true;
      }
      nodes.push(nodeChanged ? makeMessageNode(messages, node.selectIndex, node.id) : node);
      if (nodeChanged) changed = true;
    }
  } catch (error) {
    await deleteChatFiles(getDeleteChatFilesDeps(), createdUris);
    throw error instanceof Error ? error : new Error(String(error));
  }
  return changed ? patchConversation(conversation, { messageNodes: nodes }) : conversation;
};
