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
package com.volcengine.veadk.utils;

import org.apache.commons.lang3.StringUtils;

public class EnvUtil {

    // env
    private static final String VOLCENGINE_ACCESS_KEY = "VOLCENGINE_ACCESS_KEY";
    private static final String VOLCENGINE_SECRET_KEY = "VOLCENGINE_SECRET_KEY";
    private static final String TLS_ENDPOINT = "OBSERVABILITY_OPENTELEMETRY_TLS_ENDPOINT";
    private static final String TLS_SERVICE_NAME = "OBSERVABILITY_OPENTELEMETRY_TLS_SERVICE_NAME";
    private static final String TLS_REGION = "OBSERVABILITY_OPENTELEMETRY_TLS_REGION";
    private static final String VIKINGMEM_MEMORY_TYPE = "DATABASE_VIKINGMEM_MEMORY_TYPE";
    private static final String MODEL_AGENT_API_KEY = "MODEL_AGENT_API_KEY";
    private static final String TOOL_CODE_SANDBOX_URL = "TOOL_CODE_SANDBOX_URL";
    private static final String AGENTKIT_TOOL_ID = "AGENTKIT_TOOL_ID";
    private static final String AGENTKIT_TOOL_SERVICE = "AGENTKIT_TOOL_SERVICE_CODE";
    private static final String AGENTKIT_TOOL_REGION = "AGENTKIT_TOOL_REGION";
    private static final String AGENTKIT_TOOL_HOST = "AGENTKIT_TOOL_HOST";

    // default value
    private static final String DEFAULT_TLS_ENDPONT = "https://tls-cn-beijing.volces.com:4317";
    private static final String DEFAULT_TLS_REGION = "cn-beijing";
    private static final String DEFAULT_VIKING_MEMORY_TYPE = "sys_event_v1";
    private static final String DEFAULT_AGENTKIT_SERVICE = "agentkit";
    private static final String DEFAULT_AGENTKIT_REGION = "cn-beijing";

    private EnvUtil() {}

    public static String getAgentKitToolId() {
        String toolId = System.getenv(AGENTKIT_TOOL_ID);
        if (StringUtils.isBlank(toolId)) {
            throw getIllegalStateException(AGENTKIT_TOOL_ID);
        }
        return toolId;
    }

    public static String getAgentKitService() {
        String service = System.getenv(AGENTKIT_TOOL_SERVICE);
        return StringUtils.isBlank(service) ? DEFAULT_AGENTKIT_SERVICE : service;
    }

    public static String getAgentKitRegion() {
        String region = System.getenv(AGENTKIT_TOOL_REGION);
        return StringUtils.isBlank(region) ? DEFAULT_AGENTKIT_REGION : region;
    }

    public static String getAgentKitHost() {
        String host = System.getenv(AGENTKIT_TOOL_HOST);
        if (StringUtils.isBlank(host)) {
            return getAgentKitService() + "." + getAgentKitRegion() + ".volces.com";
        }
        return host;
    }

    public static String getAgentApiKey() {
        String apiKey = System.getenv(MODEL_AGENT_API_KEY);
        if (StringUtils.isBlank(apiKey)) {
            throw getIllegalStateException(MODEL_AGENT_API_KEY);
        }
        return apiKey;
    }

    public static String getAccessKey() {
        String accessKey = System.getenv(VOLCENGINE_ACCESS_KEY);
        if (StringUtils.isBlank(accessKey)) {
            throw getIllegalStateException(VOLCENGINE_ACCESS_KEY);
        }
        return accessKey;
    }

    public static String getSecretKey() {
        String secretKey = System.getenv(VOLCENGINE_SECRET_KEY);
        if (StringUtils.isBlank(secretKey)) {
            throw getIllegalStateException(VOLCENGINE_SECRET_KEY);
        }
        return secretKey;
    }

    public static String getTLSEndpoint() {
        String tlsEndpint = System.getenv(TLS_ENDPOINT);
        if (StringUtils.isBlank(tlsEndpint)) {
            return DEFAULT_TLS_ENDPONT;
        }
        return tlsEndpint;
    }

    public static String getTLSServiceName() {
        String serviceName = System.getenv(TLS_SERVICE_NAME);
        if (StringUtils.isBlank(serviceName)) {
            throw getIllegalStateException(TLS_SERVICE_NAME);
        }
        return serviceName;
    }

    public static String getTLSRegion() {
        String tlsRegion = System.getenv(TLS_REGION);
        if (StringUtils.isBlank(tlsRegion)) {
            return DEFAULT_TLS_REGION;
        }
        return tlsRegion;
    }

    public static String getVikingMmemoryType() {
        String memoryType = System.getenv(VIKINGMEM_MEMORY_TYPE);
        if (StringUtils.isBlank(memoryType)) {
            return DEFAULT_VIKING_MEMORY_TYPE;
        }
        return memoryType;
    }

    public static String getCodeSandboxUrl() {
        String url = System.getenv(TOOL_CODE_SANDBOX_URL);
        if (StringUtils.isBlank(url)) {
            throw getIllegalStateException(TOOL_CODE_SANDBOX_URL);
        }
        return url;
    }

    private static IllegalStateException getIllegalStateException(String configName) {
        return new IllegalStateException(
                "Missing required configuration: "
                        + configName
                        + ". Please configure the environment variable before startup.");
    }
}
