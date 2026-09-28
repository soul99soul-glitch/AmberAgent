import type { JsonObject } from '../json.ts';
import type { UIMessagePartTool } from '../message.ts';
import type { RecipeImportPreview } from './ports.ts';

export const RECIPE_SCHEMA: string = 'amber.recipe.v1';
export const RECIPE_MAX_STEPS: number = 8;
export const RECIPE_DEFAULT_TIMEOUT_SECONDS: number = 60;
export const RECIPE_MAX_TIMEOUT_SECONDS: number = 600;

export type RecipeInputType = 'string' | 'number' | 'boolean';
export interface RecipeStep { id: string; tool: string; arguments: JsonObject; timeoutSeconds?: number; }
export interface RecipeManifest {
  schema: string;
  name: string;
  version: string;
  description: string;
  inputs: Record<string, RecipeInputType>;
  steps: RecipeStep[];
  outputs: JsonObject;
}
export interface RecipeIssue { code: string; path: string; message: string; }
export interface RecipeEnvelope {
  tools: string[];
  mutates: boolean;
  needsApproval: boolean;
  risk: 'normal' | 'sensitive' | 'high';
}
export interface RecipeDescriptor { hash: string; canonicalJSON: string; manifest: RecipeManifest; }
export interface InstalledRecipe { descriptor: RecipeDescriptor; enabled: boolean; }
export interface RecipeBinding { kind: 'input' | 'step'; name: string; field: string | null; }
export interface RecipeRunCheckpoint {
  kind: 'run';
  executionId: string;
  descriptor: RecipeDescriptor;
  inputs: JsonObject;
  nextIndex: number;
  completedSteps: string[];
  stepOutputs: Record<string, string>;
  phase: 'ready' | 'awaiting_approval' | 'started' | 'finished';
  pendingStep: UIMessagePartTool | null;
}
export interface RecipeImportCheckpoint { kind: 'import'; preview: RecipeImportPreview; }
