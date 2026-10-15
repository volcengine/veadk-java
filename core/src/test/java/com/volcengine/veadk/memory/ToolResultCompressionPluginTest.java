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

import static org.assertj.core.api.Assertions.assertThat;

import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResultCompressionPluginTest {

    @Test
    void leavesSmallResultsUnchanged() {
        ToolResultCompressionPlugin plugin = new ToolResultCompressionPlugin(512, 128);

        assertThat(
                        plugin.afterToolCallback(null, Map.of(), null, Map.of("value", 42))
                                .isEmpty()
                                .blockingGet())
                .isTrue();
    }

    @Test
    void compactsLargeResultsAndPreservesDiagnosticsAndRepresentativeFacts() {
        ToolResultCompressionPlugin plugin = new ToolResultCompressionPlugin(512, 256);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            rows.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "name",
                                    "row-" + index,
                                    "score",
                                    index,
                                    "payload",
                                    "x".repeat(300))));
        }
        Map<String, Object> result = new LinkedHashMap<>(Map.of("status", "success", "rows", rows));

        Map<String, Object> compressed =
                plugin.afterToolCallback(null, Map.of(), null, result).blockingGet();

        assertThat(compressed)
                .containsEntry(ToolResultCompressionPlugin.COMPRESSED_MARKER, true)
                .containsEntry("status", "success");
        assertThat(compressed.get("original_chars")).isInstanceOf(Integer.class);
        assertThat(JSONUtil.toJson(compressed).length())
                .isLessThan(JSONUtil.toJson(result).length());
        assertThat(String.valueOf(compressed.get("summary")))
                .contains("row-0", "row-39", "_omitted_items")
                .doesNotContain("x".repeat(100));
    }

    @Test
    void doesNotCompressAnAlreadyCompactedResultAgain() {
        ToolResultCompressionPlugin plugin = new ToolResultCompressionPlugin(256, 128);
        Map<String, Object> result =
                Map.of(
                        ToolResultCompressionPlugin.COMPRESSED_MARKER,
                        true,
                        "summary",
                        "x".repeat(1000));

        assertThat(plugin.afterToolCallback(null, Map.of(), null, result).isEmpty().blockingGet())
                .isTrue();
    }
}
