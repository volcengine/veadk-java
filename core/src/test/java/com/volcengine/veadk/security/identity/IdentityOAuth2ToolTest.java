/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.State;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IdentityOAuth2ToolTest {

    @Test
    void injectsImmediateTokenWithoutExposingOrAcceptingCredentialArgument() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        CapturingTool delegate = new CapturingTool();
        AtomicInteger authorizationUrls = new AtomicInteger();
        IdentityOAuth2Tool tool =
                tool(
                        delegate,
                        client,
                        new TestScheduler(),
                        ignored -> authorizationUrls.incrementAndGet());
        ToolContext context = context();

        Map<String, Object> result =
                tool.runAsync(Map.of("query", "weather", "access_token", "model-supplied"), context)
                        .blockingGet();

        assertThat(result).containsEntry("token", "managed-token");
        assertThat(delegate.arguments.get())
                .containsEntry("query", "weather")
                .containsEntry("access_token", "managed-token");
        assertThat(authorizationUrls).hasValue(0);
        assertThat(client.forceAuthenticationValues).containsExactly(false);
        com.google.genai.types.Schema parameters =
                tool.declaration().orElseThrow().parameters().orElseThrow();
        assertThat(parameters.properties().orElseThrow()).containsOnlyKeys("query");
        assertThat(parameters.required().orElseThrow()).containsExactly("query");
    }

    @Test
    void hidesCredentialFromJsonSchemaDeclarations() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(new JsonSchemaTool(), new IdentityCredentials(client))
                        .providerName("calendar")
                        .credentialArgument("access_token")
                        .onAuthorizationUrl(ignored -> {})
                        .build();

        Object schema = tool.declaration().orElseThrow().parametersJsonSchema().orElseThrow();

        assertThat(schema)
                .isInstanceOfSatisfying(
                        Map.class,
                        value -> {
                            assertThat(value.get("properties"))
                                    .isEqualTo(Map.of("query", Map.of("type", "string")));
                            assertThat(value.get("required")).isEqualTo(List.of("query"));
                        });
    }

    @Test
    void forwardsOAuthConfigurationToIdentity() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(new CapturingTool(), new IdentityCredentials(client))
                        .providerName("calendar")
                        .credentialArgument("access_token")
                        .flow("USER_FEDERATION")
                        .scopes(List.of("calendar.read"))
                        .redirectUrl("https://app.example/oauth/callback")
                        .onAuthorizationUrl(ignored -> {})
                        .build();

        tool.runAsync(Map.of("query", "events"), context()).blockingGet();

        assertThat(client.providerName).isEqualTo("calendar");
        assertThat(client.identityToken).isEqualTo("workload-token");
        assertThat(client.flow).isEqualTo("USER_FEDERATION");
        assertThat(client.scopes).containsExactly("calendar.read");
        assertThat(client.redirectUrl).isEqualTo("https://app.example/oauth/callback");
    }

    @Test
    void wrapsAdkFunctionToolAndInjectsHiddenCredential() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        BaseTool functionTool = FunctionTool.create(CalendarFunction.class, "search");
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(functionTool, new IdentityCredentials(client))
                        .providerName("calendar")
                        .credentialArgument("access_token")
                        .onAuthorizationUrl(ignored -> {})
                        .build();

        Map<String, Object> result =
                tool.runAsync(Map.of("query", "events"), context()).blockingGet();

        assertThat(result).containsEntry("query", "events").containsEntry("token", "managed-token");
        assertThat(
                        tool.declaration()
                                .orElseThrow()
                                .parameters()
                                .orElseThrow()
                                .properties()
                                .orElseThrow())
                .containsOnlyKeys("query");
    }

    @Test
    void preservesDelegateRequestProcessing() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        CapturingTool delegate = new CapturingTool();
        IdentityOAuth2Tool tool = tool(delegate, client, new TestScheduler(), ignored -> {});

        tool.processLlmRequest(mock(LlmRequest.Builder.class), context()).blockingAwait();

        assertThat(delegate.processRequestCalls).hasValue(1);
    }

    @Test
    void waitsForUserAuthorizationThenRunsOriginalToolOnce() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"),
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"),
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "authorized-token"));
        CapturingTool delegate = new CapturingTool();
        TestScheduler scheduler = new TestScheduler();
        List<String> urls = new ArrayList<>();
        IdentityOAuth2Tool tool = tool(delegate, client, scheduler, urls::add);

        TestObserver<Map<String, Object>> observer =
                tool.runAsync(Map.of("query", "calendar"), context()).test();

        observer.assertNotComplete();
        assertThat(urls).containsExactly("https://identity.example/authorize");
        assertThat(delegate.calls).hasValue(0);

        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);

        observer.assertComplete().assertNoErrors();
        assertThat(observer.values())
                .singleElement()
                .satisfies(value -> assertThat(value).containsEntry("token", "authorized-token"));
        assertThat(delegate.calls).hasValue(1);
        assertThat(client.forceAuthenticationValues).containsExactly(false, false, false);
    }

    @Test
    void forcesOnlyInitialAuthorizationRequestAndTimesOutWithoutRunningTool() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CapturingTool delegate = new CapturingTool();
        TestScheduler scheduler = new TestScheduler();
        IdentityCredentials credentials = new IdentityCredentials(client);
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(delegate, credentials)
                        .providerName("calendar")
                        .credentialArgument("access_token")
                        .forceAuthentication(true)
                        .pollInterval(Duration.ofSeconds(1))
                        .authorizationTimeout(Duration.ofSeconds(3))
                        .onAuthorizationUrl(ignored -> {})
                        .scheduler(scheduler)
                        .build();

        TestObserver<Map<String, Object>> observer =
                tool.runAsync(Map.of("query", "events"), context()).test();
        scheduler.advanceTimeBy(3, TimeUnit.SECONDS);

        observer.assertError(java.util.concurrent.TimeoutException.class);
        assertThat(delegate.calls).hasValue(0);
        assertThat(client.forceAuthenticationValues.get(0)).isTrue();
        assertThat(client.forceAuthenticationValues.stream().skip(1)).allMatch(value -> !value);
    }

    @Test
    void cancellationStopsPollingAndDoesNotRunOriginalTool() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CapturingTool delegate = new CapturingTool();
        TestScheduler scheduler = new TestScheduler();
        IdentityOAuth2Tool tool = tool(delegate, client, scheduler, ignored -> {});
        TestObserver<Map<String, Object>> observer =
                tool.runAsync(Map.of("query", "events"), context()).test();

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);
        int callsBeforeCancellation = client.forceAuthenticationValues.size();
        observer.dispose();
        scheduler.advanceTimeBy(10, TimeUnit.SECONDS);

        assertThat(client.forceAuthenticationValues).hasSize(callsBeforeCancellation);
        assertThat(delegate.calls).hasValue(0);
    }

    @Test
    void authorizationUrlHandlerFailureStopsBeforePollingOrToolExecution() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CapturingTool delegate = new CapturingTool();
        TestScheduler scheduler = new TestScheduler();
        IdentityOAuth2Tool tool =
                tool(
                        delegate,
                        client,
                        scheduler,
                        ignored -> {
                            throw new IllegalStateException("delivery failed");
                        });

        tool.runAsync(Map.of("query", "events"), context())
                .test()
                .assertError(
                        error ->
                                error instanceof IllegalStateException
                                        && "delivery failed".equals(error.getMessage()));

        scheduler.advanceTimeBy(10, TimeUnit.SECONDS);
        assertThat(client.forceAuthenticationValues).hasSize(1);
        assertThat(delegate.calls).hasValue(0);
    }

    @Test
    void requiresAnAuthorizationUrlHandler() {
        CapturingTool delegate = new CapturingTool();
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "token"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(
                                                delegate, new IdentityCredentials(client))
                                        .providerName("calendar")
                                        .credentialArgument("access_token")
                                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("authorizationUrlHandler");
    }

    @Test
    void rejectsBlankIdentityConfigurationAndSubMillisecondIntervals() {
        CapturingTool delegate = new CapturingTool();
        IdentityCredentials credentials =
                new IdentityCredentials(
                        new SequenceIdentityClient(
                                new OAuth2Credential(OAuth2Credential.Type.TOKEN, "token")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(delegate, credentials)
                                        .providerName(" ")
                                        .credentialArgument("access_token")
                                        .onAuthorizationUrl(ignored -> {})
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(delegate, credentials)
                                        .providerName("calendar")
                                        .credentialArgument("access_token")
                                        .pollInterval(Duration.ofNanos(1))
                                        .onAuthorizationUrl(ignored -> {})
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
    }

    private static IdentityOAuth2Tool tool(
            CapturingTool delegate,
            IdentityClient client,
            TestScheduler scheduler,
            java.util.function.Consumer<String> handler) {
        return IdentityOAuth2Tool.builder(delegate, new IdentityCredentials(client))
                .providerName("calendar")
                .credentialArgument("access_token")
                .pollInterval(Duration.ofSeconds(1))
                .authorizationTimeout(Duration.ofSeconds(10))
                .onAuthorizationUrl(handler)
                .scheduler(scheduler)
                .build();
    }

    private static ToolContext context() {
        ToolContext context = mock(ToolContext.class);
        when(context.state()).thenReturn(new State(new HashMap<>()));
        when(context.agentName()).thenReturn("assistant");
        when(context.userId()).thenReturn("user-1");
        return context;
    }

    private static final class CapturingTool extends BaseTool {
        private final AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger processRequestCalls = new AtomicInteger();

        private CapturingTool() {
            super("calendar_search", "Search the user's calendar.");
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            return Optional.of(
                    FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parameters(
                                    com.google.genai.types.Schema.builder()
                                            .type("OBJECT")
                                            .properties(
                                                    ImmutableMap.of(
                                                            "query",
                                                            com.google.genai.types.Schema.builder()
                                                                    .type("STRING")
                                                                    .build(),
                                                            "access_token",
                                                            com.google.genai.types.Schema.builder()
                                                                    .type("STRING")
                                                                    .build()))
                                            .required("query", "access_token")
                                            .build())
                            .build());
        }

        @Override
        public Completable processLlmRequest(LlmRequest.Builder request, ToolContext context) {
            processRequestCalls.incrementAndGet();
            return Completable.complete();
        }

        @Override
        public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
            calls.incrementAndGet();
            arguments.set(Map.copyOf(args));
            return Single.just(Map.of("token", args.get("access_token")));
        }
    }

    public static final class CalendarFunction {
        public static Map<String, Object> search(
                @Schema(name = "query") String query,
                @Schema(name = "access_token") String accessToken) {
            return Map.of("query", query, "token", accessToken);
        }
    }

    private static final class JsonSchemaTool extends BaseTool {
        private JsonSchemaTool() {
            super("calendar_search", "Search the user's calendar.");
        }

        @Override
        public Optional<FunctionDeclaration> declaration() {
            Map<String, Object> properties =
                    Map.of(
                            "query", Map.of("type", "string"),
                            "access_token", Map.of("type", "string"));
            return Optional.of(
                    FunctionDeclaration.builder()
                            .name(name())
                            .description(description())
                            .parametersJsonSchema(
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            properties,
                                            "required",
                                            List.of("query", "access_token")))
                            .build());
        }
    }

    private static final class SequenceIdentityClient implements IdentityClient {
        private final Deque<OAuth2Credential> credentials = new ArrayDeque<>();
        private OAuth2Credential lastCredential;
        private final List<Boolean> forceAuthenticationValues = new ArrayList<>();
        private String providerName;
        private String identityToken;
        private String flow;
        private List<String> scopes;
        private String redirectUrl;

        private SequenceIdentityClient(OAuth2Credential... credentials) {
            this.credentials.addAll(List.of(credentials));
            this.lastCredential = credentials[credentials.length - 1];
        }

        @Override
        public WorkloadToken getWorkloadToken(
                String workloadName, String userId, String userToken) {
            return new WorkloadToken("workload-token", Instant.now().plusSeconds(3600));
        }

        @Override
        public boolean checkPermission(PermissionRequest request) {
            return true;
        }

        @Override
        public OAuth2Credential getOAuth2Credential(
                String providerName,
                String identityToken,
                String flow,
                List<String> scopes,
                String redirectUrl,
                boolean forceAuthentication) {
            this.providerName = providerName;
            this.identityToken = identityToken;
            this.flow = flow;
            this.scopes = scopes;
            this.redirectUrl = redirectUrl;
            forceAuthenticationValues.add(forceAuthentication);
            if (!credentials.isEmpty()) lastCredential = credentials.removeFirst();
            return lastCredential;
        }
    }
}
