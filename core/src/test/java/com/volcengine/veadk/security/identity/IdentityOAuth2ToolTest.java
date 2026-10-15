/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.sessions.State;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdentityOAuth2ToolTest {

    @Test
    void injectsImmediateTokenWithoutExposingOrAcceptingModelCredential() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        CalendarFunctions functions = new CalendarFunctions();
        AtomicInteger authorizationUrls = new AtomicInteger();
        IdentityOAuth2Tool tool =
                tool(
                        functions,
                        client,
                        new TestScheduler(),
                        ignored -> authorizationUrls.incrementAndGet());

        Map<String, Object> result =
                tool.runAsync(
                                Map.of("query", "weather", "access_token", "model-supplied"),
                                context())
                        .blockingGet();

        assertThat(result)
                .containsEntry("query", "weather")
                .containsEntry("token", "managed-token");
        assertThat(functions.calls).hasValue(1);
        assertThat(authorizationUrls).hasValue(0);
        assertThat(client.forceAuthenticationValues).containsExactly(false);
        assertThat(
                        tool.declaration()
                                .orElseThrow()
                                .parameters()
                                .orElseThrow()
                                .properties()
                                .orElseThrow())
                .containsOnlyKeys("query");
        assertThat(
                        tool.declaration()
                                .orElseThrow()
                                .parameters()
                                .orElseThrow()
                                .required()
                                .orElseThrow())
                .containsExactly("query");
    }

    @Test
    void waitsForUserAuthorizationThenRunsOriginalFunctionOnce() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"),
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"),
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "authorized-token"));
        CalendarFunctions functions = new CalendarFunctions();
        TestScheduler scheduler = new TestScheduler();
        List<String> urls = new ArrayList<>();
        IdentityOAuth2Tool tool = tool(functions, client, scheduler, urls::add);

        TestObserver<Map<String, Object>> observer =
                tool.runAsync(Map.of("query", "calendar"), context()).test();

        observer.assertNotComplete();
        assertThat(urls).containsExactly("https://identity.example/authorize");
        assertThat(functions.calls).hasValue(0);

        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);

        observer.assertComplete().assertNoErrors();
        assertThat(observer.values())
                .singleElement()
                .satisfies(value -> assertThat(value).containsEntry("token", "authorized-token"));
        assertThat(functions.calls).hasValue(1);
        assertThat(client.forceAuthenticationValues).containsExactly(false, false, false);
    }

    @Test
    void forwardsOAuthConfigurationToIdentity() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(OAuth2Credential.Type.TOKEN, "managed-token"));
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(
                                functionTool(new CalendarFunctions()),
                                new IdentityCredentials(client))
                        .providerName("calendar")
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
    void forcesOnlyInitialAuthorizationRequestAndTimesOutWithoutRunningFunction() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CalendarFunctions functions = new CalendarFunctions();
        TestScheduler scheduler = new TestScheduler();
        IdentityOAuth2Tool tool =
                IdentityOAuth2Tool.builder(functionTool(functions), new IdentityCredentials(client))
                        .providerName("calendar")
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
        assertThat(functions.calls).hasValue(0);
        assertThat(client.forceAuthenticationValues.get(0)).isTrue();
        assertThat(client.forceAuthenticationValues.stream().skip(1)).allMatch(value -> !value);
    }

    @Test
    void cancellationStopsPollingAndDoesNotRunFunction() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CalendarFunctions functions = new CalendarFunctions();
        TestScheduler scheduler = new TestScheduler();
        IdentityOAuth2Tool tool = tool(functions, client, scheduler, ignored -> {});
        TestObserver<Map<String, Object>> observer =
                tool.runAsync(Map.of("query", "events"), context()).test();

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS);
        int callsBeforeCancellation = client.forceAuthenticationValues.size();
        observer.dispose();
        scheduler.advanceTimeBy(10, TimeUnit.SECONDS);

        assertThat(client.forceAuthenticationValues).hasSize(callsBeforeCancellation);
        assertThat(functions.calls).hasValue(0);
    }

    @Test
    void authorizationUrlHandlerFailureStopsBeforePollingOrFunctionExecution() {
        SequenceIdentityClient client =
                new SequenceIdentityClient(
                        new OAuth2Credential(
                                OAuth2Credential.Type.AUTHORIZATION_URL,
                                "https://identity.example/authorize"));
        CalendarFunctions functions = new CalendarFunctions();
        TestScheduler scheduler = new TestScheduler();
        IdentityOAuth2Tool tool =
                tool(
                        functions,
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
        assertThat(functions.calls).hasValue(0);
    }

    @Test
    void validatesRequiredConfigurationAndPollingInterval() {
        FunctionTool functionTool = functionTool(new CalendarFunctions());
        IdentityCredentials credentials =
                new IdentityCredentials(
                        new SequenceIdentityClient(
                                new OAuth2Credential(OAuth2Credential.Type.TOKEN, "token")));

        assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(functionTool, credentials)
                                        .providerName("calendar")
                                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("authorizationUrlHandler");
        assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(functionTool, credentials)
                                        .providerName(" ")
                                        .onAuthorizationUrl(ignored -> {})
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName");
        assertThatThrownBy(
                        () ->
                                IdentityOAuth2Tool.builder(functionTool, credentials)
                                        .providerName("calendar")
                                        .pollInterval(Duration.ofNanos(1))
                                        .onAuthorizationUrl(ignored -> {})
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pollInterval");
    }

    private static IdentityOAuth2Tool tool(
            CalendarFunctions functions,
            IdentityClient client,
            TestScheduler scheduler,
            java.util.function.Consumer<String> handler) {
        return IdentityOAuth2Tool.builder(functionTool(functions), new IdentityCredentials(client))
                .providerName("calendar")
                .pollInterval(Duration.ofSeconds(1))
                .authorizationTimeout(Duration.ofSeconds(10))
                .onAuthorizationUrl(handler)
                .scheduler(scheduler)
                .build();
    }

    private static FunctionTool functionTool(CalendarFunctions functions) {
        return FunctionTool.create(functions, "search");
    }

    private static ToolContext context() {
        ToolContext context = mock(ToolContext.class);
        when(context.state()).thenReturn(new State(new HashMap<>()));
        when(context.agentName()).thenReturn("assistant");
        when(context.userId()).thenReturn("user-1");
        return context;
    }

    public static final class CalendarFunctions {
        private final AtomicInteger calls = new AtomicInteger();

        public Map<String, Object> search(
                @Schema(name = "query") String query,
                @Schema(name = "access_token") String accessToken) {
            calls.incrementAndGet();
            return Map.of("query", query, "token", accessToken);
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
