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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RemoteVeAgentAuthTest {

    @Test
    void sendsBearerHeaderWhileResolvingCard() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (CardServer server =
                new CardServer(
                        exchange ->
                                authorization.set(
                                        exchange.getRequestHeaders().getFirst("Authorization")))) {
            RemoteVeAgent remote =
                    RemoteVeAgent.builder()
                            .name("remote")
                            .url(server.url())
                            .authToken("secret-token")
                            .authMethod("header")
                            .build();
            assertThat(authorization).hasValue("Bearer secret-token");
            assertThat(remote.agentCard().url()).isEqualTo(server.url());
            remote.close().blockingAwait();
        }
    }

    @Test
    void sendsQueryTokenWhileResolvingCard() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        try (CardServer server =
                new CardServer(exchange -> query.set(exchange.getRequestURI().getRawQuery()))) {
            RemoteVeAgent remote =
                    RemoteVeAgent.builder()
                            .name("remote")
                            .url(server.url())
                            .authToken("a b")
                            .authMethod("querystring")
                            .build();
            assertThat(query).hasValue("token=a+b");
            remote.close().blockingAwait();
        }
    }

    @Test
    void rejectsUnsupportedAuthMethod() {
        assertThatThrownBy(() -> RemoteVeAgent.builder().authMethod("cookie"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header")
                .hasMessageContaining("querystring");
    }

    @FunctionalInterface
    private interface RequestCapture {
        void accept(com.sun.net.httpserver.HttpExchange exchange);
    }

    private static final class CardServer implements AutoCloseable {
        private final HttpServer server;

        private CardServer(RequestCapture capture) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/.well-known/agent-card.json",
                    exchange -> {
                        capture.accept(exchange);
                        String url = "http://127.0.0.1:" + server.getAddress().getPort();
                        String body =
                                "{\"name\":\"remote\",\"description\":\"Remote agent\","
                                        + "\"url\":\""
                                        + url
                                        + "\",\"version\":\"1\","
                                        + "\"capabilities\":{\"streaming\":false,\"pushNotifications\":false,\"stateTransitionHistory\":false},"
                                        + "\"defaultInputModes\":[\"text\"],\"defaultOutputModes\":[\"text\"],"
                                        + "\"skills\":[],\"preferredTransport\":\"HTTP+JSON\"}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
