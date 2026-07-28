// Assistant 纯逻辑子集 — 只含 transformer 管线所需字段
// Android 基准: core/model/Assistant.kt(messageTemplate/regexes)+ AssistantRegex
// 完整 Assistant(温度/tools/memory 等)随 Chat MVP 后续切片引入。

import { newId } from './ids.ts';

// 对应 Android AssistantAffectScope(USER/ASSISTANT)
export type AssistantAffectScope = 'user' | 'assistant';

// 对应 Android AssistantRegex
export interface AssistantRegex {
  id: string;
  enabled: boolean;
  findRegex: string;
  replaceString: string;
  affectingScope: AssistantAffectScope[];
  visualOnly: boolean;
}

export const makeAssistantRegex = (opts: Partial<AssistantRegex> = {}): AssistantRegex => ({
  id: opts.id ?? newId(),
  enabled: opts.enabled ?? true,
  findRegex: opts.findRegex ?? '',
  replaceString: opts.replaceString ?? '',
  affectingScope: opts.affectingScope ?? ['user', 'assistant'],
  visualOnly: opts.visualOnly ?? false,
});

export interface Assistant {
  id: string;
  name: string;
  messageTemplate: string | null;   // Android: assistants[].messageTemplate
  regexes: AssistantRegex[];
}

export const makeAssistant = (opts: Partial<Assistant> = {}): Assistant => ({
  id: opts.id ?? newId(),
  name: opts.name ?? 'assistant',
  messageTemplate: opts.messageTemplate ?? null,
  regexes: opts.regexes ?? [],
});
