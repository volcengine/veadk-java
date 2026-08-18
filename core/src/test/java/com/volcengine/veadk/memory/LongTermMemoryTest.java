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
package com.volcengine.veadk.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LongTermMemoryTest {

    @Test
    void facadeSavesOnlyUserTextAndKeepsUsersIsolated() {
        InMemoryLongTermMemoryBackend backend = new InMemoryLongTermMemoryBackend("memory");
        LongTermMemory memory = new LongTermMemory(backend);
        Event userEvent =
                Event.builder()
                        .author("user")
                        .content(
                                Content.builder()
                                        .role("user")
                                        .parts(List.of(Part.fromText("I prefer Java")))
                                        .build())
                        .build();
        Event assistantEvent =
                Event.builder()
                        .author("assistant")
                        .content(
                                Content.builder()
                                        .role("model")
                                        .parts(List.of(Part.fromText("I will remember")))
                                        .build())
                        .build();
        Session session =
                Session.builder("session-1")
                        .appName("sample-app")
                        .userId("user-1")
                        .events(List.of(userEvent, assistantEvent))
                        .build();

        memory.addSessionToMemory(session).blockingAwait();

        SearchMemoryResponse response =
                memory.searchMemory("sample-app", "user-1", "Java").blockingGet();
        assertThat(backend.size("user-1")).isEqualTo(1);
        assertThat(backend.size("user-2")).isZero();
        assertThat(response.memories())
                .extracting(entry -> entry.content().parts().orElseThrow().get(0).text().orElse(""))
                .containsExactly("I prefer Java");
    }

    @Test
    void inMemoryBackendRanksResultsAndValidatesTopK() {
        InMemoryLongTermMemoryBackend backend = new InMemoryLongTermMemoryBackend("memory");
        backend.saveMemory(
                "user-1",
                List.of(
                        "{\"role\":\"user\",\"parts\":[{\"text\":\"Java reactive streams\"}]}",
                        "{\"role\":\"user\",\"parts\":[{\"text\":\"Python data science\"}]}",
                        "{\"role\":\"user\",\"parts\":[{\"text\":\"Java build tools\"}]}"));

        assertThat(backend.searchMemory("user-1", "Java reactive", 2))
                .containsExactly(
                        "{\"role\":\"user\",\"parts\":[{\"text\":\"Java reactive streams\"}]}",
                        "{\"role\":\"user\",\"parts\":[{\"text\":\"Java build tools\"}]}");
        assertThat(backend.searchMemory("other-user", "Java", 5)).isEmpty();
        assertThatThrownBy(() -> backend.searchMemory("user-1", "Java", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void facadeConvertsNestedAndPlainBackendResults() {
        LongTermMemoryBackend backend =
                new LongTermMemoryBackend() {
                    @Override
                    public String index() {
                        return "memory";
                    }

                    @Override
                    public boolean saveMemory(
                            String userId, List<String> eventStrings, Map<String, Object> options) {
                        return true;
                    }

                    @Override
                    public List<String> searchMemory(
                            String userId, String query, int topK, Map<String, Object> options) {
                        return List.of(
                                "{\"memories\":[{\"summary\":\"nested"
                                        + " memory\",\"role\":\"assistant\"}]}",
                                "plain memory");
                    }
                };

        List<MemoryEntry> entries =
                new LongTermMemory(backend)
                        .searchMemory("app", "user", "query")
                        .blockingGet()
                        .memories();

        assertThat(entries).extracting(MemoryEntry::author).containsExactly("assistant", "user");
        assertThat(entries)
                .extracting(entry -> entry.content().parts().orElseThrow().get(0).text().orElse(""))
                .containsExactly("nested memory", "plain memory");
    }

    @Test
    void legacyMemoryServiceAdapterSupportsReadOnlyMigration() {
        BaseMemoryService service = mock(BaseMemoryService.class);
        MemoryEntry entry =
                MemoryEntry.builder()
                        .author("user")
                        .content(
                                Content.builder()
                                        .role("user")
                                        .parts(List.of(Part.fromText("legacy memory")))
                                        .build())
                        .build();
        when(service.searchMemory("app", "user", "query"))
                .thenReturn(
                        Single.just(
                                SearchMemoryResponse.builder()
                                        .setMemories(List.of(entry))
                                        .build()));
        MemoryServiceBackendAdapter adapter = new MemoryServiceBackendAdapter("default", service);

        List<String> results = adapter.searchMemory("user", "query", 5, Map.of("appName", "app"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).contains("legacy memory");
        assertThatThrownBy(() -> adapter.saveMemory("user", List.of("new memory")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
