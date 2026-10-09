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
package com.volcengine.veadk.example.web;

import com.google.adk.memory.BaseMemoryService;
import com.google.adk.web.AdkWebServer;
import com.volcengine.veadk.memory.viking.VikingMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AdkWeb extends AdkWebServer {

    private static final Logger log = LoggerFactory.getLogger(AdkWeb.class);

    @Bean
    @Primary
    @Override
    public BaseMemoryService memoryService() {
        log.info("Using VikingMemoryService");
        return new VikingMemoryService("ark_agent");
    }

    public static void main(String[] args) {
        String[] customArgs = {"--adk.agents.source-dir=example/target", "--server.port=9000"};
        // OpenTelemetry.initOpenTelemetry(Arrays.asList(new TLSExporter()));
        SpringApplication.run(AdkWeb.class, customArgs);
        log.info("AdkWeb application started successfully.");
    }
}
