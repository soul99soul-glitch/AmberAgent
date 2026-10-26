// Skill metadata and the installed SKILL.md frontmatter format.

// ===== SkillMetadata/SkillScanIssue(SkillManager.kt:285-298;skillDir File → path 串) =====

export interface SkillMetadata {
  name: string;
  description: string;
  compatibility: string | null;
  allowedTools: string[];
  skillDir: string;
}

export interface SkillScanIssue {
  directoryName: string;
  reason: string;
}

export const makeSkillMetadata = (
  name: string, description: string, skillDir: string,
  compatibility: string | null = null, allowedTools: string[] = [],
): SkillMetadata => ({
  name, description, compatibility, allowedTools, skillDir,
});

// ===== SkillFrontmatterParser(SkillManager.kt:300-470 逐字) =====

// :301 — Regex("""\r?\n---(?:\r?\n|$)""");find(content, startIndex=3)
const FRONTMATTER_END_REGEX: RegExp = /\r?\n---(?:\r?\n|$)/;

// :302-318 占位描述集合(逐字)
const PLACEHOLDER_DESCRIPTIONS: string[] = [
  '|', '｜', '-', '--', '---', '.', '...', '…', 'todo', 'tbd', 'none', 'n/a',
  'null', 'description', '描述',
];

// :388-391
const findFrontmatterEnd = (content: string): { first: number; last: number } | null => {
  if (!content.startsWith('---')) return null;
  const m: RegExpExecArray | null = FRONTMATTER_END_REGEX.exec(content.slice(3));
  if (m === null) return null;
  const first: number = 3 + m.index;
  return { first, last: first + m[0].length - 1 };
};

// Kotlin String.lines()/lineSequence():\r\n|\r|\n 三分
const kotlinLines = (s: string): string[] => s.split(/\r\n|\r|\n/);

// :320-336
export const skillParseFrontmatter = (content: string): Record<string, string> => {
  const result: Record<string, string> = {};
  if (!content.startsWith('---')) return result;
  const end = findFrontmatterEnd(content);
  if (end === null) return result;
  const yaml: string = content.substring(3, end.first).trim();
  kotlinLines(yaml).forEach((line: string): void => {
    const colonIdx: number = line.indexOf(':');
    if (colonIdx > 0) {
      const key: string = line.substring(0, colonIdx).trim();
      // removeSurrounding("\"")
      let value: string = line.substring(colonIdx + 1).trim();
      if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        value = value.substring(1, value.length - 1);
      }
      if (key.length > 0 && value.length > 0) {
        result[key] = value;
      }
    }
  });
  return result;
};

