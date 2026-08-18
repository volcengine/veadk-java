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
package com.volcengine.veadk.memory.sqlite;

import com.google.adk.sessions.BaseSessionService;
import com.volcengine.veadk.memory.ShortTermMemoryBackend;
import java.nio.file.Path;
import java.util.Objects;

/** Optional SQLite backend for {@code ShortTermMemory}. */
public final class SQLiteShortTermMemoryBackend implements ShortTermMemoryBackend {

    private final Path databasePath;
    private final SQLiteSessionService sessionService;

    public SQLiteShortTermMemoryBackend(Path databasePath) {
        this.databasePath =
                Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        this.sessionService = new SQLiteSessionService(this.databasePath);
    }

    public SQLiteShortTermMemoryBackend(String databasePath) {
        this(Path.of(databasePath));
    }

    public Path databasePath() {
        return databasePath;
    }

    @Override
    public BaseSessionService sessionService() {
        return sessionService;
    }
}
