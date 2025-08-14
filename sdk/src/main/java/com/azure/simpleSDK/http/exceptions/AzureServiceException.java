package com.azure.simpleSDK.http.exceptions;

import java.util.Map;

public class AzureServiceException extends AzureException {
    private final int statusCode;
    private final Map<String, String> headers;
    private final String errorCode;
    private final String responseBody;

    public AzureServiceException(String message, int statusCode, Map<String, String> headers, String errorCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.headers = headers;
        this.errorCode = errorCode;
        this.responseBody = responseBody;
    }

    public AzureServiceException(String message, int statusCode, Map<String, String> headers, String errorCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.headers = headers;
        this.errorCode = errorCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}