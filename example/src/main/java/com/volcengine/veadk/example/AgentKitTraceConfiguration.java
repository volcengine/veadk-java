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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the HTTP server parent span required by the AgentKit APMPlus trace merger. */
@Configuration
public class AgentKitTraceConfiguration {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> agentKitHttpServerTraceFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
                new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        String path = request.getRequestURI();
                        return !("/run".equals(path) || "/run_sse".equals(path));
                    }

                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain filterChain)
                            throws ServletException, IOException {
                        String route = request.getRequestURI();
                        Span span =
                                GlobalOpenTelemetry.getTracer("veadk-http")
                                        .spanBuilder(request.getMethod() + " " + route)
                                        .setSpanKind(SpanKind.SERVER)
                                        .setAttribute("http.method", request.getMethod())
                                        .setAttribute("http.route", route)
                                        .startSpan();
                        try (Scope ignored = span.makeCurrent()) {
                            filterChain.doFilter(request, response);
                            span.setAttribute("http.status_code", (long) response.getStatus());
                            if (response.getStatus() >= 500) {
                                span.setStatus(StatusCode.ERROR);
                            }
                        } catch (RuntimeException | ServletException | IOException exception) {
                            span.recordException(exception);
                            span.setStatus(StatusCode.ERROR);
                            throw exception;
                        } finally {
                            span.end();
                        }
                    }
                });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
