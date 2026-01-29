package com.volcengine.veadk.tools.sandbox;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.volcengine.veadk.integration.agentkit.AgentKitWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tool that executes code in the Volcengine Cloud Sandbox.
 *
 * <p>It uses Volcengine OpenAPI (InvokeTool) to execute code securely.
 */
public class RunCodeTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(RunCodeTool.class);
    private final AgentKitWrapper agentKitWrapper;

    public RunCodeTool() {
        super(
                "run_code",
                "Run code in a code sandbox and return the output. For C++ code, don't execute it"
                    + " directly, compile and execute via Python; write sources and object files to"
                    + " /tmp.",
                false);
        this.agentKitWrapper =
                new AgentKitWrapper(
                        EnvUtil.getAgentKitHost(),
                        EnvUtil.getAgentKitRegion(),
                        EnvUtil.getAccessKey(),
                        EnvUtil.getSecretKey());
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(
                FunctionDeclaration.builder()
                        .name(name())
                        .description(description())
                        .parameters(
                                Schema.builder()
                                        .type("OBJECT")
                                        .properties(
                                                ImmutableMap.of(
                                                        "code",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description("The code to run")
                                                                .build(),
                                                        "language",
                                                        Schema.builder()
                                                                .type("STRING")
                                                                .description(
                                                                        "The programming language"
                                                                            + " (e.g., python3)")
                                                                .build(),
                                                        "timeout",
                                                        Schema.builder()
                                                                .type("INTEGER")
                                                                .description(
                                                                        "The execution timeout in"
                                                                            + " seconds. Defaults"
                                                                            + " to 30.")
                                                                .build()))
                                        .required(ImmutableList.of("code", "language"))
                                        .build())
                        .build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        String code = (String) args.get("code");
        String language = (String) args.get("language");

        int timeout = 30; // Default
        if (args.containsKey("timeout")) {
            Object t = args.get("timeout");
            if (t instanceof Number) {
                timeout = ((Number) t).intValue();
            } else if (t instanceof String) {
                timeout = Integer.parseInt((String) t);
            }
        }
        int finalTimeout = timeout;

        return Single.fromCallable(() -> execute(code, language, finalTimeout, context));
    }

    private Map<String, Object> execute(
            String code, String language, int timeout, ToolContext context) {

        String sessionId = context.sessionId();
        logger.debug("Running code: lang={}, sessionId={}", language, sessionId);

        try {
            String toolId = EnvUtil.getAgentKitToolId();
            String output = agentKitWrapper.runCode(toolId, sessionId, code, language, timeout);
            return ImmutableMap.of("result", output);
        } catch (Exception e) {
            logger.error("Failed to execute code sandbox request", e);
            return ImmutableMap.of("error", e.getMessage());
        }
    }
}
