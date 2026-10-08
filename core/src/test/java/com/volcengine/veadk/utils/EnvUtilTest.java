package com.volcengine.veadk.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class EnvUtilTest {

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "test_api_key")
    void getAgentApiKey() {
        assertThat(EnvUtil.getAgentApiKey()).isEqualTo("test_api_key");
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY_ID", value = " test_api_key_id ")
    void getModelAgentApiKeyId() {
        assertThat(EnvUtil.getModelAgentApiKeyId()).isEqualTo("test_api_key_id");
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY_NAME", value = " test_api_key_name ")
    void getModelAgentApiKeyName() {
        assertThat(EnvUtil.getModelAgentApiKeyName()).isEqualTo("test_api_key_name");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void getAgentApiKey_withMissingEnv_shouldThrowException() {
        assertThatThrownBy(EnvUtil::getAgentApiKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_PROJECT_NAME")
    void getModelAgentProjectName_withMissingEnv_shouldReturnDefault() {
        assertThat(EnvUtil.getModelAgentProjectName()).isEqualTo("default");
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_PROJECT_NAME", value = "agent_project")
    void getModelAgentProjectName() {
        assertThat(EnvUtil.getModelAgentProjectName()).isEqualTo("agent_project");
    }

    @Test
    @SetEnvironmentVariable(key = "CLOUD_PROVIDER", value = " byteplus ")
    void getCloudProvider() {
        assertThat(EnvUtil.getCloudProvider()).isEqualTo("byteplus");
    }

    @Test
    @SetEnvironmentVariable(key = "VOLCENGINE_ACCESS_KEY", value = "test_access_key")
    void getAccessKey() {
        assertThat(EnvUtil.getAccessKey()).isEqualTo("test_access_key");
    }

    @Test
    @ClearEnvironmentVariable(key = "VOLCENGINE_ACCESS_KEY")
    void getAccessKey_withMissingEnv_shouldThrowException() {
        assertThatThrownBy(EnvUtil::getAccessKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SetEnvironmentVariable(key = "VOLCENGINE_SECRET_KEY", value = "test_secret_key")
    void getSecretKey() {
        assertThat(EnvUtil.getSecretKey()).isEqualTo("test_secret_key");
    }

    @Test
    @ClearEnvironmentVariable(key = "VOLCENGINE_SECRET_KEY")
    void getSecretKey_withMissingEnv_shouldThrowException() {
        assertThatThrownBy(EnvUtil::getSecretKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SetEnvironmentVariable(
            key = "OBSERVABILITY_OPENTELEMETRY_TLS_ENDPOINT",
            value = "test_tls_endpoint")
    void getTLSEndpoint() {
        assertThat(EnvUtil.getTLSEndpoint()).isEqualTo("test_tls_endpoint");
    }

    @Test
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_TLS_ENDPOINT")
    void getTLSEndpoint_withMissingEnv_shouldReturnDefault() {
        assertThat(EnvUtil.getTLSEndpoint()).isEqualTo("https://tls-cn-beijing.volces.com:4317");
    }

    @Test
    @SetEnvironmentVariable(
            key = "OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME",
            value = "test_service_name")
    void getTLSServiceName() {
        assertThat(EnvUtil.getTLSServiceName()).isEqualTo("test_service_name");
    }

    @Test
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME")
    void getTLSServiceName_withMissingEnv_shouldThrowException() {
        assertThatThrownBy(EnvUtil::getTLSServiceName).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SetEnvironmentVariable(
            key = "OBSERVABILITY_OPENTELEMETRY_TLS_REGION",
            value = "test_tls_region")
    void getTLSRegion() {
        assertThat(EnvUtil.getTLSRegion()).isEqualTo("test_tls_region");
    }

    @Test
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_TLS_REGION")
    void getTLSRegion_withMissingEnv_shouldReturnDefault() {
        assertThat(EnvUtil.getTLSRegion()).isEqualTo("cn-beijing");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_VIKINGMEM_MEMORY_TYPE", value = "test_memory_type")
    void getVikingMmemoryType() {
        assertThat(EnvUtil.getVikingMmemoryType()).isEqualTo("test_memory_type");
    }

    @Test
    @ClearEnvironmentVariable(key = "DATABASE_VIKINGMEM_MEMORY_TYPE")
    void getVikingMmemoryType_withMissingEnv_shouldReturnDefault() {
        assertThat(EnvUtil.getVikingMmemoryType()).isEqualTo("sys_event_v1");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_MEM0_API_KEY", value = "mem0_api_key")
    void getMem0ApiKey() {
        assertThat(EnvUtil.getMem0ApiKey()).isEqualTo("mem0_api_key");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_MEM0_API_KEY_ID", value = "mem0_api_key_id")
    void getMem0ApiKeyId() {
        assertThat(EnvUtil.getMem0ApiKeyId()).isEqualTo("mem0_api_key_id");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_MEM0_PROJECT_ID", value = "mem0_project_id")
    void getMem0ProjectId() {
        assertThat(EnvUtil.getMem0ProjectId()).isEqualTo("mem0_project_id");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_MEM0_BASE_URL", value = "https://mem0.example.com")
    void getMem0BaseUrl() {
        assertThat(EnvUtil.getMem0BaseUrl()).isEqualTo("https://mem0.example.com");
    }

    @Test
    @ClearEnvironmentVariable(key = "DATABASE_MEM0_BASE_URL")
    void getDefaultMem0BaseUrl() {
        assertThat(EnvUtil.getDefaultMem0BaseUrl()).isEqualTo("https://api.mem0.ai");
    }

    @Test
    @SetEnvironmentVariable(key = "DATABASE_MEM0_REGION", value = "cn-shanghai")
    void getMem0Region() {
        assertThat(EnvUtil.getMem0Region()).isEqualTo("cn-shanghai");
    }

    @Test
    @ClearEnvironmentVariable(key = "DATABASE_MEM0_REGION")
    @SetEnvironmentVariable(key = "REGION", value = "cn-beijing")
    void getMem0Region_withMissingMem0Region_shouldUseRegion() {
        assertThat(EnvUtil.getMem0Region()).isEqualTo("cn-beijing");
    }
}
