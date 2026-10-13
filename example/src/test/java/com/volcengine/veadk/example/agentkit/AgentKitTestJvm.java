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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Launches test fixtures with the current JaCoCo collector, when Maven enables it. */
final class AgentKitTestJvm {
    private AgentKitTestJvm() {}

    static ProcessBuilder process(Class<?> mainClass, String... args) {
        var command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + "/bin/java");
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("org.jacoco.agent"))
                .forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), mainClass.getName()));
        command.addAll(Arrays.asList(args));
        var process = new ProcessBuilder(command);
        // A developer's runtime settings must not select a different fixture/model.
        process.environment()
                .keySet()
                .removeIf(
                        key ->
                                key.startsWith("AGENTKIT_")
                                        || key.startsWith("MODEL_AGENT_")
                                        || key.equals("PORT"));
        return process;
    }
}
