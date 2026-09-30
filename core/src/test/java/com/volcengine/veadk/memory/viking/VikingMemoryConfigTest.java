package com.volcengine.veadk.memory.viking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class VikingMemoryConfigTest {
    @Test
    @SetEnvironmentVariable(key = "DATABASE_VIKINGMEM_API_KEY", value = "env-key")
    @SetEnvironmentVariable(
            key = "DATABASE_VIKINGMEM_MEMORY_TYPE",
            value = "sys_event_v1, sys_profile_v1")
    void resolvesEnvironmentAndNormalizesMemoryTypes() {
        VikingMemoryConfig c = VikingMemoryConfig.builder().build();
        assertEquals("env-key", c.getApiKey());
        assertEquals(List.of("sys_event_v1", "sys_profile_v1"), c.getMemoryTypes());
        assertFalse(c.hasManagementCredentials());
    }

    @Test
    void explicitValuesWin() {
        VikingMemoryConfig c =
                VikingMemoryConfig.builder()
                        .apiKey(" key ")
                        .project("p")
                        .region("cn-shanghai")
                        .memoryTypes("one")
                        .baseUrl("https://example.com/")
                        .build();
        assertEquals("key", c.getApiKey());
        assertEquals("p", c.getProject());
        assertEquals("https://example.com", c.getBaseUrl());
        assertTrue(c.hasApiKey());
    }

    @Test
    void exposesAllExplicitOptions() {
        VikingMemoryConfig c =
                VikingMemoryConfig.builder()
                        .apiKey("key")
                        .project("project")
                        .region("cn-shanghai")
                        .memoryTypes("one,two")
                        .baseUrl("https://example.com")
                        .cloudProvider("volcengine")
                        .accessKey("ak")
                        .secretKey("sk")
                        .sessionToken("token")
                        .build();
        assertEquals("project", c.getProject());
        assertEquals("cn-shanghai", c.getRegion());
        assertEquals("volcengine", c.getCloudProvider());
        assertEquals("ak", c.getAccessKey());
        assertEquals("sk", c.getSecretKey());
        assertEquals("token", c.getSessionToken());
        assertEquals(List.of("one", "two"), c.getMemoryTypes());
        assertTrue(c.hasManagementCredentials());
    }

    @Test
    void rejectsEmptyMemoryTypesAndMalformedUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VikingMemoryConfig.builder().memoryTypes(",,").build());
        assertThrows(
                IllegalArgumentException.class,
                () -> VikingMemoryConfig.builder().baseUrl("http://[").build());
    }
}
