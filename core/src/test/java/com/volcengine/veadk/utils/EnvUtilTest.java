package com.volcengine.veadk.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class EnvUtilTest {
    @Test
    @SetEnvironmentVariable(key = "AGENTKIT_TOOL_HOST", value = "agentkit-tool:8711")
    @ClearEnvironmentVariable(key = "AGENTKIT_TOOL_SCHEME")
    void getAgentKitScheme_withPlatformHost_shouldDefaultToHttp() {
        assertThat(EnvUtil.getAgentKitScheme()).isEqualTo("http");
    }

    @Test
    @SetEnvironmentVariable(key = "AGENTKIT_TOOL_HOST", value = "agentkit-tool:8711")
    @SetEnvironmentVariable(key = "AGENTKIT_TOOL_SCHEME", value = "https")
    void getAgentKitScheme_withExplicitScheme_shouldUseConfiguredValue() {
        assertThat(EnvUtil.getAgentKitScheme()).isEqualTo("https");
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_KEY", value = "test_api_key")
    void getAgentApiKey() {
        assertThat(EnvUtil.getAgentApiKey()).isEqualTo("test_api_key");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_KEY")
    void getAgentApiKey_withMissingEnv_shouldThrowException() {
        assertThatThrownBy(EnvUtil::getAgentApiKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SetEnvironmentVariable(key = "MODEL_AGENT_API_BASE", value = "http://modelcenter:6789")
    void getAgentApiBase() {
        assertThat(EnvUtil.getAgentApiBase()).isEqualTo("http://modelcenter:6789");
    }

    @Test
    @ClearEnvironmentVariable(key = "MODEL_AGENT_API_BASE")
    void getAgentApiBase_withMissingEnv_shouldReturnNull() {
        assertThat(EnvUtil.getAgentApiBase()).isNull();
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
}
