-- Minimum HarmonyOS RDB schema for Phase 0 Chat persistence.
-- Source-only draft; validate against @ohos.data.relationalStore once Gate 0 is ready.

CREATE TABLE IF NOT EXISTS conversation (
  id TEXT PRIMARY KEY NOT NULL,
  assistant_id TEXT NOT NULL,
  title TEXT NOT NULL,
  is_pinned INTEGER NOT NULL DEFAULT 0,
  auto_approve_tool_calls INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS message_node (
  id TEXT PRIMARY KEY NOT NULL,
  conversation_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  select_index INTEGER NOT NULL DEFAULT 0,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_message_node_conversation_position
  ON message_node(conversation_id, position);

CREATE TABLE IF NOT EXISTS ui_message (
  id TEXT PRIMARY KEY NOT NULL,
  node_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  role TEXT NOT NULL,
  parts_json TEXT NOT NULL,
  annotations_json TEXT NOT NULL DEFAULT '[]',
  model_id TEXT,
  usage_json TEXT,
  created_at TEXT,
  finished_at TEXT,
  FOREIGN KEY(node_id) REFERENCES message_node(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ui_message_node_position
  ON ui_message(node_id, position);

CREATE TABLE IF NOT EXISTS schema_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
);

INSERT OR REPLACE INTO schema_meta(key, value) VALUES ('schema_version', '1');
