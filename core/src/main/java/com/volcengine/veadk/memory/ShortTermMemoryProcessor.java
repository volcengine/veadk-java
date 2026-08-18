/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.memory;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Rewrites loaded session history using a caller-provided message optimizer. */
public final class ShortTermMemoryProcessor {

    private final Function<List<ShortTermMemoryMessage>, List<ShortTermMemoryMessage>> optimizer;

    public ShortTermMemoryProcessor(
            Function<List<ShortTermMemoryMessage>, List<ShortTermMemoryMessage>> optimizer) {
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
    }

    public Session afterLoadSession(Session session) {
        Objects.requireNonNull(session, "session");
        List<ShortTermMemoryMessage> sourceMessages = extractMessages(session.events());
        List<ShortTermMemoryMessage> optimizedMessages =
                List.copyOf(Objects.requireNonNull(optimizer.apply(sourceMessages), "messages"));
        List<Event> optimizedEvents =
                optimizedMessages.stream().map(ShortTermMemoryProcessor::toEvent).toList();
        return Session.builder(session.id())
                .appName(session.appName())
                .userId(session.userId())
                .state(new ConcurrentHashMap<>(session.state()))
                // ADK's default appendEvent implementation appends in place. Stream.toList()
                // returns an unmodifiable list, so keep processor-created session views mutable.
                .events(new ArrayList<>(optimizedEvents))
                .lastUpdateTime(session.lastUpdateTime())
                .build();
    }

    private static List<ShortTermMemoryMessage> extractMessages(List<Event> events) {
        List<ShortTermMemoryMessage> messages = new ArrayList<>();
        for (Event event : events) {
            if (event.content().isEmpty()) {
                continue;
            }
            Content content = event.content().get();
            List<Part> parts = content.parts().orElse(List.of());
            if (parts.isEmpty() || parts.get(0).text().isEmpty()) {
                continue;
            }
            messages.add(
                    new ShortTermMemoryMessage(
                            content.role().orElse(event.author()), parts.get(0).text().get()));
        }
        return List.copyOf(messages);
    }

    private static Event toEvent(ShortTermMemoryMessage message) {
        return Event.builder()
                .author("memory_optimizer")
                .content(
                        Content.builder()
                                .role(message.role())
                                .parts(List.of(Part.fromText(message.content())))
                                .build())
                .build();
    }
}
