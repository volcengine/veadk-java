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
package com.volcengine.veadk.memory;

import java.util.Objects;

/** SQLite-backed session service for local persistent short-term memory. */
public final class SqliteSessionService extends JdbcSessionService {

    static final String JDBC_PREFIX = "jdbc:sqlite:";

    public SqliteSessionService(String localDatabasePathOrJdbcUrl) {
        super(toJdbcUrl(localDatabasePathOrJdbcUrl), Dialect.SQLITE);
    }

    private static String toJdbcUrl(String localDatabasePathOrJdbcUrl) {
        String value = Objects.requireNonNull(localDatabasePathOrJdbcUrl, "database path is null");
        if (value.startsWith(JDBC_PREFIX)) {
            return value;
        }
        return JDBC_PREFIX + value;
    }
}
