package com.azure.simpleSDK.http;

import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.*;
import com.azure.simpleSDK.http.retry.ExponentialBackoffStrategy;
import com.azure.simpleSDK.http.retry.RetryPolicy;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class AzureHttpClient {
    private final HttpClient httpClient;
    private final AzureCredentials credentials;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final ExponentialBackoffStrategy backoffStrategy;

    public AzureHttpClient(AzureCredentials credentials) {
        this(credentials, RetryPolicy.DEFAULT);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy) {
        this.credentials = credentials;
        this.retryPolicy = retryPolicy;
        this.objectMapper = new ObjectMapper();
        this.backoffStrategy = new ExponentialBackoffStrategy();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public AzureRequest get(String url) {
        return new AzureRequest("GET", url, credentials, objectMapper);
    }

    public AzureRequest post(String url) {
        return new AzureRequest("POST", url, credentials, objectMapper);
    }

    public <T> AzureResponse<T> execute(AzureRequest azureRequest, Class<T> responseType) throws AzureException {
        HttpRequest request = azureRequest.build();
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Map<String, String> responseHeaders = extractHeaders(response);
                
                if (response.statusCode() >= 400) {
                    if (shouldRetry(response.statusCode(), attempt)) {
                        sleepBeforeRetry(attempt, responseHeaders);
                        continue;
                    }
                    
                    throw createServiceException(response, responseHeaders);
                }
                
                T responseBody = null;
                if (responseType != Void.class && response.body() != null && !response.body().isEmpty()) {
                    responseBody = objectMapper.readValue(response.body(), responseType);
                }
                
                return new AzureResponse<>(response.statusCode(), responseHeaders, responseBody, response.body());
                
            } catch (HttpTimeoutException e) {
                lastException = e;
                if (retryPolicy.shouldRetryOnTimeout() && shouldRetry(attempt)) {
                    sleepBeforeRetry(attempt, null);
                    continue;
                } else {
                    throw new AzureNetworkException("Request timeout", e);
                }
            } catch (IOException e) {
                lastException = e;
                if (retryPolicy.shouldRetryOnNetworkError() && shouldRetry(attempt)) {
                    sleepBeforeRetry(attempt, null);
                    continue;
                } else {
                    throw new AzureNetworkException("Network error", e);
                }
            } catch (CompletionException e) {
                if (e.getCause() instanceof HttpTimeoutException) {
                    lastException = e;
                    if (retryPolicy.shouldRetryOnTimeout() && shouldRetry(attempt)) {
                        sleepBeforeRetry(attempt, null);
                        continue;
                    } else {
                        throw new AzureNetworkException("Request timeout", e.getCause());
                    }
                }
                throw new AzureException("Unexpected error during request execution", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AzureException("Request was interrupted", e);
            } catch (Exception e) {
                throw new AzureException("Unexpected error during request execution", e);
            }
        }
        
        throw new AzureException("Request failed after " + retryPolicy.getMaxAttempts() + " attempts", lastException);
    }

    public <T> AzureResponse<T> execute(AzureRequest azureRequest, Class<T> responseType, RetryPolicy customRetryPolicy) throws AzureException {
        RetryPolicy originalPolicy = this.retryPolicy;
        try {
            return execute(azureRequest, responseType);
        } finally {
        }
    }

    private Map<String, String> extractHeaders(HttpResponse<String> response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private boolean shouldRetry(int statusCode, int attempt) {
        return attempt < retryPolicy.getMaxAttempts() && retryPolicy.shouldRetry(statusCode);
    }

    private boolean shouldRetry(int attempt) {
        return attempt < retryPolicy.getMaxAttempts();
    }

    private void sleepBeforeRetry(int attempt, Map<String, String> responseHeaders) throws AzureException {
        try {
            Duration delay = backoffStrategy.calculateDelay(attempt, retryPolicy, responseHeaders);
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AzureException("Retry delay was interrupted", e);
        }
    }

    private AzureException createServiceException(HttpResponse<String> response, Map<String, String> headers) {
        String responseBody = response.body();
        String errorCode = null;
        String errorMessage = "HTTP " + response.statusCode();
        
        try {
            if (responseBody != null && !responseBody.isEmpty()) {
                AzureErrorResponse errorResponse = objectMapper.readValue(responseBody, AzureErrorResponse.class);
                if (errorResponse.error != null) {
                    errorCode = errorResponse.error.code;
                    errorMessage = errorResponse.error.message != null ? errorResponse.error.message : errorMessage;
                }
            }
        } catch (Exception e) {
        }
        
        switch (response.statusCode()) {
            case 401:
            case 403:
                return new AzureAuthenticationException(errorMessage);
            case 404:
                return new AzureResourceNotFoundException(errorMessage, headers, errorCode, responseBody);
            default:
                return new AzureServiceException(errorMessage, response.statusCode(), headers, errorCode, responseBody);
        }
    }

    private static class AzureErrorResponse {
        @JsonProperty("error")
        public AzureErrorDetail error;
    }

    private static class AzureErrorDetail {
        @JsonProperty("code")
        public String code;
        
        @JsonProperty("message")
        public String message;
    }
}
