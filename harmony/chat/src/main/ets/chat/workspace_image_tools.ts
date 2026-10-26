import type { JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import type { ArtifactToolTracker } from './workspace_artifact_tool.ts';
import { inputLimit, integerProp, stringProp, textJson } from './workspace_artifact_tool.ts';
import { PosixWorkspaceManager, toolInputInt, toolInputRequiredString, toolInputString } from './workspace.ts';

export const MAX_IMAGE_BYTES: number = 64 * 1024 * 1024;

export interface ArtifactImageInfo {
  width: number;
  height: number;
  mimeType: string; // BitmapFactory.Options.outMimeType;解码失败端口抛错
  // ExifInterface TAG_ORIENTATION;读不到 → ORIENTATION_UNDEFINED = 0
  exifOrientation: number;
}

export interface ArtifactImagePort {
  info(bytes: Uint8Array): Promise<ArtifactImageInfo>;
  // decode → (targetWidth/Height 非 null 时缩放)→ encode(format, quality);
  // 解码失败 → 抛(域层 image_info 前置 info 调用转成 'Unable to decode' 文案)
  convert(
    bytes: Uint8Array, targetWidth: number | null, targetHeight: number | null,
    format: string, quality: number,
  ): Promise<Uint8Array>;
}

// ===== 图片缩放(:639-645 逐字) =====

export interface ArtifactScaleResult {
  width: number;
  height: number;
  scaled: boolean; // false → 原尺寸(Android 返回原 bitmap)
}

export const artifactScaleDims = (
  width: number, height: number, maxWidth: number | null, maxHeight: number | null,
): ArtifactScaleResult => {
  const widthLimit: number = maxWidth !== null && maxWidth > 0 ? maxWidth : width;
  const heightLimit: number = maxHeight !== null && maxHeight > 0 ? maxHeight : height;
  const scale: number = Math.min(widthLimit / width, heightLimit / height, 1);
  if (scale >= 1) return { width, height, scaled: false };
  return {
    width: Math.floor(width * scale),
    height: Math.floor(height * scale),
    scaled: true,
  };
};

export interface WorkspaceImageToolsDeps {
  workspaceManager: PosixWorkspaceManager;
  image: ArtifactImagePort;
}

export const createWorkspaceImageTools = (deps: WorkspaceImageToolsDeps, track: ArtifactToolTracker): AgentTool[] => {
  const mgr: PosixWorkspaceManager = deps.workspaceManager;

  return [
    // :313-338 — image_info
    makeAgentTool({
      name: 'image_info',
      description: 'Read basic image metadata from a /workspace image.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Image path under /workspace.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('image_info', '读取图片信息', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_IMAGE_BYTES);
            const info: ArtifactImageInfo = await deps.image.info(bytes);
            return textJson({
              path,
              width: info.width,
              height: info.height,
              mime_type: info.mimeType,
              size_bytes: bytes.length,
              exif_orientation: info.exifOrientation,
            });
          }),
    }),
    // :340-385 — image_convert
    makeAgentTool({
      name: 'image_convert',
      description: 'Convert or resize a /workspace image to png, jpg, or webp.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Input image path under /workspace.'),
          destination_path: stringProp('Output path under /workspace.'),
          format: stringProp('png, jpg, or webp. Defaults from destination extension or png.'),
          max_width: integerProp('Optional maximum width.'),
          max_height: integerProp('Optional maximum height.'),
          quality: integerProp('Output quality 1-100 for jpg/webp. Defaults to 90.'),
        },
        required: ['path', 'destination_path'],
      }),
      needsApproval: true,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('image_convert', '转换图片', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const source: string = toolInputRequiredString(input, 'path');
            const destination: string = toolInputRequiredString(input, 'destination_path');
            const sourceBytes: Uint8Array = await mgr.readBytes(source);
            // decode 失败 → 'Unable to decode image: X'(:361);info 前置拿尺寸
            let info: ArtifactImageInfo;
            try {
              info = await deps.image.info(sourceBytes);
            } catch (_e) {
              throw new Error(`Unable to decode image: ${source}`);
            }
            const scale: ArtifactScaleResult = artifactScaleDims(
              info.width, info.height,
              toolInputInt(input, 'max_width'), toolInputInt(input, 'max_height'));
            const rawFormat: string | null = toolInputString(input, 'format');
            const format: string = (rawFormat !== null
              ? rawFormat
              : destination.indexOf('.') >= 0
                ? destination.substring(destination.lastIndexOf('.') + 1)
                : 'png').toLowerCase();
            const quality: number = inputLimit(input, 'quality', 90, 100);
            const bytes: Uint8Array = await deps.image.convert(
              sourceBytes, scale.scaled ? scale.width : null,
              scale.scaled ? scale.height : null, format, quality);
            const mime: string = format === 'jpg' || format === 'jpeg'
              ? 'image/jpeg'
              : format === 'webp' ? 'image/webp' : 'image/png';
            const entry = await mgr.writeBytes(destination, bytes, mime);
            return textJson({
              path: entry.path,
              mime_type: mime,
              size_bytes: bytes.length,
            });
          }),
    }),
    // :387-406 — ocr_image(stage1 桩逐字)
    makeAgentTool({
      name: 'ocr_image',
      description: 'OCR a /workspace image. Stage 1 reports runtime availability; ' +
        'use a VLM model if OCR runtime is unavailable.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Image path under /workspace.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('ocr_image', '图片 OCR', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => textJson({
            path: toolInputRequiredString(input, 'path'),
            status: 'unavailable',
            runtime: 'stage1-no-local-ocr',
            message: 'Local OCR is not bundled yet. Use image_info plus a configured ' +
              'remote multimodal/VLM model for OCR in this build.',
          })),
    })
  ];
};
