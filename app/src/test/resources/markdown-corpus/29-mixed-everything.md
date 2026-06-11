# Complete Feature Overview: AmberAgent Chat

This document exercises nearly every Markdown feature in a single realistic assistant response.

## Summary

AmberAgent is an AI chat client built on Jetpack Compose. It supports **multiple assistants**, each with its own system prompt, model parameters, and *conversation isolation*. Think of each assistant as a separate persona with its own memory and settings.

---

## Key Features

- [x] Multi-assistant support with per-assistant settings
- [x] Streaming responses via SSE
- [x] Fenced code blocks with syntax highlighting
- [ ] Voice input (planned for v2.0)
- [ ] Image generation (planned)

### Supported Providers

| Provider | Models | Streaming | Function Calling |
| :--- | :--- | :---: | :---: |
| OpenAI | GPT-4o, o1 | ✓ | ✓ |
| Anthropic | Claude 3.5 Sonnet | ✓ | ✓ |
| Google | Gemini 1.5 Pro | ✓ | ✓ |
| Ollama | Any local model | ✓ | partial |

---

## Architecture Diagram (Prose)

The architecture follows a layered approach:

1. **Surfaces layer** — Compose screens that observe `StateFlow` from ViewModels
2. **ViewModel layer** — coordinates UI state and delegates to repositories
3. **Repository layer** — single source of truth, backed by Room and remote API
4. **Agent Kernel** — pure Kotlin contracts for agent execution and event streaming

> **Note:** The Agent Kernel (`core/agent-runtime`) has no Android dependencies. It is testable on the JVM without a device.

---

## Example: Starting a Chat

```kotlin
val input = ChatTurnInput(
    assistantId = "assistant_01",
    userMessage = "Explain coroutines in 3 sentences.",
    attachments = emptyList()
)

viewModelScope.launch {
    agentRunner.run(input).collect { event ->
        when (event) {
            is AgentEvent.TextDelta -> appendText(event.delta)
            is AgentEvent.Done      -> markComplete()
            is AgentEvent.Error     -> showError(event.cause)
        }
    }
}
```

The `agentRunner.run()` call returns a `Flow<AgentEvent>` that the ViewModel collects. Each `TextDelta` carries a chunk of the assistant's response.

---

## Math Example

The attention mechanism in transformers computes:

$$\text{Attention}(Q, K, V) = \text{softmax}\!\left(\frac{QK^T}{\sqrt{d_k}}\right) V$$

where $Q$, $K$, $V$ are the query, key, and value matrices and $d_k$ is the key dimension.

---

## Footnotes and References

The project is licensed under AGPL v3[^agpl] for the open-source portions and a commercial license for proprietary forks.[^commercial]

[^agpl]: GNU Affero General Public License v3: <https://www.gnu.org/licenses/agpl-3.0.html>
[^commercial]: Commercial licensing inquiries: contact@amberagent.app

---

## CJK Support

AmberAgent 完整支持中文输入和渲染。表格、强调和代码块均可包含中文内容：

| 功能 | 状态 |
| --- | --- |
| 中文输入法 (IME) | ✓ 支持 |
| CJK 字符渲染 | ✓ 支持 |
| 混合中英文段落 | ✓ 支持 |

---

## Conclusion

~~The old architecture~~ has been replaced by the Agent Kernel pattern, which separates concerns cleanly and makes the system more testable. The combination of **Kotlin Coroutines**, *Room*, and `Jetpack Compose` provides a solid foundation.

For questions, open an issue or visit <https://github.com/amberagent/amberagent>.
