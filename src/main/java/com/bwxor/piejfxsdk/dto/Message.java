package com.bwxor.piejfxsdk.dto;

import com.bwxor.piejfxsdk.type.Role;

/**
 * One entry of a session's history. For {@link Role#TOOL_USE} entries, content is the tool display name
 * and inputs holds the tool's input JSON; for other roles inputs is null.
 */
public record Message(String content, Role role, String inputs) {
    public Message(String content, Role role) {
        this(content, role, null);
    }
}