// :373-380 — trim('"','\'') = 去首尾引号字符集
export const skillIsPlaceholderDescription = (description: string | null): boolean => {
  const normalized: string = (description ?? '')
    .trim()
    .replace(/^["']+|["']+$/g, '')
    .toLowerCase();
  return normalized.length === 0 || PLACEHOLDER_DESCRIPTIONS.indexOf(normalized) >= 0;
};

// :382-386
export const skillExtractBody = (content: string): string => {
  if (!content.startsWith('---')) return content;
  const end = findFrontmatterEnd(content);
  if (end === null) return content;
  // trimStart('\r','\n')
  return content.substring(end.last + 1).replace(/^[\r\n]+/, '');
};

// :417-431
const cleanDescriptionCandidate = (s: string): string => s
  .trim()
  .replace(/^﻿/, '') // removePrefix("﻿")(BOM,仅去首个)
  .replace(/^#{1,6}\s*/, '')
  .replace(/^[-*+]\s+/, '')
  .replace(/^\d+[.)]\s+/, '')
  .replace(/^\[[ xX]]\s+/, '')
  .replace(/`/g, '')
  .replace(/\*/g, '')
  .replace(/_/g, '')
  .replace(/\s+/g, ' ')
  .trim()
  .replace(/^[|｜]+|[|｜]+$/g, '')
  .trim();

// :433-449
const GENERIC_SKILL_HEADINGS: string[] = [
  'instructions', 'instruction', 'overview', 'description', 'usage', 'workflow',
  'steps', 'skill', 'skill instructions', '说明', '使用说明', '工作流程',
];

const isGenericSkillHeading = (s: string): boolean =>
  GENERIC_SKILL_HEADINGS.indexOf(s.toLowerCase()) >= 0;

// :451-463
const INVOCATION_CUES: string[] = [
  'use when', 'use this skill', 'when the user', '用于', '适用于', '当用户',
  '当需要', '使用场景',
];

const hasInvocationCue = (s: string): boolean => {
  const lower: string = s.toLowerCase();
  return INVOCATION_CUES.some((cue: string): boolean => lower.indexOf(cue) >= 0);
};

// :465-467 — take(maxChars-1).trimEnd() + '…'(JVM char = UTF-16 单元 → slice 同语义)
const takeDescriptionChars = (s: string, maxChars: number): string =>
  s.length <= maxChars ? s : s.slice(0, maxChars - 1).trimEnd() + '…';

// :409-415 — skillName.any { it.code > 127 }(UTF-16 单元 → charCodeAt)
const fallbackDescription = (skillName: string): string => {
  let nonAscii: boolean = false;
  for (let i: number = 0; i < skillName.length; i++) {
    if (skillName.charCodeAt(i) > 127) {
      nonAscii = true;
      break;
    }
  }
  return nonAscii
    ? `用于处理「${skillName}」相关任务。`
    : `Use when the user asks AmberAgent to work with ${skillName}.`;
};

// :393-407
const inferDescription = (content: string, skillName: string): string => {
  const body: string = skillExtractBody(content);
  const candidates: string[] = kotlinLines(body)
    .map(cleanDescriptionCandidate)
    .filter((s: string): boolean => s.length > 0)
    .filter((s: string): boolean => s.toLowerCase() !== skillName.toLowerCase())
    .filter((s: string): boolean => !isGenericSkillHeading(s));
  const preferred: string | undefined =
    candidates.find(hasInvocationCue) ?? (candidates.length > 0 ? candidates[0] : undefined);
  return preferred !== undefined
    ? takeDescriptionChars(preferred, 120)
    : fallbackDescription(skillName);
};

// :368-371
export const skillResolveDescription = (
  content: string, frontmatter: Record<string, string>, skillName: string,
): string => {
  const description: string | undefined = frontmatter['description'];
  return skillIsPlaceholderDescription(description ?? null)
    ? inferDescription(content, skillName)
    : (description as string).trim();
};

// :469
const escapeYamlString = (s: string): string =>
  s.replace(/\\/g, '\\\\').replace(/"/g, '\\"');

// :338-366
export const skillEnsureDescription = (content: string, skillNameHint: string | null = null): string => {
  const frontmatter: Record<string, string> = skillParseFrontmatter(content);
  const fmName: string | undefined = frontmatter['name'];
  const name: string = (fmName !== undefined && fmName.trim().length > 0) ? fmName
    : (skillNameHint !== null && skillNameHint.trim().length > 0) ? skillNameHint
    : 'custom-skill';
  const currentDescription: string | undefined = frontmatter['description'];
  if (!skillIsPlaceholderDescription(currentDescription ?? null)) return content;

  const description: string = inferDescription(content, name);
  const line: string = `description: "${escapeYamlString(description)}"`;
  const end = findFrontmatterEnd(content);
  if (!content.startsWith('---') || end === null) {
    return `---\nname: ${escapeYamlString(name)}\n${line}\n---\n\n${content.trimStart()}`;
  }

  const yaml: string = content.substring(3, end.first).trim();
  const lines: string[] = kotlinLines(yaml);
  // substringBefore(':').trim():无冒号 → 整串
  const keyOf = (l: string): string => {
    const idx: number = l.indexOf(':');
    return (idx < 0 ? l : l.substring(0, idx)).trim();
  };
  const descriptionIndex: number = lines.findIndex((l: string): boolean => keyOf(l) === 'description');
  if (descriptionIndex >= 0) {
    lines[descriptionIndex] = line;
  } else {
    const nameIndex: number = lines.findIndex((l: string): boolean => keyOf(l) === 'name');
    const insertIndex: number = nameIndex >= 0 ? nameIndex + 1 : lines.length;
    lines.splice(insertIndex, 0, line);
  }

  const body: string = content.substring(end.last + 1).replace(/^[\r\n]+/, '');
  return `---\n${lines.join('\n')}\n---\n\n${body}`;
};

