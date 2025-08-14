package com.azure.simpleSDK.http.exceptions;

public class AzureNetworkException extends AzureException {
    public AzureNetworkException(String message) {
        super(message);
    }

    public AzureNetworkException(String message, Throwable cause) {
        super(message, cause);
    }

    public AzureNetworkException(Throwable cause) {
        super(cause);
    }
}