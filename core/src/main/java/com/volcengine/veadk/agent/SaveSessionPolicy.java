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
package com.volcengine.veadk.agent;

import java.time.Duration;

/** Controls when completed sessions are copied into long-term memory. */
public record SaveSessionPolicy(
        int minNewEvents,
        Duration minInterval,
        boolean saveOnSessionSwitch,
        boolean suppressErrors) {

    public static final int DEFAULT_MIN_NEW_EVENTS = 10;
    public static final Duration DEFAULT_MIN_INTERVAL = Duration.ofSeconds(60);

    public SaveSessionPolicy {
        if (minNewEvents < 0) {
            throw new IllegalArgumentException("minNewEvents must not be negative");
        }
        if (minInterval == null || minInterval.isNegative()) {
            throw new IllegalArgumentException("minInterval must not be negative");
        }
    }

    public static SaveSessionPolicy defaults() {
        return new SaveSessionPolicy(
                environmentInt("MIN_MESSAGES_THRESHOLD", DEFAULT_MIN_NEW_EVENTS),
                Duration.ofSeconds(
                        environmentInt(
                                "MIN_TIME_THRESHOLD",
                                Math.toIntExact(DEFAULT_MIN_INTERVAL.toSeconds()))),
                true,
                true);
    }

    /** Policy matching the original Java callback: save every invocation without switch flushing. */
    public static SaveSessionPolicy legacy() {
        return new SaveSessionPolicy(0, Duration.ZERO, false, true);
    }

    private static int environmentInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
