/** Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates. */
package com.volcengine.veadk.integration.viking;

public class VikingDataPlaneException extends RuntimeException {
    private final String operation;
    private final Integer serviceCode;
    private final String requestId;
    private final Integer httpStatus;

    public VikingDataPlaneException(
            String operation,
            Integer serviceCode,
            String requestId,
            Integer httpStatus,
            Throwable cause) {
        super(
                "Viking data-plane request failed: operation="
                        + operation
                        + ", code="
                        + serviceCode
                        + ", requestId="
                        + requestId
                        + ", httpStatus="
                        + httpStatus,
                cause);
        this.operation = operation;
        this.serviceCode = serviceCode;
        this.requestId = requestId;
        this.httpStatus = httpStatus;
    }

    public String getOperation() {
        return operation;
    }

    public Integer getServiceCode() {
        return serviceCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
