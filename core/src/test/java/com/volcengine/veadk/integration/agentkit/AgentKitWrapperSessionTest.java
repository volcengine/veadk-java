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
package com.volcengine.veadk.integration.agentkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentKitWrapperSessionTest {

    @Test
    void listsEveryPageAndParsesExpiry() throws Exception {
        RecordingWrapper wrapper =
                new RecordingWrapper(
                        json(
                                "{\"SessionInfos\":[{\"SessionId\":\"s1\","
                                        + "\"UserSessionId\":\"logical\",\"Status\":\"Ready\","
                                        + "\"ExpireAt\":\"2026-10-16T00:00:00Z\"}],"
                                        + "\"NextToken\":\"page-2\"}"),
                        json(
                                "{\"SessionInfos\":[{\"SessionId\":\"s2\","
                                        + "\"UserSessionId\":\"other\"}]}"));

        var sessions = wrapper.listSessions("tool-1");

        assertThat(sessions)
                .extracting(value -> value.sessionId().orElseThrow())
                .containsExactly("s1", "s2");
        assertThat(sessions.get(0).expireAt()).contains("2026-10-16T00:00:00Z");
        assertThat(wrapper.calls.get(0).body())
                .containsEntry("ToolId", "tool-1")
                .containsEntry("MaxResults", 100)
                .doesNotContainKey("NextToken");
        assertThat(wrapper.calls.get(1).body()).containsEntry("NextToken", "page-2");
    }

    @Test
    void rejectsRepeatedPaginationToken() throws Exception {
        RecordingWrapper wrapper =
                new RecordingWrapper(
                        json("{\"SessionInfos\":[],\"NextToken\":\"same\"}"),
                        json("{\"SessionInfos\":[],\"NextToken\":\"same\"}"));

        assertThatThrownBy(() -> wrapper.listSessions("tool-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repeated NextToken");
    }

    @Test
    void listFailureDoesNotBecomeAnEmptyReusableResult() {
        RecordingWrapper wrapper = new RecordingWrapper();
        wrapper.failure = new IllegalStateException("list unavailable");

        assertThatThrownBy(() -> wrapper.findReusableSession("tool-1", "logical"))
                .isSameAs(wrapper.failure);
    }

    @Test
    void deletesPhysicalSessionWithControlPlaneContract() throws Exception {
        RecordingWrapper wrapper = new RecordingWrapper(json("{}"));

        wrapper.deleteSession("tool-1", "session-1");

        assertThat(wrapper.calls)
                .containsExactly(
                        new Call(
                                "DeleteSession",
                                Map.of("ToolId", "tool-1", "SessionId", "session-1")));
    }

    private static JsonNode json(String value) throws IOException {
        return JSONUtil.parseJson(value);
    }

    private record Call(String action, Map<String, Object> body) {}

    private static final class RecordingWrapper extends AgentKitWrapper {
        private final Deque<JsonNode> responses = new ArrayDeque<>();
        private final List<Call> calls = new ArrayList<>();
        private RuntimeException failure;

        private RecordingWrapper(JsonNode... responses) {
            super("example.invalid", "cn-beijing", "test-ak", "test-sk");
            this.responses.addAll(List.of(responses));
        }

        @Override
        JsonNode invokeAction(String action, Map<String, Object> requestBody) {
            calls.add(new Call(action, Map.copyOf(requestBody)));
            if (failure != null) {
                throw failure;
            }
            return responses.removeFirst();
        }
    }
}
