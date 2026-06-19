# Packet A: Android Advanced Capability Auditor

Objective:
Read only Android advanced execution implementations and summarize parity expectations for iOS.

Files / sources:
- Android subagent implementation
- Android modelcouncil implementation
- AgentCronTools
- RunPlanUpdateTool
- AskUserTool
- PermissionsStatusTool
- ToolPolicyExplainTool
- SettingAgentExecutionPage
- SettingAgentPermissionsPage
- SettingSandboxPage
- remote/runtime related implementation

Do:
- Identify data models, state transitions, permission concepts, and UI affordances iOS should mirror.
- Highlight minimum viable parity versus Android-only details that should not be ported directly.

Do not:
- Edit files.
- Modify Android code.

Expected output:
Concise audit with file references and a checklist for the main agent.
