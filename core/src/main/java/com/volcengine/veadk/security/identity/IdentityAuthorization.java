/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fail-closed Agent and tool invocation authorization backed by Identity. */
public final class IdentityAuthorization {
    private static final String FAILURE = "Failed to verify authorization.";
    private final IdentityClient client;
    private final WorkloadTokenManager tokenManager;
    private final String namespace;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdentityAuthorization(IdentityClient client) {
        this(
                client,
                new WorkloadTokenManager(client),
                env("RUNTIME_IDENTITY_NAMESPACE", "default"));
    }

    IdentityAuthorization(
            IdentityClient client, WorkloadTokenManager tokenManager, String namespace) {
        this.client = Objects.requireNonNull(client);
        this.tokenManager = Objects.requireNonNull(tokenManager);
        this.namespace = Objects.requireNonNull(namespace);
    }

    public Callbacks.BeforeAgentCallback beforeAgentCallback() {
        return context ->
                Maybe.fromCallable(
                        () ->
                                authorizeAgent(
                                        context.state(), context.agentName(), context.userId()));
    }

    public Callbacks.BeforeToolCallback beforeToolCallback() {
        return (invocation, tool, args, toolContext) ->
                Maybe.fromCallable(() -> authorizeTool(invocation, tool));
    }

    private Content authorizeAgent(Map<String, Object> state, String agentName, String userId) {
        try {
            Claims claims = claims(tokenManager.get(state, agentName, userId));
            if (claims.userId == null || claims.actors.isEmpty()) return content(FAILURE);
            String agent = claims.actors.get(0);
            PermissionRequest request =
                    request(claims, "agent", agent, claims.actors.subList(1, claims.actors.size()));
            return client.checkPermission(request)
                    ? null
                    : content(
                            "Agent "
                                    + agent
                                    + " is not authorized to run by user "
                                    + claims.userId
                                    + ".");
        } catch (Exception e) {
            return content(FAILURE);
        }
    }

    private Map<String, Object> authorizeTool(InvocationContext invocation, BaseTool tool) {
        try {
            String agentName = invocation.agent().name();
            Claims claims =
                    claims(
                            tokenManager.get(
                                    invocation.session().state(), agentName, invocation.userId()));
            if (claims.userId == null || claims.actors.isEmpty()) return Map.of("result", FAILURE);
            PermissionRequest request = request(claims, "tool", tool.name(), claims.actors);
            return client.checkPermission(request)
                    ? null
                    : Map.of(
                            "result",
                            "Tool "
                                    + tool.name()
                                    + " is not authorized for user "
                                    + claims.userId
                                    + ".");
        } catch (Exception e) {
            return Map.of("result", FAILURE);
        }
    }

    private PermissionRequest request(
            Claims claims, String resourceType, String resourceId, List<String> callers) {
        return new PermissionRequest(
                namespace,
                new PermissionRequest.Principal("user", claims.userId),
                new PermissionRequest.Principal("Action", "invoke"),
                new PermissionRequest.Principal(resourceType, resourceId),
                callers.stream().map(id -> new PermissionRequest.Principal("agent", id)).toList());
    }

    private Claims claims(String jwt) {
        try {
            String value = jwt.regionMatches(true, 0, "Bearer ", 0, 7) ? jwt.substring(7) : jwt;
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3) return new Claims(null, List.of());
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(pad(parts[1])));
            JsonNode subject = payload.get("sub");
            if (subject == null || subject.asText().isBlank()) return new Claims(null, List.of());
            List<String> actors = new ArrayList<>();
            JsonNode actor = payload.get("act");
            while (actor != null && actor.isObject()) {
                JsonNode actorSubject = actor.get("sub");
                if (actorSubject != null && !actorSubject.asText().isBlank())
                    actors.add(actorSubject.asText());
                actor = actor.get("act");
            }
            return new Claims(subject.asText(), List.copyOf(actors));
        } catch (Exception e) {
            return new Claims(null, List.of());
        }
    }

    private static String pad(String value) {
        return value + "=".repeat((4 - value.length() % 4) % 4);
    }

    private static Content content(String text) {
        return Content.builder().role("model").parts(List.of(Part.fromText(text))).build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record Claims(String userId, List<String> actors) {}
}
