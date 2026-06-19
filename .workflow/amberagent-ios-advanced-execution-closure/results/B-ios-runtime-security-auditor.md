# Packet B Result: iOS Runtime/Security Auditor

Status: completed.

Key evidence:
- `IOSLocalToolExecutor` only executes permissions status, selected-file reads, and WebMount; this is the safe local boundary.
- `ChatViewModel` declares search, memory, WebMount, MCP, SubAgent, and Council tools. This is the risky dispatch boundary.
- SSH execution is not a model tool; it requires trusted host fingerprint and password before command execution.
- Capability gates always return enabled; security must be enforced at executor/permission/runtime level, not AppShell gates.
- SubAgent/Council runners switch to real provider when an API key exists, so task records must honestly label real provider vs stub/fallback.

Accepted decisions:
- Do not expose terminal execution as a model tool on iOS.
- Keep ChatViewModel edits narrow: dispatch parsing/result text only.
- Use `IOSPermissionModels`, `SubAgentRunner`, `CouncilRunner`, and runtime views as low-conflict anchors.
