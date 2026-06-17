#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../..');
const outDir = path.join(root, 'docs', 'harmony-inventory');

const ignoreDirs = new Set([
  '.git',
  '.gradle',
  '.kotlin',
  '.idea',
  '.slim',
  'build',
  'dist',
  'node_modules',
]);

const inventories = [
  {
    file: 'rust-native.md',
    title: 'Rust Native and JNI Inventory',
    include: [/^native\//, /^core\/llm\//, /^highlight\//, /^document\//],
    match: [
      /members\s*=|jni\s*=/,
      /#\[no_mangle\]|extern\s+"system"|Java_app_amber/,
      /System\.loadLibrary|external\s+fun|LIB_NAME/,
      /panic_to_string|init_logger_once|write_varint/,
    ],
  },
  {
    file: 'providers-streaming.md',
    title: 'Provider and Streaming Inventory',
    include: [/^ai\//, /^search\//, /^tts\//],
    match: [
      /SSE|EventSource|ServerSentEventReader|text\/event-stream/,
      /streamText|generateText|callbackFlow|Flow</,
      /OpenAIProvider|ChatCompletionsAPI|ResponseAPI|ClaudeProvider|GoogleProvider/,
      /OkHttpClient|Request\.Builder|HttpURLConnection/,
    ],
  },
  {
    file: 'storage-room-preferences.md',
    title: 'Room, Schema, and Preferences Inventory',
    include: [/^core\/agent-store-room\//, /^core\/settings\//, /^app\/schemas\//, /^app\/src\/main\/(java|kotlin)\/app\/amber\/agent\/data\/db\//],
    match: [
      /@Database|@Entity|@Dao|@Query|@Insert|@Update|@Delete|Migration/,
      /preferencesDataStore|PreferencesKey|Preference|DataStore/,
      /@Serializable\s+data\s+class\s+Settings|data\s+class\s+AgentRuntimeSetting/,
      /"database"|"entities"|"version"/,
    ],
  },
  {
    file: 'platform-android-apis.md',
    title: 'Android Platform API Inventory',
    include: [/^app\//, /^feature\//, /^tts\//],
    match: [
      /AccessibilityService|dispatchGesture|AccessibilityNodeInfo/,
      /NotificationListenerService|onNotificationPosted|getActiveNotifications/,
      /MediaProjection|MediaProjectionManager|createScreenCaptureIntent/,
      /WorkManager|CoroutineWorker|WorkerParameters|ForegroundService|startForegroundService/,
      /TextToSpeech|android\.speech\.tts/,
      /WebView|WebViewClient|WebChromeClient|addJavascriptInterface/,
      /ActivityResultContracts|GetMultipleContents|OpenMultipleDocuments|OpenDocumentTree/,
      /Intent\(|Service\(|BroadcastReceiver|ContentResolver|DocumentFile/,
    ],
  },
  {
    file: 'tools.md',
    title: 'Tool Runtime Inventory',
    include: [
      /^app\/src\/main\/(java|kotlin)\/app\/amber\/feature\/tools\//,
      /^feature\/tools\//,
      /^core\/agent-runtime\//,
      /^feature\/terminal\//,
      /^feature\/modelcouncil\//,
    ],
    match: [
      /class\s+\w*Tools|interface\s+.*Tool|ToolRegistry|ToolCall|ToolResult/,
      /PermissionBroker|PermissionIntent|ToolSession|AgentRunner|AgentRuntime/,
      /TerminalRuntime|BUILTIN_ALPINE|ANDROID_SHELL|TERMUX_EXTERNAL|proot|alpine/,
      /ExternalCli|ModelCouncil|CouncilRoom/,
    ],
  },
  {
    file: 'ui-pages.md',
    title: 'Compose UI Pages Inventory',
    include: [/^app\/src\/main\/(java|kotlin)\/app\/amber\/feature\/ui\//, /^ui-graphite\//],
    match: [
      /@Composable|fun\s+\w*Page\s*\(|class\s+\w*Page/,
      /LazyColumn|LazyRow|Scaffold|NavHost|Navigation|Route/,
      /ChatInput|Message|Conversation|ToolCall|ToolResult/,
      /remember|LaunchedEffect|derivedStateOf|snapshotFlow/,
    ],
  },
];

function walk(dir, files = []) {
  const entries = fs
    .readdirSync(dir, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name));
  for (const entry of entries) {
    if (entry.isDirectory() && ignoreDirs.has(entry.name)) continue;
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(absolute, files);
    } else if (entry.isFile()) {
      files.push(absolute);
    }
  }
  return files;
}

function toRel(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function isTextCandidate(rel) {
  return /\.(kt|kts|java|rs|toml|json|xml|md|gradle|properties)$/.test(rel) || rel.endsWith('Cargo.toml');
}

function lineMatches(file, patterns) {
  const text = fs.readFileSync(file, 'utf8');
  return text.split(/\r?\n/).flatMap((line, index) => {
    if (!patterns.some((pattern) => pattern.test(line))) return [];
    return [{ line: index + 1, text: line.trim() }];
  });
}

function writeInventory(inventory, files) {
  const rows = [];
  let matchCount = 0;
  for (const file of files) {
    const rel = toRel(file);
    if (!isTextCandidate(rel)) continue;
    if (!inventory.include.some((pattern) => pattern.test(rel))) continue;
    let matches = [];
    try {
      matches = lineMatches(file, inventory.match);
    } catch (error) {
      rows.push(`- \`${rel}\`: skipped (${error.message})`);
      continue;
    }
    matchCount += matches.length;
    for (const match of matches.slice(0, 30)) {
      rows.push(`- \`${rel}:${match.line}\` — ${match.text}`);
    }
    if (matches.length > 30) {
      rows.push(`- \`${rel}\` — ${matches.length - 30} additional matches omitted`);
    }
  }

  const body = [
    `# ${inventory.title}`,
    '',
    '> Generated by `scripts/harmony-inventory/scan-harmony-inventory.mjs`.',
    `> Source commit: \`${sourceCommit()}\`.`,
    '> Regenerate after major Android-side refactors before updating HarmonyOS migration prompts.',
    '',
    `Total matches: ${matchCount}`,
    '',
    ...rows,
    '',
  ].join('\n');

  fs.writeFileSync(path.join(outDir, inventory.file), body);
}

function sourceCommit() {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: root, encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

fs.mkdirSync(outDir, { recursive: true });
const files = walk(root);
for (const inventory of inventories) {
  writeInventory(inventory, files);
}

const index = [
  '# HarmonyOS Migration Inventory',
  '',
  '> Generated by `scripts/harmony-inventory/scan-harmony-inventory.mjs`.',
  `> Source commit: \`${sourceCommit()}\`.`,
  '',
  ...inventories.map((inventory) => `- [${inventory.title}](./${inventory.file})`),
  '',
].join('\n');

fs.writeFileSync(path.join(outDir, 'README.md'), index);
console.log(`Wrote ${inventories.length + 1} inventory files to ${path.relative(root, outDir)}`);
