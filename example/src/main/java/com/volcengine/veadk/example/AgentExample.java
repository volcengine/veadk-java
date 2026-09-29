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

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.volcengine.veadk.Agent;
import com.volcengine.veadk.runner.Runner;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** A minimal real Agent example backed by Ark and executed through Runner.run(...). */
public class AgentExample {

    private static final String MODEL_NAME = "doubao-seed-2-1-pro-260628";

    public static void main(String[] args) {
        Agent agent =
                Agent.builder()
                        .name("travel_assistant")
                        .description("Answers travel questions and checks local time when useful.")
                        .instruction(
                                """
                                You are a helpful travel assistant.
                                When the user asks about local time or scheduling, call the
                                getCurrentTime tool before answering.
                                Answer in the user's language.
                                """)
                        .modelName(MODEL_NAME)
                        .tools(FunctionTool.create(AgentExample.class, "getCurrentTime"))
                        .build();

        Runner runner = new Runner(agent);
        String answer = runner.run("我现在在北京，帮我判断现在适不适合约一个 30 分钟的线上会。");

        System.out.println("Agent answer:");
        System.out.println(answer);
    }

    @Schema(description = "Get the current local time for a city.")
    public static Map<String, String> getCurrentTime(
            @Schema(name = "city", description = "The city to check local time for.") String city) {
        ZoneId zoneId =
                switch (city.toLowerCase()) {
                    case "beijing", "北京", "shanghai", "上海" -> ZoneId.of("Asia/Shanghai");
                    case "tokyo", "东京" -> ZoneId.of("Asia/Tokyo");
                    case "london", "伦敦" -> ZoneId.of("Europe/London");
                    case "new york", "纽约" -> ZoneId.of("America/New_York");
                    default -> ZoneId.systemDefault();
                };
        String currentTime =
                ZonedDateTime.now(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return Map.of("city", city, "timezone", zoneId.getId(), "current_time", currentTime);
    }
}
