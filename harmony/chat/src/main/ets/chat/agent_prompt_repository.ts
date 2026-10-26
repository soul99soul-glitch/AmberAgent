// 四个提示词文件的唯一仓库 owner；文件锁不覆盖工具层的设置 KV 更新。
import { makeSubAgentOverride, subAgentFindDefinition } from './subagent_definitions.ts';
import type { SubAgentDefinition, SubAgentOverride, SubAgentRuntimeSetting } from './subagent_definitions.ts';
import {
  makeImagePromptInjectionConfig, DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT,
  applyImagePrompt, parseImageConfig, renderImageConfig, renderSubAgentPrompts,
  renderModelCouncilPrompts, renderContextCompactionPrompt, extractFencedSection, parseIdPromptSections,
} from './agent_prompt_codec.ts';
import type { ImagePromptInjectionConfig, CouncilSettingStrategy } from './agent_prompt_codec.ts';

const DIRECTORY_NAME: string = 'agent_prompts';
const IMAGE_PROMPT_FILE: string = 'image-generation.md';
const SUB_AGENT_PROMPT_FILE: string = 'subagents.md';
const MODEL_COUNCIL_PROMPT_FILE: string = 'model-council.md';
const CONTEXT_COMPACTION_PROMPT_FILE: string = 'context-compaction-handoff.md';

const MAX_IMAGE_PROMPT_CHARS: number = 4000;
const MAX_IMAGE_NEGATIVE_PROMPT_CHARS: number = 4000;
const MAX_SUB_AGENT_PROMPT_CHARS: number = 8000;
const MAX_COUNCIL_PROMPT_CHARS: number = 2000;
const MAX_CONTEXT_COMPACTION_PROMPT_CHARS: number = 8000;
const MAX_MARKDOWN_FILE_CHARS: number = 80000;

export interface PromptConfigFilePort {
  exists(path: string): boolean;
  // File.readText():不存在 → 抛
  readText(path: string): string;
  mkdirs(path: string): void;
  atomicWrite(path: string, content: string): void;
}

export interface PromptConfigWriteResult {
  file: string;
  updatedId: string;
}

// SubAgentOverride() 全空判定(overrides - id 移除判据,Repository.kt:105)
const isEmptyOverride = (o: SubAgentOverride): boolean =>
  o.systemPrompt === null && o.modelId === null && o.temperature === null &&
  o.reasoningLevel === null && o.maxTurnsOverride === null &&
  o.timeoutMsOverride === null && o.outputBudgetOverride === null;

class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

export class AgentPromptConfigRepository<Setting, Seat> {
  private readonly files: PromptConfigFilePort;
  private readonly council: CouncilSettingStrategy<Setting, Seat>;
  private readonly mutex: AsyncMutex = new AsyncMutex();
  // context.filesDir/agent_prompts(directory getter :55-56 每次 mkdirs)
  private readonly directory: string;

  constructor(
    filesDir: string,
    files: PromptConfigFilePort,
    council: CouncilSettingStrategy<Setting, Seat>,
  ) {
    this.directory = `${filesDir}/${DIRECTORY_NAME}`;
    this.files = files;
    this.council = council;
  }

  private ensureDirectory(): string {
    this.files.mkdirs(this.directory);
    return this.directory;
  }

  // :58-68 四路径(取路径即 mkdirs,Android directory getter 语义)
  imagePromptFilePath(): string {
    return `${this.ensureDirectory()}/${IMAGE_PROMPT_FILE}`;
  }

  subAgentPromptFilePath(): string {
    return `${this.ensureDirectory()}/${SUB_AGENT_PROMPT_FILE}`;
  }

  modelCouncilPromptFilePath(): string {
    return `${this.ensureDirectory()}/${MODEL_COUNCIL_PROMPT_FILE}`;
  }

  contextCompactionPromptFilePath(): string {
    return `${this.ensureDirectory()}/${CONTEXT_COMPACTION_PROMPT_FILE}`;
  }

  // File.readText 直通(工具 get action 读原文用)
  readFileText(path: string): string {
    return this.files.readText(path);
  }

