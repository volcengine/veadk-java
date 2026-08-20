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
package com.volcengine.veadk.example;

import com.google.adk.web.AdkWebServer;
import com.volcengine.veadk.trace.OpenTelemetry;
import com.volcengine.veadk.trace.exporter.APMPlusExporter;
import com.volcengine.veadk.utils.EnvUtil;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

/** AgentKit launcher that enables the platform-provided APMPlus OTLP exporter. */
public final class AgentKitWeb {

    private static final Logger log = LoggerFactory.getLogger(AgentKitWeb.class);

    private AgentKitWeb() {}

    public static void main(String[] args) {
        if (EnvUtil.isAPMPlusConfigured()) {
            OpenTelemetry.initOpenTelemetry(List.of(new APMPlusExporter()));
            log.info(
                    "APMPlus OpenTelemetry exporter enabled for service: {}",
                    EnvUtil.getOpenTelemetryServiceName());
        } else {
            log.info("APMPlus OpenTelemetry exporter is not configured; tracing remains local.");
        }
        System.setProperty("org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", "10485760");
        SpringApplication application =
                new SpringApplication(AdkWebServer.class, AgentKitTraceConfiguration.class);
        application.run(args);
        log.info("AgentKit ADK Web application started successfully.");
    }
}
