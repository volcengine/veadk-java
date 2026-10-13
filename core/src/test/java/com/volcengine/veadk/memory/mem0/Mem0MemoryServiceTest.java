package com.volcengine.veadk.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class Mem0MemoryServiceTest {

    @Test
    void builder_invalidAppName_shouldThrow() {
        assertThatThrownBy(
                        () ->
                                Mem0MemoryService.builder()
                                        .appName("9bad")
                                        .runtimeClient(Mockito.mock(Mem0RuntimeClient.class))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addSessionToMemory_shouldStoreOnlyUserTextMessages() throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        when(runtimeClient.addMemory(Mockito.eq("user-1"), Mockito.anyList(), Mockito.eq(true)))
                .thenReturn(true);
        Mem0MemoryService service =
                Mem0MemoryService.builder().appName("AppMem").runtimeClient(runtimeClient).build();

        Event userEvent = Mockito.mock(Event.class);
        when(userEvent.author()).thenReturn("user");
        when(userEvent.content())
                .thenReturn(
                        Optional.of(
                                Content.builder()
                                        .role("user")
                                        .parts(
                                                List.of(
                                                        Part.fromText("hello"),
                                                        Part.fromText("world")))
                                        .build()));

        Event assistantEvent = Mockito.mock(Event.class);
        when(assistantEvent.author()).thenReturn("assistant");
        when(assistantEvent.content()).thenReturn(Optional.empty());

        Session session = Mockito.mock(Session.class);
        when(session.userId()).thenReturn("user-1");
        when(session.events()).thenReturn(List.of(userEvent, assistantEvent));

        service.addSessionToMemory(session).blockingAwait();

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(runtimeClient)
                .addMemory(Mockito.eq("user-1"), messagesCaptor.capture(), Mockito.eq(true));

        @SuppressWarnings("unchecked")
        List<Mem0Message> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent()).isEqualTo("hello\nworld");
    }

    @Test
    void synchronousWriteMode_waitsForSynchronousMem0Request() throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        when(runtimeClient.addMemory(Mockito.eq("user-1"), Mockito.anyList(), Mockito.eq(false)))
                .thenReturn(true);
        Mem0MemoryService service =
                Mem0MemoryService.builder()
                        .appName("AppMem")
                        .writeMode(Mem0MemoryService.WriteMode.SYNCHRONOUS)
                        .runtimeClient(runtimeClient)
                        .build();
        Event userEvent = Mockito.mock(Event.class);
        when(userEvent.author()).thenReturn("user");
        when(userEvent.content())
                .thenReturn(
                        Optional.of(
                                Content.builder()
                                        .role("user")
                                        .parts(Part.fromText("remember me"))
                                        .build()));
        Session session = Mockito.mock(Session.class);
        when(session.userId()).thenReturn("user-1");
        when(session.events()).thenReturn(List.of(userEvent));

        service.addSessionToMemory(session).blockingAwait();

        assertThat(service.writeMode()).isEqualTo(Mem0MemoryService.WriteMode.SYNCHRONOUS);
        verify(runtimeClient).addMemory(Mockito.eq("user-1"), Mockito.anyList(), Mockito.eq(false));
    }

    @Test
    void synchronousWriteFailure_propagatesToCaller() throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        when(runtimeClient.addMemory(Mockito.eq("user-1"), Mockito.anyList(), Mockito.eq(false)))
                .thenReturn(false);
        Mem0MemoryService service =
                Mem0MemoryService.builder()
                        .appName("AppMem")
                        .writeMode(Mem0MemoryService.WriteMode.SYNCHRONOUS)
                        .runtimeClient(runtimeClient)
                        .build();
        Event userEvent = Mockito.mock(Event.class);
        when(userEvent.author()).thenReturn("user");
        when(userEvent.content())
                .thenReturn(
                        Optional.of(
                                Content.builder()
                                        .role("user")
                                        .parts(Part.fromText("remember me"))
                                        .build()));
        Session session = Mockito.mock(Session.class);
        when(session.userId()).thenReturn("user-1");
        when(session.events()).thenReturn(List.of(userEvent));

        assertThatThrownBy(() -> service.addSessionToMemory(session).blockingAwait())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to add session to Mem0 memory");
    }

    @Test
    void addSessionToMemory_withoutValidMessages_shouldCompleteWithoutCallingRuntime()
            throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        Mem0MemoryService service =
                Mem0MemoryService.builder().appName("AppMem").runtimeClient(runtimeClient).build();

        Event assistantEvent = Mockito.mock(Event.class);
        when(assistantEvent.author()).thenReturn("assistant");
        when(assistantEvent.content()).thenReturn(Optional.empty());

        Session session = Mockito.mock(Session.class);
        when(session.events()).thenReturn(List.of(assistantEvent));

        service.addSessionToMemory(session).blockingAwait();

        verify(runtimeClient, never())
                .addMemory(Mockito.anyString(), Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void searchMemory_shouldMapRuntimeResultsToAdkEntries() throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        when(runtimeClient.searchMemory("user-1", "tea", 3))
                .thenReturn(List.of(new Mem0MemoryResult("likes tea", 0.9)));
        Mem0MemoryService service =
                Mem0MemoryService.builder()
                        .appName("AppMem")
                        .topK(3)
                        .runtimeClient(runtimeClient)
                        .build();

        List<MemoryEntry> entries =
                service.searchMemory("AppMem", "user-1", "tea").blockingGet().memories();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).author()).isEqualTo("user");
        assertThat(entries.get(0).content().parts().get().get(0).text()).contains("likes tea");
    }

    @Test
    void searchMemory_whenRuntimeThrows_shouldReturnEmptyResponse() throws Exception {
        Mem0RuntimeClient runtimeClient = Mockito.mock(Mem0RuntimeClient.class);
        when(runtimeClient.searchMemory(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenThrow(new IOException("boom"));
        Mem0MemoryService service =
                Mem0MemoryService.builder().appName("AppMem").runtimeClient(runtimeClient).build();

        assertThat(service.searchMemory("AppMem", "user-1", "tea").blockingGet().memories())
                .isEmpty();
    }
}
