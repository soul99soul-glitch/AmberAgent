// TtsEngine — 系统 TTS 接入层
//
// 平台事实:当前 command-line-tools SDK(ets/kits 47 个 kit)不含
//   CoreSpeechKit / @ohos.speech 任何语音合成 API(已核对 d.ts)。
//   因此 speakText 诚实返回 false(调用方 toast 降级),不伪造合成。
//   保留 API 形状:SDK 升级后在此单点接入 createEngine/speak。

import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'Amber.Tts';

// 朗读;当前 SDK 无语音合成能力 → 恒 false。
export const speakText = async (text: string): Promise<boolean> => {
  hilog.warn(DOMAIN, TAG, 'TTS unavailable: SDK lacks CoreSpeechKit (text=%{public}d chars)', text.length);
  return false;
};

export const stopSpeaking = (): void => {
  // 无引擎 → 无操作
};
