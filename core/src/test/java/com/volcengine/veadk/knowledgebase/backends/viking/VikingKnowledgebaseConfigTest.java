package com.volcengine.veadk.knowledgebase.backends.viking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class VikingKnowledgebaseConfigTest {
    @Test
    @SetEnvironmentVariable(key = "DATABASE_VIKING_API_KEY", value = "env-key")
    @SetEnvironmentVariable(key = "DATABASE_VIKING_PROJECT", value = "env-project")
    void explicitValuesOverrideEnvironmentAndDefaultsApply() {
        VikingKnowledgebaseConfig config =
                VikingKnowledgebaseConfig.builder().apiKey(" explicit ").project("p").build();
        assertEquals("explicit", config.getApiKey());
        assertEquals("p", config.getProject());
        assertEquals("cn-beijing", config.getRegion());
        assertEquals("2", config.getVersion());
        assertTrue(config.isRerank());
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_VIKING_API_KEY", value = "env-key")
    void sentinelExplicitFallsBackToEnvironment() {
        assertEquals(
                "env-key",
                VikingKnowledgebaseConfig.builder().apiKey(" None ").build().getApiKey());
    }

    @Test
    void onlyApiKeyDoesNotRequireManagementCredentials() {
        VikingKnowledgebaseConfig c = VikingKnowledgebaseConfig.builder().apiKey("key").build();
        assertTrue(c.hasApiKey());
        assertFalse(c.hasManagementCredentials());
    }

    @Test
    void rejectsInvalidBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VikingKnowledgebaseConfig.builder().baseUrl("ftp://host").build());
    }

    @Test
    void exposesAllExplicitOptionsAndLegacyConstructor() {
        VikingKnowledgebaseConfig c =
                VikingKnowledgebaseConfig.builder()
                        .apiKey("key")
                        .project("project")
                        .region("cn-shanghai")
                        .resourceId("resource")
                        .version("3")
                        .baseUrl("https://example.com/")
                        .cloudProvider("volcengine")
                        .accessKey("ak")
                        .secretKey("sk")
                        .sessionToken("token")
                        .rerank(false)
                        .chunkDiffusionCount(9)
                        .build();
        assertEquals("project", c.getProject());
        assertEquals("cn-shanghai", c.getRegion());
        assertEquals("resource", c.getResourceId());
        assertEquals("3", c.getVersion());
        assertEquals("https://example.com", c.getBaseUrl());
        assertEquals("volcengine", c.getCloudProvider());
        assertEquals("ak", c.getAccessKey());
        assertEquals("sk", c.getSecretKey());
        assertEquals("token", c.getSessionToken());
        assertFalse(c.isRerank());
        assertEquals(9, c.getChunkDiffusionCount());
        assertTrue(c.hasManagementCredentials());

        VikingKnowledgebaseConfig legacy = new VikingKnowledgebaseConfig("a", "s", true, 4);
        assertEquals("a", legacy.getAccessKey());
        assertEquals(4, legacy.getChunkDiffusionCount());
    }

    @Test
    void rejectsMalformedBaseUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> VikingKnowledgebaseConfig.builder().baseUrl("http://[").build());
    }
}
