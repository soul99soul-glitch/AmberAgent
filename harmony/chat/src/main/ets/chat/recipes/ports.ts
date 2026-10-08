import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject } from '../json.ts';
import type { UIMessagePartTool } from '../message.ts';
import type { AgentTool } from '../tool.ts';
import type { PermissionDecision } from '../tool_permission.ts';
import type { InstalledRecipe, RecipeDescriptor, RecipeEnvelope } from './models.ts';

export interface RecipeImportPreview {
  workspacePath: string;
  candidate: RecipeDescriptor;
  baseHash: string | null;
  envelope: RecipeEnvelope;
}

export interface RecipeStore {
  listInstalled(): Promise<InstalledRecipe[]>;
  prepareImport(workspacePath: string, primitives: AgentTool[]): Promise<RecipeImportPreview>;
  applyImport(preview: RecipeImportPreview, primitives: AgentTool[]): Promise<RecipeDescriptor>;
  setEnabled(name: string, expectedHash: string, enabled: boolean): Promise<void>;
  remove(name: string, expectedHash: string): Promise<void>;
}

export interface RecipeExecutionPort {
  primitive: (name: string) => AgentTool | null;
  capture: (part: UIMessagePartTool) => JsonObject | null;
  decide: (part: UIMessagePartTool, tool: AgentTool,
    signal?: AbortSignalLike) => PermissionDecision | Promise<PermissionDecision>;
  dispatch: (part: UIMessagePartTool, tool: AgentTool,
    signal?: AbortSignalLike) => Promise<UIMessagePartTool | null>;
  saveParent: (part: UIMessagePartTool) => Promise<void>;
}

export interface RecipeLoopAdapter {
  supports: (toolName: string) => boolean;
  prepare: (parent: UIMessagePartTool, primitives: AgentTool[]) => Promise<UIMessagePartTool>;
  advance: (parent: UIMessagePartTool, port: RecipeExecutionPort,
    signal?: AbortSignalLike) => Promise<UIMessagePartTool>;
}
