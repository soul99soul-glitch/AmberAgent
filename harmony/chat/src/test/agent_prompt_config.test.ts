// agent_prompt_config 测试 — D-128 钉住
// Android 锚点:SubAgentModels/SubAgentDefinitions/AgentPromptConfigRepository/
//   AgentPromptConfigTool(行号见 agent_prompt_config.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  AgentPromptConfigRepository, createAgentPromptConfigTool, SUB_AGENT_BUILT_INS, SUB_AGENT_BUILT_IN_IDS,
  subAgentFindDefinition, subAgentExtractMentions, subAgentApplyOverride, makeSubAgentOverride,
  makeSubAgentRuntimeSetting, makeImagePromptInjectionConfig, DEFAULT_IMAGE_PROMPT_INJECTION,
  DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION, DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT,
} from '../main/ets/chat/agent_prompt_config.ts';
import type {
  PromptConfigFilePort, CouncilSettingStrategy, SubAgentRuntimeSetting,
  SubAgentDefinition, ImagePromptInjectionConfig, AgentPromptSettingsPort,
} from '../main/ets/chat/agent_prompt_config.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

// ===== 内存 PromptConfigFilePort =====

class MemFiles implements PromptConfigFilePort {
  files: Map<string, string> = new Map<string, string>();

  exists(path: string): boolean {
    return this.files.has(path);
  }

  readText(path: string): string {
    const v: string | undefined = this.files.get(path);
    if (v === undefined) throw new Error(`NoSuchFile: ${path}`);
    return v;
  }

  mkdirs(_path: string): void {}

  atomicWrite(path: string, content: string): void {
    this.files.set(path, content);
  }
}

// ===== 测试 council 形状(额外字段保真验证) =====

interface TestSeat {
  seatId: string;
  name: string;
  role: string;
  systemPrompt: string;
  extra: string;
}

interface TestCouncilSetting {
  defaultSeats: TestSeat[];
  marker: string;
}

const testStrategy: CouncilSettingStrategy<TestCouncilSetting, TestSeat> = {
  seatsOf: (s: TestCouncilSetting): TestSeat[] => s.defaultSeats,
  withSeats: (s: TestCouncilSetting, seats: TestSeat[]): TestCouncilSetting =>
    ({ defaultSeats: seats, marker: s.marker }),
  seatIdOf: (seat: TestSeat): string => seat.seatId,
  seatNameOf: (seat: TestSeat): string => seat.name,
  seatRoleOf: (seat: TestSeat): string => seat.role,
  seatPromptOf: (seat: TestSeat): string => seat.systemPrompt,
  withSeatPrompt: (seat: TestSeat, p: string): TestSeat =>
    ({ seatId: seat.seatId, name: seat.name, role: seat.role, systemPrompt: p, extra: seat.extra }),
};

const makeCouncil = (): TestCouncilSetting => ({
  defaultSeats: [
    { seatId: 'supporter', name: '支持者', role: 'support', systemPrompt: '证明可行性', extra: 'E1' },
    { seatId: 'opponent', name: '反对者', role: 'oppose', systemPrompt: '寻找风险', extra: 'E2' },
  ],
  marker: 'keep-me',
});

const makeRepo = (
  files: MemFiles,
): AgentPromptConfigRepository<TestCouncilSetting, TestSeat> =>
  new AgentPromptConfigRepository<TestCouncilSetting, TestSeat>(
    '/files', files, testStrategy);

// ===== SubAgentDefinitions =====

test('builtIns:六件序 + find(id 精确/name 忽略大小写)+ builtInIds', () => {
  assert.deepEqual(SUB_AGENT_BUILT_IN_IDS,
    ['explorer', 'historian', 'oracle', 'designer', 'writer', 'fixer']);
  assert.equal(SUB_AGENT_BUILT_INS.length, 6);
  assert.equal(subAgentFindDefinition('oracle')?.id, 'oracle');
  assert.equal(subAgentFindDefinition('ORACLE')?.id, 'oracle'); // name 忽略大小写
  assert.equal(subAgentFindDefinition('Writer')?.id, 'writer');
  assert.equal(subAgentFindDefinition('nope'), null);
});

