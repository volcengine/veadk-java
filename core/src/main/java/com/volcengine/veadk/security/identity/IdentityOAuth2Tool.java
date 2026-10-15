/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Adds managed OAuth2 authorization to an ADK function tool without exposing credentials to the
 * model.
 *
 * <p>When Identity returns an authorization URL, the tool invocation remains pending while this
 * wrapper notifies the application and polls Identity. The original tool runs once after an access
 * token is available.
 */
public final class IdentityOAuth2Tool extends BaseTool {
    private static final String ACCESS_TOKEN_ARGUMENT = "access_token";

    private final FunctionTool delegate;
    private final IdentityCredentials credentials;
    private final String providerName;
    private final String flow;
    private final List<String> scopes;
    private final String redirectUrl;
    private final boolean forceAuthentication;
    private final Duration pollInterval;
    private final Duration authorizationTimeout;
    private final Consumer<String> authorizationUrlHandler;
    private final Scheduler scheduler;
    private final Optional<FunctionDeclaration> declaration;

    private IdentityOAuth2Tool(Builder builder) {
        super(
                builder.delegate.name(),
                builder.delegate.description(),
                builder.delegate.longRunning());
        this.delegate = builder.delegate;
        this.credentials = builder.credentials;
        this.providerName = requireText(builder.providerName, "providerName");
        this.flow = builder.flow;
        this.scopes = List.copyOf(builder.scopes);
        this.redirectUrl = builder.redirectUrl;
        this.forceAuthentication = builder.forceAuthentication;
        this.pollInterval = requirePositive(builder.pollInterval, "pollInterval");
        this.authorizationTimeout =
                requirePositive(builder.authorizationTimeout, "authorizationTimeout");
        this.authorizationUrlHandler =
                Objects.requireNonNull(
                        builder.authorizationUrlHandler, "authorizationUrlHandler must be set.");
        this.scheduler = Objects.requireNonNull(builder.scheduler, "scheduler must be set.");
        this.declaration = hideCredential(delegate.declaration());
        delegate.customMetadata().forEach(this::setCustomMetadata);
    }

    public static Builder builder(FunctionTool delegate, IdentityCredentials credentials) {
        return new Builder(delegate, credentials);
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return declaration;
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        Objects.requireNonNull(args, "args must be set.");
        Objects.requireNonNull(context, "context must be set.");
        return requestCredential(context, forceAuthentication)
                .flatMap(
                        credential ->
                                credential.type() == OAuth2Credential.Type.TOKEN
                                        ? Single.just(credential.value())
                                        : waitForAuthorization(context, credential.value()))
                .flatMap(token -> delegate.runAsync(withCredential(args, token), context));
    }

    private Single<OAuth2Credential> requestCredential(
            ToolContext context, boolean forceAuthentication) {
        return Single.fromCallable(
                () ->
                        credentials.oauth2(
                                context.state(),
                                context.agentName(),
                                context.userId(),
                                providerName,
                                flow,
                                scopes,
                                redirectUrl,
                                forceAuthentication));
    }

    private Single<String> waitForAuthorization(ToolContext context, String authorizationUrl) {
        Completable notifyApplication =
                Completable.fromAction(() -> authorizationUrlHandler.accept(authorizationUrl));
        Flowable<String> tokens =
                Flowable.interval(
                                pollInterval.toMillis(),
                                pollInterval.toMillis(),
                                TimeUnit.MILLISECONDS,
                                scheduler)
                        .concatMapSingle(ignored -> requestCredential(context, false))
                        .filter(value -> value.type() == OAuth2Credential.Type.TOKEN)
                        .map(OAuth2Credential::value);
        return notifyApplication
                .andThen(tokens.firstOrError())
                .timeout(authorizationTimeout.toMillis(), TimeUnit.MILLISECONDS, scheduler);
    }

    private Map<String, Object> withCredential(Map<String, Object> args, String token) {
        Map<String, Object> securedArgs = new LinkedHashMap<>(args);
        securedArgs.put(ACCESS_TOKEN_ARGUMENT, token);
        return securedArgs;
    }

    private static Optional<FunctionDeclaration> hideCredential(
            Optional<FunctionDeclaration> declaration) {
        return declaration.map(
                value -> {
                    FunctionDeclaration.Builder builder = value.toBuilder();
                    value.parameters()
                            .map(IdentityOAuth2Tool::hideAccessToken)
                            .ifPresent(builder::parameters);
                    return builder.build();
                });
    }

    private static Schema hideAccessToken(Schema schema) {
        Map<String, Schema> properties =
                new LinkedHashMap<>(schema.properties().orElseGet(Map::of));
        properties.remove(ACCESS_TOKEN_ARGUMENT);
        List<String> required =
                schema.required().orElseGet(List::of).stream()
                        .filter(value -> !ACCESS_TOKEN_ARGUMENT.equals(value))
                        .toList();
        return schema.toBuilder().properties(properties).required(required).build();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set.");
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.toMillis() < 1) {
            throw new IllegalArgumentException(name + " must be at least one millisecond.");
        }
        return value;
    }

    public static final class Builder {
        private final FunctionTool delegate;
        private final IdentityCredentials credentials;
        private String providerName;
        private String flow;
        private List<String> scopes = List.of();
        private String redirectUrl;
        private boolean forceAuthentication;
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration authorizationTimeout = Duration.ofMinutes(5);
        private Consumer<String> authorizationUrlHandler;
        private Scheduler scheduler = Schedulers.io();

        private Builder(FunctionTool delegate, IdentityCredentials credentials) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must be set.");
            this.credentials = Objects.requireNonNull(credentials, "credentials must be set.");
        }

        public Builder providerName(String providerName) {
            this.providerName = providerName;
            return this;
        }

        public Builder flow(String flow) {
            this.flow = flow;
            return this;
        }

        public Builder scopes(List<String> scopes) {
            this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes must be set."));
            return this;
        }

        public Builder redirectUrl(String redirectUrl) {
            this.redirectUrl = redirectUrl;
            return this;
        }

        public Builder forceAuthentication(boolean forceAuthentication) {
            this.forceAuthentication = forceAuthentication;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder authorizationTimeout(Duration authorizationTimeout) {
            this.authorizationTimeout = authorizationTimeout;
            return this;
        }

        public Builder onAuthorizationUrl(Consumer<String> authorizationUrlHandler) {
            this.authorizationUrlHandler = authorizationUrlHandler;
            return this;
        }

        Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public IdentityOAuth2Tool build() {
            return new IdentityOAuth2Tool(this);
        }
    }
}
