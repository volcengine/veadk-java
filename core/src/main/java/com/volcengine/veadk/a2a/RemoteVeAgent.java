/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.a2a;

import com.google.adk.a2a.agent.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.transport.rest.RestTransport;
import io.a2a.client.transport.rest.RestTransportConfigBuilder;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.TransportProtocol;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Remote A2A agent with VeADK-compatible URL and static authentication handling. */
public final class RemoteVeAgent extends BaseAgent {

    public enum AuthMethod {
        HEADER,
        QUERYSTRING;

        static AuthMethod parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.strip().toLowerCase()) {
                case "header" -> HEADER;
                case "querystring" -> QUERYSTRING;
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported auth method "
                                        + value
                                        + ", use `header` or `querystring` instead.");
            };
        }
    }

    private final String url;
    private final AuthMethod authMethod;
    private final AgentCard agentCard;
    private final RemoteA2AAgent delegate;
    private final Client client;

    private RemoteVeAgent(Builder builder, Built built) {
        super(builder.name, built.agentCard.description(), List.of(), List.of(), List.of());
        this.url = builder.url;
        this.authMethod = builder.authMethod;
        this.agentCard = built.agentCard;
        this.delegate = built.delegate;
        this.client = built.client;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String url() {
        return url;
    }

    public Optional<AuthMethod> authMethod() {
        return Optional.ofNullable(authMethod);
    }

    public AgentCard agentCard() {
        return agentCard;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
        return delegate.runAsync(invocationContext);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
        return delegate.runLive(invocationContext);
    }

    @Override
    public Completable close() {
        return Completable.mergeArray(
                super.close(), delegate.close(), Completable.fromAction(client::close));
    }

    /** Builder mirroring Python RemoteVeAgent's public connection options. */
    public static final class Builder {
        private String name;
        private String url;
        private String authToken;
        private AuthMethod authMethod;
        private A2AHttpClient httpClient;
        private Duration timeout = VeA2AHttpClient.DEFAULT_TIMEOUT;

        public Builder name(String name) {
            this.name = requireText(name, "name must be set.");
            return this;
        }

        public Builder url(String url) {
            this.url = stripTrailingSlash(requireText(url, "url must be set."));
            return this;
        }

        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder authMethod(String authMethod) {
            this.authMethod = AuthMethod.parse(authMethod);
            return this;
        }

        public Builder authMethod(AuthMethod authMethod) {
            this.authMethod = authMethod;
            return this;
        }

        public Builder httpClient(A2AHttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient must be set.");
            return this;
        }

        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive.");
            }
            this.timeout = timeout;
            return this;
        }

        public RemoteVeAgent build() {
            requireText(name, "name must be set.");
            requireText(url, "url must be set.");
            try {
                A2AHttpClient transport =
                        httpClient == null ? new VeA2AHttpClient(timeout) : httpClient;
                if (authToken != null && !authToken.isBlank() && authMethod != null) {
                    transport = new AuthenticatedHttpClient(transport, authMethod, authToken);
                }
                AgentCard discovered = A2A.getAgentCard(transport, url);
                AgentCard card =
                        new AgentCard.Builder(discovered)
                                .url(url)
                                .preferredTransport(TransportProtocol.HTTP_JSON.asString())
                                .additionalInterfaces(
                                        List.of(
                                                new AgentInterface(
                                                        TransportProtocol.HTTP_JSON.asString(),
                                                        url)))
                                .build();
                Client client =
                        Client.builder(card)
                                .clientConfig(ClientConfig.builder().setStreaming(false).build())
                                .withTransport(
                                        RestTransport.class,
                                        new RestTransportConfigBuilder().httpClient(transport))
                                .build();
                RemoteA2AAgent delegate =
                        RemoteA2AAgent.builder()
                                .name(name)
                                .agentCard(card)
                                .a2aClient(client)
                                .streaming(false)
                                .build();
                return new RemoteVeAgent(this, new Built(card, client, delegate));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize remote A2A agent " + name, e);
            }
        }
    }

    private record Built(AgentCard agentCard, Client client, RemoteA2AAgent delegate) {}

    private static final class AuthenticatedHttpClient implements A2AHttpClient {
        private final A2AHttpClient delegate;
        private final AuthMethod method;
        private final String token;

        private AuthenticatedHttpClient(A2AHttpClient delegate, AuthMethod method, String token) {
            this.delegate = delegate;
            this.method = method;
            this.token = token;
        }

        @Override
        public GetBuilder createGet() {
            return new AuthGet(delegate.createGet());
        }

        @Override
        public PostBuilder createPost() {
            return new AuthPost(delegate.createPost());
        }

        @Override
        public DeleteBuilder createDelete() {
            return new AuthDelete(delegate.createDelete());
        }

        private String authenticatedUrl(String value) {
            if (method != AuthMethod.QUERYSTRING) {
                return value;
            }
            URI uri = URI.create(value);
            String query = uri.getRawQuery();
            String tokenQuery = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
            String separator = query == null || query.isEmpty() ? "?" : "&";
            return value + separator + tokenQuery;
        }

        private <T extends A2AHttpClient.Builder<T>> T authenticate(T builder, String url) {
            builder.url(authenticatedUrl(url));
            if (method == AuthMethod.HEADER) {
                builder.addHeader("Authorization", "Bearer " + token);
            }
            return builder;
        }

        private final class AuthGet implements GetBuilder {
            private final GetBuilder delegateBuilder;

            private AuthGet(GetBuilder delegateBuilder) {
                this.delegateBuilder = delegateBuilder;
            }

            @Override
            public GetBuilder url(String url) {
                authenticate(delegateBuilder, url);
                return this;
            }

            @Override
            public GetBuilder addHeaders(Map<String, String> headers) {
                delegateBuilder.addHeaders(headers);
                return this;
            }

            @Override
            public GetBuilder addHeader(String name, String value) {
                delegateBuilder.addHeader(name, value);
                return this;
            }

            @Override
            public A2AHttpResponse get() throws IOException, InterruptedException {
                return delegateBuilder.get();
            }

            @Override
            public CompletableFuture<Void> getAsyncSSE(
                    Consumer<String> messageConsumer,
                    Consumer<Throwable> errorConsumer,
                    Runnable completeRunnable)
                    throws IOException, InterruptedException {
                return delegateBuilder.getAsyncSSE(
                        messageConsumer, errorConsumer, completeRunnable);
            }
        }

        private final class AuthPost implements PostBuilder {
            private final PostBuilder delegateBuilder;

            private AuthPost(PostBuilder delegateBuilder) {
                this.delegateBuilder = delegateBuilder;
            }

            @Override
            public PostBuilder url(String url) {
                authenticate(delegateBuilder, url);
                return this;
            }

            @Override
            public PostBuilder addHeaders(Map<String, String> headers) {
                delegateBuilder.addHeaders(headers);
                return this;
            }

            @Override
            public PostBuilder addHeader(String name, String value) {
                delegateBuilder.addHeader(name, value);
                return this;
            }

            @Override
            public PostBuilder body(String body) {
                delegateBuilder.body(body);
                return this;
            }

            @Override
            public A2AHttpResponse post() throws IOException, InterruptedException {
                return delegateBuilder.post();
            }

            @Override
            public CompletableFuture<Void> postAsyncSSE(
                    Consumer<String> messageConsumer,
                    Consumer<Throwable> errorConsumer,
                    Runnable completeRunnable)
                    throws IOException, InterruptedException {
                return delegateBuilder.postAsyncSSE(
                        messageConsumer, errorConsumer, completeRunnable);
            }
        }

        private final class AuthDelete implements DeleteBuilder {
            private final DeleteBuilder delegateBuilder;

            private AuthDelete(DeleteBuilder delegateBuilder) {
                this.delegateBuilder = delegateBuilder;
            }

            @Override
            public DeleteBuilder url(String url) {
                authenticate(delegateBuilder, url);
                return this;
            }

            @Override
            public DeleteBuilder addHeaders(Map<String, String> headers) {
                delegateBuilder.addHeaders(headers);
                return this;
            }

            @Override
            public DeleteBuilder addHeader(String name, String value) {
                delegateBuilder.addHeader(name, value);
                return this;
            }

            @Override
            public A2AHttpResponse delete() throws IOException, InterruptedException {
                return delegateBuilder.delete();
            }
        }
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
