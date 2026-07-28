// EntryLocalToolPorts — D-061 本地工具平台 Port 实现
//   - ClipboardPort:pasteboard(Android readClipboardText/writeClipboardText)
//   - sha256HexUtf8:CryptoArchitectureKit SHA-256(MessageDigest 等价;
//     deep_read_open topicId 用,工具内 take(24))
// 失败路径:hilog.warn 非静默(章程),readText 空剪贴板/异常 → ''
import { pasteboard } from '@kit.BasicServicesKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { ClipboardPort } from '@amber/chat-domain';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'EntryLocalToolPorts';

export const createEntryClipboardPort = (): ClipboardPort => ({
  readText: (): Promise<string> => {
    try {
      const board = pasteboard.getSystemPasteboard();
      if (!board.hasDataSync()) return Promise.resolve('');
      const text: string = board.getDataSync().getPrimaryText() ?? '';
      return Promise.resolve(text);
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'clipboard read failed: %{public}s',
        (e as Error).message);
      return Promise.resolve('');
    }
  },
  writeText: (text: string): Promise<void> => {
    const board = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
    return Promise.resolve();
  },
});

// MessageDigest.getInstance("SHA-256").digest(utf8) → 全小写 hex
export const sha256HexUtf8 = (input: string): string => {
  const md = cryptoFramework.createMd('SHA256');
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(input);
  md.updateSync({ data: bytes });
  const out = md.digestSync();
  let hex: string = '';
  for (const b of out.data) {
    hex += b.toString(16).padStart(2, '0');
  }
  return hex;
};
