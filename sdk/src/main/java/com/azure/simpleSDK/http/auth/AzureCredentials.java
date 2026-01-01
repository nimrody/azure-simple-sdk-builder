package com.azure.simpleSDK.http.auth;

import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;

import java.time.Instant;

public interface AzureCredentials {
    String getAccessToken() throws AzureAuthenticationException;
    boolean isExpired();
    void refresh() throws AzureAuthenticationException;
    Instant getTokenExpiry();
}
