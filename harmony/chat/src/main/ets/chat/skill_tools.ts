// Skill tool protocol and per-catalog enabled/installed snapshots.
import type { JsonObject, JsonValue } from './json.ts';
import { makeAgentTool } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import type { InflateRawPort } from './zip_archive.ts';
import type { SkillWorkspacePort } from './skill_package.ts';
import { collectWorkspaceSkillFiles } from './skill_package.ts';
import type { SkillMetadata } from './skill_metadata.ts';
import { skillParseFrontmatter, skillExtractBody } from './skill_metadata.ts';
import type { SkillManager } from './skill_library.ts';
import { buildSkillMobileRuntimePrompt } from './skill_mobile_prompt.ts';

// ===== kotlinx 取值语义(jsonPrimitive.content / contentOrNull) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

// .jsonPrimitive.content:缺 → null;primitive → 串;object/array → 抛
const jsonContent = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  throw new Error('Element is not a JsonPrimitive');
};

// .jsonPrimitive.contentOrNull:缺/JsonNull → null;primitive → 串;object/array → null
const jsonContentOrNull = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// ===== createSkillTools(SkillsTools.kt:24-153) =====

export interface CreateSkillToolsDeps {
  // assistant.enabledSkills(:25;createRunTools 时快照)
  enabledSkills: string[];
  // skillManager.listSkills()(:41;createRunTools 时快照)
  allSkills: SkillMetadata[];
  skillManager: SkillManager;
  // updateEnabledSkill(:339-350)→ 当前 assistant enabledSkills RMW(entry 闭包)
  setSkillEnabled: (name: string, enable: boolean) => Promise<void>;
  workspace: SkillWorkspacePort;
  inflateRaw: InflateRawPort;
}

// :321-337 — buildSkillArray(contains_mcp_config = mcp.json 存在性)
const buildSkillArray = (manager: SkillManager, skills: SkillMetadata[]): JsonObject[] =>
  skills.map((skill: SkillMetadata): JsonObject => {
    const obj: JsonObject = {
      name: skill.name,
      description: skill.description,
    };
    if (skill.compatibility !== null && skill.compatibility.trim().length > 0) {
      obj['compatibility'] = skill.compatibility;
    }
    obj['allowed_tools'] = skill.allowedTools as unknown as JsonValue;
    obj['contains_mcp_config'] = manager.fileExists(skill.skillDir + '/mcp.json');
    return obj;
  });

