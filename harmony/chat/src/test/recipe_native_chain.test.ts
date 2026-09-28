import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { tmpdir } from 'node:os';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { PosixWorkspaceManager, createWorkspaceTools } from '../main/ets/chat/workspace.ts';
import type { WorkspaceFsPort } from '../main/ets/chat/workspace.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort } from '../main/ets/chat/agent_task.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import { PythonRuntime } from '../main/ets/chat/python/runtime.ts';
import { createPythonTool } from '../main/ets/chat/python/tools.ts';
import type { PythonExecuteOptions, PythonNativeResult } from '../main/ets/chat/python/models.ts';
import type { PythonTransportPort } from '../main/ets/chat/python/ports.ts';
import { AgentToolDispatcher, defaultToolInvocationHooks } from '../main/ets/chat/tool_dispatcher.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { RecipeDescriptor, RecipeManifest, RecipeRunCheckpoint } from '../main/ets/chat/recipes/models.ts';
import type { RecipeExecutionPort, RecipeStore } from '../main/ets/chat/recipes/ports.ts';
import { canonicalRecipeJSON } from '../main/ets/chat/recipes/validation.ts';
import { createRecipeLoopAdapter } from '../main/ets/chat/recipes/runner.ts';

interface HostPythonOptions extends PythonExecuteOptions { resourceRoot: string; }
interface HostPythonAddon {
  pythonVersion(): string;
  pythonExecute(id: string, options: HostPythonOptions): Promise<PythonNativeResult>;
  pythonCancel(id: string): void;
}

