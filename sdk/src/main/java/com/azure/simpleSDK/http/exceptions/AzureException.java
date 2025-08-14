package com.azure.simpleSDK.http.exceptions;

public class AzureException extends Exception {
    public AzureException(String message) {
        super(message);
    }

    public AzureException(String message, Throwable cause) {
        super(message, cause);
    }

    public AzureException(Throwable cause) {
        super(cause);
    }
}