test('extractMentions:前置空白/文首门 + 长度降序 + id 边界 + 去重', () => {
  assert.deepEqual(subAgentExtractMentions('看看 @explorer 这个'), ['explorer']);
  assert.deepEqual(subAgentExtractMentions('@oracle 复议一下'), ['oracle']);
  // 邮件样 '@' 前置非空白 → 不触发
  assert.deepEqual(subAgentExtractMentions('foo@bar.com @writer'), ['writer']);
  // id 后随字母 → 不匹配
  assert.deepEqual(subAgentExtractMentions('@explorers 在哪'), []);
  // 去重保序
  assert.deepEqual(subAgentExtractMentions('@fixer 然后 @fixer 再 @writer'), ['fixer', 'writer']);
  // 无 @ / 空 validIds → 空
  assert.deepEqual(subAgentExtractMentions('没有任何提及'), []);
});

test('applyOverride:非 null 字段覆盖;systemPrompt blank 回退内置', () => {
  const base: SubAgentDefinition = SUB_AGENT_BUILT_INS[0];
  const o = makeSubAgentOverride();
  o.maxTurnsOverride = 9;
  o.modelId = 'model-x';
  const next: SubAgentDefinition = subAgentApplyOverride(base, o);
  assert.equal(next.maxTurns, 9);
  assert.equal(next.modelId, 'model-x');
  assert.equal(next.systemPrompt, base.systemPrompt); // null 不覆盖
  o.systemPrompt = '   ';
  const next2: SubAgentDefinition = subAgentApplyOverride(base, o);
  assert.equal(next2.systemPrompt, base.systemPrompt); // blank 回退
  assert.equal(subAgentApplyOverride(base, null), base); // null → 原样
});

// ===== Repository:image =====

test('image:首读建默认文件;render/parse 往返;enabled:false → false', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const cfg: ImagePromptInjectionConfig = await repo.readImageConfig();
  assert.deepEqual(cfg, makeImagePromptInjectionConfig());
  const path: string = repo.imagePromptFilePath();
  const text: string = files.readText(path);
  assert.equal(text.startsWith('# Image Generation Prompt Injection\n\nenabled: true\n'), true);
  assert.equal(text.includes(`## Default prompt\n\n\`\`\`text\n${DEFAULT_IMAGE_PROMPT_INJECTION}\n\`\`\``), true);
  // enabled:false 回读
  files.atomicWrite(path, text.replace('enabled: true', 'enabled: FALSE'));
  const cfg2: ImagePromptInjectionConfig = await repo.readImageConfig();
  assert.equal(cfg2.enabled, false);
  assert.equal(cfg2.negativePrompt, DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION);
});

test('image:writeImageConfig validPrompt 超长错误逐字;effectiveImagePrompt 拼接', async () => {
  const repo = makeRepo(new MemFiles());
  await assert.rejects(
    repo.writeImageConfig({
      enabled: true, defaultPrompt: 'x'.repeat(4001), negativePrompt: '',
    }),
    /image default prompt is too long: 4001 chars, max 4000/);
  // 拼接骨架(:256-264)
  const composed: string = await repo.effectiveImagePrompt('画一只猫');
  assert.equal(composed.startsWith('画一只猫\n\nImage generation defaults to apply unless the user explicitly conflicts:\n'), true);
  assert.equal(composed.includes('\n\nAvoid / de-emphasize:\n'), true);
  // disabled → 原样
  await repo.writeImageConfig({ enabled: false, defaultPrompt: 'D', negativePrompt: 'N' });
  assert.equal(await repo.effectiveImagePrompt('画一只猫'), '画一只猫');
});

// ===== Repository:subagent =====

test('subagent:未知 id 错误逐字;等于内置默认 → override 移除;镜像 markdown 头', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const setting: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
  await assert.rejects(
    repo.writeSubAgentPrompt(setting, 'ghost', 'x'),
    /Unknown built-in subagent id: ghost/);
  // 写入自定义
  const designer: SubAgentDefinition = SUB_AGENT_BUILT_INS[3];
  const [next, result] = await repo.writeSubAgentPrompt(setting, 'designer', '新 designer 提示词');
  assert.equal(result.updatedId, 'designer');
  assert.equal(next.overrides.get('designer')?.systemPrompt, '新 designer 提示词');
  // 镜像 markdown:## designer 段 + name 行 + fence
  const md: string = files.readText(repo.subAgentPromptFilePath());
  assert.equal(md.startsWith('# SubAgent Prompt Overrides\n\n'), true);
  assert.equal(md.includes('## designer\n\nname: Designer\n\n```text\n新 designer 提示词\n```'), true);
  // 未覆盖角色写内置原文(applyOverride 生效)
  assert.equal(md.includes(`## explorer\n\nname: Explorer\n\n\`\`\`text\n${designer.id === 'x' ? '' : ''}`), true);
  // 写回内置默认 → override 移除
  const [reverted] = await repo.writeSubAgentPrompt(next, 'designer', designer.systemPrompt);
  assert.equal(reverted.overrides.has('designer'), false);
});

