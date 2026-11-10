package com.bwxor.piejfxsdk.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.messages.*;
import com.bwxor.piejfxsdk.dto.DelegateTaskReturnItem;
import com.bwxor.piejfxsdk.dto.Session;
import com.bwxor.piejfxsdk.dto.ToolUseDisplayItem;
import com.bwxor.piejfxsdk.factory.MessageFactory;
import com.bwxor.piejfxsdk.state.MCPServerState.MCPServer;
import com.bwxor.piejfxsdk.state.OpenedFolderState;
import com.bwxor.piejfxsdk.state.PermissionState;
import com.bwxor.piejfxsdk.state.ServiceState;
import com.bwxor.piejfxsdk.state.SessionState;
import com.bwxor.piejfxsdk.state.UIState;
import com.bwxor.piejfxsdk.tool.*;
import com.bwxor.piejfxsdk.type.Permission;
import com.bwxor.piejfxsdk.type.Role;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import javafx.application.Platform;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class ClaudeMessageService {
    private static final String EMPTY_STRING = "";
    private static final String NEWLINE_STRING = "\n";
    private static final Integer TOKEN_AMOUNT_TO_START_CLEANING = 100_000;
    private static final Integer TOKEN_AMOUNT_TO_CLEAR_AT_LEAST = 20_000;
    // REVIEW: how many review -> fix cycles before remaining issues are just shown to the user
    private static final int MAX_REVIEW_ROUNDS = 2;
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final String MCP_DISPLAY_SYMBOL = "\uD83D\uDD0C";
    private static final String SYSTEM_PROMPT = """
            You are a coding agent working inside the user's workspace in the Pie editor.
            
            Files:
            - Create or modify whatever project files the task needs, including
              build and config files such as pom.xml, build.gradle or package.json.
            - The only Markdown file you may create is CHANGES.md. Do not create other
              documentation or guideline files such as README.md, GUIDE.md, SETUP.md,
              USAGE.md or NOTES.md, unless the user explicitly asks for one.
            - Put any explanations, instructions or usage notes in your answer to
              the user instead of in separate files.
            
            When you have finished making changes:
            - Write CHANGES.md in the workspace root. List each file you created or
              modified, with a one-line description of the change. If CHANGES.md
              already exists, replace its contents.
            - Then give the user a short summary of what you did.
            
            Read files before modifying them. Keep answers concise.
            """;

    private AnthropicClient client;
    private String model;
    // REVIEW
    private ReviewService reviewService;
    private boolean reviewEnabled = true;

    // MCP: connected servers, the tools they expose (as Anthropic tool definitions) and how to call each tool
    private final List<McpSyncClient> mcpClients = new CopyOnWriteArrayList<>();
    private final List<BetaTool> mcpToolDefinitions = new CopyOnWriteArrayList<>();
    private final Map<String, McpToolBinding> mcpToolBindings = new ConcurrentHashMap<>();

    public ClaudeMessageService(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder()
                .authToken(apiKey)
                .baseUrl("https://openrouter.ai/api")
                .putHeader("X-Title", "PieJFX")
                .build();
        this.model = model;
        // REVIEW
        this.reviewService = new ReviewService(client, model);
    }

    /**
     * Connects to the configured MCP servers and registers the tools they expose. Servers that fail to connect
     * are skipped. Replaces any servers registered earlier by this service. Tools of servers without a permission
     * entry in permissions.json are still registered, but calls to them are refused.
     */
    public void registerMcpServers(List<MCPServer> servers) {
        closeMcpClients();

        for (MCPServer server : servers) {
            if (server.getName() == null || server.getName().isBlank() || server.getUrl() == null || server.getUrl().isBlank()) {
                System.out.println("Skipping MCP server with missing name or url: " + server.getName());
                continue;
            }

            McpSyncClient mcp;
            try {
                mcp = connect(server);
            } catch (Exception e) {
                System.out.println("MCP server '" + server.getName() + "' is unavailable: " + e.getMessage());
                continue;
            }

            try {
                for (McpSchema.Tool tool : mcp.listTools().tools()) {
                    String exposedName = exposedToolName(server.getName(), tool.name());
                    if (mcpToolBindings.containsKey(exposedName)) {
                        System.out.println("Skipping duplicate MCP tool '" + exposedName + "'");
                        continue;
                    }
                    mcpToolBindings.put(exposedName, new McpToolBinding(server.getName(), mcp, tool.name()));
                    mcpToolDefinitions.add(toBetaTool(exposedName, tool));
                }
                mcpClients.add(mcp);
            } catch (Exception e) {
                System.out.println("Could not list tools of MCP server '" + server.getName() + "': " + e.getMessage());
                mcp.closeGracefully();
            }
        }
    }

    public void sendAgentic(String prompt, int maxIterations, Consumer<String> displayFunction, Consumer<ToolUseDisplayItem> toolUseDisplayFunction, Consumer<Boolean> toggleWorkingFunction, Consumer<Double> progressBarFunction, Consumer<Long> usedTokensFunction, Consumer<String> errorFunction, Runnable openFolderRunnable) {
        boolean isClaude = model.contains("anthropic/");

        try {
            SessionState sessionState = SessionState.instance;

            Session currentSession = sessionState.getSessions().get(sessionState.getCurrentSessionIndex());

            currentSession.getMessages().add(new com.bwxor.piejfxsdk.dto.Message(prompt, Role.USER));
            System.out.println("[" + sessionState.getCurrentSessionIndex() + "] User: " + prompt);

            if (currentSession.getConversation() == null) {
                var conversation = MessageCreateParams.builder()
                        .model(this.model)
                        .maxTokens(16000)
                        .system(SYSTEM_PROMPT)
                        .addTool(ListWorkspaceFiles.class)
                        .addTool(ReadWorkspaceFile.class)
                        .addTool(WriteWorkspaceFile.class)
                        .addTool(ExecuteTerminalCommand.class)
                        .addTool(DelegateTask.class)
                        .putAdditionalBodyProperty("session_id", JsonValue.from(currentSession.getId()));

                mcpToolDefinitions.forEach(tool -> conversation.addTool(tool));

                currentSession.setConversation(conversation);

                if (isClaude) {
                    currentSession.getConversation()
                            .putAdditionalBodyProperty("context_management", JsonValue.from(Map.of(
                                    "edits", List.of(Map.of(
                                            "type", "clear_tool_uses_20250919",
                                            "trigger", Map.of("type", "input_tokens", "value", TOKEN_AMOUNT_TO_START_CLEANING),
                                            "keep", Map.of("type", "tool_uses", "value", 4),
                                            "clear_at_least", Map.of("type", "input_tokens", "value", TOKEN_AMOUNT_TO_CLEAR_AT_LEAST),
                                            "clear_tool_inputs", true
                                    ))
                            )))
                            .putAdditionalBodyProperty("cache_control", JsonValue.from(Map.of("type", "ephemeral")))
                            .putAdditionalHeader("anthropic-beta", "context-management-2025-06-27");
                }
            }

            currentSession.getConversation().addUserMessage(prompt);

            toggleWorkingFunction.accept(true);

            var changedFiles = new HashSet<String>();
            runLoop(currentSession, isClaude, reviewEnabled, changedFiles, maxIterations, displayFunction, toolUseDisplayFunction, progressBarFunction, usedTokensFunction, openFolderRunnable);

        } catch (Exception e) {
            e.printStackTrace();
            errorFunction.accept(e.getMessage());
        } finally {
            toggleWorkingFunction.accept(false);
        }
    }

    public void runLoop(Session currentSession, boolean isClaude, boolean reviewEnabled, Set<String> changedFiles, int maxIterations, Consumer<String> displayFunction, Consumer<ToolUseDisplayItem> toolUseDisplayFunction, Consumer<Double> progressBarFunction, Consumer<Long> usedTokensFunction, Runnable openFolderRunnable) {
        ServiceState serviceState = ServiceState.instance;
        OpenedFolderState openedFolderState = OpenedFolderState.instance;

        File openedFolder = openedFolderState.getOpenedFolder();

        // REVIEW: files written in this task, tracked from actual tool calls (not from CHANGES.md)
        int reviewRounds = 0;

        for (int i = 0; i < maxIterations; i++) {
            if (i > 0) {
                displayFunction.accept(NEWLINE_STRING);
            }

            BetaMessageAccumulator messageAccumulator = BetaMessageAccumulator.create();
            BetaStopReason[] stopReason = {null};
            Exception[] accumulateError = {null};

            try (var streamResponse = client.beta().messages()
                    .createStreaming(currentSession.getConversation().build())) {
                streamResponse.stream()
                        .peek(event -> {
                            event.messageDelta().ifPresent(d -> stopReason[0] = d.delta().stopReason().orElse(null));
                            if (accumulateError[0] == null) {
                                try {
                                    messageAccumulator.accumulate(event);
                                } catch (Exception ex) {
                                    accumulateError[0] = ex;
                                }
                            }
                        })
                        .flatMap(event -> event.contentBlockDelta().stream())
                        .flatMap(deltaEvent -> deltaEvent.delta().text().stream())
                        .forEach(textDelta -> displayFunction.accept(textDelta.text()));
            }

            if (accumulateError[0] != null) {
                System.out.println("Tool JSON broken. stop_reason=" + stopReason[0]);
                // recover as in the previous snippet: placeholder assistant msg + "retry smaller" user msg, continue
            }

            displayFunction.accept(NEWLINE_STRING);

            var response = messageAccumulator.message();

            var limit = isClaude ? TOKEN_AMOUNT_TO_START_CLEANING : serviceState.getModelInfoService().getContextLength(model);
            var usage = response.usage();
            var usedTokens = usage.inputTokens() + usage.cacheReadInputTokens().orElse(0L) + usage.cacheCreationInputTokens().orElse(0L);
            double progress = Math.min(1.0, (double) usedTokens / limit);
            progressBarFunction.accept(progress);
            usedTokensFunction.accept(usedTokens);

            response.content().forEach(cb -> {
                if (cb.toolUse().isPresent()) {
                    System.out.println("Tool use: " + cb.toolUse().get().name() + " input=" + cb.toolUse().get()._input());
                }
            });

            var toolUseBlocks = response.content().stream()
                    .flatMap(cb -> cb.toolUse().stream())
                    .toList();

            // AI History
            var assistantBlocks = response.content().stream()
                    .map(cb -> {
                        if (cb.toolUse().isPresent()) {
                            var tub = cb.toolUse().get();
                            return BetaContentBlockParam.ofToolUse(
                                    BetaToolUseBlockParam.builder()
                                            .name(tub.name())
                                            .id(tub.id())
                                            .input(tub._input())
                                            .build());
                        } else if (cb.text().isPresent()) {
                            var textBlock = cb.text().get();
                            return BetaContentBlockParam.ofText(
                                    BetaTextBlockParam.builder()
                                            .text(textBlock.text())
                                            .build());
                        } else if (cb.thinking().isPresent()) {
                            var t = cb.thinking().get();
                            return BetaContentBlockParam.ofThinking(BetaThinkingBlockParam.builder()
                                    .thinking(t.thinking()).signature(t.signature()).build());
                        } else if (cb.redactedThinking().isPresent()) {
                            return BetaContentBlockParam.ofRedactedThinking(BetaRedactedThinkingBlockParam.builder()
                                    .data(cb.redactedThinking().get().data()).build());
                        } else {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            boolean hitMaxTokens = response.stopReason()
                    .map(r -> r.equals(BetaStopReason.MAX_TOKENS))
                    .orElse(false);

            if (hitMaxTokens && !toolUseBlocks.isEmpty()) {
                // The last tool call is probably truncated - don't execute it
                displayFunction.accept("Response was cut off (max tokens). Try asking for smaller files.");
                return;
            }

            if (!assistantBlocks.isEmpty()) {
                currentSession.getConversation().addAssistantMessageOfBetaContentBlockParams(assistantBlocks);
            }

            // if no tool use blocks, show only text and end iteration
            if (toolUseBlocks.isEmpty()) {
                var assistantBlocksText = assistantBlocks.stream()
                        .flatMap(cb -> cb.text().stream())
                        .map(BetaTextBlockParam::text)
                        .collect(Collectors.joining()).strip();

                // REVIEW: before accepting "done", check that the project compiles
                if (reviewEnabled && !changedFiles.isEmpty()) {
                    boolean sentBackForFixes = review(openedFolder, changedFiles, reviewRounds,
                            currentSession.getConversation(), assistantBlocks.isEmpty(),
                            displayFunction, toolUseDisplayFunction);
                    if (sentBackForFixes) {
                        reviewRounds++;
                        continue;
                    }
                }

                currentSession.getMessages().add(new com.bwxor.piejfxsdk.dto.Message(assistantBlocksText, Role.ASSISTANT));
                return;
            }

            // REVIEW: remember which files the agent wrote
            toolUseBlocks.stream()
                    .filter(t -> "write_workspace_file".equals(t.name()))
                    .map(ClaudeMessageService::writtenPath)
                    .filter(Objects::nonNull)
                    .forEach(changedFiles::add);

            // Application history
            var resultBlocks = toolUseBlocks.stream()
                    .map(tub -> BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder()
                                    .toolUseId(tub.id())
                                    .contentAsJson(callTool(tub, changedFiles, toolUseDisplayFunction, openFolderRunnable))
                                    .build()))
                    .toList();

            currentSession.getConversation()
                    .addUserMessageOfBetaContentBlockParams(resultBlocks);
        }

        displayFunction.accept("Hit max iterations without completing.");
    }

    /**
     * REVIEW: runs the compile check. Returns true if compiler errors were sent back to the agent
     * (the loop should continue), false if the task can finish.
     */
    private boolean review(File openedFolder, Set<String> changedFiles, int reviewRounds,
                           MessageCreateParams.Builder conversation, boolean noAssistantMessage,
                           Consumer<String> displayFunction, Consumer<ToolUseDisplayItem> toolUseDisplayFunction) {
        if (openedFolder == null) {
            displayFunction.accept("Compile check skipped: no folder is open." + NEWLINE_STRING);
            return false;
        }

        ReviewService.CompileResult result;
        try {
            displayFunction.accept(NEWLINE_STRING + "I will check if the project compiles." + NEWLINE_STRING);
            result = reviewService.check(openedFolder.toPath(), changedFiles, toolUseDisplayFunction);
        } catch (Exception e) {
            // A broken check must never break the task itself.
            e.printStackTrace();
            displayFunction.accept("Compile check failed: " + e.getMessage() + NEWLINE_STRING);
            return false;
        }

        switch (result.status()) {
            case SKIPPED -> {
                displayFunction.accept("Compile check skipped: " + result.output() + NEWLINE_STRING);
                return false;
            }
            case PASSED -> {
                displayFunction.accept("✓ Review successful. The project compiles." + NEWLINE_STRING);
                return false;
            }
            default -> {
                // FAILED, handled below
            }
        }

        if (reviewRounds < MAX_REVIEW_ROUNDS) {
            displayFunction.accept("✗ Compilation failed. Sending errors back for fixes ("
                    + (reviewRounds + 1) + "/" + MAX_REVIEW_ROUNDS + ")." + NEWLINE_STRING);
            if (noAssistantMessage) {
                // keep user/assistant turns alternating
                conversation.addAssistantMessageOfBetaContentBlockParams(List.of(
                        BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text("Done.").build())));
            }
            conversation.addUserMessage("""
                    `%s` failed. Fix the compilation errors, then update CHANGES.md and give a short summary.
                    
                    %s""".formatted(result.command(), result.output()));
            return true;
        }

        displayFunction.accept("✗ Still does not compile after " + MAX_REVIEW_ROUNDS + " rounds:"
                + NEWLINE_STRING + result.output() + NEWLINE_STRING);
        return false;
    }

    // REVIEW: path the write tool wrote to. Adjust the accessor to match your WriteWorkspaceFile class.
    private static String writtenPath(BetaToolUseBlock toolUseBlock) {
        try {
            Map<?, ?> input = toolUseBlock.input(Map.class);
            Object path = input != null ? input.get("path") : null;
            return path != null ? path.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Object callTool(BetaToolUseBlock toolUseBlock, Set<String> changedFiles, Consumer<ToolUseDisplayItem> toolUseDisplayFunction, Runnable openFolderRunnable) {
        McpToolBinding mcpBinding = mcpToolBindings.get(toolUseBlock.name());
        if (mcpBinding != null) {
            return callMcpTool(toolUseBlock, mcpBinding, toolUseDisplayFunction);
        }

        Class<? extends BuiltInTool<?>> clazz;

        String displaySymbol;

        if ("list_workspace_files".equals(toolUseBlock.name())) {
            clazz = ListWorkspaceFiles.class;
            displaySymbol = "\uD83D\uDCC2";
        } else if ("read_workspace_file".equals(toolUseBlock.name())) {
            clazz = ReadWorkspaceFile.class;
            displaySymbol = "\uD83D\uDCC4";
        } else if ("write_workspace_file".equals(toolUseBlock.name())) {
            clazz = WriteWorkspaceFile.class;
            displaySymbol = "✏";
        } else if ("execute_terminal_command".equals(toolUseBlock.name())) {
            clazz = ExecuteTerminalCommand.class;
            displaySymbol = "\uD83D\uDCBB";
        } else if ("delegate_task".equals(toolUseBlock.name())) {
            clazz = DelegateTask.class;
            displaySymbol = "\uD83E\uDD16";
        } else {
            throw new IllegalArgumentException("Unknown tool: " + toolUseBlock.name());
        }

        // permissions.json decides: allow runs the tool, deny refuses it, prompt asks the user first.
        Permission permission = PermissionState.instance.getPermission(toolUseBlock.name());

        if (permission == Permission.DENY) {
            return "The tool " + toolUseBlock.name() + " is denied by the user's permission settings. Do not retry it; continue without it or ask the user how to proceed.";
        }

        if (permission == Permission.PROMPT && !askApproval(displaySymbol + " " + toolUseBlock.name(), toolUseBlock._input().toString())) {
            return "The user denied this action. Do not retry it; continue without it or ask the user how to proceed.";
        }

        var tool = toolUseBlock.input(clazz);
        toolUseDisplayFunction.accept(new ToolUseDisplayItem(displaySymbol + " " + toolUseBlock.name(), toolUseBlock._input().toString()));
        var result = tool != null ? tool.execute() : EMPTY_STRING;

        if ("write_workspace_file".equals(toolUseBlock.name()) || "execute_terminal_command".equals(toolUseBlock.name())) {
            openFolderRunnable.run();
        }

        if ("delegate_task".equals(toolUseBlock.name()) && (result instanceof DelegateTaskReturnItem returnItem)) {
            changedFiles.addAll(returnItem.changedItems());
            return returnItem.content();
        }

        return result;
    }

    /**
     * Calls a tool on an MCP server. The server's permission comes from permissions.json ("mcpServers" section).
     * A server without an entry has no permission and is refused.
     */
    private Object callMcpTool(BetaToolUseBlock toolUseBlock, McpToolBinding binding, Consumer<ToolUseDisplayItem> toolUseDisplayFunction) {
        String serverName = binding.serverName();
        Permission permission = PermissionState.instance.getMcpServerPermission(serverName);

        if (permission == Permission.DENY) {
            return "The MCP server '" + serverName + "' is denied by the user's permission settings (no permission is set for it in permissions.json). Do not retry it; continue without it or ask the user how to proceed.";
        }

        if (permission == Permission.PROMPT && !askApproval(MCP_DISPLAY_SYMBOL + " " + toolUseBlock.name(), toolUseBlock._input().toString())) {
            return "The user denied this action. Do not retry it; continue without it or ask the user how to proceed.";
        }

        toolUseDisplayFunction.accept(new ToolUseDisplayItem(MCP_DISPLAY_SYMBOL + " " + toolUseBlock.name(), toolUseBlock._input().toString()));

        try {
            Map<String, Object> arguments = new HashMap<>();
            Map<?, ?> input = toolUseBlock.input(Map.class);
            if (input != null) {
                input.forEach((key, value) -> arguments.put(String.valueOf(key), value));
            }

            McpSchema.CallToolResult result = binding.client().callTool(new McpSchema.CallToolRequest(binding.toolName(), arguments));

            String text = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining(NEWLINE_STRING));

            return Boolean.TRUE.equals(result.isError()) ? "Error: " + text : text;
        } catch (Exception e) {
            // Report MCP failures to the agent instead of breaking the loop.
            e.printStackTrace();
            return "Error: MCP tool " + binding.toolName() + " on server '" + serverName + "' failed: " + e.getMessage();
        }
    }

    private static McpSyncClient connect(MCPServer server) {
        URI uri = URI.create(server.getUrl());
        if (uri.getScheme() == null || uri.getRawAuthority() == null
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("url must be an http or https address: " + server.getUrl());
        }

        // The config holds the full URL; the transport wants the base address and the endpoint path separately.
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        String endpoint = (path == null || path.isEmpty()) ? "/mcp" : path;

        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .build();
        McpSyncClient mcp = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("crumb", "1.0.0"))
                .build();
        try {
            mcp.initialize();
        } catch (RuntimeException e) {
            mcp.closeGracefully();
            throw e;
        }
        return mcp;
    }

    private void closeMcpClients() {
        mcpClients.forEach(McpSyncClient::closeGracefully);
        mcpClients.clear();
        mcpToolDefinitions.clear();
        mcpToolBindings.clear();
    }

    /** Tool names are sent to the model, so they are prefixed with the server and limited to the allowed characters. */
    private static String exposedToolName(String serverName, String toolName) {
        String name = ("mcp__" + serverName + "__" + toolName).replaceAll("[^a-zA-Z0-9_-]", "_");
        return name.length() > MAX_TOOL_NAME_LENGTH ? name.substring(0, MAX_TOOL_NAME_LENGTH) : name;
    }

    private static BetaTool toBetaTool(String exposedName, McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();

        var properties = BetaTool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.forEach((key, value) -> properties.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
        }

        var inputSchema = BetaTool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(properties.build());
        if (schema.get("required") instanceof List<?> required) {
            inputSchema.required(required.stream().map(String::valueOf).toList());
        }

        return BetaTool.builder()
                .name(exposedName)
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(inputSchema.build())
                .build();
    }

    private static boolean askApproval(String title, String details) {
        UIState uiState = UIState.instance;

        var answer = new CompletableFuture<Boolean>();
        Platform.runLater(
                () -> uiState.getMessageHistoryVBox().getChildren()
                .add(MessageFactory.createApprovalMessage(title, details, answer, () -> uiState.getMessageHistoryVBox().getChildren().removeLast())));
        return answer.join();
    }

    /** An MCP tool as seen by the agent: which server it belongs to, the live client, and the tool's own name. */
    private record McpToolBinding(String serverName, McpSyncClient client, String toolName) {}
}
