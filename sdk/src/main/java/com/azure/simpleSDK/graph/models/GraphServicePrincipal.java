package com.azure.simpleSDK.graph.models;

/**
 * Minimal representation of a Microsoft Graph service principal response.
 */
public record GraphServicePrincipal(
        String id,
        String appId,
        String displayName,
        String servicePrincipalType) {
}
