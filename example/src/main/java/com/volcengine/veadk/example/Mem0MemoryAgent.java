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
package com.volcengine.veadk.example;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.tools.LoadMemoryTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.memory.SaveSessionToMemoryCallback;
import com.volcengine.veadk.memory.mem0.Mem0MemoryService;
import com.volcengine.veadk.model.ArkLlm;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import java.util.Scanner;

public class Mem0MemoryAgent {

    private static final String APP_NAME = "mem0_memory_agent";
    private static final String MODEL_ID = "doubao-seed-1-8-251228";

    public static void main(String[] args) {
        BaseAgent agent =
                LlmAgent.builder()
                        .name(APP_NAME)
                        .description("A helpful assistant with Mem0 long-term memory.")
                        .instruction(
                                """
                                Answer user questions to the best of your knowledge.
                                Use loadMemory when the user asks about facts that may have been
                                mentioned in previous sessions.
                                """)
                        .model(new ArkLlm(MODEL_ID))
                        .tools(new LoadMemoryTool())
                        .afterAgentCallback(new SaveSessionToMemoryCallback())
                        .build();

        Mem0MemoryService memoryService =
                Mem0MemoryService.builder().appName(APP_NAME).topK(5).build();
        Runner runner = new Runner(agent, memoryService);
        RunConfig runConfig =
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.NONE).build();

        String userId = "user";
        String sessionId = "mem0-session";
        Session session = getOrCreateSession(runner, userId, sessionId);

        try (Scanner scanner = new Scanner(System.in, UTF_8)) {
            while (true) {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine();
                if ("quit".equalsIgnoreCase(userInput)) {
                    break;
                }

                Content userMsg = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events =
                        runner.runAsync(session.userId(), session.id(), userMsg, runConfig);

                events.blockingForEach(
                        event -> {
                            if (event.finalResponse()) {
                                System.out.println("\nAgent > " + event.stringifyContent());
                            }
                        });
            }
        }
    }

    private static Session getOrCreateSession(Runner runner, String userId, String sessionId) {
        return runner.sessionService()
                .getSession(runner.appName(), userId, sessionId, Optional.empty())
                .switchIfEmpty(
                        runner.sessionService()
                                .createSession(
                                        runner.appName(), userId, java.util.Map.of(), sessionId))
                .blockingGet();
    }
}