test('subagent:applySubAgentMarkdownToSetting 从 markdown 回收 overrides', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const setting: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
  // 文件不存在 → 原样
  const same: SubAgentRuntimeSetting = await repo.applySubAgentMarkdownToSetting(setting);
  assert.equal(same.overrides.size, 0);
  await repo.writeSubAgentPrompt(setting, 'fixer', 'fixer 定制');
  const recovered: SubAgentRuntimeSetting = await repo.applySubAgentMarkdownToSetting(setting);
  assert.equal(recovered.overrides.get('fixer')?.systemPrompt, 'fixer 定制');
  // 未知段 id 跳过(find null → fold 原样)
  files.atomicWrite(
    repo.subAgentPromptFilePath(),
    '# SubAgent Prompt Overrides\n\n## ghost\n\n```text\nboo\n```\n');
  const skipped: SubAgentRuntimeSetting = await repo.applySubAgentMarkdownToSetting(setting);
  assert.equal(skipped.overrides.size, 0);
});

// ===== Repository:council =====

test('council:seat 匹配三键(id/role/name 忽略大小写);未知错误逐字;extra 字段零丢失', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const setting: TestCouncilSetting = makeCouncil();
  await assert.rejects(
    repo.writeModelCouncilSeatPrompt(setting, 'ghost', 'x'),
    /Unknown model council seat: ghost/);
  // role 键匹配
  const [next, result] = await repo.writeModelCouncilSeatPrompt(setting, ' SUPPORT ', '新支持者提示');
  assert.equal(result.updatedId, 'supporter');
  assert.equal(next.defaultSeats[0].systemPrompt, '新支持者提示');
  assert.equal(next.defaultSeats[0].extra, 'E1'); // 未触字段保真
  assert.equal(next.marker, 'keep-me');
  assert.equal(next.defaultSeats[1].systemPrompt, '寻找风险'); // 他席不动
  // name 键(中文)匹配
  const [byName] = await repo.writeModelCouncilSeatPrompt(next, '反对者', '新反对提示');
  assert.equal(byName.defaultSeats[1].systemPrompt, '新反对提示');
  // markdown 镜像:name/role 行 + fence
  const md: string = files.readText(repo.modelCouncilPromptFilePath());
  assert.equal(md.includes('## opponent\n\nname: 反对者\nrole: oppose\n\n```text\n新反对提示\n```'), true);
});

test('council:applyModelCouncilMarkdownToSetting 回收;blank 段跳过', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const setting: TestCouncilSetting = makeCouncil();
  const same: TestCouncilSetting = await repo.applyModelCouncilMarkdownToSetting(setting);
  assert.equal(same.defaultSeats[0].systemPrompt, '证明可行性');
  await repo.writeModelCouncilSeatPrompt(setting, 'supporter', '回收验证提示');
  const recovered: TestCouncilSetting = await repo.applyModelCouncilMarkdownToSetting(setting);
  assert.equal(recovered.defaultSeats[0].systemPrompt, '回收验证提示');
  assert.equal(recovered.defaultSeats[0].extra, 'E1');
  assert.equal(recovered.defaultSeats[1].systemPrompt, '寻找风险');
});

// ===== Repository:context compaction + mirrors =====

test('context:首读建默认(## Prompt fence 抽取);写读往返;超长错误逐字', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  const def: string = await repo.readContextCompactionPrompt();
  assert.equal(def, DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT);
  const result = await repo.writeContextCompactionPrompt('  自定义交接格式  ');
  assert.equal(result.updatedId, 'context_compaction');
  assert.equal(await repo.readContextCompactionPrompt(), '自定义交接格式');
  const raw: string = files.readText(repo.contextCompactionPromptFilePath());
  assert.equal(raw.startsWith('# Context Compaction Handoff Prompt\n'), true);
  assert.equal(raw.includes('## Prompt\n\n```text\n自定义交接格式\n```'), true);
  await assert.rejects(
    repo.writeContextCompactionPrompt('x'.repeat(8001)),
    /context compaction prompt is too long: 8001 chars, max 8000/);
});