test('real Workspace → existing CPython addon → Workspace runs only each individually approved effect once', async () => {
  const native = createRequire(import.meta.url)(fileURLToPath(new URL('../../../native/python/build/host/amber_python_host.node', import.meta.url))) as HostPythonAddon;
  const resourceRoot = fileURLToPath(new URL('../../../entry/src/main/resources/rawfile/python', import.meta.url));
  const base = fs.mkdtempSync(path.join(tmpdir(), 'amber-recipe-native-chain-'));
  try {
    const workspace = path.join(base, 'workspace'); fs.mkdirSync(workspace);
    const input = '{"中文":"值","z":["你好","😀"],"a":1}';
    fs.writeFileSync(path.join(workspace, 'input.json'), input, 'utf8');
    const fsPort: WorkspaceFsPort = {
      exists: fs.existsSync, isDirectory: (p) => fs.existsSync(p) && fs.statSync(p).isDirectory(),
      isFile: (p) => fs.existsSync(p) && fs.statSync(p).isFile(),
      listNames: (p) => fs.readdirSync(p).map((name) => { const stat = fs.statSync(path.join(p, name));
        return { name, directory: stat.isDirectory(), sizeBytes: stat.isDirectory() ? null : stat.size }; }),
      readText: (p) => fs.readFileSync(p, 'utf8'), writeText: (p, content, append) => fs.writeFileSync(p, content, { flag: append ? 'a' : 'w' }),
      readBytes: (p) => fs.readFileSync(p), writeBytes: (p, content, append) => fs.writeFileSync(p, content, { flag: append ? 'a' : 'w' }),
      fileSize: (p) => fs.statSync(p).size, mkdirs: (p) => { fs.mkdirSync(p, { recursive: true }); },
      rename: fs.renameSync, copyFile: fs.copyFileSync,
      deleteRecursively: (p) => { const existed = fs.existsSync(p); fs.rmSync(p, { recursive: true, force: true }); return existed; },
    };
    const taskFiles: AgentTaskFilePort = {
      mkdirs: async (p) => { fs.mkdirSync(p, { recursive: true }); },
      listJsonFileNames: async (p) => fs.readdirSync(p).filter((name) => name.endsWith('.json')),
      readText: async (p) => fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null,
      writeText: async (p, value) => { fs.writeFileSync(p, value); }, delete: async (p) => { fs.rmSync(p, { force: true }); },
      exists: async (p) => fs.existsSync(p), isPathInside: async (root, p) => path.resolve(p).startsWith(path.resolve(root) + path.sep),
    };
    const taskStore = await AgentTaskStore.create({ taskDir: path.join(base, 'tasks'), appFilesDir: base, files: taskFiles });
    let nativeCalls = 0;
    const transport: PythonTransportPort = {
      version: () => native.pythonVersion(),
      execute: async (id, options, signal?: AbortSignalLike) => {
        assert.equal(signal?.aborted, false); nativeCalls++;
        const abort = (): void => native.pythonCancel(id);
        signal?.addEventListener?.('abort', abort);
        try { return await native.pythonExecute(id, { ...options, resourceRoot }); }
        finally { signal?.removeEventListener?.('abort', abort); }
      },
    };
    const activities = new AgentToolActivityStore();
    const primitives = createWorkspaceTools(new PosixWorkspaceManager({ port: fsPort, rootAbs: workspace }), activities);
    primitives.push(createPythonTool({ runtime: new PythonRuntime({ transport, taskStore }), activityStore: activities, conversationId: 'native-chain' }));
    const manifest: RecipeManifest = { schema: 'amber.recipe.v1', name: 'normalize', version: '1', description: 'Normalize small Chinese JSON',
      inputs: { input_path: 'string', output_path: 'string' }, steps: [
        { id: 'read', tool: 'file_read', arguments: { path: '${input.input_path}' } },
        { id: 'compute', tool: 'python_execute', arguments: { code: 'import json;print(json.dumps(json.loads(stdin),ensure_ascii=False,sort_keys=True))', stdin: '${step.read.output.content}' } },
        { id: 'save', tool: 'file_write', arguments: { path: '${input.output_path}', content: '${step.compute.output.stdout}' } },
      ], outputs: { saved_path: '${step.save.output.path}' } };
    const descriptor: RecipeDescriptor = { manifest, canonicalJSON: canonicalRecipeJSON(manifest), hash: 'native-chain-pinned-descriptor' };
    const store: RecipeStore = { listInstalled: async () => [{ descriptor, enabled: true }],
      prepareImport: async () => { throw new Error('No import in this fixture'); }, applyImport: async () => { throw new Error('No import in this fixture'); },
      setEnabled: async () => { throw new Error('No management in this fixture'); }, remove: async () => { throw new Error('No management in this fixture'); } };
    const adapter = createRecipeLoopAdapter({ store, installed: [{ descriptor, enabled: true }] });
    const dispatcher = new AgentToolDispatcher({ resolver: new PermissionDecisionResolver(), hooks: defaultToolInvocationHooks() });
    const executed: string[] = [], approved: string[] = [];
    const checkpoint = (p: UIMessagePartTool): RecipeRunCheckpoint => p.metadata!['recipe_v1'] as unknown as RecipeRunCheckpoint;
    const persisted = path.join(base, 'parent.json');
    const port: RecipeExecutionPort = {
      primitive: (name) => primitives.find((p) => p.name === name) ?? null, capture: () => null,
      decide: (p, def) => dispatcher.resolveDecision(def, p, false, false, []),
      dispatch: async (p, def, signal) => {
        const saved = JSON.parse(fs.readFileSync(persisted, 'utf8')) as UIMessagePartTool;
        assert.equal(checkpoint(saved).phase, 'started'); assert.equal(checkpoint(saved).pendingStep!.toolCallId, p.toolCallId);
        executed.push(p.toolName); if (p.approvalState.type === 'approved') approved.push(p.toolName);
        return dispatcher.execute(p, def, false, false, [], 'normal', undefined, signal);
      },
      saveParent: async (p) => { fs.writeFileSync(persisted, JSON.stringify(p)); },
    };
    let parent: UIMessagePartTool = { type: 'tool', toolName: 'recipe__normalize', toolCallId: 'native-parent',
      input: '{"input_path":"input.json","output_path":"output.json"}', output: [], approvalState: { type: 'auto' }, metadata: null };
    parent = await adapter.advance(await adapter.prepare(parent, primitives), port);
    assert.equal(parent.approvalState.type, 'pending'); assert.deepEqual(parent.output, []);
    assert.equal(checkpoint(parent).pendingStep!.toolName, 'python_execute'); assert.equal(nativeCalls, 0);
    assert.equal(fs.existsSync(path.join(workspace, 'output.json')), false); assert.deepEqual(executed, ['file_read']);
    parent = await adapter.advance({ ...parent, approvalState: { type: 'approved' } }, port);
    assert.equal(parent.approvalState.type, 'pending'); assert.deepEqual(parent.output, []);
    assert.equal(checkpoint(parent).pendingStep!.toolName, 'file_write'); assert.equal(nativeCalls, 1);
    assert.equal(fs.existsSync(path.join(workspace, 'output.json')), false);
    const compute = JSON.parse(checkpoint(parent).stepOutputs['compute']!) as JsonObject;
    assert.equal(compute['status'], 'completed'); assert.equal(compute['exit_code'], 0);
    parent = await adapter.advance({ ...parent, approvalState: { type: 'approved' } }, port);
    const result = JSON.parse((parent.output[0] as { text: string }).text) as JsonObject;
    assert.equal(result['status'], 'succeeded'); assert.deepEqual(result['completed_steps'], ['read', 'compute', 'save']);
    assert.deepEqual(result['outputs'], { saved_path: 'output.json' });
    assert.equal(fs.readFileSync(path.join(workspace, 'output.json'), 'utf8'), '{"a": 1, "z": ["你好", "😀"], "中文": "值"}\n');
    assert.equal(fs.readFileSync(path.join(workspace, 'input.json'), 'utf8'), input);
    assert.deepEqual(approved, ['python_execute', 'file_write']); assert.deepEqual(executed, ['file_read', 'python_execute', 'file_write']);
    await adapter.advance(parent, port);
    assert.equal(nativeCalls, 1); assert.deepEqual(executed, ['file_read', 'python_execute', 'file_write']);
  } finally { fs.rmSync(base, { recursive: true, force: true }); }
});
