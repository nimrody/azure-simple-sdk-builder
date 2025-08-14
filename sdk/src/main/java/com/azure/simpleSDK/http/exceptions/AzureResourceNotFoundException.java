package com.azure.simpleSDK.http.exceptions;

import java.util.Map;

public class AzureResourceNotFoundException extends AzureServiceException {
    public AzureResourceNotFoundException(String message, Map<String, String> headers, String errorCode, String responseBody) {
        super(message, 404, headers, errorCode, responseBody);
    }

    public AzureResourceNotFoundException(String message, Map<String, String> headers, String errorCode, String responseBody, Throwable cause) {
        super(message, 404, headers, errorCode, responseBody, cause);
    }
}