  // :70-72
  async effectiveImagePrompt(userPrompt: string): Promise<string> {
    return applyImagePrompt(userPrompt, this.readImageConfigBlocking());
  }

  // :74-76
  async readImageConfig(): Promise<ImagePromptInjectionConfig> {
    return this.readImageConfigBlocking();
  }

  // :78-91
  async writeImageConfig(config: ImagePromptInjectionConfig): Promise<PromptConfigWriteResult> {
    const next: ImagePromptInjectionConfig = {
      enabled: config.enabled,
      defaultPrompt: this.validPrompt(
        config.defaultPrompt, MAX_IMAGE_PROMPT_CHARS, 'image default prompt'),
      negativePrompt: this.validPrompt(
        config.negativePrompt, MAX_IMAGE_NEGATIVE_PROMPT_CHARS, 'image negative prompt'),
    };
    return this.mutex.withLock(async (): Promise<PromptConfigWriteResult> => {
      this.writeTextAtomically(this.imagePromptFilePath(), renderImageConfig(next));
      return { file: this.imagePromptFilePath(), updatedId: 'image_generation' };
    });
  }

  // :93-115 — 返回 [nextSetting, result](Pair 逐字)
  async writeSubAgentPrompt(
    setting: SubAgentRuntimeSetting, subAgentId: string, prompt: string,
  ): Promise<[SubAgentRuntimeSetting, PromptConfigWriteResult]> {
    const builtIn: SubAgentDefinition | null = subAgentFindDefinition(subAgentId);
    if (builtIn === null) throw new Error(`Unknown built-in subagent id: ${subAgentId}`);
    const trimmed: string = this.validPrompt(prompt, MAX_SUB_AGENT_PROMPT_CHARS, 'subagent prompt');
    const current: SubAgentOverride = setting.overrides.get(builtIn.id) ?? makeSubAgentOverride();
    const promptOverride: string | null =
      trimmed.length > 0 && trimmed !== builtIn.systemPrompt.trim() ? trimmed : null;
    const nextOverride: SubAgentOverride = {
      systemPrompt: promptOverride,
      modelId: current.modelId,
      temperature: current.temperature,
      reasoningLevel: current.reasoningLevel,
      maxTurnsOverride: current.maxTurnsOverride,
      timeoutMsOverride: current.timeoutMsOverride,
      outputBudgetOverride: current.outputBudgetOverride,
    };
    const nextOverrides: Map<string, SubAgentOverride> = new Map(setting.overrides);
    if (isEmptyOverride(nextOverride)) {
      nextOverrides.delete(builtIn.id);
    } else {
      nextOverrides.set(builtIn.id, nextOverride);
    }
    const copied: SubAgentRuntimeSetting = this.copySetting(setting);
    const nextSetting: SubAgentRuntimeSetting = {
      enabled: copied.enabled,
      mode: copied.mode,
      allowDynamicSubAgents: copied.allowDynamicSubAgents,
      maxConcurrentRuns: copied.maxConcurrentRuns,
      timeoutMs: copied.timeoutMs,
      maxTurns: copied.maxTurns,
      outputBudgetChars: copied.outputBudgetChars,
      overrides: nextOverrides,
      customDefinitions: copied.customDefinitions,
    };
    await this.mutex.withLock(async (): Promise<void> => {
      this.writeSubAgentMarkdownBlocking(nextSetting);
    });
    return [nextSetting, { file: this.subAgentPromptFilePath(), updatedId: builtIn.id }];
  }

  // :117-121
  async writeSubAgentMarkdown(setting: SubAgentRuntimeSetting): Promise<string> {
    return this.mutex.withLock(async (): Promise<string> => {
      return this.writeSubAgentMarkdownBlocking(setting);
    });
  }

