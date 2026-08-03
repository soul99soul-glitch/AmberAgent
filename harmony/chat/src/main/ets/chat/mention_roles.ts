// mention_roles — @mention 角色列表纯逻辑(ai/MentionRoles.kt:66 行)
//
// 构建 @mention 候选角色列表(subagent 内置角色 + 可选 council 入口)+
// 按查询过滤。供 MentionPanel UI 渲染。
//
// 纯逻辑:零 UI/零 IO,可单测。

import type { SubAgentDefinition } from './agent_prompt_config.ts';
import { SUB_AGENT_BUILT_INS } from './agent_prompt_config.ts';

export type MentionRoleKind = 'subagent' | 'council';

export interface MentionRoleItem {
  id: string;
  name: string;
  description: string;
  kind: MentionRoleKind;
}

// buildMentionRoleItems(MentionRoles.kt:24-55)
//   subAgentEnabled + councilEnabled 门控;subAgentMode=smart_dynamic 时不列内置角色
export const buildMentionRoleItems = (
  subAgentEnabled: boolean,
  councilEnabled: boolean,
  subAgentMode: string = 'roster',
  customSubAgents: SubAgentDefinition[] = [],
  builtIns: SubAgentDefinition[] = SUB_AGENT_BUILT_INS,
): MentionRoleItem[] => {
  const items: MentionRoleItem[] = [];
  if (subAgentEnabled) {
    if (subAgentMode !== 'smart_dynamic') {
      for (const def of builtIns) {
        items.push({
          id: def.id,
          name: def.name,
          description: def.description,
          kind: 'subagent',
        });
      }
    }
    for (const def of customSubAgents) {
      items.push({
        id: def.id,
        name: def.name,
        description: def.description,
        kind: 'subagent',
      });
    }
  }
  if (councilEnabled) {
    items.push({
      id: 'council',
      name: 'Model Council',
      description: '召集多模型议会进行合议',
      kind: 'council',
    });
  }
  return items;
};

// filterMentionRoleItems(MentionRoles.kt:57-65)
//   按 id/name/description 大小写不敏感子串匹配
export const filterMentionRoleItems = (
  items: MentionRoleItem[],
  query: string,
): MentionRoleItem[] => {
  if (query.trim().length === 0) return items;
  const q: string = query.trim().toLowerCase();
  return items.filter((item: MentionRoleItem): boolean =>
    item.id.toLowerCase().includes(q) ||
    item.name.toLowerCase().includes(q) ||
    item.description.toLowerCase().includes(q),
  );
};

// detectMentionContext(ChatInputComposers.kt:909-933)
//   从 text 的 cursor 位置往回扫,遇到 @ 且前面是行首或空白 → 返回 MentionContext
//   遇到空格先于 @ → null(防止匹配邮箱等)
export interface MentionContext {
  atIndex: number;   // @ 字符的位置
  query: string;     // @ 后到 cursor 的文本
}

export const detectMentionContext = (
  text: string,
  cursor: number,
): MentionContext | null => {
  if (cursor <= 0 || cursor > text.length) return null;
  const before: string = text.substring(0, cursor);
  // 从 cursor 往回找 @
  let i: number = before.length - 1;
  let queryChars: string = '';
  while (i >= 0) {
    const ch: string = before[i];
    if (ch === '@') {
      // @ 前面必须是行首或空白
      if (i === 0 || before[i - 1] === ' ' || before[i - 1] === '\n' || before[i - 1] === '\t') {
        return { atIndex: i, query: queryChars };
      }
      return null; // @ 在词中间(如邮箱)
    }
    if (ch === ' ' || ch === '\n' || ch === '\t') return null; // 空格先于 @
    queryChars = ch + queryChars;
    i--;
  }
  return null;
};

// replaceMention(ChatInputComposers.kt:928-933)
//   将 @query 替换为 @roleId + 空格
export const replaceMention = (
  text: string,
  ctx: MentionContext,
  roleId: string,
): string => {
  const before: string = text.substring(0, ctx.atIndex);
  const after: string = text.substring(ctx.atIndex + 1 + ctx.query.length);
  return `${before}@${roleId} ${after}`;
};
