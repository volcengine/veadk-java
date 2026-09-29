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

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.runner.Runner;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Scanner;

public class AgentCliRunner {

    public static void main(String[] args) {

        RunConfig runConfig =
                RunConfig.builder().streamingMode(RunConfig.StreamingMode.NONE).build();

        Runner runner = new Runner(ArkAgent.ROOT_AGENT);

        // enable VikingMemoryService
        // BaseMemoryService memoryService = new VikingMemoryService(ArkAgent.ROOT_AGENT.name());
        // Runner runner = new Runner(ArkAgent.ROOT_AGENT, memoryService);

        // enable OpenTelemetry
        // OpenTelemetry.initOpenTelemetry(Arrays.asList(new TLSExporter()));

        String userId = "user";
        String sessionId = "session";
        Session session =
                runner.sessionService()
                        .createSession(runner.appName(), userId, java.util.Map.of(), sessionId)
                        .blockingGet();

        try (Scanner scanner = new Scanner(System.in, UTF_8)) {
            while (true) {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine();
                if ("quit".equalsIgnoreCase(userInput)) {
                    // enable this block to save session to memory
                    // Maybe<Session> sessionMaybe =
                    //         runner.sessionService()
                    //                 .getSession(
                    //                         runner.appName(), userId, sessionId,
                    // Optional.empty());
                    // memoryService.addSessionToMemory(sessionMaybe.blockingGet()).blockingAwait();
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
}
