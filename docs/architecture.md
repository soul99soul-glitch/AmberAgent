# AmberAgent Architecture

> Last updated: 2026-05-28 (Phase A-D refactoring in progress)

## Overview

AmberAgent is an Android AI agent platform evolving from a chat client toward a
modular Agent Kernel + Surfaces architecture.

## Module Map

```
┌─── UI Surfaces ─────────────────────────────────────┐
│ ChatPage · DeepReadScreen · BoardScreen · ...       │
│ (Compose, observe AgentRunner, dispatch commands)   │
└─────────────────┬───────────────────────────────────┘
                  │ observe(runId) / launch(input)
┌─────────────────▼───────────────────────────────────┐
│ Agent Kernel (:core:agent-runtime)                  │
│   AgentRegistry · AgentRunner · AgentEventStore     │
│   RunScope (events/tools/llm/tracing/permission)    │
│   Surface<STATE, COMMAND>                           │
└─────────────────┬───────────────────────────────────┘
                  │
   ┌──────────────┼──────────────┐
   ▼              ▼              ▼
 :feature:      :feature:      (future)
 chat:api       deepread:api   :feature:*:api
   │              │
 :feature:      :feature:
 chat:impl      deepread:impl
   │              │
   └──────┬───────┘
          │
┌─────────▼───────────────────────────────────────────┐
│ Platform Layer                                      │
│  :core:agent-runtime  (pure Kotlin, KMP-ready)      │
│  :core:agent-store-room (Room persistence)          │
│  :core:agent-utils    (shared JSON utilities)       │
│  :ai                  (LLM provider abstraction)    │
│  :common :highlight :search :tts :document :web     │
└─────────────────────────────────────────────────────┘
```

## Key Concepts

| Concept | Role | Location |
|---|---|---|
| **Agent** | Typed capability with handler | `:core:agent-runtime` |
| **AgentRun** | Lifecycle of one execution | `agent_runtime.db` |
| **AgentEvent** | Final (persisted) + Transient (SharedFlow only) | `:core:agent-runtime` |
| **RunScope** | Execution context (events, tools, llm, tracing) | `:core:agent-runtime` |
| **Surface** | UI boundary consuming events, dispatching commands | `:core:agent-runtime` |
| **Assistant** | User-facing configuration (system prompt, model, tools) | `:app` (Room) |
| **Conversation** | Persistent thread with MessageNode tree | `:app` (Room) |

## Database Strategy

- **`rikka_hub` (AppDatabase)**: Chat history, assistants, conversations, board items.
  30 migrations. Frozen surface (ADR-0001).
- **`agent_runtime.db` (AgentRuntimeDatabase)**: Agent runs, events, trace spans,
  permission intents. Version 1. Independent lifecycle.

## ADR Index

| ADR | Title |
|---|---|
| [0001](adr/0001-legacy-frozen-surfaces.md) | Legacy Frozen Surfaces |
| [0002](adr/0002-agent-kernel-interface-design.md) | Agent Kernel Interface Design |
| [0003](adr/0003-uniffi-vs-hand-written-jni.md) | UniFFI vs Hand-Written JNI |
