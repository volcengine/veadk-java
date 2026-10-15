package com.volcengine.veadk.tools.builtin.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LarkToolsetTest {

    @Test
    void buildsOfficialLarkMcpCommandWithoutStartingProcess() {
        var parameters = LarkToolset.connectionParameters("app-id", "app-secret", "user-token");

        assertEquals("npx", parameters.command());
        assertEquals(
                List.of(
                        "-y",
                        "@larksuiteoapi/lark-mcp",
                        "mcp",
                        "-a",
                        "app-id",
                        "-s",
                        "app-secret",
                        "-u",
                        "user-token"),
                parameters.args());
    }

    @Test
    void rejectsMissingCredentialsBeforeStartingChildProcess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LarkToolset.connectionParameters(" ", "secret", "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> LarkToolset.connectionParameters("app", null, "token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> LarkToolset.connectionParameters("app", "secret", ""));
    }
}
