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

import com.volcengine.veadk.Agent;
import com.volcengine.veadk.Runner;
import com.volcengine.veadk.memory.ShortTermMemory;

/** Demonstrates an agent backed by SQLite short-term memory. */
public class SqliteShortTermMemoryAgent {

    private static final String MODEL_NAME = "doubao-seed-2-1-pro-260628";
    private static final String SQLITE_PATH = "./target/veadk-short-term-memory.db";

    public static void main(String[] args) {
        ShortTermMemory shortTermMemory = ShortTermMemory.builder().sqlite(SQLITE_PATH).build();

        Agent agent =
                Agent.builder()
                        .name("sqlite_short_term_memory_agent")
                        .instruction("Remember facts the user tells you within this session.")
                        .modelProvider("ark")
                        .modelName(MODEL_NAME)
                        .shortTermMemory(shortTermMemory)
                        .build();

        Runner runner = new Runner(agent, "sqlite_short_term_memory_demo");

        System.out.println("SQLite session database: " + SQLITE_PATH);
        System.out.println(runner.run("user_1", "session_1", "我叫小明。"));
        System.out.println(runner.run("user_1", "session_1", "我叫什么？"));
    }
}
