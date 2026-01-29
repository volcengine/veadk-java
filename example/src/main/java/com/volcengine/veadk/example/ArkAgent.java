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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LoadMemoryTool;
import com.volcengine.veadk.agent.SaveSessionToMemoryCallback;
import com.volcengine.veadk.knowledgebase.viking.VikingKnowledgebaseService;
import com.volcengine.veadk.model.ArkLlm;
import com.volcengine.veadk.tools.knowledgebase.LoadKnowledgebaseTool;
import com.volcengine.veadk.tools.sandbox.RunCodeTool;
import com.volcengine.veadk.tools.websearch.WebSearchTool;
import java.util.Map;

public class ArkAgent {

    public static BaseAgent ROOT_AGENT = initAgent();
    //     public static BaseAgent ROOT_AGENT = initAgentWithVeTools();

    private static final String appName = "ark_agent";
    private static final String modelId = "doubao-seed-1-8-251228";

    private static BaseAgent initAgent() {
        return LlmAgent.builder()
                .name(appName)
                .description("A helpful assistant for user questions.")
                .instruction(
                        """
                        Answer user questions to the best of your knowledge.
                        1. use the 'getCurrentTime' tool to query the city’s current time.
                        2. use the 'getWeather' tool to query the city’s current weather.
                        """)
                .model(new ArkLlm(modelId))
                .tools(
                        FunctionTool.create(ArkAgent.class, "getCurrentTime"),
                        FunctionTool.create(ArkAgent.class, "getWeather"))
                .build();
    }

    private static BaseAgent initAgentWithVeTools() {
        return LlmAgent.builder()
                .name(appName)
                .description("A helpful assistant for user questions.")
                .instruction(
                        """
                        You are a helpful assistant, you can:
                        1. use the 'loadMemory' to load information from memory. This function can retrieve user personal information including height, gender, age, weight, birthday, and other profile data when available.
                        2. use the 'loadKnowledgebase' to load information from knowledgebase. This function can retrieve enterprise internal professional information, corporate documents, business data, and specialized resources when available.
                        3. use the 'web_search' to search the information. This function can retrieve real-time internet information including current news, market trends, public data, supplementary research.
                        Tool Usage Strategy:
                        • Personal data → loadMemory
                        • Company/internal info → loadKnowledgebase
                        • Current/general info → web_search
                        • Complex queries → Combine relevant tools
                        • Always check internal sources before searching web for company topics
                        """)
                .model(new ArkLlm(modelId))
                .tools(
                        FunctionTool.create(ArkAgent.class, "getCurrentTime"),
                        FunctionTool.create(ArkAgent.class, "getWeather"),
                        // enable web search tool
                        new WebSearchTool(),
                        // enable knowledgebase tool
                        new LoadKnowledgebaseTool(new VikingKnowledgebaseService(appName)),
                        // enable memory tool
                        new LoadMemoryTool(),
                        // enable runcode tool
                        new RunCodeTool())
                .afterAgentCallback(new SaveSessionToMemoryCallback())
                .build();
    }

    /**
     * Mock tool implementation
     */
    @Schema(description = "Get the current time for a given city")
    public static Map<String, String> getCurrentTime(
            @Schema(name = "city", description = "Name of the city to get the time for")
                    String city) {
        return Map.of("city", city, "forecast", "The time is 10:30am.");
    }

    @Schema(description = "Get the weather for a given city")
    public static Map<String, String> getWeather(
            @Schema(
                            name = "city",
                            description =
                                    "The name of the city for which to retrieve the weather report")
                    String city) {
        return Map.of(
                "status",
                "success",
                "report",
                "The weather in "
                        + city
                        + " is sunny with a temperature of 25 degrees Celsius (77 degrees"
                        + " Fahrenheit).");
    }
}
