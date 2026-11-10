package com.bwxor.piejfxsdk.service;

import com.bwxor.piejfxsdk.state.ConfigurationState;
import com.bwxor.piejfxsdk.state.MCPServerState;
import com.bwxor.piejfxsdk.state.PermissionState;
import com.bwxor.piejfxsdk.type.Permission;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConfigurationService {
    private static final String EMPTY_STRING = "";
    private static final int INDENT_FACTOR = 2;

    public void loadCredentials() throws IOException {
        ConfigurationState configurationState = ConfigurationState.instance;
        File confFile = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString(), "config.json").toUri());

        createDirectoryAndFile();

        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(confFile))) {
            String confContent = bufferedReader.readAllAsString();

            JSONObject jsonObject = new JSONObject(confContent);
            JSONObject config = jsonObject.getJSONObject("config");

            configurationState.setApiKey(config.getString("apiKey"));

            String modelKey = config.optString("model", null);
            configurationState.setModel(modelKey);

            configurationState.setMaxIterations(config.optInt("maxIterations", 50));
        }
    }

    /** Reads mcp_servers.json (creating it if missing) into MCPServerState. */
    public void loadMcpServers() throws IOException {
        File mcpFile = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString(), "mcp_servers.json").toUri());

        createMcpFileIfMissing();

        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(mcpFile))) {
            String content = bufferedReader.readAllAsString();
            JSONObject jsonObject = new JSONObject(content);
            MCPServerState.fromJson(jsonObject);
        } catch (JSONException e) {
            // Keep the previous servers in place and let the caller notify the user.
            throw new IOException("Invalid mcp_servers.json: " + e.getMessage(), e);
        }
    }

    /**
     * Loads permissions.json (creating it with defaults if missing). Tools missing from the file keep their
     * default permission; unrecognised values are treated as "prompt".
     * The optional "mcpServers" section maps MCP server names to a permission; servers not listed are denied.
     */
    public void loadPermissions() throws IOException {
        File permissionsFile = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString(), "permissions.json").toUri());

        createPermissionsFileIfMissing(permissionsFile);

        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(permissionsFile))) {
            JSONObject root = new JSONObject(bufferedReader.readAllAsString());
            JSONObject entries = root.getJSONObject("permissions");

            Map<String, Permission> loaded = new LinkedHashMap<>(PermissionState.DEFAULTS);
            for (String toolName : entries.keySet()) {
                loaded.put(toolName, Permission.fromString(entries.optString(toolName, null)));
            }
            PermissionState.instance.setPermissions(loaded);

            Map<String, Permission> mcpLoaded = new LinkedHashMap<>();
            JSONObject mcpEntries = root.optJSONObject("mcpServers");
            if (mcpEntries != null) {
                for (String serverName : mcpEntries.keySet()) {
                    mcpLoaded.put(serverName, Permission.fromString(mcpEntries.optString(serverName, null)));
                }
            }
            PermissionState.instance.setMcpServerPermissions(mcpLoaded);
        } catch (JSONException e) {
            // Keep the previous permissions in place and let the caller notify the user.
            throw new IOException("Invalid permissions.json: " + e.getMessage(), e);
        }
    }

    private void createDirectoryAndFile() {
        File confDir = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString()).toUri());
        File confFile = new File(Paths.get(confDir.toString(), "config.json").toUri());

        if (!confDir.exists()) {
            confDir.mkdirs();
            createFile();
        } else if (!confFile.exists()) {
            createFile();
        }
    }

    private void createFile() {
        File confDir = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString()).toUri());
        File confFile = new File(Paths.get(confDir.toString(), "config.json").toUri());

        try {
            confFile.createNewFile();

            BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(confFile));

            JSONObject root = new JSONObject();
            JSONObject config = new JSONObject();
            root.put("config", config);

            config.put("apiKey", EMPTY_STRING);
            config.put("model", "anthropic/claude-haiku-4.5");
            config.put("maxIterations", 50);

            bufferedWriter.write(root.toString(INDENT_FACTOR));
            bufferedWriter.flush();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createMcpFileIfMissing() {
        File mcpFile = new File(Paths.get(ConfigurationState.instance.getConfigurationDirectory().toString(), "mcp_servers.json").toUri());

        if (!mcpFile.exists()) {
            try {
                mcpFile.createNewFile();

                JSONObject root = new JSONObject();
                JSONArray servers = new JSONArray();
                root.put("mcpServers", servers);

                try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(mcpFile))) {
                    bufferedWriter.write(root.toString(INDENT_FACTOR));
                    bufferedWriter.flush();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void createPermissionsFileIfMissing(File permissionsFile) throws IOException {
        if (permissionsFile.exists()) {
            return;
        }

        File dir = permissionsFile.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        JSONObject entries = new JSONObject();
        for (Map.Entry<String, Permission> entry : PermissionState.DEFAULTS.entrySet()) {
            entries.put(entry.getKey(), entry.getValue().toJsonValue());
        }

        JSONObject root = new JSONObject();
        root.put("permissions", entries);
        root.put("mcpServers", new JSONObject());

        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(permissionsFile))) {
            bufferedWriter.write(root.toString(INDENT_FACTOR));
            bufferedWriter.flush();
        }
    }
}
