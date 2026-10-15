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

import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.volcengine.veadk.utils.JSONUtil;
import io.reactivex.rxjava3.core.Maybe;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compresses oversized tool responses while retaining bounded evidence for the model. */
public final class ToolResultCompressionPlugin extends BasePlugin {

    static final String COMPRESSED_MARKER = "veadk_compressed";
    private static final int MAX_DEPTH = 6;
    private static final int MAX_MAP_ENTRIES = 24;
    private static final int MAX_COLLECTION_ITEMS = 12;

    private final int maxResultChars;
    private final int summaryChars;

    public ToolResultCompressionPlugin(int maxResultChars, int summaryChars) {
        super("veadk_tool_result_compression");
        if (maxResultChars < 256) {
            throw new IllegalArgumentException("maxResultChars must be at least 256.");
        }
        if (summaryChars < 128 || summaryChars >= maxResultChars) {
            throw new IllegalArgumentException(
                    "summaryChars must be at least 128 and less than maxResultChars.");
        }
        this.maxResultChars = maxResultChars;
        this.summaryChars = summaryChars;
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(
            BaseTool tool,
            Map<String, Object> toolArgs,
            ToolContext toolContext,
            Map<String, Object> result) {
        Objects.requireNonNull(result, "result must be set.");
        String serialized = serializedResult(result);
        if (serialized.length() <= maxResultChars
                || Boolean.TRUE.equals(result.get(COMPRESSED_MARKER))) {
            return Maybe.empty();
        }

        Map<String, Object> compressed = new LinkedHashMap<>();
        compressed.put(COMPRESSED_MARKER, true);
        compressed.put("tool", tool == null ? "unknown" : tool.name());
        compressed.put("original_chars", serialized.length());
        compressed.put("summary", compactValue(result, summaryChars, 0));
        copyDiagnostic(result, compressed, "status");
        copyDiagnostic(result, compressed, "error");
        copyDiagnostic(result, compressed, "code");
        if (serializedResult(compressed).length() >= serialized.length()) {
            compressed.put("summary", compactText(serialized, summaryChars));
        }
        return Maybe.just(compressed);
    }

    private static void copyDiagnostic(
            Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, compactValue(source.get(key), 256, 0));
        }
    }

    private static Object compactValue(Object value, int budget, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return "<max-depth>";
        }
        if (value instanceof CharSequence sequence) {
            return compactText(sequence.toString(), budget);
        }
        if (value instanceof Map<?, ?> map) {
            return compactMap(map, budget, depth + 1);
        }
        if (value instanceof Collection<?> collection) {
            return compactCollection(new ArrayList<>(collection), budget, depth + 1);
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(Array.get(value, index));
            }
            return compactCollection(values, budget, depth + 1);
        }
        return compactText(String.valueOf(value), budget);
    }

    private static Map<String, Object> compactMap(Map<?, ?> map, int budget, int depth) {
        Map<String, Object> compacted = new LinkedHashMap<>();
        int entryBudget = Math.max(32, budget / Math.max(1, Math.min(map.size(), MAX_MAP_ENTRIES)));
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_MAP_ENTRIES) {
                break;
            }
            compacted.put(
                    compactText(String.valueOf(entry.getKey()), 128),
                    compactValue(entry.getValue(), entryBudget, depth));
            count++;
        }
        if (map.size() > count) {
            compacted.put("_omitted_entries", map.size() - count);
        }
        return compacted;
    }

    private static List<Object> compactCollection(List<?> values, int budget, int depth) {
        List<Object> compacted = new ArrayList<>();
        int kept = Math.min(values.size(), MAX_COLLECTION_ITEMS);
        int head = Math.min(kept, (kept + 1) / 2);
        int tail = kept - head;
        int itemBudget = Math.max(32, budget / Math.max(1, kept));
        for (int index = 0; index < head; index++) {
            compacted.add(compactValue(values.get(index), itemBudget, depth));
        }
        if (values.size() > kept) {
            compacted.add(Map.of("_omitted_items", values.size() - kept));
        }
        for (int index = values.size() - tail; index < values.size(); index++) {
            compacted.add(compactValue(values.get(index), itemBudget, depth));
        }
        return compacted;
    }

    private static String compactText(String value, int budget) {
        if (value.length() <= budget) {
            return value;
        }
        String marker = "\n...<" + (value.length() - budget) + " chars omitted>...\n";
        int available = Math.max(0, budget - marker.length());
        int head = (available + 1) / 2;
        int tail = available - head;
        return value.substring(0, head) + marker + value.substring(value.length() - tail);
    }

    private static String serializedResult(Map<String, Object> result) {
        try {
            return JSONUtil.toJson(result);
        } catch (RuntimeException e) {
            return String.valueOf(result);
        }
    }
}
