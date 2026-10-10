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
package com.volcengine.veadk.tools.builtin.sandbox;

import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

final class ToolErrorResponse {

    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String CODE_SANDBOX_FAILED = "CODE_SANDBOX_FAILED";
    static final String SKILLS_SANDBOX_A2A_FAILED = "SKILLS_SANDBOX_A2A_FAILED";
    static final String SKILLS_SANDBOX_SESSION_FAILED = "SKILLS_SANDBOX_SESSION_FAILED";
    static final String SKILLS_SANDBOX_TIMEOUT = "SKILLS_SANDBOX_TIMEOUT";

    private ToolErrorResponse() {}

    static Map<String, Object> codeSandbox(Throwable error) {
        return errorResponse(
                error,
                CODE_SANDBOX_FAILED,
                "Check AGENTKIT_TOOL_ID, the sandbox tool status, and the submitted code/language.",
                false);
    }

    static Map<String, Object> skillsSandbox(Throwable error) {
        return errorResponse(
                error,
                SKILLS_SANDBOX_A2A_FAILED,
                "Check AGENTKIT_TOOL_ID_SKILLS or AGENTKIT_TOOL_ID and the Skills Sandbox status.",
                true);
    }

    static Map<String, Object> errorResponse(
            Throwable error,
            String defaultCode,
            String defaultSuggestion,
            boolean defaultRetryable) {
        if (error instanceof ToolExecutionException toolError) {
            return ImmutableMap.of(
                    "error",
                    errorObject(
                            toolError.code(),
                            message(error),
                            toolError.suggestion(),
                            toolError.retryable(),
                            details(error)));
        }
        if (error instanceof IllegalArgumentException) {
            return ImmutableMap.of(
                    "error",
                    errorObject(
                            INVALID_ARGUMENT,
                            message(error),
                            "Check the required tool arguments and value types.",
                            false,
                            details(error)));
        }
        return ImmutableMap.of(
                "error",
                errorObject(
                        defaultCode,
                        message(error),
                        defaultSuggestion,
                        defaultRetryable,
                        details(error)));
    }

    private static Map<String, Object> errorObject(
            String code, String message, String suggestion, boolean retryable, String details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("suggestion", suggestion);
        error.put("retryable", retryable);
        if (StringUtils.isNotBlank(details)) {
            error.put("details", details);
        }
        return error;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return StringUtils.isBlank(message) ? error.getClass().getSimpleName() : message;
    }

    private static String details(Throwable error) {
        Throwable cause = error.getCause();
        if (cause == null || StringUtils.isBlank(cause.getMessage())) {
            return "";
        }
        if (cause.getMessage().equals(error.getMessage())) {
            return "";
        }
        return cause.getMessage();
    }

    static final class ToolExecutionException extends IllegalStateException {
        private final String code;
        private final String suggestion;
        private final boolean retryable;

        ToolExecutionException(String code, String message, String suggestion, boolean retryable) {
            super(message);
            this.code = code;
            this.suggestion = suggestion;
            this.retryable = retryable;
        }

        String code() {
            return code;
        }

        String suggestion() {
            return suggestion;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
