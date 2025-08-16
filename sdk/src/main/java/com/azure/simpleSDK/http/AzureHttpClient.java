package com.azure.simpleSDK.http;

import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.*;
import com.azure.simpleSDK.http.retry.ExponentialBackoffStrategy;
import com.azure.simpleSDK.http.retry.RetryPolicy;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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
    private static final String AZURE_MANAGEMENT_BASE_URL = "https://management.azure.com";
    
    private final HttpClient httpClient;
    private final AzureCredentials credentials;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final ExponentialBackoffStrategy backoffStrategy;
    private final boolean failOnUnknownProperties;

    public AzureHttpClient(AzureCredentials credentials) {
        this(credentials, RetryPolicy.DEFAULT, false);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy) {
        this(credentials, retryPolicy, false);
    }

    public AzureHttpClient(AzureCredentials credentials, boolean failOnUnknownProperties) {
        this(credentials, RetryPolicy.DEFAULT, failOnUnknownProperties);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy, boolean failOnUnknownProperties) {
        this.credentials = credentials;
        this.retryPolicy = retryPolicy;
        this.failOnUnknownProperties = failOnUnknownProperties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
        this.backoffStrategy = new ExponentialBackoffStrategy();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public AzureRequest get(String url) {
        return new AzureRequest("GET", buildFullUrl(url), credentials, objectMapper);
    }

    public AzureRequest post(String url) {
        return new AzureRequest("POST", buildFullUrl(url), credentials, objectMapper);
    }

    private String buildFullUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return AZURE_MANAGEMENT_BASE_URL + (url.startsWith("/") ? url : "/" + url);
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
                    try {
                        responseBody = objectMapper.readValue(response.body(), responseType);
                    } catch (UnrecognizedPropertyException e) {
                        if (failOnUnknownProperties) {
                            logUnknownPropertiesDetails(request.uri().toString(), response.body(), e);
                            throw new AzureException("Unknown properties found in Azure API response", e);
                        } else {
                            // This shouldn't happen since we set FAIL_ON_UNKNOWN_PROPERTIES to false for non-strict mode
                            throw new AzureException("Unexpected deserialization error", e);
                        }
                    }
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

    private void logUnknownPropertiesDetails(String url, String responseBody, UnrecognizedPropertyException e) {
        System.err.println("================================================================================");
        System.err.println("UNKNOWN PROPERTIES DETECTED IN AZURE API RESPONSE");
        System.err.println("================================================================================");
        System.err.println("URL: " + url);
        System.err.println("Unknown Property: " + e.getPropertyName());
        System.err.println("Target Type: " + e.getReferringClass());
        System.err.println("Reference Chain: " + e.getPathReference());
        
        // Try to extract the unknown property value from the JSON response
        try {
            JsonNode rootNode = objectMapper.readTree(responseBody);
            String[] pathComponents = e.getPathReference().split("->");
            JsonNode currentNode = rootNode;
            
            System.err.println("Navigation Path:");
            for (int i = 0; i < pathComponents.length && currentNode != null; i++) {
                String component = pathComponents[i].trim();
                System.err.println("  [" + i + "] " + component);
                
                if (component.contains("[") && component.contains("]")) {
                    // Handle array access like "java.util.ArrayList[0]"
                    String arrayField = component.substring(0, component.indexOf('['));
                    String indexStr = component.substring(component.indexOf('[') + 1, component.indexOf(']'));
                    try {
                        int index = Integer.parseInt(indexStr);
                        if (currentNode.has(arrayField) && currentNode.get(arrayField).isArray()) {
                            currentNode = currentNode.get(arrayField).get(index);
                        }
                    } catch (NumberFormatException ignored) {
                        // Skip if index is not a number
                    }
                } else if (component.contains(".")) {
                    // Skip class name parts like "com.azure.simpleSDK.models.SomeClass"
                    String fieldName = component.substring(component.lastIndexOf('.') + 1);
                    if (currentNode.has(fieldName)) {
                        currentNode = currentNode.get(fieldName);
                    }
                }
            }
            
            // Try to find the unknown property in the current context
            if (currentNode != null && currentNode.has(e.getPropertyName())) {
                JsonNode unknownValue = currentNode.get(e.getPropertyName());
                System.err.println("Unknown Property Value: " + unknownValue.toString());
                System.err.println("Value Type: " + unknownValue.getNodeType());
            }
            
            // Also show all unknown properties at this level
            if (currentNode != null && currentNode.isObject()) {
                System.err.println("All properties at this level:");
                currentNode.fields().forEachRemaining(entry -> {
                    System.err.println("  " + entry.getKey() + " = " + entry.getValue().toString());
                });
            }
            
        } catch (Exception jsonException) {
            System.err.println("Could not parse JSON to extract unknown property details: " + jsonException.getMessage());
        }
        
        System.err.println("================================================================================");
        System.err.println("Raw JSON Response (first 1000 chars):");
        System.err.println(responseBody.length() > 1000 ? responseBody.substring(0, 1000) + "..." : responseBody);
        System.err.println("================================================================================");
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
