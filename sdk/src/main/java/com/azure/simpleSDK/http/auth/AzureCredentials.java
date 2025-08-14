package com.azure.simpleSDK.http.auth;

import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;

public interface AzureCredentials {
    String getAccessToken() throws AzureAuthenticationException;
    boolean isExpired();
    void refresh() throws AzureAuthenticationException;
}