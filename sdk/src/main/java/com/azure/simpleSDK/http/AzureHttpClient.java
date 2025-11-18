package com.azure.simpleSDK.http;

import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.*;
import com.azure.simpleSDK.http.retry.ExponentialBackoffStrategy;
import com.azure.simpleSDK.http.retry.RetryPolicy;
import com.azure.simpleSDK.http.recording.HttpInteractionRecorder;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class AzureHttpClient {
    private static final String AZURE_MANAGEMENT_BASE_URL = "https://management.azure.com";
    private static volatile HttpInteractionRecorder globalRecorder;
    
    private final HttpClient httpClient;
    private final AzureCredentials credentials;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final ExponentialBackoffStrategy backoffStrategy;
    private final boolean failOnUnknownProperties;
    private final HttpInteractionRecorder recorder;

    public static void setGlobalRecorder(HttpInteractionRecorder recorder) {
        globalRecorder = recorder;
    }

    public AzureHttpClient(AzureCredentials credentials) {
        this(credentials, RetryPolicy.DEFAULT, false, null);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy) {
        this(credentials, retryPolicy, false, null);
    }

    public AzureHttpClient(AzureCredentials credentials, boolean failOnUnknownProperties) {
        this(credentials, RetryPolicy.DEFAULT, failOnUnknownProperties, null);
    }

    public AzureHttpClient(AzureCredentials credentials, HttpInteractionRecorder recorder) {
        this(credentials, RetryPolicy.DEFAULT, false, recorder);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy, HttpInteractionRecorder recorder) {
        this(credentials, retryPolicy, false, recorder);
    }

    public AzureHttpClient(AzureCredentials credentials, boolean failOnUnknownProperties, HttpInteractionRecorder recorder) {
        this(credentials, RetryPolicy.DEFAULT, failOnUnknownProperties, recorder);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy, boolean failOnUnknownProperties) {
        this(credentials, retryPolicy, failOnUnknownProperties, null);
    }

    public AzureHttpClient(AzureCredentials credentials, RetryPolicy retryPolicy, boolean failOnUnknownProperties, HttpInteractionRecorder recorder) {
        this.credentials = credentials;
        this.retryPolicy = retryPolicy;
        this.failOnUnknownProperties = failOnUnknownProperties;
        this.recorder = recorder != null ? recorder : globalRecorder;
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
                HttpCallResult result = sendHttpRequest(request, azureRequest.getSerializedBody());
                Map<String, String> responseHeaders = result.headers();
                
                if (result.statusCode() >= 400) {
                    if (shouldRetry(result.statusCode(), attempt)) {
                        sleepBeforeRetry(attempt, responseHeaders);
                        continue;
                    }
                    
                    throw createServiceException(result.statusCode(), responseHeaders, result.body());
                }
                
                T responseBody = null;
                if (responseType != Void.class && result.body() != null && !result.body().isEmpty()) {
                    try {
                        responseBody = objectMapper.readValue(result.body(), responseType);
                    } catch (UnrecognizedPropertyException e) {
                        if (failOnUnknownProperties) {
                            logUnknownPropertiesDetails(request.uri().toString(), result.body(), e);
                            throw new AzureException("Unknown properties found in Azure API response", e);
                        } else {
                            // This shouldn't happen since we set FAIL_ON_UNKNOWN_PROPERTIES to false for non-strict mode
                            throw new AzureException("Unexpected deserialization error", e);
                        }
                    }
                }
                
                // Handle pagination for list results
                if (responseBody != null && isPaginatedListResult(responseType)) {
                    return handlePaginationInline(responseBody, responseType, result.statusCode(), responseHeaders, result.body());
                }
                
                return new AzureResponse<>(result.statusCode(), responseHeaders, responseBody, result.body());
                
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
                // e.g. AzureAuthenticationException
                throw e;
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

    private HttpCallResult sendHttpRequest(HttpRequest request, String serializedBody) throws IOException, InterruptedException, AzureException {
        if (recorder != null && recorder.isPlayback()) {
            return recorder.playback(request, serializedBody);
        }

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        HttpCallResult result = HttpCallResult.fromHttpResponse(response);

        if (recorder != null && recorder.isRecording()) {
            recorder.record(request, serializedBody, result);
        }

        return result;
    }

    private HttpCallResult fetchNextPage(String nextLink) throws IOException, InterruptedException, AzureException {
        AzureRequest nextRequest = new AzureRequest("GET", buildFullUrl(nextLink), credentials, objectMapper);
        nextRequest.timeout(Duration.ofSeconds(30));
        HttpRequest request = nextRequest.build();
        return sendHttpRequest(request, nextRequest.getSerializedBody());
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

    private AzureException createServiceException(int statusCode, Map<String, String> headers, String responseBody) {
        String errorCode = null;
        String errorMessage = "HTTP " + statusCode;
        
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
        
        switch (statusCode) {
            case 401:
            case 403:
                return new AzureAuthenticationException(errorMessage);
            case 404:
                return new AzureResourceNotFoundException(errorMessage, headers, errorCode, responseBody);
            default:
                return new AzureServiceException(errorMessage, statusCode, headers, errorCode, responseBody);
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
        JsonNode rootNode = null;
        try {
            rootNode = objectMapper.readTree(responseBody);
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
        System.err.println("Complete JSON Response (pretty printed):");
        if (rootNode != null) {
            // Reuse the already parsed JsonNode for pretty printing
            try {
                String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                System.err.println(prettyJson);
            } catch (Exception prettyPrintException) {
                System.err.println("Failed to pretty print parsed JSON: " + prettyPrintException.getMessage());
                System.err.println("Raw response (first 1000 chars):");
                System.err.println(responseBody.length() > 1000 ? responseBody.substring(0, 1000) + "..." : responseBody);
            }
        } else {
            // If JSON parsing failed above, fall back to raw response (first 1000 chars)
            System.err.println("JSON parsing failed, showing raw response (first 1000 chars):");
            System.err.println(responseBody.length() > 1000 ? responseBody.substring(0, 1000) + "..." : responseBody);
        }
        System.err.println("================================================================================");
    }

    @SuppressWarnings("unchecked")
    private <T> AzureResponse<T> handlePaginationInline(T firstPage, Class<T> responseType, int statusCode, Map<String, String> headers, String firstPageRawResponse) throws AzureException {
        try {
            Method nextLinkMethod = responseType.getMethod("nextLink");
            String nextLink = (String) nextLinkMethod.invoke(firstPage);
            
            if (nextLink == null || nextLink.trim().isEmpty()) {
                // No pagination needed - return single page response
                return new AzureResponse<>(statusCode, headers, firstPage, firstPageRawResponse);
            }
            
            List<T> allPages = new ArrayList<>();
            allPages.add(firstPage);
            
            String currentNextLink = nextLink;
            int pageCount = 1;
            final int maxPages = 50; // Safety limit to prevent infinite loops
            
            while (currentNextLink != null && !currentNextLink.trim().isEmpty() && pageCount < maxPages) {
                try {
                    HttpCallResult nextResult = fetchNextPage(currentNextLink);
                    
                    if (nextResult.statusCode() >= 400) {
                        System.err.println("Warning: Failed to fetch page " + (pageCount + 1) + " of paginated results: HTTP " + nextResult.statusCode());
                        break;
                    }
                    
                    if (nextResult.body() != null && !nextResult.body().isEmpty()) {
                        T nextPageData = objectMapper.readValue(nextResult.body(), responseType);
                        allPages.add(nextPageData);
                        
                        // Get the next link for the following page
                        currentNextLink = (String) nextLinkMethod.invoke(nextPageData);
                        pageCount++;
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Error fetching page " + (pageCount + 1) + " of paginated results: " + e.getMessage());
                    break;
                }
            }
            
            if (pageCount >= maxPages) {
                System.err.println("Warning: Reached maximum page limit (" + maxPages + ") for paginated results. Some results may be missing.");
            }
            
            // Combine all pages into a single response
            T combinedResult = combinePagedResults(firstPage, allPages.subList(1, allPages.size()), responseType);
            
            System.out.println("Pagination: Successfully combined " + pageCount + " pages of results");
            
            return new AzureResponse<>(statusCode, headers, combinedResult, "Combined response from " + pageCount + " pages");
            
        } catch (Exception e) {
            System.err.println("Warning: Error during pagination: " + e.getMessage());
            // Fall back to single page response
            return new AzureResponse<>(statusCode, headers, firstPage, firstPageRawResponse);
        }
    }

    private boolean isPaginatedListResult(Class<?> responseType) {
        try {
            // Check if the class has both 'value' and 'nextLink' methods (record accessors)
            Method valueMethod = responseType.getMethod("value");
            Method nextLinkMethod = responseType.getMethod("nextLink");
            
            // Check if value() returns a List
            Type returnType = valueMethod.getGenericReturnType();
            if (returnType instanceof ParameterizedType) {
                ParameterizedType paramType = (ParameterizedType) returnType;
                return List.class.isAssignableFrom((Class<?>) paramType.getRawType());
            }
            return false;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T combinePagedResults(T firstPage, List<T> additionalPages, Class<T> responseType) {
        try {
            Method valueMethod = responseType.getMethod("value");
            
            // Get the list from the first page
            List<Object> combinedList = new ArrayList<>((List<Object>) valueMethod.invoke(firstPage));
            
            // Add items from all additional pages
            for (T page : additionalPages) {
                List<Object> pageItems = (List<Object>) valueMethod.invoke(page);
                if (pageItems != null) {
                    combinedList.addAll(pageItems);
                }
            }
            
            // Create a new instance with combined list and null nextLink
            // Since these are records, we need to use reflection to create new instance
            try {
                // Try to find constructor that matches the record pattern
                var constructors = responseType.getConstructors();
                if (constructors.length > 0) {
                    var constructor = constructors[0];
                    var paramCount = constructor.getParameterCount();
                    
                    if (paramCount == 2) {
                        // Assume first param is value (List), second is nextLink (String)
                        return (T) constructor.newInstance(combinedList, null);
                    }
                }
            } catch (Exception e) {
                // If we can't create a new instance, return the first page as-is
                System.err.println("Warning: Could not combine paginated results, returning first page only: " + e.getMessage());
                return firstPage;
            }
            
            return firstPage; // fallback
        } catch (Exception e) {
            System.err.println("Warning: Could not combine paginated results: " + e.getMessage());
            return firstPage;
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
