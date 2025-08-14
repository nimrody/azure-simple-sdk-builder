package com.azure.simpleSDK.http.exceptions;

public class AzureAuthenticationException extends AzureException {
    public AzureAuthenticationException(String message) {
        super(message);
    }

    public AzureAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    public AzureAuthenticationException(Throwable cause) {
        super(cause);
    }
}