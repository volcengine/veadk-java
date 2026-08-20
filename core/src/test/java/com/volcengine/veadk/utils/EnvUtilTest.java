package com.volcengine.veadk.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

class EnvUtilTest {

    @Test
    @SetEnvironmentVariable(
            key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_ENDPOINT",
            value = "http://apmplus:4317")
    @SetEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_API_KEY", value = "test-key")
    void apmPlusConfiguration_shouldReadRuntimeEnvironment() {
        assertThat(EnvUtil.isAPMPlusConfigured()).isTrue();
        assertThat(EnvUtil.getAPMPlusEndpoint()).isEqualTo("http://apmplus:4317");
        assertThat(EnvUtil.getAPMPlusApiKey()).isEqualTo("test-key");
    }

    @Test
    @SetEnvironmentVariable(
            key = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            value = "http://collector/path/v1/traces")
    @SetEnvironmentVariable(key = "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", value = "http/protobuf")
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_ENDPOINT")
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_API_KEY")
    void apmPlusConfiguration_shouldPreferStandardOtlpEnvironment() {
        assertThat(EnvUtil.isAPMPlusConfigured()).isTrue();
        assertThat(EnvUtil.getOpenTelemetryTracesEndpoint())
                .isEqualTo("http://collector/path/v1/traces");
        assertThat(EnvUtil.getOpenTelemetryTracesProtocol()).isEqualTo("http/protobuf");
    }

    @Test
    @ClearEnvironmentVariable(key = "OBSERVABILITY_OPENTELEMETRY_APMPLUS_SERVICE_NAME")
    @SetEnvironmentVariable(key = "OTEL_SERVICE_NAME", value = "runtime.agent")
    void getOpenTelemetryServiceName_shouldUseRuntimeServiceName() {
        assertThat(EnvUtil.getOpenTelemetryServiceName()).isEqualTo("runtime.agent");
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
