/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.volcengine.veadk.security;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Package-private Volcengine HMAC-SHA256 V4 request signer. */
public final class VolcengineV4Signer {
    private static final String ALGORITHM = "HMAC-SHA256";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withLocale(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private VolcengineV4Signer() {}

    public static Map<String, String> sign(
            URI uri,
            String query,
            byte[] body,
            String region,
            String service,
            String accessKey,
            String secretKey,
            String sessionToken,
            Clock clock) {
        String xDate = DATE_FORMAT.format(clock.instant());
        String shortDate = xDate.substring(0, 8);
        String payloadHash = sha256Hex(body);
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("content-type", "application/json");
        headers.put("host", canonicalHost(uri));
        headers.put("x-content-sha256", payloadHash);
        headers.put("x-date", xDate);
        if (sessionToken != null && !sessionToken.isBlank()) {
            headers.put("x-security-token", sessionToken.trim());
        }
        String canonicalHeaders =
                headers.entrySet().stream()
                        .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                        .reduce("", String::concat);
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest =
                String.join(
                        "\n",
                        "POST",
                        canonicalPath(uri.getRawPath()),
                        query == null ? "" : query,
                        canonicalHeaders,
                        signedHeaders,
                        payloadHash);
        String scope = shortDate + "/" + region + "/" + service + "/request";
        String stringToSign =
                String.join(
                        "\n",
                        ALGORITHM,
                        xDate,
                        scope,
                        sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] dateKey = hmac(secretKey.getBytes(StandardCharsets.UTF_8), shortDate);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        String signature =
                HexFormat.of().formatHex(hmac(hmac(serviceKey, "request"), stringToSign));
        TreeMap<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        result.put("Content-Type", "application/json");
        result.put("X-Content-Sha256", payloadHash);
        result.put("X-Date", xDate);
        if (headers.containsKey("x-security-token")) {
            result.put("X-Security-Token", headers.get("x-security-token"));
        }
        result.put(
                "Authorization",
                ALGORITHM
                        + " Credential="
                        + accessKey
                        + "/"
                        + scope
                        + ", SignedHeaders="
                        + signedHeaders
                        + ", Signature="
                        + signature);
        return result;
    }

    private static String canonicalHost(URI uri) {
        int port = uri.getPort();
        return port < 0
                        || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                        || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                ? uri.getHost()
                : uri.getHost() + ":" + port;
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] parts = path.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append('/');
            result.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return result.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate request signature.", e);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to calculate payload hash.", e);
        }
    }
}
