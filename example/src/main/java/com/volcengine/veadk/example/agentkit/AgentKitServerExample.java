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
package com.volcengine.veadk.example.agentkit;

import com.google.adk.agents.BaseAgent;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.controller.ExecutionController;
import com.google.adk.web.dto.AgentRunRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** ADK Web APIs plus AgentKit health and invocation routes. Deploy behind a trusted gateway. */
@SpringBootApplication(scanBasePackages = "com.google.adk.web")
@Import(AgentKitServerExample.AgentKitRoutes.class)
public class AgentKitServerExample {
    @Bean(destroyMethod = "close")
    public AgentKitAgentConfiguration agentConfiguration() {
        return AgentKitAgentConfiguration.fromEnvironment(System.getenv());
    }

    @Bean
    @Primary
    public AgentLoader configuredAgentLoader(AgentKitAgentConfiguration configuration) {
        return new AgentLoader() {
            public ImmutableList<String> listAgents() {
                return ImmutableList.of(configuration.agent().name());
            }

            public BaseAgent loadAgent(String name) {
                if (!configuration.agent().name().equals(name)) {
                    throw new IllegalArgumentException("Unknown agent");
                }
                return configuration.agent();
            }
        };
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentKitServerExample.class);
        app.setDefaultProperties(
                Map.of(
                        "adk.agents.loader", "agentkit",
                        "spring.config.name", "agentkit",
                        "server.address",
                                System.getenv().getOrDefault("AGENTKIT_BIND_HOST", "127.0.0.1"),
                        "server.port", System.getenv().getOrDefault("PORT", "8000"),
                        "spring.mvc.async.request-timeout", "120000",
                        "logging.level.com.google.adk", "WARN",
                        "logging.level.com.volcengine.veadk", "INFO"));
        app.run(args);
    }

    @RestController
    public static class AgentKitRoutes {
        private final ExecutionController execution;
        private final BaseSessionService sessions;
        private final AgentKitAgentConfiguration configuration;

        public AgentKitRoutes(
                ExecutionController execution,
                BaseSessionService sessions,
                AgentKitAgentConfiguration configuration) {
            this.execution = execution;
            this.sessions = sessions;
            this.configuration = configuration;
        }

        @GetMapping("/ping")
        public Map<String, String> ping() {
            return Map.of("status", "pong!");
        }

        @GetMapping({"/health", "/readiness", "/liveness"})
        public Map<String, String> health() {
            return Map.of("status", "healthy");
        }

        @PostMapping(value = "/invoke", produces = "text/event-stream")
        public SseEmitter invoke(
                @RequestBody Map<String, Object> payload,
                @RequestHeader(value = "user_id", defaultValue = "agentkit_user") String user,
                @RequestHeader(value = "session_id", required = false) String session) {
            if (!(payload.get("prompt") instanceof String prompt) || prompt.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A non-empty prompt is required");
            }
            if (session == null) session = UUID.randomUUID().toString();
            if (!user.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || !session.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid context headers");
            }
            String appName = configuration.agent().name();
            String sessionId = session;
            // Creation is synchronized only for the short session lookup/create operation.
            synchronized (this) {
                sessions.getSession(appName, user, sessionId, Optional.empty())
                        .switchIfEmpty(sessions.createSession(appName, user, Map.of(), sessionId))
                        .blockingGet();
            }
            AgentRunRequest request = new AgentRunRequest();
            request.appName = appName;
            request.userId = user;
            request.sessionId = sessionId;
            request.newMessage = Content.fromParts(Part.fromText(prompt));
            request.streaming = true;
            return execution.agentRunSse(request);
        }
    }
}
