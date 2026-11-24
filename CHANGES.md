# Changes

- `src/main/java/com/bwxor/piejfxsdk/type/Role.java`: added `TOOL_USE` role for stored tool calls.
- `src/main/java/com/bwxor/piejfxsdk/dto/Message.java`: added `inputs` field (tool input JSON) and a 2-argument constructor that keeps existing callers working.
- `src/main/java/com/bwxor/piejfxsdk/service/ClaudeMessageService.java`: `runLoop` now stores the assistant text written before tool calls and every tool call shown in the chat (including compile-check and MCP calls) in the session history; added `textOf` helper.
- `src/main/java/com/bwxor/piejfxsdk/factory/TabFactory.java`: `showSessionHistory` replays `TOOL_USE` messages, so tool calls appear again when a chat is reopened.
