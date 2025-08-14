package com.azure.simpleSDK.http.auth;

import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

public class ManagedIdentityCredentials implements AzureCredentials {
    private static final String IMDS_ENDPOINT = "http://169.254.169.254/metadata/identity/oauth2/token";
    private static final String API_VERSION = "2018-02-01";

    private final String resource;
    private final String clientId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;

    private String accessToken;
    private Instant tokenExpiry;

    public ManagedIdentityCredentials() {
        this("https://management.azure.com/", null);
    }

    public ManagedIdentityCredentials(String resource) {
        this(resource, null);
    }

    public ManagedIdentityCredentials(String resource, String clientId) {
        this.resource = resource;
        this.clientId = clientId;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
    }

    @Override
    public String getAccessToken() throws AzureAuthenticationException {
        tokenLock.lock();
        try {
            if (accessToken == null || isExpired()) {
                refresh();
            }
            return accessToken;
        } finally {
            tokenLock.unlock();
        }
    }

    @Override
    public boolean isExpired() {
        return tokenExpiry == null || Instant.now().isAfter(tokenExpiry.minus(Duration.ofMinutes(5)));
    }

    @Override
    public void refresh() throws AzureAuthenticationException {
        try {
            StringBuilder urlBuilder = new StringBuilder(IMDS_ENDPOINT)
                .append("?api-version=").append(API_VERSION)
                .append("&resource=").append(resource);

            if (clientId != null && !clientId.isEmpty()) {
                urlBuilder.append("&client_id=").append(clientId);
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBuilder.toString()))
                .header("Metadata", "true")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new AzureAuthenticationException(
                    String.format("Managed Identity authentication failed with status %d: %s", 
                        response.statusCode(), response.body()));
            }

            TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
            this.accessToken = tokenResponse.accessToken;
            this.tokenExpiry = Instant.now().plus(Duration.ofSeconds(tokenResponse.expiresIn));

        } catch (Exception e) {
            throw new AzureAuthenticationException("Failed to refresh Managed Identity access token", e);
        }
    }

    private static class TokenResponse {
        @JsonProperty("access_token")
        public String accessToken;

        @JsonProperty("expires_in")
        public long expiresIn;
    }
}