test('ensureMarkdownMirrors:缺失四件全建;已存在不动', async () => {
  const files: MemFiles = new MemFiles();
  const repo = makeRepo(files);
  await repo.ensureMarkdownMirrors(makeSubAgentRuntimeSetting(), makeCouncil());
  assert.equal(files.exists(repo.imagePromptFilePath()), true);
  assert.equal(files.exists(repo.subAgentPromptFilePath()), true);
  assert.equal(files.exists(repo.modelCouncilPromptFilePath()), true);
  assert.equal(files.exists(repo.contextCompactionPromptFilePath()), true);
  files.atomicWrite(repo.imagePromptFilePath(), 'CUSTOM');
  await repo.ensureMarkdownMirrors(makeSubAgentRuntimeSetting(), makeCouncil());
  assert.equal(files.readText(repo.imagePromptFilePath()), 'CUSTOM');
});

// ===== 工具 =====

class MemSettingsPort implements AgentPromptSettingsPort<TestCouncilSetting> {
  subAgent: SubAgentRuntimeSetting = makeSubAgentRuntimeSetting();
  council: TestCouncilSetting = makeCouncil();

  getSubAgentSetting(): Promise<SubAgentRuntimeSetting> {
    return Promise.resolve(this.subAgent);
  }

  updateSubAgentSetting(next: SubAgentRuntimeSetting): Promise<void> {
    this.subAgent = next;
    return Promise.resolve();
  }

  getCouncilSetting(): Promise<TestCouncilSetting> {
    return Promise.resolve(this.council);
  }

  updateCouncilSetting(next: TestCouncilSetting): Promise<void> {
    this.council = next;
    return Promise.resolve();
  }
}

const makeTool = (
  files: MemFiles, settings: MemSettingsPort,
): AgentTool => createAgentPromptConfigTool<TestCouncilSetting, TestSeat>({
  repository: makeRepo(files),
  settings,
});

const execText = async (t: AgentTool, input: object): Promise<JsonObject_> => {
  const parts: UIMessagePart[] = await t.execute(input as never);
  if (parts[0].type !== 'text') throw new Error('text part');
  return JSON.parse(parts[0].text) as JsonObject_;
};
type JsonObject_ = Record<string, unknown>;

test('tool:标志 + action enum + required 逐字', () => {
  const t: AgentTool = makeTool(new MemFiles(), new MemSettingsPort());
  assert.equal(t.name, 'agent_prompt_config');
  assert.equal(t.needsApproval, true);
  assert.equal(t.allowsAutoApproval, true);
});

test('tool:get all — 四文件建镜像 + payload 键序/路径;target 过滤', async () => {
  const files: MemFiles = new MemFiles();
  const t: AgentTool = makeTool(files, new MemSettingsPort());
  const all = await execText(t, { action: 'get' });
  assert.equal(all['status'], 'ok');
  assert.equal(all['target'], 'all');
  assert.deepEqual(Object.keys(all), ['status', 'target', 'files', 'markdown']);
  const f = all['files'] as JsonObject_;
  assert.deepEqual(Object.keys(f),
    ['image_generation', 'context_compaction', 'subagents', 'model_council']);
  assert.equal(f['image_generation'], '/files/agent_prompts/image-generation.md');
  // target 过滤:只留一件
  const one = await execText(t, { action: 'get', target: 'subagents' });
  assert.deepEqual(Object.keys(one['files'] as JsonObject_), ['subagents']);
});

