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
package com.volcengine.veadk.a2a.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight in-memory A2A Hub with the same HTTP contract as VeADK Python. */
public final class A2AHubServer implements AutoCloseable {

    private final Map<String, Map<String, JsonNode>> agentCards = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    public synchronized A2AHubServer start(String host, int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("A2A Hub server is already started.");
        }
        server = HttpServer.create(new InetSocketAddress(requireText(host, "host"), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        return this;
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("A2A Hub server is not started.");
        }
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/ping".equals(path)) {
                respond(exchange, Map.of("msg", "pong!"));
                return;
            }
            if ("POST".equals(method) && "/create_group".equals(path)) {
                String groupId = query(exchange).getOrDefault("group_id", "");
                agentCards.put(requireText(groupId, "group_id"), new ConcurrentHashMap<>());
                respond(exchange, baseResponse(Map.of("group_id", groupId)));
                return;
            }
            if ("POST".equals(method) && "/register_agent".equals(path)) {
                register(exchange);
                return;
            }
            if ("GET".equals(method) && "/groups".equals(path)) {
                respond(
                        exchange,
                        baseResponse(Map.of("group_ids", new ArrayList<>(agentCards.keySet()))));
                return;
            }
            String[] segments = path.split("/");
            if ("GET".equals(method)
                    && segments.length == 4
                    && "group".equals(segments[1])
                    && "agents".equals(segments[3])) {
                listAgents(exchange, decode(segments[2]));
                return;
            }
            if ("GET".equals(method)
                    && segments.length == 5
                    && "group".equals(segments[1])
                    && "agent".equals(segments[3])) {
                getAgent(exchange, decode(segments[2]), decode(segments[4]));
                return;
            }
            write(exchange, 404, Map.of("err_code", 1, "msg", "not found"));
        } catch (IllegalArgumentException e) {
            write(exchange, 400, Map.of("err_code", 1, "msg", e.getMessage()));
        } catch (Exception e) {
            write(exchange, 500, Map.of("err_code", 1, "msg", "internal server error"));
        } finally {
            exchange.close();
        }
    }

    private void register(HttpExchange exchange) throws IOException {
        JsonNode request = JSONUtil.parseJson(exchange.getRequestBody().readAllBytes());
        String groupId = request.path("group_id").asText("");
        String agentId = request.path("agent_id").asText("");
        JsonNode agentCard = request.path("agent_card");
        Map<String, JsonNode> group = agentCards.get(groupId);
        if (group == null) {
            respond(exchange, error("group " + groupId + " not exist"));
            return;
        }
        if (group.putIfAbsent(agentId, agentCard) != null) {
            respond(exchange, error("agent " + agentId + " already exist"));
            return;
        }
        respond(
                exchange,
                baseResponse(
                        Map.of("group_id", groupId, "agent_id", agentId, "agent_card", agentCard)));
    }

    private void listAgents(HttpExchange exchange, String groupId) throws IOException {
        Map<String, JsonNode> group = agentCards.get(groupId);
        if (group == null) {
            respond(exchange, error("group " + groupId + " not exist"));
            return;
        }
        List<Map<String, Object>> infos =
                group.entrySet().stream()
                        .map(
                                entry ->
                                        Map.<String, Object>of(
                                                "agent_id", entry.getKey(),
                                                "agent_card", entry.getValue()))
                        .toList();
        respond(exchange, baseResponse(Map.of("group_id", groupId, "agent_infos", infos)));
    }

    private void getAgent(HttpExchange exchange, String groupId, String agentId)
            throws IOException {
        Map<String, JsonNode> group = agentCards.get(groupId);
        if (group == null) {
            respond(exchange, error("group " + groupId + " not exist"));
            return;
        }
        JsonNode card = group.get(agentId);
        if (card == null) {
            respond(exchange, error("agent " + agentId + " in group " + groupId + " not exist"));
            return;
        }
        respond(exchange, baseResponse(Map.of("agent_id", agentId, "agent_card", card)));
    }

    private static Map<String, Object> baseResponse(Map<String, ?> values) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("err_code", 0);
        response.put("msg", "");
        response.putAll(values);
        return response;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("err_code", 1, "msg", message);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            values.put(decode(pair[0]), pair.length == 2 ? decode(pair[1]) : "");
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, Object body) throws IOException {
        write(exchange, 200, body);
    }

    private static void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSONUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set.");
        }
        return value;
    }
}