export const createSkillTools = (deps: CreateSkillToolsDeps): AgentTool[] => {
  const available: SkillMetadata[] =
    deps.allSkills.filter((s: SkillMetadata): boolean => deps.enabledSkills.indexOf(s.name) >= 0);
  const installedNames: string[] = deps.allSkills.map((s: SkillMetadata): string => s.name);
  const missingEnabled: string[] = deps.enabledSkills
    .filter((n: string): boolean => installedNames.indexOf(n) < 0)
    .sort();
  const disabled: SkillMetadata[] =
    deps.allSkills.filter((s: SkillMetadata): boolean => deps.enabledSkills.indexOf(s.name) < 0);

  const tools: AgentTool[] = [];

  // :37-83 — skills_list
  tools.push(makeAgentTool({
    name: 'skills_list',
    description: 'List AmberAgent skills and their load status. Use this first when you are ' +
      'unsure which skills are installed, enabled, disabled, or missing.',
    parameters: (): InputSchemaObj => ({ type: 'object', properties: {}, required: null }),
    systemPrompt: (): string => {
      let out: string = '';
      const line = (s: string): void => {
        out += s + '\n';
      };
      line('**Skill library status**');
      line(`Installed skills: ${deps.allSkills.length}. Enabled skills: ${available.length}.`);
      line('If you are unsure which skill is available, call `skills_list` before choosing `use_skill`.');
      if (available.length > 0) {
        line('<available_skills>');
        available.forEach((skill: SkillMetadata): void => {
          line('  <skill>');
          line(`    <name>${skill.name}</name>`);
          line(`    <description>${skill.description}</description>`);
          line('  </skill>');
        });
        line('</available_skills>');
      }
      if (disabled.length > 0) {
        line('Some installed skills are disabled. `use_skill` can only load enabled skills.');
      }
      if (missingEnabled.length > 0) {
        line('Some configured enabled skills are missing from disk. Call `skills_list` for details.');
      }
      return out;
    },
    execute: (): Promise<UIMessagePart[]> => {
      const payload: JsonObject = {
        installed_count: deps.allSkills.length,
        enabled_count: available.length,
        configured_enabled_count: deps.enabledSkills.length,
        available_skills: buildSkillArray(deps.skillManager, available) as unknown as JsonValue,
        disabled_installed_skills: buildSkillArray(deps.skillManager, disabled) as unknown as JsonValue,
        missing_enabled_skills: missingEnabled as unknown as JsonValue,
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  }));

  // :206-248 — skill_validate
  tools.push(makeAgentTool({
    name: 'skill_validate',
    description: 'Validate an installed skill by name or a /workspace skill folder/zip before import.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Installed skill name.' },
        workspace_path: {
          type: 'string',
          description: 'Workspace skill folder, SKILL.md, or zip archive.',
        },
      },
      required: null,
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const name: string | null = jsonContentOrNull(obj['name']);
      const workspacePath: string | null = jsonContentOrNull(obj['workspace_path']);
      let files: Map<string, string>;
      if (name !== null && name.trim().length > 0) {
        files = deps.skillManager.collectFilesFromDirectory(deps.skillManager.getSkillDir(name));
      } else if (workspacePath !== null && workspacePath.trim().length > 0) {
        files = await collectWorkspaceSkillFiles(deps.workspace, workspacePath, deps.inflateRaw);
      } else {
        throw new Error('name or workspace_path is required');
      }
      const skillMd: string = files.get('SKILL.md') ?? '';
      const frontmatter: Record<string, string> = skillParseFrontmatter(skillMd);
      const issues: string[] = [];
      if (skillMd.trim().length === 0) issues.push('缺少 SKILL.md');
      const fmName: string | undefined = frontmatter['name'];
      if (fmName === undefined || fmName.trim().length === 0) issues.push('SKILL.md 缺少 name');
      const fmDesc: string | undefined = frontmatter['description'];
      if (fmDesc === undefined || fmDesc.trim().length === 0) issues.push('SKILL.md 缺少 description');
      const payload: JsonObject = {
        valid: issues.length === 0,
        name: fmName ?? '',
        description: fmDesc ?? '',
        file_count: files.size,
        contains_mcp_config: files.has('mcp.json'),
        issues: issues as unknown as JsonValue,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }));

  // :250-288 — skill_import(needsApproval)
  tools.push(makeAgentTool({
    name: 'skill_import',
    description: 'Import a skill folder, SKILL.md file, or zip archive from /workspace. ' +
      'Imported skills are enabled by default.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        workspace_path: {
          type: 'string',
          description: 'Workspace path to a skill folder, SKILL.md, or zip archive.',
        },
      },
      required: ['workspace_path'],
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const workspacePath: string | null = jsonContentOrNull(obj['workspace_path']);
      if (workspacePath === null) throw new Error('workspace_path is required');
      const files: Map<string, string> =
        await collectWorkspaceSkillFiles(deps.workspace, workspacePath, deps.inflateRaw);
      const skillMd: string | undefined = files.get('SKILL.md');
      if (skillMd === undefined) throw new Error('Skill package does not contain SKILL.md');
      const frontmatter: Record<string, string> = skillParseFrontmatter(skillMd);
      const fmName: string | undefined = frontmatter['name'];
      if (fmName === undefined || fmName.trim().length === 0) {
        throw new Error('SKILL.md missing name');
      }
      const name: string = fmName;
      const fmDesc: string | undefined = frontmatter['description'];
      if (fmDesc === undefined || fmDesc.trim().length === 0) {
        throw new Error('SKILL.md missing description');
      }
      const saved: boolean = deps.skillManager.saveSkillFilesAtomically(name, files);
      if (!saved) throw new Error('Failed to save skill files');
      await deps.setSkillEnabled(name, true);
      const payload: JsonObject = {
        success: true,
        name: name,
        file_count: files.size,
        enabled: true,
        contains_mcp_config: files.has('mcp.json'),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }));

  // :290-319 — skill_enable / skill_disable(needsApproval)
  const makeEnableTool = (enable: boolean): AgentTool => makeAgentTool({
    name: enable ? 'skill_enable' : 'skill_disable',
    description: enable
      ? 'Enable an installed skill for the default AmberAgent.'
      : 'Disable an installed skill for the default AmberAgent.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Skill name.' },
      },
      required: ['name'],
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const name: string | null = jsonContentOrNull(obj['name']);
      if (name === null) throw new Error('name is required');
      await deps.setSkillEnabled(name, enable);
      const payload: JsonObject = {
        success: true,
        name: name,
        enabled: enable,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  });
  tools.push(makeEnableTool(true));
  tools.push(makeEnableTool(false));

  // :90 — available 空 → 不出 use_skill
  if (available.length === 0) return tools;

  // :92-151 — use_skill
  tools.push(makeAgentTool({
    name: 'use_skill',
    description: 'Load and apply a skill to get specialized instructions or capabilities.\n' +
      'Call this tool when the user\'s request matches one of the available skills.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'The name of the skill to use' },
        path: {
          type: 'string',
          description: 'Optional relative path to a file inside the skill directory. ' +
            'Omit to read the default SKILL.md instructions. Only use paths extracted from ' +
            'Markdown links in the SKILL.md content. Do NOT guess or infer paths.',
        },
      },
      required: ['name'],
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const nameRaw: string | null = jsonContent(obj['name']);
      if (nameRaw === null) throw new Error('name is required');
      const name: string = nameRaw;
      if (deps.enabledSkills.indexOf(name) < 0) {
        throw new Error(
          `Skill '${name}' is not enabled. Call skills_list to see installed and enabled skills.`);
      }
      const path: string | null = jsonContentOrNull(obj['path']);
      let content: string;
      if (path === null || path.trim().length === 0) {
        const skillMd: string | null = deps.skillManager.readSkillFileText(name, 'SKILL.md');
        if (skillMd === null) throw new Error(`Skill '${name}' not found`);
        content = skillExtractBody(skillMd);
      } else {
        const target: string | null = deps.skillManager.resolveSkillFile(name, path);
        if (target === null) {
          throw new Error(`Path '${path}' is outside the skill directory`);
        }
        const fileContent: string | null = deps.skillManager.readSkillFileText(name, path);
        if (fileContent === null) {
          const availableFiles: string = deps.skillManager.listSkillFiles(name).join(', ');
          const hint: string = availableFiles.trim().length > 0
            ? `This skill only ships with these files: ${availableFiles}. Do not retry with ` +
              'other paths from SKILL.md links — those reference files were not bundled. ' +
              'Re-read SKILL.md (omit the path argument) and follow its inline instructions directly.'
            : 'This skill ships with only SKILL.md. Re-read it (omit the path argument) and ' +
              'follow its inline instructions without fetching sub-files.';
          throw new Error(`File '${path}' not found in skill '${name}'. ${hint}`);
        }
        content = fileContent;
      }
      return Promise.resolve([{
        type: 'text',
        text: buildSkillMobileRuntimePrompt(name, path, content),
        metadata: null,
      }]);
    },
  }));
  return tools;
};
