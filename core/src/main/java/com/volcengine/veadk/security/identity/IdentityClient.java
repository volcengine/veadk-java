/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. */
package com.volcengine.veadk.security.identity;

/** Minimal stable client contract used by identity callbacks and test doubles. */
public interface IdentityClient {
    WorkloadToken getWorkloadToken(String workloadName, String userId, String userToken)
            throws Exception;

    boolean checkPermission(PermissionRequest request) throws Exception;

    default String getApiKey(String providerName, String identityToken) throws Exception {
        throw new UnsupportedOperationException(
                "Managed API keys are not supported by this client.");
    }

    default OAuth2Credential getOAuth2Credential(
            String providerName,
            String identityToken,
            String flow,
            java.util.List<String> scopes,
            String redirectUrl,
            boolean forceAuthentication)
            throws Exception {
        throw new UnsupportedOperationException("OAuth2 is not supported by this client.");
    }
}
