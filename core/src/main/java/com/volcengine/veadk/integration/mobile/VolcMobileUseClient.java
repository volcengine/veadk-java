/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package com.volcengine.veadk.integration.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.JSONUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;

/** Signed client for the Volcengine Mobile Use Agent APIs. */
public final class VolcMobileUseClient extends BaseServiceImpl {

    private static final ServiceInfo SERVICE =
            new ServiceInfo(
                    new HashMap<>() {
                        {
                            put(Const.CONNECTION_TIMEOUT, 10_000);
                            put(Const.SOCKET_TIMEOUT, 60_000);
                            put(Const.Host, "open.volcengineapi.com");
                            put(
                                    Const.Header,
                                    List.<Header>of(new BasicHeader("Accept", "application/json")));
                            put(Const.Credentials, new Credentials("cn-north-1", "ipaas"));
                        }
                    });

    private static final Map<String, ApiInfo> APIS =
            new HashMap<>() {
                {
                    put("RunAgentTaskOneStep", api("POST"));
                    put("GetAgentResult", api("GET"));
                    put("CancelTask", api("POST"));
                }
            };

    public VolcMobileUseClient(String accessKey, String secretKey, String sessionToken) {
        super(SERVICE, APIS);
        setAccessKey(accessKey);
        setSecretKey(secretKey);
        setSessionToken(sessionToken == null ? "" : sessionToken);
    }

    public String start(
            String productId,
            String podId,
            String systemPrompt,
            String userPrompt,
            int maxSteps,
            int stepIntervalSeconds,
            int timeoutSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("RunName", "veadk-mobile-use");
        body.put("PodId", podId);
        body.put("ProductId", productId);
        body.put("SystemPrompt", systemPrompt);
        body.put("UserPrompt", userPrompt);
        body.put("MaxStep", maxSteps);
        body.put("StepInterval", stepIntervalSeconds);
        body.put("Timeout", timeoutSeconds);
        JsonNode response = request("RunAgentTaskOneStep", params("RunAgentTaskOneStep"), body);
        String runId = response.path("Result").path("RunId").asText();
        if (runId.isBlank()) throw new IllegalStateException("Mobile task response has no RunId");
        return runId;
    }

    public TaskResult result(String runId) {
        JsonNode response = request("GetAgentResult", params("GetAgentResult", runId), Map.of());
        JsonNode result = response.path("Result");
        if (result.isMissingNode()) throw new IllegalStateException("Mobile result is missing");
        return new TaskResult(result.path("IsSuccess").asInt(), result.path("Content").asText());
    }

    public void cancel(String runId) {
        request("CancelTask", params("CancelTask", runId), Map.of());
    }

    private JsonNode request(String api, List<NameValuePair> parameters, Map<String, Object> body) {
        try {
            RawResponse response = json(api, parameters, JSONUtil.toJson(body));
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                throw new IllegalStateException(
                        "Mobile API request failed", response.getException());
            }
            return JSONUtil.parseJson(response.getData());
        } catch (IOException failure) {
            throw new IllegalStateException("Mobile API request failed", failure);
        }
    }

    private static List<NameValuePair> params(String action) {
        return List.of(
                new BasicNameValuePair("Action", action),
                new BasicNameValuePair("Version", "2023-08-01"));
    }

    private static List<NameValuePair> params(String action, String runId) {
        List<NameValuePair> result = new ArrayList<>(params(action));
        result.add(new BasicNameValuePair("RunId", runId));
        return result;
    }

    private static ApiInfo api(String method) {
        return new ApiInfo(
                new HashMap<>() {
                    {
                        put(Const.Method, method);
                        put(
                                Const.Header,
                                List.of(
                                        new BasicHeader("Accept", "application/json"),
                                        new BasicHeader("Content-Type", "application/json")));
                    }
                });
    }

    public record TaskResult(int status, String content) {}
}
