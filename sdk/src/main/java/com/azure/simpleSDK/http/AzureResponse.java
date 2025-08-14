package com.azure.simpleSDK.http;

import java.util.Map;
import java.util.Optional;

public class AzureResponse<T> {
    private final int statusCode;
    private final Map<String, String> headers;
    private final T body;
    private final String rawBody;

    public AzureResponse(int statusCode, Map<String, String> headers, T body, String rawBody) {
        this.statusCode = statusCode;
        this.headers = Map.copyOf(headers);
        this.body = body;
        this.rawBody = rawBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        return headers.get(name);
    }

    public Optional<String> getHeaderOptional(String name) {
        return Optional.ofNullable(headers.get(name));
    }

    public T getBody() {
        return body;
    }

    public String getRawBody() {
        return rawBody;
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    public boolean isError() {
        return statusCode >= 400;
    }

    @Override
    public String toString() {
        return String.format("AzureResponse{statusCode=%d, headers=%s, body=%s}", 
            statusCode, headers, body != null ? body.toString() : "null");
    }
}