  // :123-141
  async applySubAgentMarkdownToSetting(
    setting: SubAgentRuntimeSetting,
  ): Promise<SubAgentRuntimeSetting> {
    if (!this.files.exists(this.subAgentPromptFilePath())) return setting;
    const prompts: Map<string, string> =
      parseIdPromptSections(this.files.readText(this.subAgentPromptFilePath()));
    const next: SubAgentRuntimeSetting = this.copySetting(setting);
    prompts.forEach((prompt: string, key: string): void => {
      const builtIn: SubAgentDefinition | null = subAgentFindDefinition(key);
      if (builtIn === null) return;
      const valid: string = this.validPrompt(prompt, MAX_SUB_AGENT_PROMPT_CHARS, 'subagent prompt');
      const cur: SubAgentOverride = next.overrides.get(builtIn.id) ?? makeSubAgentOverride();
      const promptOverride: string | null =
        valid.length > 0 && valid !== builtIn.systemPrompt.trim() ? valid : null;
      const nextOverride: SubAgentOverride = {
        systemPrompt: promptOverride,
        modelId: cur.modelId,
        temperature: cur.temperature,
        reasoningLevel: cur.reasoningLevel,
        maxTurnsOverride: cur.maxTurnsOverride,
        timeoutMsOverride: cur.timeoutMsOverride,
        outputBudgetOverride: cur.outputBudgetOverride,
      };
      if (isEmptyOverride(nextOverride)) {
        next.overrides.delete(builtIn.id);
      } else {
        next.overrides.set(builtIn.id, nextOverride);
      }
    });
    return next;
  }

  // :143-165
  async writeModelCouncilSeatPrompt(
    setting: Setting, seatKey: string, prompt: string,
  ): Promise<[Setting, PromptConfigWriteResult]> {
    const normalizedKey: string = seatKey.trim();
    const seat: Seat | undefined = this.council.seatsOf(setting).find(
      (s: Seat): boolean => this.seatMatches(s, normalizedKey));
    if (seat === undefined) throw new Error(`Unknown model council seat: ${seatKey}`);
    const trimmed: string = this.validPrompt(prompt, MAX_COUNCIL_PROMPT_CHARS, 'model council prompt');
    const seatId: string = this.council.seatIdOf(seat);
    const nextSetting: Setting = this.council.withSeats(
      setting,
      this.council.seatsOf(setting).map((current: Seat): Seat =>
        this.council.seatIdOf(current) === seatId
          ? this.council.withSeatPrompt(current, trimmed)
          : current));
    await this.mutex.withLock(async (): Promise<void> => {
      this.writeModelCouncilMarkdownBlocking(nextSetting);
    });
    return [nextSetting, { file: this.modelCouncilPromptFilePath(), updatedId: seatId }];
  }

  // :167-172
  async writeModelCouncilMarkdown(setting: Setting): Promise<string> {
    return this.mutex.withLock(async (): Promise<string> => {
      return this.writeModelCouncilMarkdownBlocking(setting);
    });
  }

  // :174-176
  async readContextCompactionPrompt(): Promise<string> {
    return this.readContextCompactionPromptBlocking();
  }

  // :178-185
  async writeContextCompactionPrompt(prompt: string): Promise<PromptConfigWriteResult> {
    const trimmed: string = this.validPrompt(
      prompt, MAX_CONTEXT_COMPACTION_PROMPT_CHARS, 'context compaction prompt');
    return this.mutex.withLock(async (): Promise<PromptConfigWriteResult> => {
      this.writeTextAtomically(
        this.contextCompactionPromptFilePath(), renderContextCompactionPrompt(trimmed));
      return { file: this.contextCompactionPromptFilePath(), updatedId: 'context_compaction' };
    });
  }

  // :187-203
  async applyModelCouncilMarkdownToSetting(setting: Setting): Promise<Setting> {
    if (!this.files.exists(this.modelCouncilPromptFilePath())) return setting;
    const prompts: Map<string, string> =
      parseIdPromptSections(this.files.readText(this.modelCouncilPromptFilePath()));
    const seats: Seat[] = this.council.seatsOf(setting).map((seat: Seat): Seat => {
      let found: string | null = null;
      prompts.forEach((value: string, key: string): void => {
        if (found === null && this.seatMatches(seat, key)) found = value;
      });
      if (found === null || (found as string).trim().length === 0) return seat;
      return this.council.withSeatPrompt(
        seat, this.validPrompt(found, MAX_COUNCIL_PROMPT_CHARS, 'model council prompt'));
    });
    return this.council.withSeats(setting, seats);
  }

