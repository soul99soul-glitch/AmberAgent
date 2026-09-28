import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { canonicalRecipeJSON, decodeRecipe, normalizeWorkspacePath, recipeEnvelope, validateRecipe } from '@amber/chat-domain';
import type {
  AgentTool, InstalledRecipe, PosixWorkspaceManager, RecipeDescriptor, RecipeImportPreview,
  RecipeIssue, RecipeManifest, RecipeStore,
} from '@amber/chat-domain';
import { sharedKvMutex, writeTextFileAtomic } from './AtomicJsonFile';

class RecipeStoreError extends Error {
  public readonly code: string;
  constructor(code: string, message: string = code) { super(message); this.code = code; }
}

const requireName = (name: string): void => {
  const match: RegExpMatchArray | null = name.match(/^[a-z][a-z0-9_]{1,31}$/);
  if (match === null || match[0] !== name) throw new RecipeStoreError('invalid_recipe_name');
};

const descriptorFor = (manifest: RecipeManifest): RecipeDescriptor => {
  const canonicalJSON: string = canonicalRecipeJSON(manifest);
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(canonicalJSON);
  const length: Uint8Array = new Uint8Array(8);
  let remaining: number = bytes.byteLength;
  for (let index: number = 7; index >= 0; index--) {
    length[index] = remaining % 256; remaining = Math.floor(remaining / 256);
  }
  const md = cryptoFramework.createMd('SHA256');
  md.updateSync({ data: util.TextEncoder.create('utf-8').encodeInto('amber.recipe.package.v1\0') });
  md.updateSync({ data: length });
  md.updateSync({ data: bytes });
  const digest = md.digestSync();
  let hash: string = '';
  for (const byte of digest.data) hash += byte.toString(16).padStart(2, '0');
  return { hash, canonicalJSON, manifest };
};

class EntryRecipeStore implements RecipeStore {
  private readonly root: string;
  private readonly workspace: PosixWorkspaceManager;
  constructor(root: string, workspace: PosixWorkspaceManager) {
    this.root = root;
    this.workspace = workspace;
  }

  private directory(name: string): string { requireName(name); return `${this.root}/${name}`; }

  private async installed(name: string): Promise<InstalledRecipe | null> {
    const directory: string = this.directory(name);
    if (!fileIo.accessSync(directory)) return null;
    if (!fileIo.statSync(directory).isDirectory()) throw new RecipeStoreError('recipe_store_corrupt');
    const manifest: RecipeManifest = decodeRecipe(await fileIo.readText(`${directory}/recipe.json`));
    if (manifest.name !== name) throw new RecipeStoreError('recipe_store_corrupt');
    const enabled: boolean = JSON.parse(await fileIo.readText(`${directory}/enabled.json`)) as boolean;
    if (enabled !== true && enabled !== false) throw new RecipeStoreError('recipe_store_corrupt');
    return { descriptor: descriptorFor(manifest), enabled };
  }

  private async candidate(path: string, primitives: AgentTool[]): Promise<RecipeDescriptor> {
    const bytes: Uint8Array = await this.workspace.readBytesCapped(normalizeWorkspacePath(path), 256 * 1024);
    const manifest: RecipeManifest = decodeRecipe(util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes));
    const issues: RecipeIssue[] = validateRecipe(manifest, primitives);
    if (issues.length > 0) throw new RecipeStoreError('invalid_recipe', issues.map(
      (issue: RecipeIssue): string => `${issue.path}: ${issue.message}`).join('\n'));
    return descriptorFor(manifest);
  }

  async listInstalled(): Promise<InstalledRecipe[]> {
    if (!fileIo.accessSync(this.root)) return [];
    if (!fileIo.statSync(this.root).isDirectory()) throw new RecipeStoreError('recipe_store_corrupt');
    const recipes: InstalledRecipe[] = [];
    for (const name of fileIo.listFileSync(this.root).sort()) {
      const recipe: InstalledRecipe | null = await sharedKvMutex.withLock(this.directory(name),
        (): Promise<InstalledRecipe | null> => this.installed(name));
      if (recipe !== null) recipes.push(recipe);
    }
    return recipes;
  }

  async prepareImport(workspacePath: string, primitives: AgentTool[]): Promise<RecipeImportPreview> {
    const path: string = normalizeWorkspacePath(workspacePath);
    const candidate: RecipeDescriptor = await this.candidate(path, primitives);
    const base: InstalledRecipe | null = await sharedKvMutex.withLock(this.directory(candidate.manifest.name),
      (): Promise<InstalledRecipe | null> => this.installed(candidate.manifest.name));
    return { workspacePath: path, candidate, baseHash: base?.descriptor.hash ?? null,
      envelope: recipeEnvelope(candidate.manifest, primitives) };
  }

  applyImport(preview: RecipeImportPreview, primitives: AgentTool[]): Promise<RecipeDescriptor> {
    const name: string = preview.candidate.manifest.name;
    const directory: string = this.directory(name);
    const path: string = normalizeWorkspacePath(preview.workspacePath);
    const candidateHash: string = preview.candidate.hash;
    const baseHash: string | null = preview.baseHash;
    return sharedKvMutex.withLock(directory, async (): Promise<RecipeDescriptor> => {
      const candidate: RecipeDescriptor = await this.candidate(path, primitives);
      if (candidate.hash !== candidateHash || candidate.manifest.name !== name) {
        throw new RecipeStoreError('stale_candidate');
      }
      const base: InstalledRecipe | null = await this.installed(name);
      if ((base?.descriptor.hash ?? null) !== baseHash) throw new RecipeStoreError('stale_base');
      try {
        if (base === null) await writeTextFileAtomic(`${directory}/enabled.json`, 'true');
        await writeTextFileAtomic(`${directory}/recipe.json`, candidate.canonicalJSON);
      } catch (error) {
        if (base === null) {
          try { if (fileIo.accessSync(directory)) fileIo.rmdirSync(directory); }
          catch { /* Keep the original write failure. */ }
        }
        throw error as Error;
      }
      return candidate;
    });
  }

  setEnabled(name: string, expectedHash: string, enabled: boolean): Promise<void> {
    const directory: string = this.directory(name);
    return sharedKvMutex.withLock(directory, async (): Promise<void> => {
      const base: InstalledRecipe | null = await this.installed(name);
      if (base === null || base.descriptor.hash !== expectedHash) throw new RecipeStoreError('stale_base');
      await writeTextFileAtomic(`${directory}/enabled.json`, JSON.stringify(enabled));
    });
  }

  remove(name: string, expectedHash: string): Promise<void> {
    const directory: string = this.directory(name);
    return sharedKvMutex.withLock(directory, async (): Promise<void> => {
      const base: InstalledRecipe | null = await this.installed(name);
      if (base === null || base.descriptor.hash !== expectedHash) throw new RecipeStoreError('stale_base');
      fileIo.rmdirSync(directory);
    });
  }
}

export const createEntryRecipeStore = async (
  context: common.UIAbilityContext, workspace: PosixWorkspaceManager,
): Promise<RecipeStore> => new EntryRecipeStore(`${context.filesDir}/recipes`, workspace);
