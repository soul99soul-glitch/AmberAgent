## Streaming Truncated Response

This file simulates what happens when a streaming LLM response is cut off mid-generation. The parser and renderer must handle an unclosed code fence gracefully — without crashing and ideally rendering the partial code block as best it can.

The response below was truncated because the context window was exhausted mid-token:

Here is a code example:

```kotlin
fun partial(