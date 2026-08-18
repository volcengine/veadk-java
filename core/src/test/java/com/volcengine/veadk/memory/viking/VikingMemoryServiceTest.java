package com.volcengine.veadk.memory.viking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.adk.events.Event;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.volcengine.veadk.integration.vikingmemory.Message;
import com.volcengine.veadk.integration.vikingmemory.Metadata;
import com.volcengine.veadk.integration.vikingmemory.VikingMemoryWrapper;
import com.volcengine.veadk.utils.EnvUtil;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class VikingMemoryServiceTest {

    @Test
    void constructor_invalidAppName_shouldThrow_and_notConstructWrapper() {
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(VikingMemoryWrapper.class)) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1");

            assertThrows(IllegalArgumentException.class, () -> new VikingMemoryService("9bad"));
            assertEquals(0, mockedCtor.constructed().size());
        }
    }

    @Test
    void constructor_validApp_collectionExists_shouldNotCreate() {
        String appName = "MyMemoryApp";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1,user_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);
            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);

            verify(wrapperMock).isCollectionExists(appName);
            verify(wrapperMock, never()).createCollection(Mockito.eq(appName), Mockito.anyList());
            mockedEnv.verify(EnvUtil::getAccessKey);
            mockedEnv.verify(EnvUtil::getSecretKey);
            mockedEnv.verify(EnvUtil::getVikingMmemoryType);
        }
    }

    @Test
    void constructor_validApp_collectionMissing_shouldCreate_withBuiltinEventTypes() {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName))
                                            .thenReturn(false);
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1,user_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);
            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);

            ArgumentCaptor<List> eventTypesCaptor = ArgumentCaptor.forClass(List.class);
            verify(wrapperMock).isCollectionExists(appName);
            verify(wrapperMock).createCollection(Mockito.eq(appName), eventTypesCaptor.capture());

            @SuppressWarnings("unchecked")
            List<String> eventTypes = eventTypesCaptor.getValue();
            assertEquals(List.of("sys_event_v1", "user_event_v1"), eventTypes);
        }
    }

    @Test
    void addSessionToMemory_noValidMessages_shouldComplete_and_notInvokeAddSession()
            throws Exception {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);
            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);

            // Prepare invalid events: assistant event and user event without content
            Event assistantEvent = Mockito.mock(Event.class);
            Mockito.when(assistantEvent.author()).thenReturn("assistant");
            Mockito.when(assistantEvent.content()).thenReturn(Optional.empty());

            Event userNoContentEvent = Mockito.mock(Event.class);
            Mockito.when(userNoContentEvent.author()).thenReturn("user");
            Mockito.when(userNoContentEvent.content()).thenReturn(Optional.empty());

            Session session = Mockito.mock(Session.class);
            Mockito.when(session.events()).thenReturn(List.of(assistantEvent, userNoContentEvent));
            Mockito.when(session.appName()).thenReturn(appName);
            Mockito.when(session.userId()).thenReturn("user-1");

            TestObserver<Void> to = service.addSessionToMemory(session).test();
            to.assertComplete();

            verify(wrapperMock, never())
                    .addSession(
                            Mockito.anyString(), Mockito.anyList(), Mockito.any(Metadata.class));
        }
    }

    @Test
    void addSessionToMemory_withValidMessages_callsAddSession_and_buildsMetadata()
            throws Exception {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                    Mockito.doReturn(true)
                                            .when(mock)
                                            .addSession(
                                                    Mockito.eq(appName),
                                                    Mockito.anyList(),
                                                    Mockito.any(Metadata.class));
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);
            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);

            // Valid user events with text content
            Content content1 = Content.builder().role("user").parts(Part.fromText("hello")).build();
            Event e1 = Mockito.mock(Event.class);
            Mockito.when(e1.author()).thenReturn("user");
            Mockito.when(e1.content()).thenReturn(Optional.of(content1));

            Content content2 = Content.builder().role("user").parts(Part.fromText("world")).build();
            Event e2 = Mockito.mock(Event.class);
            Mockito.when(e2.author()).thenReturn("user");
            Mockito.when(e2.content()).thenReturn(Optional.of(content2));

            Session session = Mockito.mock(Session.class);
            Mockito.when(session.events()).thenReturn(List.of(e1, e2));
            Mockito.when(session.appName()).thenReturn(appName);
            Mockito.when(session.userId()).thenReturn("user-1");

            service.addSessionToMemory(session).blockingAwait();

            ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
            verify(wrapperMock)
                    .addSession(
                            Mockito.eq(appName),
                            messagesCaptor.capture(),
                            metadataCaptor.capture());

            @SuppressWarnings("unchecked")
            List<com.volcengine.veadk.integration.vikingmemory.Message> msgs =
                    messagesCaptor.getValue();
            assertEquals(2, msgs.size());
            assertEquals("hello", msgs.get(0).getContent());
            assertEquals("world", msgs.get(1).getContent());

            Metadata md = metadataCaptor.getValue();
            assertEquals("user-1", md.getDefaultUserId());
            assertEquals("assistant", md.getDefaultAssistantId());
        }
    }

    @Test
    void addSessionToMemory_whenWrapperThrows_shouldPropagateRuntimeException() throws Exception {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                    Mockito.doThrow(new Exception("backend error"))
                                            .when(mock)
                                            .addSession(
                                                    Mockito.eq(appName),
                                                    Mockito.anyList(),
                                                    Mockito.any(Metadata.class));
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);

            Content content = Content.builder().role("user").parts(Part.fromText("hello")).build();
            Event e = Mockito.mock(Event.class);
            Mockito.when(e.author()).thenReturn("user");
            Mockito.when(e.content()).thenReturn(Optional.of(content));

            Session session = Mockito.mock(Session.class);
            Mockito.when(session.events()).thenReturn(List.of(e));
            Mockito.when(session.appName()).thenReturn(appName);
            Mockito.when(session.userId()).thenReturn("user-1");

            assertThrows(
                    RuntimeException.class,
                    () -> service.addSessionToMemory(session).blockingAwait());
        }
    }

    @Test
    void searchMemory_returnsResponseWithEntries_and_callsWrapperWithExpectedArgs()
            throws Exception {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                    Mockito.doReturn(
                                                    Collections.singletonList(
                                                            Mockito.mock(MemoryEntry.class)))
                                            .when(mock)
                                            .searchMemory(
                                                    Mockito.eq(appName),
                                                    Mockito.eq("user-1"),
                                                    Mockito.eq("q"),
                                                    Mockito.eq(5),
                                                    Mockito.anyList());
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1,user_event_v1");

            VikingMemoryService service = new VikingMemoryService(appName);
            List<MemoryEntry> entries =
                    service.searchMemory(appName, "user-1", "q").blockingGet().memories();

            assertEquals(1, entries.size());

            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);
            ArgumentCaptor<List> eventTypesCaptor = ArgumentCaptor.forClass(List.class);
            verify(wrapperMock)
                    .searchMemory(
                            Mockito.eq(appName),
                            Mockito.eq("user-1"),
                            Mockito.eq("q"),
                            Mockito.eq(5),
                            eventTypesCaptor.capture());

            @SuppressWarnings("unchecked")
            List<String> eventTypes = eventTypesCaptor.getValue();
            assertEquals(List.of("sys_event_v1", "user_event_v1"), eventTypes);
        }
    }

    @Test
    void backendContractKeepsLegacyVikingServiceUsable() throws Exception {
        String appName = "AppMem";
        try (MockedStatic<EnvUtil> mockedEnv = Mockito.mockStatic(EnvUtil.class);
                MockedConstruction<VikingMemoryWrapper> mockedCtor =
                        Mockito.mockConstruction(
                                VikingMemoryWrapper.class,
                                (mock, context) -> {
                                    Mockito.when(mock.isCollectionExists(appName)).thenReturn(true);
                                    Mockito.when(
                                                    mock.addSession(
                                                            Mockito.eq(appName),
                                                            Mockito.anyList(),
                                                            Mockito.any(Metadata.class)))
                                            .thenReturn(true);
                                })) {
            mockedEnv.when(EnvUtil::getAccessKey).thenReturn("ak");
            mockedEnv.when(EnvUtil::getSecretKey).thenReturn("sk");
            mockedEnv.when(EnvUtil::getVikingMmemoryType).thenReturn("sys_event_v1");
            VikingMemoryService service = new VikingMemoryService(appName);

            boolean saved =
                    service.saveMemory(
                            "user-1",
                            List.of(
                                    "{\"role\":\"model\",\"parts\":[{\"text\":\"first\"},{\"text\":\"second\"}]}"),
                            Map.of());

            assertTrue(saved);
            assertEquals(appName, service.index());
            VikingMemoryWrapper wrapperMock = mockedCtor.constructed().get(0);
            ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
            verify(wrapperMock)
                    .addSession(
                            Mockito.eq(appName),
                            messagesCaptor.capture(),
                            Mockito.any(Metadata.class));
            @SuppressWarnings("unchecked")
            List<Message> messages = messagesCaptor.getValue();
            assertEquals("assistant", messages.get(0).getRole());
            assertEquals("first\nsecond", messages.get(0).getContent());
        }
    }
}
