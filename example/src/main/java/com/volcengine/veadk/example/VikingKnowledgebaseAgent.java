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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.knowledgebase.KnowledgeBase;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.runner.Runner;
import com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseTool;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.Scanner;
import org.apache.commons.lang3.StringUtils;

public class VikingKnowledgebaseAgent {

    private static final String APP_NAME = "viking_knowledgebase_agent";
    private static final String MODEL_ID = "deepseek-v4-flash-260425";
    private static final String DEFAULT_COLLECTION = "viking_knowledgebase_agent";

    public static void main(String[] args) throws IOException {
        try (KnowledgeBase vikingKnowledge =
                KnowledgeBase.builder()
                        .backend("viking")
                        .appName(resolveCollectionName())
                        .topK(5)
                        .build()) {
            loadTosDocuments(vikingKnowledge, args);

            BaseAgent agent =
                    LlmAgent.builder()
                            .name(APP_NAME)
                            .description("A helpful assistant with a Viking knowledgebase.")
                            .instruction(
                                    """
                                    Answer user questions to the best of your knowledge.
                                    Use loadKnowledgebase first when the user asks about documents
                                    stored in the Viking knowledgebase.
                                    """)
                            .model(new ArkLlm(MODEL_ID))
                            .tools(new LoadKnowledgebaseTool(vikingKnowledge))
                            .build();

            Runner runner = new Runner(agent);
            RunConfig runConfig =
                    RunConfig.builder().streamingMode(RunConfig.StreamingMode.NONE).build();

            String userId = "user";
            String sessionId = "viking-knowledgebase-session";
            Session session =
                    runner.sessionService()
                            .createSession(runner.appName(), userId, java.util.Map.of(), sessionId)
                            .blockingGet();

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
    }

    private static String resolveCollectionName() {
        String collectionName = System.getenv("DATABASE_VIKING_KNOWLEDGEBASE_APP_NAME");
        return StringUtils.isBlank(collectionName) ? DEFAULT_COLLECTION : collectionName;
    }

    private static void loadTosDocuments(KnowledgeBase knowledgeBase, String[] args)
            throws IOException {
        if (args == null || args.length == 0) {
            System.out.println("No TOS documents provided. Using existing Viking knowledgebase.");
            System.out.println(
                    "You can pass one or more tos:// URIs as arguments to add documents.");
            return;
        }

        for (String tosUri : args) {
            if (StringUtils.isBlank(tosUri)) {
                continue;
            }
            if (!tosUri.startsWith("tos://")) {
                System.out.println("Skip non-TOS document URI: " + tosUri);
                continue;
            }
            if (knowledgeBase.addDoc(tosUri)) {
                System.out.println("Added TOS document to Viking knowledgebase: " + tosUri);
            } else {
                System.out.println("Failed to add TOS document to Viking knowledgebase: " + tosUri);
            }
        }
    }
}
