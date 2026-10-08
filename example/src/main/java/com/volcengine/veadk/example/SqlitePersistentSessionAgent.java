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

/** Demonstrates loading a persisted SQLite session from a fresh agent runner. */
public class SqlitePersistentSessionAgent {

    private static final String MODEL_NAME = "deepseek-v4-flash-ga-260731";
    private static final String APP_NAME = "sqlite_persistent_session_demo";
    private static final String USER_ID = "user_1";
    private static final String SESSION_ID = "session_1";
    private static final String SQLITE_PATH = "./target/veadk-persistent-session.db";

    public static void main(String[] args) {
        Runner firstRunner = createRunner();

        System.out.println("SQLite session database: " + SQLITE_PATH);
        System.out.println(firstRunner.run(USER_ID, SESSION_ID, "我叫小明，我喜欢咖啡。请记住。"));

        Runner freshRunner = createRunner();

        System.out.println(freshRunner.run(USER_ID, SESSION_ID, "我叫什么？我喜欢什么？"));
    }

    private static Runner createRunner() {
        ShortTermMemory shortTermMemory = ShortTermMemory.builder().sqlite(SQLITE_PATH).build();
        Agent agent =
                Agent.builder()
                        .name("sqlite_persistent_session_agent")
                        .instruction("Use the current session history to answer the user.")
                        .modelProvider("ark")
                        .modelName(MODEL_NAME)
                        .shortTermMemory(shortTermMemory)
                        .build();
        return new Runner(agent, APP_NAME);
    }
}
