package com.azure.simpleSDK.http.auth;

import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

public class ServicePrincipalCredentials implements AzureCredentials {
    private final String clientId;
    private final String clientSecret;
    private final String tenantId;
    private final String scope;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;

    private String accessToken;
    private Instant tokenExpiry;

    public ServicePrincipalCredentials(String clientId, String clientSecret, String tenantId) {
        this(clientId, clientSecret, tenantId, "https://management.azure.com/.default");
    }

    public ServicePrincipalCredentials(String clientId, String clientSecret, String tenantId, String scope) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tenantId = tenantId;
        this.scope = scope;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
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
            String requestBody = String.format(
                "grant_type=client_credentials&client_id=%s&client_secret=%s&scope=%s",
                clientId, clientSecret, scope);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenantId)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new AzureAuthenticationException(
                    String.format("Authentication failed with status %d: %s", response.statusCode(), response.body()));
            }

            TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
            this.accessToken = tokenResponse.accessToken;
            this.tokenExpiry = Instant.now().plus(Duration.ofSeconds(tokenResponse.expiresIn));

        } catch (Exception e) {
            throw new AzureAuthenticationException("Failed to refresh access token", e);
        }
    }

    @Override
    public Instant getTokenExpiry() {
        return tokenExpiry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenResponse {
        @JsonProperty("access_token")
        public String accessToken;

        @JsonProperty("expires_in")
        public long expiresIn;

        @JsonProperty("token_type")
        public String tokenType;
    }
}
