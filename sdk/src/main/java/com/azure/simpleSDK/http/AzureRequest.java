package com.azure.simpleSDK.http;

import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AzureRequest {
    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private Object body;
    private Duration timeout;
    private final AzureCredentials credentials;
    private final ObjectMapper objectMapper;

    public AzureRequest(String method, String url, AzureCredentials credentials, ObjectMapper objectMapper) {
        this.method = method;
        this.url = url;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.headers = new HashMap<>();
        this.queryParameters = new HashMap<>();
        this.timeout = Duration.ofSeconds(30);
        
        setDefaultHeaders();
    }

    private void setDefaultHeaders() {
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "azure-simple-sdk/1.0.0");
        headers.put("x-ms-client-request-id", UUID.randomUUID().toString());
    }

    public AzureRequest header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public AzureRequest headers(Map<String, String> headers) {
        this.headers.putAll(headers);
        return this;
    }

    public AzureRequest queryParam(String name, String value) {
        if (value != null) {
            queryParameters.put(name, value);
        }
        return this;
    }

    public AzureRequest queryParams(Map<String, String> queryParams) {
        this.queryParameters.putAll(queryParams);
        return this;
    }

    public AzureRequest version(String apiVersion) {
        headers.put("x-ms-version", apiVersion);
        queryParameters.put("api-version", apiVersion);
        return this;
    }

    public AzureRequest body(Object body) {
        this.body = body;
        return this;
    }

    public AzureRequest timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public HttpRequest build() throws AzureException {
        try {
            String finalUrl = buildUrlWithQuery();
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(finalUrl))
                .timeout(timeout);

            addAuthenticationHeader();
            
            for (Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.header(header.getKey(), header.getValue());
            }

            HttpRequest.BodyPublisher bodyPublisher;
            if (body != null) {
                if (body instanceof String) {
                    bodyPublisher = HttpRequest.BodyPublishers.ofString((String) body);
                } else {
                    String jsonBody = objectMapper.writeValueAsString(body);
                    bodyPublisher = HttpRequest.BodyPublishers.ofString(jsonBody);
                }
            } else {
                bodyPublisher = HttpRequest.BodyPublishers.noBody();
            }

            switch (method.toUpperCase()) {
                case "GET":
                    requestBuilder.GET();
                    break;
                case "POST":
                    requestBuilder.POST(bodyPublisher);
                    break;
                case "PUT":
                    requestBuilder.PUT(bodyPublisher);
                    break;
                case "DELETE":
                    requestBuilder.DELETE();
                    break;
                case "PATCH":
                    requestBuilder.method("PATCH", bodyPublisher);
                    break;
                case "HEAD":
                    requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported HTTP method: " + method);
            }

            return requestBuilder.build();
        } catch (Exception e) {
            throw new AzureException("Failed to build HTTP request", e);
        }
    }

    private String buildUrlWithQuery() {
        if (queryParameters.isEmpty()) {
            return url;
        }

        StringBuilder urlBuilder = new StringBuilder(url);
        urlBuilder.append(url.contains("?") ? "&" : "?");
        
        boolean first = true;
        for (Map.Entry<String, String> param : queryParameters.entrySet()) {
            if (!first) {
                urlBuilder.append("&");
            }
            urlBuilder.append(param.getKey()).append("=").append(param.getValue());
            first = false;
        }
        
        return urlBuilder.toString();
    }

    private void addAuthenticationHeader() throws AzureAuthenticationException {
        if (credentials != null) {
            String accessToken = credentials.getAccessToken();
            headers.put("Authorization", "Bearer " + accessToken);
        }
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return Map.copyOf(headers);
    }

    public Object getBody() {
        return body;
    }

    public Duration getTimeout() {
        return timeout;
    }
}