test('tool:update_image_generation — 部分字段合并 + 严格布尔 + trim', async () => {
  const files: MemFiles = new MemFiles();
  const t: AgentTool = makeTool(files, new MemSettingsPort());
  const r = await execText(t, {
    action: 'update_image_generation', prompt: '  新默认 ', enabled: true,
  });
  assert.deepEqual(r, {
    status: 'ok', updated: 'image_generation', file: '/files/agent_prompts/image-generation.md',
  });
  const repo = makeRepo(files);
  const cfg: ImagePromptInjectionConfig = await repo.readImageConfig();
  assert.equal(cfg.defaultPrompt, '新默认'); // trim 生效
  assert.equal(cfg.enabled, true);
  assert.equal(cfg.negativePrompt, DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION); // 未传保持
  // 'yes' 非严格布尔 → 保持
  await execText(t, { action: 'update_image_generation', enabled: 'yes', prompt: 'P2' });
  const cfg2: ImagePromptInjectionConfig = await repo.readImageConfig();
  assert.equal(cfg2.enabled, true);
  assert.equal(cfg2.defaultPrompt, 'P2');
});

test('tool:update_context_compaction_prompt — 缺 prompt 错误逐字 + 成功', async () => {
  const files: MemFiles = new MemFiles();
  const t: AgentTool = makeTool(files, new MemSettingsPort());
  await assert.rejects(
    t.execute({ action: 'update_context_compaction_prompt' } as never),
    /prompt is required/);
  const r = await execText(t, { action: 'update_context_compaction_prompt', prompt: '交接 V2' });
  assert.deepEqual(r, {
    status: 'ok', updated: 'context_compaction',
    file: '/files/agent_prompts/context-compaction-handoff.md',
  });
});

test('tool:update_subagent_prompt — 必填错误 + 设置同步 + 镜像', async () => {
  const files: MemFiles = new MemFiles();
  const settings: MemSettingsPort = new MemSettingsPort();
  const t: AgentTool = makeTool(files, settings);
  await assert.rejects(
    t.execute({ action: 'update_subagent_prompt', prompt: 'x' } as never),
    /subagent_id is required/);
  await assert.rejects(
    t.execute({ action: 'update_subagent_prompt', subagent_id: 'writer' } as never),
    /prompt is required/);
  const r = await execText(t, {
    action: 'update_subagent_prompt', subagent_id: 'writer', prompt: '写作新规则',
  });
  assert.deepEqual(r, {
    status: 'ok', updated: 'writer', file: '/files/agent_prompts/subagents.md',
  });
  assert.equal(settings.subAgent.overrides.get('writer')?.systemPrompt, '写作新规则');
});

test('tool:update_council_seat_prompt — 必填错误 + 设置同步(extra 保真)', async () => {
  const files: MemFiles = new MemFiles();
  const settings: MemSettingsPort = new MemSettingsPort();
  const t: AgentTool = makeTool(files, settings);
  await assert.rejects(
    t.execute({ action: 'update_council_seat_prompt', prompt: 'x' } as never),
    /seat_id_or_role is required/);
  // judge 不存在于默认两席 → 抛 Unknown
  await assert.rejects(
    t.execute({ action: 'update_council_seat_prompt', seat_id_or_role: 'judge', prompt: '裁判新规' } as never),
    /Unknown model council seat: judge/);
  const ok = await execText(t, {
    action: 'update_council_seat_prompt', seat_id_or_role: 'supporter', prompt: '支持新规',
  });
  assert.deepEqual(ok, {
    status: 'ok', updated: 'supporter', file: '/files/agent_prompts/model-council.md',
  });
  assert.equal(settings.council.defaultSeats[0].systemPrompt, '支持新规');
  assert.equal(settings.council.defaultSeats[0].extra, 'E1');
});

test('tool:sync_markdown_to_settings — payload 逐字 + 两侧回收', async () => {
  const files: MemFiles = new MemFiles();
  const settings: MemSettingsPort = new MemSettingsPort();
  const t: AgentTool = makeTool(files, settings);
  const repo = makeRepo(files);
  await repo.writeSubAgentPrompt(settings.subAgent, 'oracle', 'oracle 定制');
  const r = await execText(t, { action: 'sync_markdown_to_settings' });
  assert.deepEqual(r, {
    status: 'ok', synced: true,
    subagents: '/files/agent_prompts/subagents.md',
    model_council: '/files/agent_prompts/model-council.md',
  });
  assert.equal(settings.subAgent.overrides.get('oracle')?.systemPrompt, 'oracle 定制');
});

test('tool:未知 action 错误逐字', async () => {
  const t: AgentTool = makeTool(new MemFiles(), new MemSettingsPort());
  await assert.rejects(t.execute({ action: 'boom' } as never), /Unsupported action: boom/);
});
