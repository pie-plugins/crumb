package com.bwxor.piejfxsdk.state;

import com.bwxor.piejfxsdk.type.Permission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionState {
    public static final Map<String, Permission> DEFAULTS = defaults();
    public static final PermissionState instance = new PermissionState();

    /** Every built-in tool with the permission used when permissions.json does not mention it. */

    private volatile Map<String, Permission> permissions = new ConcurrentHashMap<>(DEFAULTS);

    /** Permission per MCP server name, from the "mcpServers" section of permissions.json. */
    private volatile Map<String, Permission> mcpServerPermissions = Map.of();

    private PermissionState() {}

    private static Map<String, Permission> defaults() {
        Map<String, Permission> map = new LinkedHashMap<>();
        map.put("list_workspace_files", Permission.ALLOW);
        map.put("read_workspace_file", Permission.ALLOW);
        map.put("write_workspace_file", Permission.PROMPT);
        map.put("execute_terminal_command", Permission.PROMPT);
        map.put("delegate_task", Permission.ALLOW);
        return map;
    }

    public void setPermissions(Map<String, Permission> permissions) {
        this.permissions = new ConcurrentHashMap<>(permissions);
    }

    public void setMcpServerPermissions(Map<String, Permission> mcpServerPermissions) {
        this.mcpServerPermissions = Map.copyOf(mcpServerPermissions);
    }

    /** Tools that are not listed in permissions.json always prompt. */
    public Permission getPermission(String toolName) {
        return permissions.getOrDefault(toolName, Permission.PROMPT);
    }

    /** MCP servers that are not listed in permissions.json always prompt. */
    public Permission getMcpServerPermission(String serverName) {
        return mcpServerPermissions.getOrDefault(serverName, Permission.PROMPT);
    }
}
