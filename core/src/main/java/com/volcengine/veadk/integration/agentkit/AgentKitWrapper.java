package com.volcengine.veadk.integration.agentkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.volcengine.error.SdkError;
import com.volcengine.helper.Const;
import com.volcengine.model.ApiInfo;
import com.volcengine.model.Credentials;
import com.volcengine.model.ServiceInfo;
import com.volcengine.model.response.RawResponse;
import com.volcengine.service.BaseServiceImpl;
import com.volcengine.veadk.utils.JSONUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A wrapper service implementation to invoke Volcengine AgentKit tools.
 */
public class AgentKitWrapper extends BaseServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(AgentKitWrapper.class);

    private static final String ACTION_INVOKE_TOOL = "InvokeTool";

    private static final ServiceInfo SERVICE_INFO =
            new ServiceInfo(
                    new HashMap<String, Object>() {
                        {
                            put(Const.CONNECTION_TIMEOUT, 5000);
                            put(Const.SOCKET_TIMEOUT, 30000); // Sandbox might be slow
                            put(Const.Scheme, "https");
                            put(
                                    Const.Header,
                                    new ArrayList<Header>() {
                                        {
                                            add(new BasicHeader("Accept", "application/json"));
                                        }
                                    });
                            put(Const.Credentials, new Credentials("cn-beijing", "agentkit"));
                        }
                    });

    private static final Map<String, ApiInfo> API_INFO_LIST =
            new HashMap<String, ApiInfo>() {
                {
                    put(
                            ACTION_INVOKE_TOOL,
                            new ApiInfo(
                                    new HashMap<String, Object>() {
                                        {
                                            put(Const.Method, "POST");
                                            put(Const.Path, "/");
                                            put(
                                                    Const.Header,
                                                    Arrays.asList(
                                                            new BasicHeader(
                                                                    "Accept", "application/json"),
                                                            new BasicHeader(
                                                                    "Content-Type",
                                                                    "application/json")));
                                        }
                                    }));
                }
            };

    private static final List<NameValuePair> INVOKETOOL_PARAMS =
            Arrays.asList(
                    new BasicNameValuePair("Action", ACTION_INVOKE_TOOL),
                    new BasicNameValuePair("Version", "2025-10-30"));

    public AgentKitWrapper(String host, String region, String ak, String sk) {
        super(SERVICE_INFO, API_INFO_LIST);
        this.setAccessKey(ak);
        this.setSecretKey(sk);
        this.setHost(host);
        this.getServiceInfo().setHost(host);
        this.setRegion(region);
        this.getServiceInfo().getCredentials().setRegion(region);
    }

    public String runCode(
            String toolId, String sessionId, String code, String language, int timeout) {

        try {
            String toolUserSessionId = "veadk_java_" + sessionId;

            Map<String, Object> payload = new HashMap<>();
            payload.put("code", code);
            payload.put("timeout", timeout);
            payload.put("kernel_name", language);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ToolId", toolId);
            requestBody.put("UserSessionId", toolUserSessionId);
            requestBody.put("OperationType", "RunCode");
            requestBody.put("OperationPayload", JSONUtil.toJson(payload));
            requestBody.put("Ttl", 1800);

            String bodyStr = JSONUtil.toJson(requestBody);

            RawResponse response = json(ACTION_INVOKE_TOOL, INVOKETOOL_PARAMS, bodyStr);
            if (response.getCode() != SdkError.SUCCESS.getNumber()) {
                log.error(
                        "InvokeTool request:{}, raw response:{}",
                        bodyStr,
                        response.getException().getMessage());
                throw response.getException();
            }
            log.debug(
                    "InvokeTool request:{}, raw response:{}",
                    bodyStr,
                    JSONUtil.parseJson(response.getData()));

            // Parse response to get "Result"
            JsonNode rootNode = JSONUtil.parseJson(response.getData());
            JsonNode resultNode = rootNode.path("Result").path("Result");

            if (!resultNode.isMissingNode()) {
                return resultNode.asText();
            }
            return rootNode.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to run code via AgentKit", e);
        }
    }
}