  // :205-221
  async ensureMarkdownMirrors(subAgent: SubAgentRuntimeSetting, council: Setting): Promise<void> {
    return this.mutex.withLock(async (): Promise<void> => {
      if (!this.files.exists(this.subAgentPromptFilePath())) {
        this.writeSubAgentMarkdownBlocking(subAgent);
      }
      if (!this.files.exists(this.modelCouncilPromptFilePath())) {
        this.writeModelCouncilMarkdownBlocking(council);
      }
      if (!this.files.exists(this.imagePromptFilePath())) {
        this.writeTextAtomically(
          this.imagePromptFilePath(), renderImageConfig(makeImagePromptInjectionConfig()));
      }
      if (!this.files.exists(this.contextCompactionPromptFilePath())) {
        this.writeTextAtomically(
          this.contextCompactionPromptFilePath(),
          renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT));
      }
    });
  }

  // :223-228
  private readImageConfigBlocking(): ImagePromptInjectionConfig {
    if (!this.files.exists(this.imagePromptFilePath())) {
      this.writeTextAtomically(
        this.imagePromptFilePath(), renderImageConfig(makeImagePromptInjectionConfig()));
    }
    return parseImageConfig(this.files.readText(this.imagePromptFilePath()));
  }

  // :230-238
  private writeSubAgentMarkdownBlocking(setting: SubAgentRuntimeSetting): string {
    const path: string = this.subAgentPromptFilePath();
    this.writeTextAtomically(path, renderSubAgentPrompts(setting));
    return path;
  }

  private writeModelCouncilMarkdownBlocking(setting: Setting): string {
    const path: string = this.modelCouncilPromptFilePath();
    this.writeTextAtomically(path, renderModelCouncilPrompts(setting, this.council));
    return path;
  }

  // :240-248
  private readContextCompactionPromptBlocking(): string {
    if (!this.files.exists(this.contextCompactionPromptFilePath())) {
      this.writeTextAtomically(
        this.contextCompactionPromptFilePath(),
        renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT));
    }
    const extracted: string = extractFencedSection(
      this.files.readText(this.contextCompactionPromptFilePath()), 'Prompt');
    return extracted.trim().length === 0 ? DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT : extracted;
  }

  // :417-421 — seatId/role/name 任一忽略大小写相等
  private seatMatches(seat: Seat, key: string): boolean {
    const k: string = key.toLowerCase();
    return this.council.seatIdOf(seat).toLowerCase() === k
      || this.council.seatRoleOf(seat).toLowerCase() === k
      || this.council.seatNameOf(seat).toLowerCase() === k;
  }

  // :425-431
  private validPrompt(input: string, maxChars: number, label: string): string {
    const trimmed: string = input.trim();
    if (trimmed.length > maxChars) {
      throw new Error(`${label} is too long: ${trimmed.length} chars, max ${maxChars}`);
    }
    return trimmed;
  }

  // :433-444 — 体量 require 在域层逐字;原子落盘由端口承载
  private writeTextAtomically(path: string, text: string): void {
    if (text.length > MAX_MARKDOWN_FILE_CHARS) {
      throw new Error(
        `Prompt config file is too large: ${text.length} chars, max ${MAX_MARKDOWN_FILE_CHARS}`);
    }
    this.files.atomicWrite(path, text);
  }

  // setting.copy(overrides = X) 辅助(全字段显式拷贝)
  private copySetting(setting: SubAgentRuntimeSetting): SubAgentRuntimeSetting {
    return {
      enabled: setting.enabled,
      mode: setting.mode,
      allowDynamicSubAgents: setting.allowDynamicSubAgents,
      maxConcurrentRuns: setting.maxConcurrentRuns,
      timeoutMs: setting.timeoutMs,
      maxTurns: setting.maxTurns,
      outputBudgetChars: setting.outputBudgetChars,
      overrides: new Map<string, SubAgentOverride>(setting.overrides),
      customDefinitions: setting.customDefinitions,
    };
  }
}
