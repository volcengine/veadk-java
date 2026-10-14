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

import com.google.adk.agents.BaseAgent;
import com.volcengine.veadk.Version;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.TransportProtocol;
import java.util.List;
import java.util.Objects;

/** Creates the public A2A card used by VeADK agents. */
public final class AgentCards {

    private AgentCards() {}

    public static AgentCard fromAgent(BaseAgent agent, String url) {
        return fromAgent(agent, url, Version.JAVA_VEADK_VERSION, "veadk");
    }

    public static AgentCard fromAgent(
            BaseAgent agent, String url, String version, String providerOrganization) {
        Objects.requireNonNull(agent, "agent must be set.");
        String resolvedUrl = requireText(url, "url must be set.");
        return new AgentCard.Builder()
                .name(agent.name())
                .description(agent.description())
                .url(resolvedUrl)
                .provider(
                        new AgentProvider(
                                requireText(providerOrganization, "provider must be set."), ""))
                .version(requireText(version, "version must be set."))
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(
                        List.of(
                                new AgentSkill(
                                        "0",
                                        "chat",
                                        "Basically chat with user.",
                                        List.of("chat", "talk"),
                                        null,
                                        null,
                                        null,
                                        null)))
                .preferredTransport(TransportProtocol.HTTP_JSON.asString())
                .build();
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
